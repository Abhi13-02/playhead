package com.playhead.domain;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One upcoming tentpole — a premiere or live match with a known start time (FR-7).
 *
 * <p>Nothing to do with {@link Heartbeat} or the {@code playback_events} log, despite "event"
 * appearing in both. That log records what viewers have already done; this is a short forward
 * list of what is about to happen, and it exists only so capacity can be raised before the traffic
 * arrives rather than in response to it (FR-8, DIFF-2).
 *
 * <p>{@code expectedPeakRps} is the field the whole phase turns on. Dividing it by the measured
 * per-instance ceiling from BENCHMARKS.md is what converts "a premiere is coming" into a concrete
 * replica count, so it is required rather than optional — an event without it cannot be planned
 * for, only reacted to.
 *
 * @param id assigned by Postgres on insert; null on the way in
 */
public record ScheduledEvent(
        Long id,
        @NotBlank String name,
        @NotNull Instant startsAt,
        @Positive int expectedPeakRps,
        List<String> titleIds
) {
}
