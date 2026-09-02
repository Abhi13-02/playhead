package com.playhead;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
public class IngestController {

    private final Store store;

    public IngestController(Store store) {
        this.store = store;
    }

    @PostMapping("/v1/playback/heartbeat")
    public ResponseEntity<Void> receiveHeartbeat(@Valid @RequestBody Heartbeat heartbeat) {
        store.applyHeartbeat(heartbeat);
        return ResponseEntity.status(202).build();
    }
}
