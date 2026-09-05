package com.playhead.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * "Popular now" — the top 20 titles by heartbeat volume over the last {@value #WINDOW_MINUTES}
 * minutes. Backs the {@code BROWSE_READ} tier: browse traffic, safe to shed under overload and
 * safe to lose outright, unlike playback state (FR-3/FR-4).
 *
 * <p>Counts live only in Redis, one sorted set per minute — {@code popular:<yyyyMMddHHmm>}, member
 * = titleId, score = heartbeat count. A heartbeat is one {@code ZINCRBY}; the key is created on
 * first write and expires on its own {@value #BUCKET_TTL_MINUTES} minutes later, so nothing is ever
 * explicitly deleted. Reading "now" unions the last {@value #WINDOW_MINUTES} per-minute keys into a
 * scratch key and takes its top 20 — that union is what turns a set of fixed buckets into a sliding
 * window, and it is why the count is a proxy (heartbeats, not distinct viewers) rather than an exact
 * figure. There is deliberately no Postgres copy: this list rebuilds itself from live traffic within
 * {@value #WINDOW_MINUTES} minutes of any outage, so persisting it would buy nothing (D-007 territory
 * — no bespoke top-K structure either; a Redis sorted set already keeps itself ordered).
 *
 * <p>The union is real work against 15 keys, so reads go through two caching tiers before touching
 * it, mirroring the Caffeine-then-Redis-then-Postgres tiering already used on the read path
 * (D-023): (1) a per-instance snapshot held {@value #SNAPSHOT_TTL_SECONDS} seconds behind a single
 * {@link AtomicReference} — instant, no network call; (2) a snapshot of the same age shared in
 * Redis under {@value #SHARED_SNAPSHOT_KEY} — one cheap {@code GET}, shared by every replica, so
 * with N instances only whichever one's local snapshot expires first pays for the union in any
 * given 5-second window, not all N. The list and its timestamp are bundled into one immutable
 * {@link Snapshot} so the local swap is one atomic write — two separate fields would let a reader
 * observe a new list paired with a stale timestamp, and a lock to prevent that would cost more,
 * under {@code BROWSE_READ}'s traffic, than the five-second staleness it is guarding against.
 *
 * <p>Redis is a best-effort dependency here, same as the read path (D-025/D-027): a failed write is
 * logged and dropped (a missed vote self-heals with the next heartbeat), and a failed read serves
 * the last known snapshot rather than an error — this list is never worth a 500.
 */
@Service
public class PopularityService {

    private static final Logger log = LoggerFactory.getLogger(PopularityService.class);

    private static final String BUCKET_PREFIX = "popular:";
    private static final DateTimeFormatter BUCKET_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private static final int WINDOW_MINUTES = 15;
    private static final int BUCKET_TTL_MINUTES = 20;
    private static final int TOP_N = 20;

    private static final String UNION_KEY = "popular:combined";
    private static final Duration UNION_TTL = Duration.ofSeconds(30);
    private static final int SNAPSHOT_TTL_SECONDS = 5;

    // Shared across every replica, so only one instance per 5-second window pays for the union.
    private static final String SHARED_SNAPSHOT_KEY = "popular:snapshot";
    private static final Duration SHARED_SNAPSHOT_TTL = Duration.ofSeconds(SNAPSHOT_TTL_SECONDS);

    private record Snapshot(List<String> titles, Instant computedAt) {
    }

    private final StringRedisTemplate redis;
    private final CircuitBreaker redisBreaker = new CircuitBreaker("redis-popularity", 5, Duration.ofSeconds(5));
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(List.of(), Instant.EPOCH));

    public PopularityService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records one heartbeat's vote for a title in the current minute's bucket (FR unnumbered — this
     * feature sits outside the spec's original scope; see DECISIONS.md). Best-effort: a Redis
     * failure is logged and swallowed, never propagated to the caller.
     */
    public void record(String titleId) {
        if (!redisBreaker.allowRequest()) {
            return;
        }
        try {
            String key = bucketKey(Instant.now());
            redis.opsForZSet().incrementScore(key, titleId, 1);
            redis.expire(key, Duration.ofMinutes(BUCKET_TTL_MINUTES));
            redisBreaker.recordSuccess();
        } catch (Exception e) {
            redisBreaker.recordFailure();
            log.warn("Redis write failed while recording popularity vote for {}", titleId, e);
        }
    }

    /**
     * The top 20 titles by heartbeat volume over the last {@value #WINDOW_MINUTES} minutes. Served
     * from a 5-second in-memory snapshot; recomputed from Redis only when that snapshot has expired,
     * and falls back to the last known snapshot (possibly empty) if Redis is unavailable.
     */
    public List<String> topTitles() {
        Snapshot current = snapshot.get();
        boolean fresh = Duration.between(current.computedAt(), Instant.now())
                .compareTo(Duration.ofSeconds(SNAPSHOT_TTL_SECONDS)) < 0;
        if (fresh) {
            return current.titles();
        }

        List<String> refreshed = readSharedSnapshotOrRecompute();
        if (refreshed == null) {
            return current.titles();
        }
        snapshot.set(new Snapshot(refreshed, Instant.now()));
        return refreshed;
    }

    /**
     * The second cache tier: a 5-second snapshot shared in Redis by every replica. A hit here means
     * some other instance already paid for the union in this 5-second window. A miss means this
     * instance does, and it is the one that populates the shared key for everyone else.
     */
    private List<String> readSharedSnapshotOrRecompute() {
        if (!redisBreaker.allowRequest()) {
            return null;
        }
        try {
            String cached = redis.opsForValue().get(SHARED_SNAPSHOT_KEY);
            redisBreaker.recordSuccess();
            if (cached != null) {
                return decodeSnapshot(cached);
            }
        } catch (Exception e) {
            redisBreaker.recordFailure();
            log.warn("Redis read failed while checking the shared popularity snapshot", e);
            return null;
        }
        return recomputeFromRedis();
    }

    private List<String> recomputeFromRedis() {
        if (!redisBreaker.allowRequest()) {
            return null;
        }
        try {
            List<String> bucketKeys = recentBucketKeys();
            redis.opsForZSet().unionAndStore(
                    bucketKeys.get(0), bucketKeys.subList(1, bucketKeys.size()), UNION_KEY);
            redis.expire(UNION_KEY, UNION_TTL);

            Set<String> top = redis.opsForZSet().reverseRange(UNION_KEY, 0, TOP_N - 1);
            List<String> result = top == null ? List.of() : new ArrayList<>(top);

            redis.opsForValue().set(SHARED_SNAPSHOT_KEY, encodeSnapshot(result), SHARED_SNAPSHOT_TTL);
            redisBreaker.recordSuccess();
            return result;
        } catch (Exception e) {
            redisBreaker.recordFailure();
            log.warn("Redis read failed while computing popular-now — serving last known list", e);
            return null;
        }
    }

    private static String encodeSnapshot(List<String> titles) {
        return String.join("\n", titles);
    }

    private static List<String> decodeSnapshot(String value) {
        return value.isEmpty() ? List.of() : Arrays.asList(value.split("\n", -1));
    }

    private List<String> recentBucketKeys() {
        Instant now = Instant.now();
        List<String> keys = new ArrayList<>(WINDOW_MINUTES);
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            keys.add(bucketKey(now.minus(Duration.ofMinutes(i))));
        }
        return keys;
    }

    private String bucketKey(Instant instant) {
        return BUCKET_PREFIX + BUCKET_FORMAT.format(instant);
    }
}
