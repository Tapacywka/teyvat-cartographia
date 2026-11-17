package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where the tooltips by the pointer go: right of it and above it, kept inside the screen's margin. */
class TooltipPlaceTest {
    private static final int M = Ui.Grid.MARGIN;

    @Test
    void rightOfAndAboveThePointer() {
        assertEquals(112, Ui.tipX(100, 50, 640), 1e-6);
        assertEquals(100 - 30 - 4, Ui.tipY(100, 30), 1e-6);
    }

    @Test
    void keptInsideTheScreen() {
        assertEquals(640 - 50 - M, Ui.tipX(620, 50, 640), 1e-6, "at the right edge it moves back in");
        assertEquals(M, Ui.tipX(5, 700, 640), 1e-6, "wider than the screen: from the left margin");
        assertEquals(M, Ui.tipY(10, 30), 1e-6, "at the top it stays under the margin");
    }
}
