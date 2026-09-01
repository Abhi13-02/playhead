/**
 * Combines the current {@link PlaybackState} with one incoming {@link Heartbeat} to produce the
 * next state.
 *
 * <p>This is the mechanism behind cross-device resume correctness (SPEC.md FR-6): a heartbeat is
 * only applied if its {@code sequence} is strictly newer than the one that produced the current
 * state. A stale or duplicate heartbeat — arrived late, retried, or delivered twice — is silently
 * ignored instead of rewinding the viewer.
 *
 * <p>Deliberately never receives a {@code null} current state. The caller is responsible for
 * supplying a real starting {@link PlaybackState} — placeholder or otherwise — so this method has
 * exactly one job: compare two sequence numbers and decide.
 */
public class Fold {

    public static PlaybackState fold(PlaybackState current, Heartbeat next) {
        if (next.sequence() > current.sequence()) {
            return new PlaybackState(
                    next.profileId(),
                    next.titleId(),
                    next.positionSeconds(),
                    next.durationSeconds(),
                    next.sequence()
            );
        }
        return current;
    }
}
