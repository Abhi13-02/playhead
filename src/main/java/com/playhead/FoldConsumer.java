package com.playhead;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class FoldConsumer {

    private final Store store;

    public FoldConsumer(Store store) {
        this.store = store;
    }

    @KafkaListener(topics = "heartbeats", groupId = "fold-consumer")
    public void onHeartbeat(Heartbeat heartbeat, Acknowledgment ack) {
        store.applyHeartbeat(heartbeat);
        ack.acknowledge();
    }
}
