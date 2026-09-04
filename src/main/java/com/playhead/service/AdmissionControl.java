package com.playhead.service;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Priority-tiered admission control (FR-9).
 *
 * <p>Phase 2 shipped a single {@link TokenBucket} on the write path, which left the roadmap's
 * central claim unproven: under overload the only traffic that could be shed was the traffic worth
 * the most. The read endpoints added in phase 5 were admitted without any limit at all, so they
 * competed with playback writes for the same threads and CPU while writes took the {@code 429}s.
 *
 * <p>This class puts every entry point behind a tier, and gives each tier its own bucket. The
 * buckets are sized so the low tiers run dry <em>before</em> the write tier feels anything:
 *
 * <ul>
 *   <li><b>PLAYBACK_WRITE</b> — a lost heartbeat is unrecoverable. The viewer's position is gone
 *       and no retry brings it back. Never shed while the other two have anything left to give.
 *   <li><b>RESUME_READ</b> — a failed resume is retried by the client a moment later, and the
 *       correct answer is still in Redis when it is.
 *   <li><b>BROWSE_READ</b> — a failed continue-watching call shows a stale browse row. Shed first.
 * </ul>
 *
 * <p><b>Sizing.</b> BENCHMARKS.md Run A measured the unprotected ceiling at ~10,000-13,000 req/s.
 * The three capacities total 10,000, holding admitted traffic below that ceiling, and the split
 * puts 80% of it behind the write tier. Under a surge past the ceiling, BROWSE_READ exhausts its
 * 500/s first, RESUME_READ its 1,500/s next, and PLAYBACK_WRITE keeps refilling at a rate the
 * system was measured to sustain.
 *
 * <p>The write path keeps its own in-flight {@code Semaphore} in
 * {@link com.playhead.controller.IngestController} as a backlog safety net. That guards against
 * slow downstream calls piling up, which is a different failure from too many arrivals, and the
 * two limits are deliberately not merged.
 *
 * <p>Every decision is counted per tier, so a shed is visible at runtime through
 * {@code /actuator/metrics/admission.shed} and not only in a load-test summary.
 */
@Component
public class AdmissionControl {

    /**
     * A traffic class, with the default bucket sizing that encodes its priority.
     *
     * <p>These defaults describe a replica with the whole host to itself, which is what the
     * phase-2 measurements were taken on. They are <em>rates one instance can serve</em>, not a
     * fleet-wide budget, so a replica given a fraction of the host needs a correspondingly smaller
     * number — a replica capped at one core cannot serve 8,000 writes/sec no matter what the bucket
     * says, and a bucket that never rejects turns overload into queueing and timeouts instead of a
     * fast {@code 429}. That is the failure this made visible: capping a replica's CPU without
     * resizing the tiers left admission control admitting roughly forty times what the replica
     * could actually serve.
     *
     * <p>Overridable per deployment (see the constructor) precisely because the right number is a
     * property of the instance's resources, not of the code.
     */
    public enum Tier {

        PLAYBACK_WRITE(8000, 8000),
        RESUME_READ(1500, 1500),
        BROWSE_READ(500, 500);

        private final long capacity;
        private final double refillTokensPerSecond;

        Tier(long capacity, double refillTokensPerSecond) {
            this.capacity = capacity;
            this.refillTokensPerSecond = refillTokensPerSecond;
        }
    }

    private final Map<Tier, TokenBucket> buckets = new EnumMap<>(Tier.class);
    private final Map<Tier, Counter> admittedCounters = new EnumMap<>(Tier.class);
    private final Map<Tier, Counter> shedCounters = new EnumMap<>(Tier.class);

    /**
     * @param rateOverrides per-tier requests/sec, keyed by lower-cased tier name
     *                      ({@code playhead.admission.playback_write=700}). Absent keys keep the
     *                      enum default.
     */
    public AdmissionControl(
            MeterRegistry meterRegistry,
            @Value("#{${playhead.admission:{:}}}") Map<String, Integer> rateOverrides) {

        for (Tier tier : Tier.values()) {
            Integer override = rateOverrides.get(tier.name().toLowerCase(Locale.ROOT));
            long capacity = override != null ? override : tier.capacity;
            double refill = override != null ? override : tier.refillTokensPerSecond;
            buckets.put(tier, new TokenBucket(capacity, refill));
            admittedCounters.put(tier, Counter.builder("admission.admitted")
                    .description("Requests admitted, by priority tier")
                    .tag("tier", tier.name())
                    .register(meterRegistry));
            shedCounters.put(tier, Counter.builder("admission.shed")
                    .description("Requests shed by admission control, by priority tier")
                    .tag("tier", tier.name())
                    .register(meterRegistry));
        }
    }

    /**
     * Whether a request in this tier should be served now. {@code false} means the tier's bucket is
     * empty and the caller should return {@code 429} with a {@code Retry-After} header.
     */
    public boolean tryAdmit(Tier tier) {
        if (buckets.get(tier).tryConsume()) {
            admittedCounters.get(tier).increment();
            return true;
        }
        shedCounters.get(tier).increment();
        return false;
    }
}
