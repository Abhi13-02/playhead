package com.playhead.service;

import com.playhead.domain.PlaybackState;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import tools.jackson.databind.ObjectMapper;

/**
 * The read path for playback state: resume position (FR-3) and continue-watching (FR-4, FR-5).
 *
 * <p>Both reads are cache-aside. {@code resume} has three tiers — a per-instance Caffeine cache, a
 * shared Redis cache, then Postgres — because it is a lookup by exact key that Redis serves well.
 * {@code continueWatching} has two — Caffeine, then Postgres — because it is a sorted, filtered,
 * paginated list that the {@code idx_continue_watching} covering index already answers in well
 * under a millisecond; a Redis copy would cost a per-heartbeat invalidation for no gain
 * (DECISIONS.md D-023). Every tier populates the ones above it on the way back.
 *
 * <p>"Completed" is not stored. A title past 95% of its duration is excluded by the
 * continue-watching query's {@code WHERE} clause.
 *
 * <p>Redis is a best-effort tier: a failed Redis read or write is logged and treated as a cache
 * miss, so a Redis outage degrades the read path to Postgres rather than breaking it.
 */
@Service
public class PlaybackReadService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackReadService.class);

    private static final Duration REDIS_TTL = Duration.ofHours(1);

    // One breaker for the Redis dependency, shared by the read and write helpers below. Trips after
    // 5 consecutive failures; stays open 5 s before letting a single probe through. Drill 1 showed a
    // bounded per-call timeout is not enough on its own (ENGINEERING_LOG.md, DECISIONS.md D-027).
    private final CircuitBreaker redisBreaker = new CircuitBreaker("redis", 5, Duration.ofSeconds(5));

    private static final String SELECT_STATE = """
            SELECT profile_id, title_id, position_seconds, duration_seconds, sequence
            FROM playback_state
            WHERE profile_id = :profileId AND title_id = :titleId
            """;

    private static final String SELECT_CONTINUE_WATCHING = """
            SELECT profile_id, title_id, position_seconds, duration_seconds, sequence
            FROM playback_state
            WHERE profile_id = :profileId
              AND position_seconds < 0.95 * duration_seconds
            ORDER BY updated_at DESC
            LIMIT :size OFFSET :offset
            """;

    private final Cache<String, PlaybackState> resumeCache;
    private final Cache<String, List<PlaybackState>> continueWatchingCache;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PlaybackReadService(
            Cache<String, PlaybackState> resumeCache,
            Cache<String, List<PlaybackState>> continueWatchingCache,
            StringRedisTemplate redis,
            JdbcClient jdbcClient,
            ObjectMapper objectMapper) {
        this.resumeCache = resumeCache;
        this.continueWatchingCache = continueWatchingCache;
        this.redis = redis;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /**
     * The resume position for one (profile, title), or empty if never watched. Cache-aside across
     * Caffeine, then Redis, then Postgres; a hit at any tier populates the tiers above it.
     */
    public Optional<PlaybackState> resume(String profileId, String titleId) {
        String key = "state:" + profileId + ":" + titleId;

        PlaybackState local = resumeCache.getIfPresent(key);
        if (local != null) {
            return Optional.of(local);
        }

        String cached = readFromRedis(key);
        if (cached != null) {
            PlaybackState state = objectMapper.readValue(cached, PlaybackState.class);
            resumeCache.put(key, state);
            return Optional.of(state);
        }

        Optional<PlaybackState> fromDb = jdbcClient.sql(SELECT_STATE)
                .param("profileId", profileId)
                .param("titleId", titleId)
                .query(PlaybackState.class)
                .optional();

        fromDb.ifPresent(state -> {
            writeToRedis(key, state);
            resumeCache.put(key, state);
        });
        return fromDb;
    }

    /**
     * A profile's in-progress titles, most recently watched first, one page at a time. Titles at or
     * past 95% of their duration are excluded (FR-5). Cache-aside across Caffeine, then Postgres.
     */
    public List<PlaybackState> continueWatching(String profileId, int page, int size) {
        String key = profileId + ":" + page + ":" + size;

        List<PlaybackState> local = continueWatchingCache.getIfPresent(key);
        if (local != null) {
            return local;
        }

        List<PlaybackState> fromDb = jdbcClient.sql(SELECT_CONTINUE_WATCHING)
                .param("profileId", profileId)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(PlaybackState.class)
                .list();

        continueWatchingCache.put(key, fromDb);
        return fromDb;
    }

    private String readFromRedis(String key) {
        if (!redisBreaker.allowRequest()) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(key);
            redisBreaker.recordSuccess();
            return value;
        } catch (Exception e) {
            redisBreaker.recordFailure();
            log.warn("Redis read failed for {} — falling back to Postgres", key, e);
            return null;
        }
    }

    private void writeToRedis(String key, PlaybackState state) {
        if (!redisBreaker.allowRequest()) {
            return;
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(state), REDIS_TTL);
            redisBreaker.recordSuccess();
        } catch (Exception e) {
            redisBreaker.recordFailure();
            log.warn("Redis write failed for {} — cache will fill on a later read", key, e);
        }
    }
}
