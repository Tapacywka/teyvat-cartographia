package net.tapacywka.teyvatcartographia.marker;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kinds of pins: custom and interactive for every player, and the automatic points. */
class MarkerTypeTest {
    @Test
    void threeInteractiveTypes() {
        Set<MarkerType> interactive = EnumSet.noneOf(MarkerType.class);
        for (MarkerType t : MarkerType.values()) if (t.category == MarkerType.Category.INTERACTION) interactive.add(t);
        assertEquals(EnumSet.of(MarkerType.SIGN, MarkerType.PARTY, MarkerType.STRUCTURE), interactive);
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
        assertTrue(MarkerType.SIGN.custom);
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
        // Up to 0.1.2: WAYPOINT, STATUE and DOMAIN (files, exports and a server's shared pins).
        assertEquals(MarkerType.SIGN, parse("WAYPOINT"));
        assertEquals(MarkerType.PARTY, parse("STATUE"));
        assertEquals(MarkerType.STRUCTURE, parse("DOMAIN"));
        // And the custom ones, named by what they show since 0.2.0.
        assertEquals(MarkerType.DEMON, parse("BOSS"));
        assertEquals(MarkerType.MEAT, parse("FOOD"));
        assertEquals(MarkerType.FLOWER, parse("CLOVER"));
        assertEquals(MarkerType.PICKAXE, parse("MINE"));
        assertEquals(MarkerType.HOOK, parse("FISHING"));
        for (MarkerType t : MarkerType.values()) if (t.custom) assertEquals(t, parse(t.name()), "the new names read back");
        assertEquals("PARTY", MarkerType.PIN_CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, MarkerType.PARTY)
                .getOrThrow().getAsString(), "written with the new name");
    }

    private static MarkerType parse(String name) {
        return MarkerType.PIN_CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(name)).getOrThrow();
    }

    @Test
    void sectionHeadersHaveTexts() {
        assertEquals("teyvatcartographia.pin.category.exploration", MarkerType.Category.EXPLORATION.langKey());
        assertEquals("teyvatcartographia.pin.category.interaction", MarkerType.Category.INTERACTION.langKey());
    }
}
