package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mouse pointer of the mod's menus: its size per interface scale and which shape wins in a frame. */
class CursorsTest {
    @Test
    void theSizeFollowsTheInterfaceScale() {
        assertEquals(33, Cursors.size(3), "GUI 3: about a system arrow");
        assertEquals(66, Cursors.size(6), "GUI 6 on 2K");
        assertEquals(16, Cursors.size(1), "never smaller than 16");
        assertEquals(128, Cursors.size(20), "never larger than 128");
    }

    @Test
    void aDragBeatsWhatTheMouseIsOver() {
        assertTrue(Cursors.Shape.MOVE.priority > Cursors.Shape.HAND.priority);
        assertTrue(Cursors.Shape.HAND.priority > Cursors.Shape.LOADING.priority, "a button under the mouse while searching");
        assertTrue(Cursors.Shape.LOADING.priority > Cursors.Shape.DEFAULT.priority);
    }
}
