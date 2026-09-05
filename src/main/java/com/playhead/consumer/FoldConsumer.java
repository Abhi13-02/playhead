package com.playhead.consumer;

import com.playhead.domain.Fold;
import com.playhead.domain.Heartbeat;
import com.playhead.domain.PlaybackState;
import com.playhead.service.PopularityService;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Consumes the {@code heartbeats} topic and folds each heartbeat into PostgreSQL, then mirrors the
 * new current state into Redis for the read path.
 *
 * <p>Two writes per message go to Postgres in one transaction: an unconditional append to
 * {@code playback_events} (the immutable log), and an upsert into {@code playback_state} (the
 * current answer). The anti-rewind rule lives in the upsert's
 * {@code WHERE excluded.sequence > playback_state.sequence} clause — a stale or duplicate heartbeat
 * updates nothing — so this class never calls {@link Fold} and never reads the current row first.
 * Postgres row-locks the primary key, which serialises concurrent consumers on the same key without
 * any application locking (DECISIONS.md D-019).
 *
 * <p>After the Postgres writes, and only when the upsert actually applied (a stale heartbeat
 * changes zero rows), the folded state is written to Redis under {@code state:{profileId}:{titleId}}
 * with a one-hour TTL. Redis is a disposable read-path accelerator, not a system of record: it is
 * outside the transaction, its failure is logged and swallowed, and a missed write self-heals on
 * the next heartbeat for that key or on a cache-aside miss that reloads from Postgres.
 *
 * <p>The Kafka offset is committed only after the method returns cleanly. A failed Postgres
 * transaction throws, the offset is not advanced, and the message is redelivered — safe because the
 * upsert's sequence guard makes reprocessing a no-op.
 *
 * <p>Every heartbeat, including stale or duplicate ones, also casts one vote for its title in
 * {@link PopularityService} — unlike the state upsert, popularity is a rough "how much is being
 * watched" signal (D-034), not a source of truth, so a duplicate vote is harmless and not worth
 * gating on {@code stateRowsApplied}.
 */
@Component
public class FoldConsumer {

    private static final Logger log = LoggerFactory.getLogger(FoldConsumer.class);

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private static final String INSERT_EVENT = """
            INSERT INTO playback_events
                (profile_id, title_id, device_id, position_seconds, duration_seconds,
                 client_timestamp, sequence)
            VALUES (:profileId, :titleId, :deviceId, :position, :duration, :clientTimestamp, :sequence)
            """;

    private static final String UPSERT_STATE = """
            INSERT INTO playback_state
                (profile_id, title_id, position_seconds, duration_seconds, sequence, updated_at)
            VALUES (:profileId, :titleId, :position, :duration, :sequence, now())
            ON CONFLICT (profile_id, title_id) DO UPDATE SET
                position_seconds = excluded.position_seconds,
                duration_seconds = excluded.duration_seconds,
                sequence         = excluded.sequence,
                updated_at       = now()
            WHERE excluded.sequence > playback_state.sequence
            """;

    private final JdbcClient jdbcClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PopularityService popularityService;

    public FoldConsumer(
            JdbcClient jdbcClient,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            PopularityService popularityService) {
        this.jdbcClient = jdbcClient;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.popularityService = popularityService;
    }

    @KafkaListener(topics = "heartbeats", groupId = "fold-consumer")
    @Transactional
    public void onHeartbeat(Heartbeat heartbeat, Acknowledgment ack) {
        jdbcClient.sql(INSERT_EVENT)
                .param("profileId", heartbeat.profileId())
                .param("titleId", heartbeat.titleId())
                .param("deviceId", heartbeat.deviceId())
                .param("position", heartbeat.positionSeconds())
                .param("duration", heartbeat.durationSeconds())
                .param("clientTimestamp", heartbeat.clientTimestamp())
                .param("sequence", heartbeat.sequence())
                .update();

        int stateRowsApplied = jdbcClient.sql(UPSERT_STATE)
                .param("profileId", heartbeat.profileId())
                .param("titleId", heartbeat.titleId())
                .param("position", heartbeat.positionSeconds())
                .param("duration", heartbeat.durationSeconds())
                .param("sequence", heartbeat.sequence())
                .update();

        if (stateRowsApplied > 0) {
            cacheState(heartbeat);
        }

        popularityService.record(heartbeat.titleId());

        ack.acknowledge();
    }

    private void cacheState(Heartbeat heartbeat) {
        PlaybackState state = new PlaybackState(
                heartbeat.profileId(),
                heartbeat.titleId(),
                heartbeat.positionSeconds(),
                heartbeat.durationSeconds(),
                heartbeat.sequence());
        String key = "state:" + heartbeat.profileId() + ":" + heartbeat.titleId();
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(state), CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis cache write failed for {} — reads will fall back to Postgres", key, e);
        }
    }
}
