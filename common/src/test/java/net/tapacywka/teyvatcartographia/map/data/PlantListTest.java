package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

/** Which blocks are plants the map colours (Settings > Map > Plants). */
class PlantListTest {
    /** Leaves are never plants, also those the game tags as flowers (cherry, flowering azalea; 24.10.2026). */
    @Test
    void leavesAreNotPlants() {
        org.junit.jupiter.api.Assertions.assertFalse(Plants.plant(true, false, true), "cherry leaves: in the flower tag");
        org.junit.jupiter.api.Assertions.assertTrue(Plants.plant(false, false, true), "a modded flower by its tag");
        org.junit.jupiter.api.Assertions.assertTrue(Plants.plant(false, true, false), "a bush");
        org.junit.jupiter.api.Assertions.assertFalse(Plants.plant(false, false, false), "stone");
    }
}
