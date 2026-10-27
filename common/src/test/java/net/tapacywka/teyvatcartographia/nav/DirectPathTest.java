package net.tapacywka.teyvatcartographia.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The straight line to the target (Route: Straight): from the player to the pin, evenly spaced, never too many points. */
class DirectPathTest {
    private final float[] out = new float[2 * DirectPath.MAX_POINTS];

    @Test
    void itRunsFromThePlayerToThePin() {
        int n = DirectPath.line(10.5, -20.5, 110.5, 79.5, out);
        assertEquals(10.5f, out[0], 1e-4);
        assertEquals(-20.5f, out[1], 1e-4);
        assertEquals(110.5f, out[2 * n - 2], 1e-3);
        assertEquals(79.5f, out[2 * n - 1], 1e-3);
        // Every point on the line x - z = 31.
        for (int i = 0; i < n; i++) assertEquals(31f, out[2 * i] - out[2 * i + 1], 1e-3, "point " + i);
    }

    @Test
    void thePointsAreEvenAndAtMostTheSpacingApart() {
        int n = DirectPath.line(0, 0, 1000, 0, out);
        float step = out[2] - out[0];
        assertTrue(step <= DirectPath.SPACING + 1e-3, "step " + step);
        for (int i = 1; i < n; i++) assertEquals(step, out[2 * i] - out[2 * i - 2], 1e-2, "step " + i);
    }

    @Test
    void farAwayTheNumberOfPointsIsCapped() {
        int n = DirectPath.line(0, 0, 0, 30_000, out);
        assertEquals(DirectPath.MAX_POINTS, n);
        assertEquals(30_000f, out[2 * n - 1], 1e-1);
    }

    @Test
    void onThePinThereAreStillTwoPoints() {
        assertEquals(2, DirectPath.line(5, 5, 5, 5, out));
    }
}
