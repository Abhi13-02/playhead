package com.playhead.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the {@code heartbeats} topic explicitly instead of letting the broker auto-create it.
 *
 * <p>Auto-creation was what happened until now, and it silently produced a <b>single partition</b>
 * — because that is the broker default, not a decision anyone made. A partition is the unit of
 * consumer parallelism: one partition can be read by only one consumer in a group at a time, so
 * with one partition every extra {@code fold-consumer} instance sits idle no matter how many
 * replicas run. The scaling work in phase 7 would have been invisible on the consumer side.
 *
 * <p><b>Why 3, and why not a large number.</b> Kafka routes by {@code hash(key) % partitionCount},
 * and the key here is {@code profileId} — that is what keeps one viewer's heartbeats ordered in a
 * single partition, which is what FR-6's anti-rewind rule depends on. Changing the count later
 * changes that formula, so a key's old and new messages can land in different partitions and the
 * per-key ordering guarantee is lost across the change. The count is therefore effectively decided
 * once, and "just make it huge later" is not available.
 *
 * <p>Over-provisioning is not free either: every partition is a set of files the broker keeps open
 * and tracks, and consumer-group rebalances get slower as the count grows. 3 is sized against the
 * realistic consumer count for this system rather than an arbitrary large number.
 *
 * <p>Increasing the count on the already-populated dev topic is safe here only because the existing
 * rows are load-test data. On a real topic this is a migration, not a config change.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic heartbeatsTopic() {
        return new NewTopic("heartbeats", 3, (short) 1);
    }
}
