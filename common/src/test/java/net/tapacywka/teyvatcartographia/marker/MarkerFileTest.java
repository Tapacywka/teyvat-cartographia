package net.tapacywka.teyvatcartographia.marker;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The export file of the pin list: every dimension, back without losses, damaged pins left out, other files refused. */
class MarkerFileTest {
    @TempDir
    Path dir;

    @Test
    void pinsOfEveryDimensionComeBack() throws IOException {
        Marker a = Marker.custom("Camp", MarkerType.DEMON, Level.OVERWORLD, 10, 70, -20);
        a.band = 2;
        Marker b = Marker.custom("Fortress", MarkerType.QUEST, Level.NETHER, -5, Integer.MIN_VALUE, 8);
        Marker c = Marker.custom("Gateway", MarkerType.STAR, Level.END, 1000, 64, 1000);
        Path file = dir.resolve("sub/pins.json");
        MarkerFile.write(file, List.of(a, b, c));
        List<Marker> back = MarkerFile.read(file);
        assertEquals(3, back.size());
        for (int i = 0; i < 3; i++) {
            Marker in = List.of(a, b, c).get(i), out = back.get(i);
            assertEquals(in.id, out.id);
            assertEquals(in.name, out.name);
            assertEquals(in.type, out.type);
            assertEquals(in.dimension, out.dimension);
            assertEquals(in.x, out.x);
            assertEquals(in.y, out.y);
            assertEquals(in.z, out.z);
            assertEquals(in.band, out.band);
        }
        assertFalse(back.get(1).hasY());
    }

    @Test
    void namesWithQuotesAndCyrillicSurvive() throws IOException {
        Marker m = Marker.custom("Статуя \"Семи\" <тест>", MarkerType.STAR, Level.OVERWORLD, 0, 64, 0);
        Path file = dir.resolve("pins.json");
        MarkerFile.write(file, List.of(m));
        assertEquals(m.name, MarkerFile.read(file).get(0).name);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("<тест>"), "readable text, no escapes");
    }

    @Test
    void aDamagedPinIsLeftOut() throws IOException {
        Path file = dir.resolve("pins.json");
        Files.writeString(file, """
                {"format": "teyvatcartographia:pins", "version": 1, "markers": [
                  {"id": "cccccccc-0000-4000-8000-000000000001", "name": "Good", "type": "STAR", "dimension": "minecraft:overworld", "x": 1, "z": 1},
                  {"id": "cccccccc-0000-4000-8000-000000000002", "name": "No z", "type": "STAR", "dimension": "minecraft:overworld", "x": 1}
                ]}""", StandardCharsets.UTF_8);
        List<Marker> pins = MarkerFile.read(file);
        assertEquals(1, pins.size());
        assertEquals("Good", pins.get(0).name);
    }

    @Test
    void anotherWorldsMarkersJsonIsAPinFileToo() throws IOException {
        Path file = dir.resolve("markers.json");
        Files.writeString(file, """
                {"version": 1, "markers": [
                  {"id": "cccccccc-0000-4000-8000-000000000003", "name": "Home", "type": "STAR", "dimension": "minecraft:overworld", "x": 5, "y": 64, "z": 5}
                ], "target": {"dimension": "minecraft:overworld", "x": 5.5, "z": 5.5}}""", StandardCharsets.UTF_8);
        assertEquals(1, MarkerFile.read(file).size());
    }

    @Test
    void otherFilesAreRefused() throws IOException {
        Path notJson = dir.resolve("notes.json");
        Files.writeString(notJson, "this is not json {", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MarkerFile.read(notJson));
        Path otherJson = dir.resolve("other.json");
        Files.writeString(otherJson, "{\"something\": 1}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> MarkerFile.read(otherJson));
    }
}
