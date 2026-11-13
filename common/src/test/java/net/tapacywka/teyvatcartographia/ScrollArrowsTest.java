package net.tapacywka.teyvatcartographia;

import net.tapacywka.teyvatcartographia.gui.ScrollArrows;
import net.tapacywka.teyvatcartographia.gui.SmoothScroll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arrows at the ends of a scroll bar: a row a click, on and on while held, never past either end. */
class ScrollArrowsTest {
    private static final float ROW = 20, MAX = 100;
    private static final long MS = 1_000_000L;

    private static SmoothScroll list() {
        SmoothScroll s = new SmoothScroll();
        s.update(MAX);
        return s;
    }

    @Test
    void eachArrowHasItsEnd() {
        float cx = 50, top = 0, bottom = 100;
        assertEquals(-1, ScrollArrows.at(50, 2, cx, top, bottom));
        assertEquals(1, ScrollArrows.at(51, 97, cx, top, bottom));
        assertEquals(0, ScrollArrows.at(50, 50, cx, top, bottom), "the track between them");
        assertEquals(0, ScrollArrows.at(60, 2, cx, top, bottom), "beside the bar");
        assertEquals(0, ScrollArrows.at(50, 2, cx, 0, 10), "no room for both");
    }

    @Test
    void aClickScrollsARowAndStopsAtTheEnds() {
        SmoothScroll s = list();
        ScrollArrows a = new ScrollArrows();
        assertFalse(ScrollArrows.can(-1, s), "at the top");
        assertFalse(a.press(-1, s, ROW, 0));
        assertEquals(0, a.held(), "a dim arrow is not held");
        assertTrue(a.press(1, s, ROW, 0));
        assertEquals(ROW, s.target());
        a.release();
        for (int i = 0; i < 10; i++) a.press(1, s, ROW, 0);
        assertEquals(MAX, s.target(), "never past the end");
        assertFalse(ScrollArrows.can(1, s));
        assertTrue(ScrollArrows.can(-1, s));
    }

    @Test
    void heldItGoesOnAfterAPause() {
        SmoothScroll s = list();
        ScrollArrows a = new ScrollArrows();
        a.press(1, s, ROW, 0);
        a.update(s, ROW, 100 * MS);
        a.update(s, ROW, 250 * MS);
        assertEquals(ROW, s.target(), "still the one row in the pause");
        a.update(s, ROW, 350 * MS);
        a.update(s, ROW, 450 * MS);
        assertTrue(s.target() > ROW, "then on by itself");
        a.release();
        float at = s.target();
        a.update(s, ROW, 900 * MS);
        assertEquals(at, s.target(), "let go, it stops");
    }

    @Test
    void noListNoArrows() {
        SmoothScroll s = new SmoothScroll();
        s.update(0);
        assertFalse(ScrollArrows.can(-1, s));
        assertFalse(ScrollArrows.can(1, s));
    }
}
