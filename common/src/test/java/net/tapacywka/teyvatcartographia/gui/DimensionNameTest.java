package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Modded dimensions without a name of ours: their id made readable, every word with a capital letter. */
class DimensionNameTest {
    @Test
    void everyWordStartsWithACapital() {
        assertEquals("The Aether", WorldMapScreen.readableName("the_aether"));
        assertEquals("Deep Dark Depths", WorldMapScreen.readableName("deep-dark/depths"));
        assertEquals("Twilightforest", WorldMapScreen.readableName("twilightforest"));
    }

    @Test
    void emptyPartsAreSkipped() {
        assertEquals("Mining World", WorldMapScreen.readableName("_mining__world_"));
        assertEquals("", WorldMapScreen.readableName("__"));
    }
}
