package net.tapacywka.teyvatcartographia.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The minimap cuts the path line exactly at its edge (user report 25.10.2026: it ended far inside the frame). */
class EdgeClipTest {
    @Test
    void aLineLeavingTheRoundMapEndsOnItsEdge() {
        // From the centre 100 units right, the map's radius 50: the line ends half way, on the edge.
        assertTrue(EdgeClip.clip(0, 0, 100, 0, 0, 0, 50, false));
        assertEquals(0, EdgeClip.t0(), 1e-6);
        assertEquals(0.5f, EdgeClip.t1(), 1e-6);
    }

    @Test
    void aLineCrossingTheRoundMapIsCutAtBothSides() {
        assertTrue(EdgeClip.clip(-100, 30, 100, 30, 0, 0, 50, false));
        // x from -40 to 40 at y 30: t 0.3 to 0.7.
        assertEquals(0.3f, EdgeClip.t0(), 1e-5);
        assertEquals(0.7f, EdgeClip.t1(), 1e-5);
        assertFalse(EdgeClip.clip(-100, 60, 100, 60, 0, 0, 50, false), "passing outside");
    }

    @Test
    void theSquareMapReachesItsCorners() {
        // A diagonal from the centre: the round map ends at radius 50, the square one at its corner (50, 50).
        assertTrue(EdgeClip.clip(0, 0, 100, 100, 0, 0, 50, true));
        assertEquals(0.5f, EdgeClip.t1(), 1e-6);
        assertTrue(EdgeClip.clip(0, 0, 100, 100, 0, 0, 50, false));
        assertEquals(50 / Math.hypot(100, 100), EdgeClip.t1(), 1e-5);
        assertTrue(EdgeClip.clip(-100, 20, 100, 20, 0, 0, 50, true));
        assertEquals(0.25f, EdgeClip.t0(), 1e-6);
        assertEquals(0.75f, EdgeClip.t1(), 1e-6);
    }

    @Test
    void insideStaysWhole() {
        assertTrue(EdgeClip.clip(-10, -10, 10, 5, 0, 0, 50, false));
        assertEquals(0, EdgeClip.t0(), 1e-6);
        assertEquals(1, EdgeClip.t1(), 1e-6);
        assertTrue(EdgeClip.clip(-10, -10, 10, 5, 0, 0, 50, true));
        assertEquals(1, EdgeClip.t1(), 1e-6);
    }
}
