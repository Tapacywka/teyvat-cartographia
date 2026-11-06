package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlideTest {
    private static final float FRAME = Slide.SECONDS / 20;

    private static void frames(Slide s, int n) {
        for (int i = 0; i < n; i++) s.update(FRAME);
    }

    @Test
    void startsOutOfSight() {
        Slide s = new Slide();
        assertFalse(s.visible());
        assertTrue(s.gone());
        assertEquals(100, s.offset(100), 1e-4);
    }

    @Test
    void reachesItsPlaceInTheWholeTime() {
        Slide s = new Slide();
        s.show();
        frames(s, 19);
        assertFalse(s.settled());
        frames(s, 2);
        assertTrue(s.settled());
        assertEquals(1, s.amount(), 1e-6);
        assertEquals(0, s.offset(100), 1e-4);
    }

    @Test
    void goingOutTakesTheSameTimeAndStaysOnScreenUntilTheEnd() {
        Slide s = new Slide();
        s.show();
        frames(s, 21);
        s.hide();
        frames(s, 19);
        assertTrue(s.visible());
        assertFalse(s.gone());
        frames(s, 2);
        assertTrue(s.gone());
    }

    @Test
    void outRunsTheWayInBackwards() {
        Slide in = new Slide(), out = new Slide();
        in.show();
        out.show();
        frames(out, 21);
        out.hide();
        frames(in, 5);
        frames(out, 15);
        assertEquals(in.amount(), out.amount(), 1e-4);
    }

    @Test
    void turningBackHalfWayDoesNotJump() {
        Slide s = new Slide();
        s.show();
        frames(s, 21);
        s.hide();
        frames(s, 7);
        float at = s.offset(216);
        s.show();
        assertEquals(at, s.offset(216), 1e-4);
        float last = at;
        for (int i = 0; i < 7; i++) {
            s.update(FRAME);
            float now = s.offset(216);
            assertTrue(now < last, "keeps moving back in");
            assertTrue(last - now < 216 * 0.2f, "in small steps");
            last = now;
        }
        frames(s, 21);
        assertTrue(s.settled());
    }

    @Test
    void anotherSlideGoesOnFromTheSamePlace() {
        Slide old = new Slide(), again = new Slide();
        old.show();
        frames(old, 10);
        old.hide();
        again.takeFrom(old);
        again.show();
        assertEquals(old.amount(), again.amount());
        assertTrue(again.visible());
    }

    @Test
    void rowsFollowEachOtherAndEndTogether() {
        Slide s = new Slide();
        s.show();
        frames(s, 6);
        float first = s.staggered(0, 3), last = s.staggered(2, 3);
        assertTrue(first > last, "the first row leads on the way in");
        frames(s, 21);
        for (int i = 0; i < 5; i++) assertEquals(1, s.staggered(i, 5), 1e-6);
        s.hide();
        frames(s, 21);
        for (int i = 0; i < 5; i++) assertEquals(0, s.staggered(i, 5), 1e-6);
    }

    @Test
    void oneRowIsTheWholeSlide() {
        Slide s = new Slide();
        s.show();
        frames(s, 8);
        assertEquals(s.amount(), s.staggered(0, 1));
    }

    @Test
    void manyRowsStillGetTime() {
        Slide s = new Slide();
        s.show();
        frames(s, 19);
        for (int i = 0; i < 40; i++) assertTrue(s.staggered(i, 40) > 0, "row " + i + " has started");
    }
}
