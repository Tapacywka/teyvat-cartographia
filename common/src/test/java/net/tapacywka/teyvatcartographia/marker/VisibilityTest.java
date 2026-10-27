package net.tapacywka.teyvatcartographia.marker;

import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which pins the world map and the minimap draw, and how a pin of another cave layer is marked. */
class VisibilityTest {
    @Test
    void eachPinBelongsToItsKind() {
        assertEquals(MapConfig.PinKind.CUSTOM, MarkerVisibility.kind(Marker.custom("a", MarkerType.DEMON, Level.OVERWORLD, 0, 64, 0)));
        assertEquals(MapConfig.PinKind.INTERACTIVE, MarkerVisibility.kind(Marker.custom("b", MarkerType.PARTY, Level.OVERWORLD, 0, 64, 0)));
        Marker spawn = new Marker(UUID.randomUUID(), "Spawn", MarkerType.SPAWN, Level.OVERWORLD, 0, 64, 0, 0, true);
        assertEquals(MapConfig.PinKind.SYSTEM, MarkerVisibility.kind(spawn));
    }

    /** At every zoom (1/16 and 1/32 too since 27.10.2026) by the settings of the kind and the place. */
    @Test
    void theSettingsOfTheKindAndThePlace() {
        assertTrue(MarkerVisibility.shown(false, true, true));
        assertFalse(MarkerVisibility.shown(false, true, false), "off on this map");
        assertFalse(MarkerVisibility.shown(false, false, true), "the whole kind off");
    }

    @Test
    void theTrackedPinIsAlwaysDrawn() {
        assertTrue(MarkerVisibility.shown(true, false, false));
    }

    @Test
    void pinsOfOtherCaveLayers() {
        int sea = 63;
        assertEquals(0, MarkerVisibility.layerRelation(null, null, sea), "both on the surface");
        assertEquals(0, MarkerVisibility.layerRelation(-2, -2, sea));
        assertEquals(-1, MarkerVisibility.layerRelation(-2, null, sea), "a cave pin below the surface");
        assertEquals(1, MarkerVisibility.layerRelation(null, -2, sea), "a surface pin above the cave shown");
        assertEquals(1, MarkerVisibility.layerRelation(-1, -3, sea));
        assertEquals(1, MarkerVisibility.layerRelation(4, null, sea), "a cave band above the sea is over the surface");
        assertEquals(-1, MarkerVisibility.layerRelation(3, null, sea), "band 3 (48 to 63) is under it");
    }
}
