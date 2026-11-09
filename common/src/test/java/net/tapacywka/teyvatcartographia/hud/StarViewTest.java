package net.tapacywka.teyvatcartographia.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stars of the path are built only within the view ({@link PathStars#inView}, profile 09.11.2026): a camera looking
 * north (-z), left -x, up +y, a frustum of 16:9 at a vertical field of view of 70 degrees.
 */
class StarViewTest {
    private static final float SY = (float) Math.tan(Math.toRadians(35)), SX = SY * 16 / 9f;

    private static boolean shown(float x, float y, float z) {
        return PathStars.inView(x, y, z, 0, 0, -1, -1, 0, 0, 0, 1, 0, SX, SY, PathStars.REACH);
    }

    @Test
    void aheadShows() {
        assertTrue(shown(0, 0, -20));
        assertTrue(shown(0, -2, -3), "the ground a little ahead and below");
    }

    @Test
    void behindDoesNot() {
        assertFalse(shown(0, 0, 20));
        assertFalse(shown(3, 1, 5));
    }

    @Test
    void farAsideDoesNot() {
        // 40 blocks to the side at 10 ahead: far past the edge of the view (slope 4 against 1.25).
        assertFalse(shown(40, 0, -10));
        assertFalse(shown(0, 40, -10));
    }

    @Test
    void justOutsideTheEdgeStillShowsWithinItsReach() {
        // Its centre a quarter block past the left edge 10 blocks ahead: its arms reach into the view.
        float edge = 10 * SX;
        assertTrue(shown(-(edge + 0.25f), 0, -10));
        assertFalse(shown(-(edge + 2f), 0, -10), "two blocks past the edge it cannot");
    }

    @Test
    void besideTheCameraShowsWithinReach() {
        // Right at the camera (a sparkle passing by): its arms can still be in the picture.
        assertTrue(shown(0.3f, 0, 0.2f));
    }
}
