package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The radar's rule for an entity underground, on drawn columns: 'N' natural ground, '.' air, 'W' planks, from y 0 up. */
class EntityDepthTest {
    private static final EntityDepth.Column<String> COLUMN = (c, x, y, z) -> y >= 0 && y < c.length() && c.charAt(y) == 'N';
    private static final int TOP = 320;

    /** Counts the blocks read, to see how far up the rule looks. */
    private static int reads;
    private static final EntityDepth.Column<String> COUNTED = (c, x, y, z) -> {
        reads++;
        return COLUMN.natural(c, x, y, z);
    };

    private static boolean under(String column, int y, int ground) {
        return new EntityDepth().underground(column, COLUMN, 0, y, 0, ground, true, TOP);
    }

    @Test
    void aCaveUnderStoneIsUnderground() {
        // Feet at 0, head at 1, stone from 2 to 9, the mapped ground at 10.
        assertTrue(under("..NNNNNNNN", 0, 10));
    }

    @Test
    void atOrOverTheMappedGroundIsNot() {
        String stone = "NNNNNNNNNNNNNNNNNNNN";
        assertFalse(under(stone, 9, 10), "a block under the ground's top counts as on it");
        assertFalse(under(stone, 12, 10), "over the ground");
        assertTrue(under(stone, 4, 10), "well under it: stone from 6 to the ground");
        assertFalse(under(stone, 6, 10), "only 3 blocks of stone up to the ground");
    }

    @Test
    void aHouseIsNoCave() {
        assertFalse(under("..WWWW.W..", 0, 10), "planks are not natural ground");
        assertFalse(under("..NNN.NNN.", 0, 10), "two runs of 3 are no roof of 4");
    }

    @Test
    void theRunMustLieUnderTheMappedGround() {
        // Stone only above the mapped ground (a floating island over a valley) does not count.
        assertFalse(under("..........NNNNNN", 0, 8));
        // Without a mapped ground the rule looks up to 64 blocks over the head.
        StringBuilder far = new StringBuilder("..");
        for (int i = 0; i < 70; i++) far.append('.');
        far.append("NNNN");
        assertFalse(under(far.toString(), 0, EntityDepth.UNMAPPED), "a roof 70 blocks up is past the scan");
        assertTrue(under("..........NNNN", 0, EntityDepth.UNMAPPED), "a roof 8 blocks up without a map");
    }

    @Test
    void whereTheClientHasNoBlocksOnlyTheMapCounts() {
        EntityDepth d = new EntityDepth();
        assertTrue(d.underground("", COLUMN, 0, 0, 0, 10, false, TOP), "10 under the mapped ground");
        assertFalse(d.underground("", COLUMN, 0, 6, 0, 10, false, TOP), "4 under it is not more than the roof");
        assertFalse(d.underground("", COLUMN, 0, 0, 0, EntityDepth.UNMAPPED, false, TOP), "no map, no blocks: not underground");
    }

    @Test
    void stopsReadingOnceTheRoofIsFound() {
        reads = 0;
        assertTrue(new EntityDepth().underground("..NNNNNNNNNNNNNNNNNNNN", COUNTED, 0, 0, 0, 22, true, TOP));
        assertEquals(4, reads, "four blocks of stone are enough");
    }

    @Test
    void oneObjectAnswersManyInARow() {
        EntityDepth d = new EntityDepth();
        assertTrue(d.underground("..NNNN....", COLUMN, 0, 0, 0, 10, true, TOP));
        assertFalse(d.underground("..NN......", COLUMN, 0, 0, 0, 10, true, TOP), "the roof of the last answer is not kept");
    }
}
