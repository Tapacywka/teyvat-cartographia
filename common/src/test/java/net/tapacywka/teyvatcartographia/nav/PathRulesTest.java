package net.tapacywka.teyvatcartographia.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the path is searched again (B10): the pulse key near the line only shows the stars again, off the line it
 * searches; a line that stops short of the target goes on once the player reaches its end.
 */
class PathRulesTest {
    @Test
    void thePulseKeyNearTheLineKeepsThePath() {
        Route.useForTest(new float[]{0, 0, 100, 0}, 2);
        assertTrue(Route.nearLine(50, 0));
        assertTrue(Route.nearLine(50, 8), "8 blocks off is still near");
        assertFalse(Route.nearLine(50, 8.5), "farther off it searches again");
        assertFalse(Route.nearLine(130, 0), "past the end too");
    }

    @Test
    void aLineShortOfTheTargetGoesOnAtItsEnd() {
        // The line ends at (100, 0), the target is 200 blocks farther (underground, beyond the far path's reach).
        assertTrue(PathFinder.continuesAtEnd(95, 0, 100, 0, 300, 0), "the player near the end");
        assertFalse(PathFinder.continuesAtEnd(50, 0, 100, 0, 300, 0), "still walking along it");
    }

    @Test
    void aLineThatReachesTheTargetDoesNotGoOn() {
        assertFalse(PathFinder.continuesAtEnd(99, 0, 100, 0, 102, 0));
    }
}
