package com.playhead;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Holds one {@link PlaybackState} per (profile, title) pair and applies incoming heartbeats to it.
 *
 * <p>This is the write path at its smallest: look up the current state for a heartbeat's key,
 * fold the heartbeat in, save the result back — all as one atomic step via
 * {@link Map#compute}, so no interleaving between the read and the write can drop an update.
 * A key never seen before starts from a placeholder state at position 0, sequence 0 — so
 * {@link Fold#fold} never has to handle {@code null}.
 *
 * <p>{@link ConcurrentHashMap} locks per-key internally, not the whole map — two threads updating
 * different (profile, title) pairs run fully in parallel. A single {@code synchronized} method
 * would have serialised every request through one lock, regardless of which profile it touched;
 * that would cap throughput under exactly the surge load phase 2 exists to measure.
 */
@Component
public class Store {

    private final Map<Key, PlaybackState> states = new ConcurrentHashMap<>();

    public PlaybackState applyHeartbeat(Heartbeat heartbeat) {
        Key key = new Key(heartbeat.profileId(), heartbeat.titleId());

        return states.compute(key, (k, current) -> {
            PlaybackState base = current == null
                    ? new PlaybackState(heartbeat.profileId(), heartbeat.titleId(), 0, heartbeat.durationSeconds(), 0)
                    : current;
            return Fold.fold(base, heartbeat);
        });
    }

    public PlaybackState get(Key key) {
        return states.get(key);
    }
}
