/**
 * The current, single best-known playback position for one (profile, title) pair.
 *
 * <p>Many {@link Heartbeat}s arrive over time for the same title; this is the result of folding
 * them together, not an event itself. Exactly one {@code PlaybackState} exists per
 * (profileId, titleId) at any moment, and it is replaced — never mutated — as new heartbeats fold
 * into it.
 *
 * <p>Carries {@code sequence} so the fold can tell a genuinely newer heartbeat from a stale or
 * duplicate one. Deliberately does not carry {@code deviceId} or {@code clientTimestamp}: this is
 * the current answer, and the state does not care which device produced it.
 */
public record PlaybackState(
        String profileId,
        String titleId,
        int positionSeconds,
        int durationSeconds,
        long sequence
) {
}
