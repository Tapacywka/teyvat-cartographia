package net.tapacywka.teyvatcartographia.nav;

import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tracking: the distance shown everywhere, the level arrow (above or below), arriving at a target. */
class NavigationRulesTest {
    private static final Navigation.Target SPAWN = new Navigation.Target(Level.OVERWORLD, 0.5, 0.5, 64, null, MarkerType.SPAWN);
    private static final Navigation.Target DEATH = new Navigation.Target(Level.OVERWORLD, 0.5, 0.5, 64, null, MarkerType.DEATH);
    private static final Navigation.Target PIN = new Navigation.Target(Level.OVERWORLD, 0.5, 0.5, 64, UUID.randomUUID(), null);

    @Test
    void distanceIs3DOrFlatWithoutAHeight() {
        assertEquals(5, Navigation.distance(0, 64, 0, 3, 68, 0), 1e-9);
        assertEquals(5, Navigation.distance(0, 64, 0, 3, Integer.MIN_VALUE, 4), 1e-9, "no height: on the map plane");
    }

    @Test
    void automaticPointsEndTrackingOnArrival() {
        assertTrue(Navigation.arrives(SPAWN, 3.9));
        assertTrue(Navigation.arrives(DEATH, 0));
        assertFalse(Navigation.arrives(SPAWN, 4.0), "4 blocks is not there yet");
        assertFalse(Navigation.arrives(SPAWN, -1), "another dimension");
    }

    @Test
    void customPinsStayTracked() {
        assertFalse(Navigation.arrives(PIN, 0));
        assertFalse(Navigation.arrives(null, 0));
    }

    @Test
    void theLevelArrow() {
        assertEquals(0, Navigation.level(0, 64, 0, 100, 67, 0), "a step of 3 blocks never counts");
        assertEquals(1, Navigation.level(0, 64, 0, 100, 90, 0), "more than 16 blocks higher");
        assertEquals(-1, Navigation.level(0, 64, 0, 100, 40, 0), "more than 16 blocks lower");
        assertEquals(1, Navigation.level(0, 64, 0, 5, 70, 0), "close and 6 up: steeper than 30 degrees");
        assertEquals(0, Navigation.level(0, 64, 0, 100, 74, 0), "far and 10 up: about level");
        assertEquals(0, Navigation.level(0, 64, 0, 5, Integer.MIN_VALUE, 0), "no known height");
    }
}
