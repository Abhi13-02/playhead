package com.playhead.controller;

import com.playhead.domain.Heartbeat;
import com.playhead.service.TokenBucket;

import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.concurrent.Semaphore;

@RestController
public class IngestController {

    private static final String TOPIC = "heartbeats";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    // Sized against the measured ceiling in BENCHMARKS.md Run A (~10,000-13,000 req/s): capped
    // comfortably below it so admitted traffic never approaches the danger zone.
    private final TokenBucket writeTokenBucket = new TokenBucket(8000, 8000); // capacity 8000, refills 8000/sec
    private final Semaphore inFlightPermits = new Semaphore(200); // backlog safety net, not the primary limiter

    public IngestController(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/v1/playback/heartbeat")
    public ResponseEntity<Void> receiveHeartbeat(@Valid @RequestBody Heartbeat heartbeat) {
        if (!writeTokenBucket.tryConsume()) {
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
