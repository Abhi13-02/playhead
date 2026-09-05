package com.playhead.controller;

import com.playhead.domain.Heartbeat;
import com.playhead.service.AdmissionControl;

import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.util.concurrent.Semaphore;

@RestController
public class IngestController {

    private static final String TOPIC = "heartbeats";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AdmissionControl admissionControl;

    // Backlog safety net, not the primary limiter. This bounds how many requests can be *in flight*
    // waiting on Kafka at once, which is a different failure from too many arriving per second —
    // that one is the token bucket's job, in AdmissionControl. Deliberately not merged.
    //
    // Sized from measurement, not guessed: a write-only k6 run at the 7,000/s target admitted by
    // AdmissionControl's PLAYBACK_WRITE bucket showed a real per-heartbeat Kafka confirm wait of
    // p95 26 ms (kafka.producer.record.queue.time + kafka.producer.request.latency, actuator
    // metrics). By Little's Law that means ~182 heartbeats are in flight at once even with nothing
    // else competing for CPU — the old value of 200 had effectively zero headroom, which is why it
    // alone (not the bucket) produced every 429 in that run.
    //
    // 1,000 (first resize) still shed 25,675 writes (7.1%) under the full mixed surge, because
    // reads sharing the same CPU push the real wait time higher than the isolated 26 ms. Doubling
    // to 2,000 was tried and measured worse (87.20% success vs 89.32%, with the door itself
    // shedding *zero* — every remaining loss moved to the OS refusing the connection outright).
    // That is the real signal: past this point the ceiling is total machine capacity (this one
    // laptop running the load generator, the app, and Kafka/Postgres/Redis together), not this
    // door's size. 1,000 kept as the better of the two measured values (ENGINEERING_LOG.md).
    private static final int PERMITS = 1000;

    private final Semaphore inFlightPermits = new Semaphore(PERMITS);

    public IngestController(
            KafkaTemplate<String, Object> kafkaTemplate,
            AdmissionControl admissionControl,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.admissionControl = admissionControl;

        // How many heartbeats are *currently* waiting on Kafka — the live depth of the door above,
        // which is a gauge and not a count of anything that has happened. The dashboard panel named
        // for this used to be wired to admission.admitted, a cumulative counter that only ever
        // climbs, so it showed a number that could never fall and could never indicate saturation.
        // Little's Law sized the permit count from a measured wait; this reports whether that sizing
        // is holding under load, which is the only reason the panel is worth a slot.
        Gauge.builder("playhead.ingest.inflight.permits",
                        inFlightPermits, s -> PERMITS - s.availablePermits())
                .description("Heartbeat requests currently in flight awaiting a Kafka confirm")
                .register(meterRegistry);
    }

    @PostMapping("/v1/playback/heartbeat")
    public ResponseEntity<Void> receiveHeartbeat(@Valid @RequestBody Heartbeat heartbeat) {
        if (!admissionControl.tryAdmit(AdmissionControl.Tier.PLAYBACK_WRITE)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "1")
                    .build();
        }
        if (!inFlightPermits.tryAcquire()) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "1")
                    .build();
        }
        try {
            kafkaTemplate.send(TOPIC, heartbeat.profileId(), heartbeat).get();
            return ResponseEntity.status(202).build();
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        } finally {
            inFlightPermits.release();
        }
    }
}
