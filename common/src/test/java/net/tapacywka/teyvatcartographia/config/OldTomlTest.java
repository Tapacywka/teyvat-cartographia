package net.tapacywka.teyvatcartographia.config;

import net.tapacywka.aacs.api.BoolValue;
import net.tapacywka.aacs.api.ConfigFile;
import net.tapacywka.aacs.api.DoubleValue;
import net.tapacywka.aacs.api.EnumValue;
import net.tapacywka.aacs.api.IntValue;
import net.tapacywka.aacs.api.ListValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The TOML of NeoForge (up to 0.4) taken over once into the JSON settings file. */
class OldTomlTest {
    private enum Shape { SQUARE, ROUND }

    private record Spec(ConfigFile file, BoolValue on, IntValue radius, DoubleValue zoom, EnumValue<Shape> shape, ListValue hidden) {}

    private static Spec spec() {
        ConfigFile.Builder b = ConfigFile.builder();
        b.push("minimap");
        var on = b.bool("enabled", true);
        var radius = b.integer("radius", 48, 24, 110);
        var zoom = b.decimal("zoom", 1.0, 1 / 32.0, 32.0);
        var shape = b.choice("shape", Shape.SQUARE);
        b.pop();
        b.push("appearance");
        b.text("theme", "default", s -> s.matches("[a-z0-9_.-]+"));
        b.push("map");
        var hidden = b.list("plantsHidden", List.of(), id -> true);
        b.pop();
        b.pop();
        return new Spec(b.build(), on, radius, zoom, shape, hidden);
    }

    /** As NeoForge wrote it (comments, sections, a multi-line array). */
    private static final String OLD_TOML = """
            #Minimap
            [minimap]
            \t#Show the minimap
            \tenabled = false
            \t# Range: 24 ~ 110
            \tradius = 72
            \tzoom = 0.5
            \tshape = "ROUND"

            [appearance]
            \ttheme = "default"

            \t[appearance.map]
            \t\tplantsHidden = ["minecraft:short_grass",
            \t\t "minecraft:fern"]
            """;

    @Test
    void theOldTomlIsReadAsNeoForgeWroteIt() {
        Map<String, Object> v = OldToml.readToml(OLD_TOML.lines().toList());
        assertEquals(false, v.get("minimap.enabled"));
        assertEquals(72L, v.get("minimap.radius"));
        assertEquals(0.5, v.get("minimap.zoom"));
        assertEquals("ROUND", v.get("minimap.shape"));
        assertEquals(List.of("minecraft:short_grass", "minecraft:fern"), v.get("appearance.map.plantsHidden"));
    }

    @Test
    void theSettingsOfTheOldTomlCarryOverOnce(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("teyvatcartographia-client.json"), toml = dir.resolve("teyvatcartographia-client.toml");
        Files.writeString(toml, OLD_TOML, StandardCharsets.UTF_8);
        OldToml.takeOver(json, toml);
        assertTrue(Files.exists(json), "written as JSON at once");
        Spec s = spec();
        s.file().load(json);
        assertFalse(s.on().get());
        assertEquals(72, s.radius().get());
        assertEquals(0.5, s.zoom().get());
        assertEquals(Shape.ROUND, s.shape().get());
        assertEquals(List.of("minecraft:short_grass", "minecraft:fern"), s.hidden().get());

        // From now on the JSON counts, not the TOML.
        Files.writeString(toml, OLD_TOML.replace("radius = 72", "radius = 30"), StandardCharsets.UTF_8);
        OldToml.takeOver(json, toml);
        Spec again = spec();
        again.file().load(json);
        assertEquals(72, again.radius().get());
    }
}
