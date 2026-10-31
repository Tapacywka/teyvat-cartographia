package net.tapacywka.teyvatcartographia.marker;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kinds of pins: the player's custom ones, the interactive stones of Waystones, and the automatic points. */
class MarkerTypeTest {
    @Test
    void theInteractiveTypesAreTheStonesOfWaystones() {
        Set<MarkerType> interactive = EnumSet.noneOf(MarkerType.class);
        for (MarkerType t : MarkerType.values()) if (t.category == MarkerType.Category.INTERACTION) interactive.add(t);
        assertEquals(EnumSet.of(MarkerType.WAYSTONE, MarkerType.DUNGEON, MarkerType.SHARESTONE, MarkerType.WARP_PLATE), interactive);
        for (MarkerType t : interactive) assertFalse(t.custom, t + " is an automatic point, not a pin the player makes");
    }

    @Test
    void sevenCustomTypesForEveryone() {
        Set<MarkerType> custom = EnumSet.noneOf(MarkerType.class);
        for (MarkerType t : MarkerType.values()) if (t.category == MarkerType.Category.EXPLORATION) custom.add(t);
        assertEquals(EnumSet.of(MarkerType.DEMON, MarkerType.QUEST, MarkerType.STAR, MarkerType.MEAT, MarkerType.FLOWER,
                MarkerType.PICKAXE, MarkerType.HOOK), custom);
    }

    @Test
    void automaticPointsAreNotPins() {
        assertFalse(MarkerType.SPAWN.custom);
        assertFalse(MarkerType.DEATH.custom);
        assertTrue(MarkerType.STAR.custom);
    }

    @Test
    void everyTypeHasAnIconAndAKind() {
        for (MarkerType t : MarkerType.values()) {
            assertNotNull(t.icon, t.name());
            assertNotNull(t.category, t.name());
        }
    }

    @Test
    void theTypesOfOldVersionsReadAsTheNewOnes() {
        // The interactive pin types, removed in 0.8.0 with every such pin (and their names up to 0.1.2): no type.
        for (String removed : new String[]{"SIGN", "PARTY", "STRUCTURE", "WAYPOINT", "STATUE", "DOMAIN"})
            assertTrue(MarkerType.PIN_CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(removed)).error().isPresent(), removed);
        // And the custom ones, named by what they show since 0.2.0.
        assertEquals(MarkerType.DEMON, parse("BOSS"));
        assertEquals(MarkerType.MEAT, parse("FOOD"));
        assertEquals(MarkerType.FLOWER, parse("CLOVER"));
        assertEquals(MarkerType.PICKAXE, parse("MINE"));
        assertEquals(MarkerType.HOOK, parse("FISHING"));
        for (MarkerType t : MarkerType.values()) if (t.custom) assertEquals(t, parse(t.name()), "the new names read back");
        assertEquals("FLOWER", MarkerType.PIN_CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, MarkerType.FLOWER)
                .getOrThrow().getAsString(), "written with the new name");
    }

    private static MarkerType parse(String name) {
        return MarkerType.PIN_CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(name)).getOrThrow();
    }

    @Test
    void sectionHeadersHaveTexts() {
        assertEquals("teyvatcartographia.pin.category.exploration", MarkerType.Category.EXPLORATION.langKey());
    }
}
