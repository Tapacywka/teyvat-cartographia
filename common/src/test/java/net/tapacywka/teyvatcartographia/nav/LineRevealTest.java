package net.tapacywka.teyvatcartographia.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The path line drawn in from the player over 3 s after a new search, the old one wiped the same way (26.10.2026). */
class LineRevealTest {
    private static final float[] OLD_XZ = {0, 0, 50, 0, 100, 0}, OLD_ALONG = {0, 50, 100};
    private static final float[] NEW_XZ = {0, 0, 0, 30, 0, 60, 0, 90}, NEW_ALONG = {0, 30, 60, 90};
    private static final boolean[] FLAGS = new boolean[4];

    @Test
    void withNoLineYetNothingBreaks() {
        // The minimap asks for the old line's start every frame, also before any line (it crashed the game once).
        LineReveal r = new LineReveal();
        assertEquals(0, r.oldCount(0));
        assertEquals(0f, r.oldFrom(0), 1e-6);
        assertEquals(Float.POSITIVE_INFINITY, r.shownTo(0, 0));
    }

    @Test
    void theDrawingInTakesThreeSeconds() {
        assertEquals(0f, LineReveal.eased(0), 1e-6);
        assertEquals(0.5f, LineReveal.eased(1500), 1e-6, "half way at 1.5 s (eased)");
        assertTrue(LineReveal.eased(500) < 500 / 3000f, "slow at the start");
        assertEquals(1f, LineReveal.eased(3000), 1e-6);
        assertEquals(1f, LineReveal.eased(60_000), 1e-6);
    }

    @Test
    void aNewSearchDrawsTheNewLineInAndWipesTheOld() {
        LineReveal r = new LineReveal();
        r.update(1, 10, 3, OLD_XZ, OLD_ALONG, FLAGS, FLAGS, 0); // the first line of a request
        r.update(1, 10, 3, OLD_XZ, OLD_ALONG, FLAGS, FLAGS, 5000); // long since drawn in
        assertEquals(Float.POSITIVE_INFINITY, r.shownTo(5000, 100), "all of it");
        assertEquals(0, r.oldCount(5000));

        r.update(2, 10, 3, OLD_XZ, OLD_ALONG, FLAGS, FLAGS, 6000); // the pulse key: a new request, the old line still shown
        assertEquals(0, r.oldCount(6000), "the old line is still the line itself: not drawn twice");
        r.update(2, 11, 4, NEW_XZ, NEW_ALONG, FLAGS, FLAGS, 6200); // the new line found
        assertEquals(0f, r.shownTo(6200, 90), 1e-6, "the new line starts at the player");
        assertEquals(3, r.oldCount(6200), "the old line shown whole at first");
        assertEquals(0f, r.oldFrom(6200), 1e-6);
        assertEquals(45f, r.shownTo(7700, 90), 1e-4, "half of it at 1.5 s");
        assertEquals(50f, r.oldFrom(7700), 1e-4, "the old one wiped half way");
        assertTrue(r.running(7700));
        assertEquals(Float.POSITIVE_INFINITY, r.shownTo(9200, 90), "done after 3 s");
        assertEquals(0, r.oldCount(9200));
    }

    @Test
    void aLineChangedLaterGoesOnWithoutStartingAgain() {
        LineReveal r = new LineReveal();
        r.update(1, 10, 3, OLD_XZ, OLD_ALONG, FLAGS, FLAGS, 0);
        r.update(1, 11, 4, NEW_XZ, NEW_ALONG, FLAGS, FLAGS, 1000); // searched once more (regions loaded), same request
        assertEquals(0.5f * 90, r.shownTo(1500, 90), 1e-4, "still timed from the first line");
    }

    @Test
    void aNewTargetShowsTheOldLineUntilTheNewOneComes() {
        LineReveal r = new LineReveal();
        r.update(1, 10, 3, OLD_XZ, OLD_ALONG, FLAGS, FLAGS, 0);
        r.update(2, 10, 3, OLD_XZ, OLD_ALONG, FLAGS, FLAGS, 5000); // tracking another pin
        r.update(2, 11, 0, NEW_XZ, NEW_ALONG, FLAGS, FLAGS, 5050); // the old path cleared at once, the new one not found yet
        assertEquals(3, r.oldCount(5050), "the old line stays whole meanwhile");
        r.update(2, 12, 4, NEW_XZ, NEW_ALONG, FLAGS, FLAGS, 5300);
        assertEquals(0f, r.shownTo(5300, 90), 1e-6, "then the new one is drawn in");
    }
}
