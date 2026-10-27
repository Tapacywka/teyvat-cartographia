package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search of the pin list: names without case and "ё", every dimension, the player's dimension first by distance. */
class SearchMatchTest {
    private static Marker pin(String name, net.minecraft.resources.ResourceKey<Level> dim, int x) {
        return Marker.custom(name, MarkerType.STAR, dim, x, 64, 0);
    }

    private static List<String> names(List<Marker> pins) {
        return pins.stream().map(m -> m.name).toList();
    }

    private static final List<Marker> PINS = List.of(
            pin("Camp north", Level.OVERWORLD, 200),
            pin("Camp south", Level.OVERWORLD, 20),
            pin("Nether camp", Level.NETHER, 0),
            pin("End camp", Level.END, 0),
            pin("Iron mine", Level.OVERWORLD, 5),
            pin("Ёлки", Level.OVERWORLD, 50),
            pin("Статуя Семи", Level.OVERWORLD, 60));

    @Test
    void namesWithoutCase() {
        assertEquals(List.of("Camp south", "Camp north", "End camp", "Nether camp"),
                names(MapSearch.match(PINS, MapSearch.needle("CAMP"), Level.OVERWORLD, 0, 64, 0)));
    }

    @Test
    void theRussianYoIsTheSameAsYe() {
        assertEquals(List.of("Ёлки"), names(MapSearch.match(PINS, MapSearch.needle("елки"), Level.OVERWORLD, 0, 64, 0)));
        assertEquals(List.of("Ёлки"), names(MapSearch.match(PINS, MapSearch.needle("ёлки"), Level.OVERWORLD, 0, 64, 0)));
        assertEquals(List.of("Статуя Семи"), names(MapSearch.match(PINS, MapSearch.needle("  СТАТУЯ "), Level.OVERWORLD, 0, 64, 0)));
    }

    @Test
    void thePlayersDimensionFirstThenTheOthersByDimension() {
        List<String> inNether = names(MapSearch.match(PINS, MapSearch.needle("camp"), Level.NETHER, 0, 64, 0));
        assertEquals("Nether camp", inNether.get(0));
        assertEquals(List.of("Nether camp", "Camp north", "Camp south", "End camp"), inNether,
                "then by dimension id (minecraft:overworld before minecraft:the_end) and name");
    }

    @Test
    void nearestFirstInThePlayersDimension() {
        List<String> nearFar = names(MapSearch.match(PINS, MapSearch.needle("camp"), Level.OVERWORLD, 190, 64, 0));
        assertEquals(List.of("Camp north", "Camp south"), nearFar.subList(0, 2));
    }

    @Test
    void withoutAPlayerByDimensionAndName() {
        List<Marker> found = MapSearch.match(PINS, MapSearch.needle("camp"), null, 0, 0, 0);
        assertEquals(4, found.size());
        assertEquals("Camp north", found.get(0).name, "by name when there is no distance");
    }

    @Test
    void anEmptyQueryFindsEverything() {
        assertEquals(PINS.size(), MapSearch.match(PINS, MapSearch.needle(""), Level.OVERWORLD, 0, 64, 0).size());
    }

    @Test
    void noMatch() {
        assertTrue(MapSearch.match(PINS, MapSearch.needle("zzz"), Level.OVERWORLD, 0, 64, 0).isEmpty());
        Marker spawn = new Marker(UUID.randomUUID(), "World spawn", MarkerType.SPAWN, Level.OVERWORLD, 0, 64, 0, 0, true);
        assertEquals(List.of(spawn), MapSearch.match(List.of(spawn), MapSearch.needle("spawn"), Level.OVERWORLD, 0, 64, 0), "automatic points too");
    }
}
