package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which blocks are plants the map colours (Settings > Map > Plants). */
class PlantListTest {
    /** Leaves are never plants, also those the game tags as flowers (cherry, flowering azalea). */
    @Test
    void leavesAreNotPlants() {
        assertFalse(Plants.plant(true, false, true), "cherry leaves: in the flower tag");
        assertTrue(Plants.plant(false, false, true), "a modded flower by its tag");
        assertTrue(Plants.plant(false, true, false), "a bush");
        assertFalse(Plants.plant(false, false, false), "stone");
    }
}
