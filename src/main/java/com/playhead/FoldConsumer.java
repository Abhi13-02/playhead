package com.playhead;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes the {@code heartbeats} topic and folds each heartbeat into PostgreSQL.
 *
 * <p>Two writes per message, in one transaction: an unconditional append to {@code playback_events}
 * (the immutable log), and an upsert into {@code playback_state} (the current answer). The
 * anti-rewind rule lives in the upsert's {@code WHERE excluded.sequence > playback_state.sequence}
 * clause — a stale or duplicate heartbeat updates nothing — so this class never calls
 * {@link Fold} and never reads the current row first. Postgres row-locks the primary key, which
 * serialises concurrent consumers on the same key without any application locking (DECISIONS.md
 * D-019).
 *
 * <p>The Kafka offset is committed only after the transaction commits. A failed transaction throws,
 * the offset is not advanced, and the message is redelivered — safe because the upsert's sequence
 * guard makes reprocessing a no-op.
 */
@Component
public class FoldConsumer {

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

    public FoldConsumer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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

        jdbcClient.sql(UPSERT_STATE)
                .param("profileId", heartbeat.profileId())
                .param("titleId", heartbeat.titleId())
                .param("position", heartbeat.positionSeconds())
                .param("duration", heartbeat.durationSeconds())
                .param("sequence", heartbeat.sequence())
                .update();

        ack.acknowledge();
    }
}
