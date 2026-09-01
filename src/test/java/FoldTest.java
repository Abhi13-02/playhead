import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FoldTest {

    @Test
    void appliesANewerHeartbeat() {
        // Arrange
        PlaybackState start = new PlaybackState("p7", "t1", 0, 3600, 0);
        Heartbeat newer = new Heartbeat("p7", "t1", "tv", 120, 3600, 0, 1);

        // Act
        PlaybackState result = Fold.fold(start, newer);

        // Assert
        assertEquals(120, result.positionSeconds());
    }

    @Test
    void ignoresAStaleHeartbeat() {
        // Arrange
        PlaybackState current = new PlaybackState("p7", "t1", 130, 3600, 2);
        Heartbeat stale = new Heartbeat("p7", "t1", "tv", 120, 3600, 0, 1);

        // Act
        PlaybackState result = Fold.fold(current, stale);

        // Assert
        assertEquals(130, result.positionSeconds());
    }

    @Test
    void applyingTheSameHeartbeatTwiceChangesNothing() {
        // Arrange
        PlaybackState start = new PlaybackState("p7", "t1", 0, 3600, 0);
        Heartbeat heartbeat = new Heartbeat("p7", "t1", "tv", 120, 3600, 0, 1);

        // Act
        PlaybackState afterFirst = Fold.fold(start, heartbeat);
        PlaybackState afterSecond = Fold.fold(afterFirst, heartbeat);

        // Assert
        assertEquals(afterFirst, afterSecond);
    }
}
