package com.playhead;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Holds one {@link PlaybackState} per (profile, title) pair and applies incoming heartbeats to it.
 *
 * <p>This is the write path at its smallest: look up the current state for a heartbeat's key,
 * fold the heartbeat in, save the result back. A key never seen before starts from a placeholder
 * state at position 0, sequence 0 — so {@link Fold#fold} never has to handle {@code null}.
 *
 * <p>Not thread-safe. Concurrency is a deliberate later phase, not an accident here.
 */
@Component
public class Store {

    private final Map<Key, PlaybackState> states = new HashMap<>();

    public PlaybackState applyHeartbeat(Heartbeat heartbeat) {
        Key key = new Key(heartbeat.profileId(), heartbeat.titleId());

        PlaybackState current = states.getOrDefault(
                key,
                new PlaybackState(heartbeat.profileId(), heartbeat.titleId(), 0, heartbeat.durationSeconds(), 0)
        );

        PlaybackState next = Fold.fold(current, heartbeat);
        states.put(key, next);
        return next;
    }

    public PlaybackState get(Key key) {
        return states.get(key);
    }
}
