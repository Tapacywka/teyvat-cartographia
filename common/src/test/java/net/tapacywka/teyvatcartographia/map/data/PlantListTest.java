package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The list of plants the map shows (Settings > Map > Plants): switching one on and off by its id. */
class PlantListTest {
    private static final List<String> DEFAULTS = List.of("minecraft:cactus", "minecraft:dead_bush", "minecraft:chorus_plant", "minecraft:chorus_flower");

    @Test
    void switchingOnAddsItOnce() {
        List<String> on = Plants.toggled(DEFAULTS, "minecraft:poppy", true);
        assertEquals(5, on.size());
        assertEquals("minecraft:poppy", on.get(4));
        assertEquals(on, Plants.toggled(on, "minecraft:poppy", true), "on again: still once");
    }

    @Test
    void switchingOffRemovesEveryCopy() {
        List<String> twice = List.of("minecraft:cactus", "minecraft:poppy", "minecraft:poppy");
        assertEquals(List.of("minecraft:cactus"), Plants.toggled(twice, "minecraft:poppy", false));
        assertEquals(DEFAULTS.subList(1, 4), Plants.toggled(DEFAULTS, "minecraft:cactus", false));
    }

    @Test
    void anUnknownIdOffChangesNothing() {
        assertEquals(DEFAULTS, Plants.toggled(DEFAULTS, "biomesoplenty:wildflower", false));
    }

    /** Leaves are never plants, also those the game tags as flowers (cherry, flowering azalea; 24.10.2026). */
    @Test
    void leavesAreNotPlants() {
        org.junit.jupiter.api.Assertions.assertFalse(Plants.plant(true, false, true), "cherry leaves: in the flower tag");
        org.junit.jupiter.api.Assertions.assertTrue(Plants.plant(false, false, true), "a modded flower by its tag");
        org.junit.jupiter.api.Assertions.assertTrue(Plants.plant(false, true, false), "a bush");
        org.junit.jupiter.api.Assertions.assertFalse(Plants.plant(false, false, false), "stone");
    }
}
