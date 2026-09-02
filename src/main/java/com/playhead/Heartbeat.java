package com.playhead;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One playback heartbeat: a player telling the backend where it currently is in a title.
 *
 * <p>A player emits one of these roughly every ten seconds while something is playing. It is the
 * highest-volume message in the system and the reason the write path exists, so its shape is
 * fixed early — player clients depend on this contract and changing it later is expensive.
 *
 * <p>{@code deviceId} and {@code sequence} are carried before anything reads them. Cross-device
 * resume needs both: {@code sequence} is what lets a late heartbeat be recognised as stale and
 * ignored, instead of rewinding a viewer who has already moved on.
 */
public record Heartbeat(
        @NotBlank String profileId,
        @NotBlank String titleId,
        @NotBlank String deviceId,
        @PositiveOrZero int positionSeconds,
        @Positive int durationSeconds,
        long clientTimestamp,
        @PositiveOrZero long sequence
) {
}
