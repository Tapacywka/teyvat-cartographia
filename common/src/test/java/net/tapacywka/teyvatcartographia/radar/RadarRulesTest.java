package net.tapacywka.teyvatcartographia.radar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadarRulesTest {
    @Test
    void singlePlayerShowsEverything() {
        for (RadarRules.Kind kind : RadarRules.Kind.values()) assertTrue(RadarRules.allowed(kind, true, false, false), kind.name());
    }

    @Test
    void serversShowOnlyPlayersUnlessMobsAreAllowed() {
        assertTrue(RadarRules.allowed(RadarRules.Kind.PLAYERS, false, false, false));
        assertFalse(RadarRules.allowed(RadarRules.Kind.HOSTILE, false, false, false));
        assertFalse(RadarRules.allowed(RadarRules.Kind.PETS, false, false, false));
        assertTrue(RadarRules.allowed(RadarRules.Kind.HOSTILE, false, true, false));
    }

    @Test
    void aForbiddingServerHidesEverything() {
        for (RadarRules.Kind kind : RadarRules.Kind.values()) {
            assertFalse(RadarRules.allowed(kind, true, true, true), kind.name());
            assertFalse(RadarRules.allowed(kind, false, true, true), kind.name());
        }
    }

    @Test
    void xaerosCodesForbidAndReset() {
        assertFalse(RadarRules.forbiddenAfter("Welcome!", false));
        assertTrue(RadarRules.forbiddenAfter("Welcome " + RadarRules.FAIR_PLAY, false));
        assertTrue(RadarRules.forbiddenAfter(RadarRules.NO_MINIMAP, false));
        assertTrue(RadarRules.forbiddenAfter("an ordinary message", true));
        assertFalse(RadarRules.forbiddenAfter(RadarRules.RESET, true));
    }

    @Test
    void heightCountsOnlyClearDifferences() {
        assertEquals(0, RadarRules.height(0));
        assertEquals(0, RadarRules.height(RadarRules.HEIGHT));
        assertEquals(0, RadarRules.height(-RadarRules.HEIGHT));
        assertEquals(1, RadarRules.height(RadarRules.HEIGHT + 0.5));
        assertEquals(-1, RadarRules.height(-RadarRules.HEIGHT - 0.5));
    }

    @Test
    void showOnMaps() {
        assertFalse(RadarRules.Show.OFF.on(true));
        assertTrue(RadarRules.Show.MINIMAP.on(true));
        assertFalse(RadarRules.Show.MINIMAP.on(false));
        assertTrue(RadarRules.Show.BOTH.on(false));
        assertTrue(RadarRules.Show.MAP.on(false));
        assertFalse(RadarRules.Show.MAP.on(true));
    }

    @Test
    void aBoxForEachMap() {
        for (RadarRules.Show s : RadarRules.Show.values()) {
            assertEquals(s, RadarRules.Show.of(s.on(false), s.on(true)), s.name());
        }
        assertEquals(RadarRules.Show.MAP, RadarRules.Show.of(true, false));
    }

    @Test
    void smallNamelessSubLevelsAreLeftOut() {
        assertFalse(RadarRules.shipShown(false, RadarRules.SHIP_MIN_COLUMNS - 1));
        assertTrue(RadarRules.shipShown(false, RadarRules.SHIP_MIN_COLUMNS));
        assertTrue(RadarRules.shipShown(true, 1));
    }

    @Test
    void theIconStandsInWhileTheSilhouetteIsMissingOrTiny() {
        assertFalse(RadarRules.shipSilhouette(0, 100));
        assertFalse(RadarRules.shipSilhouette(500, RadarRules.SHIP_SILHOUETTE_FROM - 1));
        assertTrue(RadarRules.shipSilhouette(500, RadarRules.SHIP_SILHOUETTE_FROM));
    }

    @Test
    void lowerColumnsAreDarkerUpToALimit() {
        assertEquals(0xFF808080, RadarRules.shade(0xFF808080, 0));
        assertEquals(0xFF7A7A7A, RadarRules.shade(0xFF808080, 1));
        int deep = RadarRules.shade(0xFFFFFFFF, 100);
        assertEquals(Math.round(255 * (1 - RadarRules.SHADE_MAX)), deep & 0xFF);
        assertEquals(0xFF000000, deep & 0xFF000000);
    }

    @Test
    void rangeIsACircle() {
        assertTrue(RadarRules.inRange(60, 80, 100));
        assertFalse(RadarRules.inRange(80, 80, 100));
    }

    /** Layers -1 and -2 mapped (the surface is layer 4: ground at y 64 to 79). */
    private static final java.util.function.IntPredicate MAPPED = b -> b == -1 || b == -2;

    @Test
    void withoutCaveLayersOnlyTheSurfaceShows() {
        assertTrue(RadarRules.onLayer(false, null, false, 4, 4, MAPPED));
        assertFalse(RadarRules.onLayer(false, null, true, 3, 4, MAPPED), "a mob in a cave");
    }

    @Test
    void theSurfaceShowsUndergroundOnesUpToTwoLayersDownInMappedLayers() {
        assertTrue(RadarRules.onLayer(true, null, true, 2, 4, b -> true));
        assertFalse(RadarRules.onLayer(true, null, true, 1, 4, b -> true), "three layers under the ground");
        assertFalse(RadarRules.onLayer(true, null, true, 3, 4, MAPPED), "a layer not mapped yet");
        assertTrue(RadarRules.onLayer(true, null, true, -1, 0, MAPPED), "low ground, a mapped layer under it");
    }

    @Test
    void aCaveLayerShowsTwoLayersAboveAndBelow() {
        assertTrue(RadarRules.onLayer(true, -1, true, -2, 4, MAPPED));
        assertFalse(RadarRules.onLayer(true, -1, true, -4, 4, MAPPED), "three layers below");
        assertTrue(RadarRules.onLayer(true, -1, false, 1, 1, MAPPED), "on the ground two layers above");
        assertFalse(RadarRules.onLayer(true, -1, false, 4, 4, MAPPED), "on the ground far above");
    }
}