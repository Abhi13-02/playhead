package com.playhead.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal circuit breaker for a single downstream dependency.
 *
 * <p>A per-call timeout bounds one slow call; it does nothing to stop a caller making thousands of
 * doomed calls per second at a dependency that is already known to be down. Phase-6 Drill 1 measured
 * that gap directly: with Redis killed under load, a 50 ms command timeout still produced a ~12.8 s
 * p50 on the read path, because the flood of failing calls buried Lettuce's single connection worker
 * (ENGINEERING_LOG.md, DECISIONS.md D-027). This breaker cuts the call rate to a dead dependency to
 * zero.
 *
 * <p>Three states:
 * <ul>
 *   <li><b>CLOSED</b> — calls flow. Consecutive failures are counted; reaching the threshold trips
 *       the breaker to OPEN.
 *   <li><b>OPEN</b> — calls are refused instantly ({@link #allowRequest()} returns {@code false}),
 *       so the caller skips the dependency and takes its fallback path with no wait. After the open
 *       duration elapses the breaker moves to HALF_OPEN.
 *   <li><b>HALF_OPEN</b> — exactly one probe call is allowed through. It succeeding closes the
 *       breaker; it failing re-opens it for another full open duration.
 * </ul>
 *
 * <p>The caller drives the state machine: check {@link #allowRequest()} before the call, then report
 * the outcome with {@link #recordSuccess()} or {@link #recordFailure()}.
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long openDurationNanos;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtNanos = 0;
    private boolean probeInFlight = false;

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDurationNanos = openDuration.toNanos();
    }

    /**
     * Whether the guarded call should be attempted now. {@code false} means the breaker is OPEN (or
     * HALF_OPEN with its one probe already out) and the caller should go straight to its fallback.
     */
    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (System.nanoTime() - openedAtNanos >= openDurationNanos) {
                state = State.HALF_OPEN;
                probeInFlight = true;
                log.info("Circuit breaker '{}' OPEN -> HALF_OPEN, letting one probe through", name);
                return true;
            }
            return false;
        }
        if (!probeInFlight) {
            probeInFlight = true;
            return true;
        }
        return false;
    }

    /** Report that a guarded call succeeded. Closes the breaker and clears the failure count. */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        probeInFlight = false;
        if (state != State.CLOSED) {
            log.info("Circuit breaker '{}' {} -> CLOSED", name, state);
            state = State.CLOSED;
        }
    }

    /** Report that a guarded call failed. Trips the breaker once failures reach the threshold. */
    public synchronized void recordFailure() {
        consecutiveFailures++;
        probeInFlight = false;
        boolean shouldOpen = state == State.HALF_OPEN || consecutiveFailures >= failureThreshold;
        if (state != State.OPEN && shouldOpen) {
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
            log.warn("Circuit breaker '{}' -> OPEN after {} consecutive failures, skipping '{}' for {} ms",
                    name, consecutiveFailures, name, openDurationNanos / 1_000_000);
        }
    }

    /** Current state name, for logging and drill evidence. */
    public synchronized String state() {
        return state.name();
    }
}
