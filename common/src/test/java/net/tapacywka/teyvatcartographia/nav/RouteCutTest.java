package net.tapacywka.teyvatcartographia.nav;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the walked part of the line ends (B11, B19): the map, the minimap and the stars start there. It moves forward
 * only, does not jump to a part of a looping line that passes close by, and finds the line again for a player far off it.
 */
class RouteCutTest {
    /** A line through the given corners, a point every block. */
    private static void line(float... corners) {
        List<Float> pts = new ArrayList<>();
        for (int c = 0; c + 3 < corners.length; c += 2) {
            float ax = corners[c], az = corners[c + 1], bx = corners[c + 2], bz = corners[c + 3];
            int steps = (int) Math.ceil(Math.hypot(bx - ax, bz - az));
            for (int i = 0; i < steps; i++) {
                pts.add(ax + (bx - ax) * i / steps);
                pts.add(az + (bz - az) * i / steps);
            }
        }
        pts.add(corners[corners.length - 2]);
        pts.add(corners[corners.length - 1]);
        float[] a = new float[pts.size()];
        for (int i = 0; i < a.length; i++) a[i] = pts.get(i);
        Route.useForTest(a, a.length / 2);
    }

    @Test
    void theCutIsThePointOfTheLineNearestThePlayer() {
        line(0, 0, 100, 0);
        Route.cut(30, 3);
        assertEquals(30, Route.cutX(), 0.01);
        assertEquals(0, Route.cutZ(), 0.01);
        assertEquals(30, Route.cutAlong(), 0.01);
    }

    @Test
    void itMovesForwardOnly() {
        line(0, 0, 100, 0);
        Route.cut(30, 0);
        // A few blocks back: the cut may follow a little (4 blocks and the rest of a segment, here 1 block), the
        // walked part stays.
        Route.cut(20, 0);
        assertTrue(Route.cutAlong() >= 25 - 0.01, "at most 5 blocks back, at " + Route.cutAlong());
        Route.cut(10, 0);
        assertTrue(Route.cutAlong() >= 25 - 0.01, "still not farther back, at " + Route.cutAlong());
        Route.cut(40, 0);
        assertEquals(40, Route.cutAlong(), 0.01);
    }

    @Test
    void aLoopingLineDoesNotJumpToItsReturn() {
        // B19: out east along z = 0, round, back west along z = 6. A player at z = 4 near the start is closer to the
        // return, but has walked only the first blocks: the cut stays on the way out.
        line(0, 0, 80, 0, 80, 6, 0, 6);
        Route.cut(2, 0);
        Route.cut(10, 4);
        assertEquals(0, Route.cutZ(), 0.01, "still on the way out");
        assertTrue(Route.cutAlong() < 20, "near the start, at " + Route.cutAlong());
    }

    @Test
    void walkingOnTheCutFollows() {
        line(0, 0, 80, 0, 80, 6, 0, 6);
        for (int x = 0; x <= 80; x += 10) Route.cut(x, 0);
        Route.cut(80, 3);
        Route.cut(70, 6);
        assertEquals(6, Route.cutZ(), 0.01, "on the way back now");
        assertTrue(Route.cutAlong() > 80, "past the turn, at " + Route.cutAlong());
    }

    @Test
    void aPlayerFarOffTheStretchFindsTheNearestPointOfTheWholeLine() {
        line(0, 0, 200, 0);
        Route.cut(0, 0);
        // Flown far ahead and off the line: more than 16 blocks from the stretch in front, the whole line counts.
        Route.cut(150, 30);
        assertEquals(150, Route.cutX(), 0.01);
    }

    @Test
    void theDistanceToTheLine() {
        line(0, 0, 100, 0);
        assertEquals(7, Route.distanceTo(50, 7), 0.01);
        assertEquals(5, Route.distanceTo(-3, 4), 0.01);
    }
}
