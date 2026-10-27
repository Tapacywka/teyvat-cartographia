package net.tapacywka.teyvatcartographia.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where the far path goes on after the near path ({@link Route#join}). A target on an island within the near path's
 * reach: the near path ends on the shore nearest to it, and the far path joined at a point near the player drew a
 * straight line back across the lake and the way round it once more (24.10.2026).
 */
class RouteJoinTest {
    /** A far path through the given corners, a point every 4 blocks (as FarPath spaces them). */
    private static float[] path(float... corners) {
        float[] out = new float[2 * 1024];
        int n = 0;
        for (int c = 0; c + 3 < corners.length; c += 2) {
            float ax = corners[c], az = corners[c + 1], bx = corners[c + 2], bz = corners[c + 3];
            int steps = (int) Math.ceil(Math.hypot(bx - ax, bz - az) / 4);
            for (int i = 0; i < steps; i++) {
                out[2 * n] = ax + (bx - ax) * i / steps;
                out[2 * n + 1] = az + (bz - az) * i / steps;
                n++;
            }
        }
        out[2 * n] = corners[corners.length - 2];
        out[2 * n + 1] = corners[corners.length - 1];
        count = n + 1;
        return out;
    }

    private static int count;

    @Test
    void theFarPathGoesOnAfterItsPointNearestTheNearEnd() {
        float[] far = path(0, 0, 400, 0);
        // The near path ends at x = 100, the far path's point 25.
        assertEquals(26, Route.join(far, count, 100, 1));
    }

    @Test
    void aFarPathLoopingBackPastTheEndIsNotCutShort() {
        // Out east 120 blocks, round, back west 4 blocks off: near the end the way back passes closer than the way out.
        float[] far = path(0, 0, 120, 0, 120, 4, 0, 4);
        assertEquals(16, Route.join(far, count, 60, 3), "joins the way out (point 15), not the way back");
    }

    @Test
    void anEndPastTheFirstPointsStillFindsItsPlace() {
        float[] far = path(0, 0, 400, 0);
        // Point 75, beyond the first 40.
        assertEquals(76, Route.join(far, count, 300, 2));
    }

    @Test
    void aNearPathEndingAwayFromTheFarPathStandsAlone() {
        // The island: round the lake on the far path; the near path ends on another shore, 30 blocks from it.
        float[] far = path(0, 0, 0, 100, 100, 100);
        assertEquals(count, Route.join(far, count, 70, 50));
    }
}
