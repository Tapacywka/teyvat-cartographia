package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.NavigableSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world map's layer field without a screen: the order of its buttons, how many show, scrolling, where they stand. */
class LayerFieldTest {
    /** A screen 640 x 360 with the given bands and the sea at 63; the band shown can be set. */
    private static final class Screen implements LayerField.Host {
        final TreeSet<Integer> bands = new TreeSet<>();
        Integer viewed;

        Screen(Integer... bands) {
            this.bands.addAll(Arrays.asList(bands));
        }

        public int width() {
            return 640;
        }

        public int height() {
            return 360;
        }

        public boolean on() {
            return !bands.isEmpty();
        }

        public boolean takesMouse() {
            return on();
        }

        public Integer viewed() {
            return viewed;
        }

        public NavigableSet<Integer> bands() {
            return bands.descendingSet();
        }

        public int seaLevel() {
            return 63;
        }

        public float snap(float v) {
            return Math.round(v);
        }
    }

    private static final int S = LayerField.SURFACE;

    @Test
    void bandsAboveTheSeaOverTheSurface() {
        // Band 4 is 64 to 79 (over the sea), band 3 is 48 to 63, band -1 under 0.
        LayerField f = new LayerField(new Screen(4, 3, -1));
        assertArrayEquals(new int[]{4, S, 3, -1}, f.items());
    }

    @Test
    void closedOnlyTheLargeButton() {
        LayerField f = new LayerField(new Screen(4, 3, -1));
        assertFalse(f.isOpen());
        assertEquals(1, f.shownTo() - f.shownFrom(), "one button");
        assertEquals(S, f.items()[f.shownFrom()], "the surface");
        assertEquals(180, f.itemY(f.shownFrom()), 0.01, "on the middle of the screen's height");
    }

    @Test
    void openAtMostFiveSmallButtonsAroundTheShownLayer() {
        Screen screen = new Screen(3, 2, 1, 0, -1, -2, -3, -4);
        LayerField f = new LayerField(screen);
        assertEquals(9, f.items().length, "8 bands and the surface");
        screen.viewed = -3;
        f.open();
        f.update(1);
        assertTrue(f.isOpen());
        int small = 0;
        boolean viewedShown = false;
        for (int i = f.shownFrom(); i < f.shownTo(); i++) {
            if (f.items()[i] != S) small++;
            if (f.items()[i] == -3) viewedShown = true;
        }
        assertEquals(LayerField.SMALL_MAX, small);
        assertTrue(viewedShown, "opened with the shown layer in view");
    }

    @Test
    void scrollStopsAtBothEnds() {
        LayerField f = new LayerField(new Screen(3, 2, 1, 0, -1, -2, -3, -4));
        f.open();
        f.update(1);
        f.scroll(-100);
        assertEquals(0, f.shownFrom(), "the top");
        f.scroll(100);
        assertEquals(f.items().length, f.shownTo(), "the bottom: the field reaches the end of the list");
        int last = f.shownFrom();
        f.scroll(1);
        assertEquals(last, f.shownFrom(), "no further");
    }

    @Test
    void buttonsStandInOrderCentredTogether() {
        LayerField f = new LayerField(new Screen(4, 3, -1));
        f.open();
        f.update(1);
        float prev = -1;
        for (int i = f.shownFrom(); i < f.shownTo(); i++) {
            assertTrue(f.itemY(i) > prev, "top to bottom");
            prev = f.itemY(i);
        }
        // One big slot and three small ones, centred on 180.
        float height = LayerField.BIG_SLOT + 3 * LayerField.STEP;
        assertEquals(Math.round(180 - height / 2), f.itemY(0) - LayerField.STEP / 2f, 1);
        assertTrue(f.top() < f.itemY(0) && f.bottom() > f.itemY(3), "the chrome's rectangle holds the field and its arrows");
        f.close();
        assertFalse(f.isOpen());
    }
}
