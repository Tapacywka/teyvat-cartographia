package net.tapacywka.teyvatcartographia.marker;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins as {@code markers.json} keeps them: files of older versions and damaged entries must still load. */
class MarkerCodecTest {
    private static final Codec<List<Marker>> LIST = Marker.CODEC.listOf();

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    /** What the store keeps of a list: the good entries even when others are damaged. */
    private static List<Marker> load(String s) {
        DataResult<List<Marker>> r = LIST.parse(JsonOps.INSTANCE, json(s));
        return r.resultOrPartial(e -> {}).orElse(List.of());
    }

    @Test
    void pinsWrittenBy010StillLoad() {
        List<Marker> pins = load("""
                [
                  {"id": "aaaaaaaa-0000-4000-8000-000000000001", "name": "Old cave camp", "type": "BOSS", "dimension": "minecraft:overworld", "x": 40, "y": 20, "z": 40, "created": 1, "band": 1},
                  {"id": "aaaaaaaa-0000-4000-8000-000000000002", "name": "Old no height", "type": "STAR", "dimension": "minecraft:the_nether", "x": -50, "z": 60, "created": 2}
                ]""");
        assertEquals(2, pins.size());
        Marker cave = pins.get(0);
        assertEquals(UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"), cave.id);
        assertEquals("Old cave camp", cave.name);
        assertEquals(MarkerType.DEMON, cave.type);
        assertEquals(Level.OVERWORLD, cave.dimension);
        assertEquals(40, cave.x);
        assertEquals(20, cave.y);
        assertEquals(40, cave.z);
        assertEquals(1, cave.band);
        Marker noY = pins.get(1);
        assertFalse(noY.hasY(), "a pin saved without a height keeps having none");
        assertNull(noY.band);
        assertEquals(Level.NETHER, noY.dimension);
    }

    @Test
    void oldAndUnknownTypeNamesBecomeCustomTypes() {
        List<Marker> pins = load("""
                [
                  {"id": "aaaaaaaa-0000-4000-8000-000000000003", "name": "Herbs", "type": "HERB", "dimension": "minecraft:overworld", "x": 1, "z": 1},
                  {"id": "aaaaaaaa-0000-4000-8000-000000000004", "name": "Home", "type": "HOME", "dimension": "minecraft:overworld", "x": 2, "z": 2},
                  {"id": "aaaaaaaa-0000-4000-8000-000000000005", "name": "Odd", "type": "NO_SUCH_TYPE", "dimension": "minecraft:overworld", "x": 3, "z": 3}
                ]""");
        assertEquals(3, pins.size());
        assertEquals(MarkerType.FLOWER, pins.get(0).type);
        assertEquals(MarkerType.STAR, pins.get(1).type);
        assertEquals(MarkerType.STAR, pins.get(2).type);
    }

    @Test
    void aDamagedEntryIsLeftOutAndTheRestLoads() {
        DataResult<List<Marker>> r = LIST.parse(JsonOps.INSTANCE, json("""
                [
                  {"id": "bbbbbbbb-0000-4000-8000-000000000001", "name": "Good", "type": "STAR", "dimension": "minecraft:overworld", "x": 10, "y": 70, "z": 10},
                  {"id": "bbbbbbbb-0000-4000-8000-000000000003", "name": "No x", "type": "BOSS", "dimension": "minecraft:overworld", "y": 70, "z": 30}
                ]"""));
        assertTrue(r.error().isPresent(), "the damaged entry is reported");
        List<Marker> pins = r.resultOrPartial(e -> {}).orElse(List.of());
        assertEquals(1, pins.size());
        assertEquals("Good", pins.get(0).name);
    }

    @Test
    void aPinSurvivesSavingAndLoading() {
        Marker m = Marker.custom("Round trip", MarkerType.HOOK, Level.END, -123, 64, 456);
        m.band = -2;
        JsonElement saved = Marker.CODEC.encodeStart(JsonOps.INSTANCE, m).getOrThrow();
        Marker back = Marker.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(m.id, back.id);
        assertEquals(m.name, back.name);
        assertEquals(m.type, back.type);
        assertEquals(m.dimension, back.dimension);
        assertEquals(m.x, back.x);
        assertEquals(m.y, back.y);
        assertEquals(m.z, back.z);
        assertEquals(m.band, back.band);
    }

    @Test
    void theTrackedTargetLoads() {
        Navigation.Target pin = Navigation.Target.CODEC.parse(JsonOps.INSTANCE, json("""
                {"dimension": "minecraft:overworld", "x": 40.5, "z": 40.5, "y": 20, "marker": "aaaaaaaa-0000-4000-8000-000000000001"}""")).getOrThrow();
        assertEquals(UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"), pin.markerId());
        assertNull(pin.auto());
        Navigation.Target spawn = Navigation.Target.CODEC.parse(JsonOps.INSTANCE, json("""
                {"dimension": "minecraft:overworld", "x": 0.5, "z": 0.5, "auto": "SPAWN"}""")).getOrThrow();
        assertEquals(MarkerType.SPAWN, spawn.auto());
    }
}
