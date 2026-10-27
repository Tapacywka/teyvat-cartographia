package net.tapacywka.teyvatcartographia.config;

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

/** The settings file since 0.5.0: JSON, the TOML of NeoForge (up to 0.4) taken over once, bad values to defaults. */
class ConfigSpecTest {
    private enum Shape { SQUARE, ROUND }

    private record Spec(ConfigSpec spec, ConfigSpec.BooleanValue on, ConfigSpec.IntValue radius, ConfigSpec.DoubleValue zoom,
                        ConfigSpec.EnumValue<Shape> shape, ConfigSpec.ConfigValue<String> theme,
                        ConfigSpec.ConfigValue<List<? extends String>> hidden) {}

    private static Spec spec() {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("minimap");
        var on = b.comment("on").define("enabled", true);
        var radius = b.defineInRange("radius", 48, 24, 110);
        var zoom = b.defineInRange("zoom", 1.0, 1 / 32.0, 32.0);
        var shape = b.defineEnum("shape", Shape.SQUARE);
        b.pop();
        b.push("appearance");
        var theme = b.define("theme", "default", o -> o instanceof String s && s.matches("[a-z0-9_.-]+"));
        b.push("map");
        var hidden = b.<String>defineListAllowEmpty("plantsHidden", List.of(), () -> "", o -> o instanceof String);
        b.pop();
        b.pop();
        return new Spec(b.build(), on, radius, zoom, shape, theme, hidden);
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
        Map<String, Object> v = ConfigSpec.readToml(OLD_TOML.lines().toList());
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
        Spec s = spec();
        s.spec().load(json, toml);
        assertFalse(s.on().get());
        assertEquals(72, s.radius().get());
        assertEquals(0.5, s.zoom().get());
        assertEquals(Shape.ROUND, s.shape().get());
        assertEquals(List.of("minecraft:short_grass", "minecraft:fern"), s.hidden().get());
        assertTrue(Files.exists(json), "written as JSON at once");

        // From now on the JSON counts, not the TOML.
        Files.writeString(toml, OLD_TOML.replace("radius = 72", "radius = 30"), StandardCharsets.UTF_8);
        Spec again = spec();
        again.spec().load(json, toml);
        assertEquals(72, again.radius().get());
    }

    @Test
    void aChangedValueIsSavedAndReadBack(@TempDir Path dir) {
        Path json = dir.resolve("c.json");
        Spec s = spec();
        s.spec().load(json, dir.resolve("none.toml"));
        assertEquals(48, s.radius().get(), "no file: the defaults");
        s.radius().set(100);
        s.shape().set(Shape.ROUND);
        s.theme().set("my_pack.theme");
        s.radius().save();
        Spec read = spec();
        read.spec().load(json, dir.resolve("none.toml"));
        assertEquals(100, read.radius().get());
        assertEquals(Shape.ROUND, read.shape().get());
        assertEquals("my_pack.theme", read.theme().get());
        assertEquals(List.of("minimap", "radius"), read.radius().getPath());
    }

    @Test
    void valuesOutOfRangeOrOfTheWrongKindBecomeTheDefault(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("c.json");
        Files.writeString(json, """
                { "minimap": { "enabled": "yes", "radius": 500, "zoom": 2.5, "shape": "TRIANGLE" },
                  "appearance": { "theme": "Bad Theme!", "map": { "plantsHidden": "minecraft:fern" } } }
                """, StandardCharsets.UTF_8);
        Spec s = spec();
        s.spec().load(json, dir.resolve("none.toml"));
        assertTrue(s.on().get());
        assertEquals(48, s.radius().get());
        assertEquals(2.5, s.zoom().get(), "in range: kept");
        assertEquals(Shape.SQUARE, s.shape().get());
        assertEquals("default", s.theme().get());
        assertEquals(List.of(), s.hidden().get());
        assertTrue(s.radius().getSpec().test(110) && !s.radius().getSpec().test(111));
    }

    @Test
    void anUnreadableFileLeavesTheDefaults(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("c.json");
        Files.writeString(json, "{ not json", StandardCharsets.UTF_8);
        Spec s = spec();
        s.spec().load(json, dir.resolve("none.toml"));
        assertEquals(48, s.radius().get());
    }
}
