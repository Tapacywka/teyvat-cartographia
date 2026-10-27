package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Underground and the cave bands: 4+ natural blocks in a row over the head, bands of 16 blocks, no flicker at a cave mouth. */
class UndergroundTest {
    @Test
    void fourSolidBlocksOverTheHead() {
        assertNull(Underground.band(0, 70), "open sky");
        assertNull(Underground.band(3, 70), "a roof of a house, a tree");
        assertEquals(4, Underground.band(4, 70), "70 is in band 4 (64 to 79)");
    }

    /** Columns over the head, from the head up: N natural, '.' air, W a floor or roof (planks, glass, a shelf). */
    private static int run(String column) {
        Underground.Roof roof = new Underground.Roof();
        for (char c : column.toCharArray()) roof.add(c == 'N');
        return roof.best();
    }

    @Test
    void onlyNaturalBlocksInARowMakeACaveRoof() {
        assertEquals(0, run("...W...W...W....W"), "a house of four floors with its roof (user report 25.10.2026)");
        assertNull(Underground.band(run("..WWWW..W"), 70), "a thick roof of planks and barrels");
        assertNull(Underground.band(run("NNN.W.NNN"), 70), "dirt on a roof: 3, then 3");
        assertEquals(4, Underground.band(run("NNNN"), 70), "four blocks of stone");
        assertEquals(4, Underground.band(run("NN....NNNNNNNNN"), 70), "a thin ceiling, a cave above, then the ground");
        assertEquals(4, Underground.band(run("W..NNNNN"), 70), "a mine with planks under the rock");
    }

    @Test
    void bandsOf16Blocks() {
        assertEquals(0, CaveScanner.band(0));
        assertEquals(0, CaveScanner.band(15));
        assertEquals(1, CaveScanner.band(16));
        assertEquals(-1, CaveScanner.band(-1));
        assertEquals(-4, CaveScanner.band(-64), "the bottom of the world");
    }

    @Test
    void aNewStateHoldsAfter10Ticks() {
        Underground u = new Underground();
        for (int i = 0; i < Underground.TICKS; i++) assertNull(u.update(-2), "not yet, tick " + i);
        assertEquals(-2, u.update(-2), "after 10 more ticks underground");
    }

    @Test
    void goingInAndOutQuicklyDoesNotFlicker() {
        Underground u = new Underground();
        for (int i = 0; i < 50; i++) assertNull(u.update(i % 6 < 3 ? -2 : null), "tick " + i);
    }

    @Test
    void anotherBandStartsItsOwnCount() {
        Underground u = new Underground();
        for (int i = 0; i <= Underground.TICKS; i++) u.update(-2);
        assertEquals(-2, u.current());
        for (int i = 0; i < Underground.TICKS; i++) assertEquals(-2, u.update(-3));
        assertEquals(-3, u.update(-3));
        u.reset();
        assertNull(u.current());
    }
}
