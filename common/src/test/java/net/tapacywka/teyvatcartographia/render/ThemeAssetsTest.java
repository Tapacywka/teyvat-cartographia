package net.tapacywka.teyvatcartographia.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in theme is complete: every colour of {@link Theme} in its {@code colors.json} and nothing unknown, every
 * {@link Icon}, the fog; its {@code theme.json} has only a name (the shapes are settings, not part of a theme), and the
 * Genshin Impact pack of 0.1.x is gone (its theme was the same but for the shapes).
 */
class ThemeAssetsTest {
    private static final Path RES = Path.of(System.getProperty("teyvatcartographia.projectDir", ".")).resolve("src/main/resources");
    private static final Path DEFAULT = RES.resolve("assets/teyvatcartographia/themes").resolve(Theme.DEFAULT);

    @SuppressWarnings("unchecked")
    private static Set<String> themeKeys() throws ReflectiveOperationException {
        Field f = Theme.class.getDeclaredField("FIELDS");
        f.setAccessible(true);
        return ((Map<String, ?>) f.get(null)).keySet();
    }

    private static Set<String> colorKeys(Path theme) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(theme.resolve("colors.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject colors = root.has("colors") ? root.getAsJsonObject("colors") : root;
        Set<String> keys = new TreeSet<>();
        for (String k : colors.keySet()) keys.add(k.toLowerCase(Locale.ROOT));
        return keys;
    }

    @Test
    void theColoursAreComplete() throws Exception {
        Set<String> expected = new TreeSet<>(themeKeys()), actual = colorKeys(DEFAULT);
        Set<String> missing = new TreeSet<>(expected), unknown = new TreeSet<>(actual);
        missing.removeAll(actual);
        unknown.removeAll(expected);
        assertTrue(missing.isEmpty(), "colours missing " + missing);
        assertTrue(unknown.isEmpty(), "unknown colours " + unknown);
    }

    @Test
    void everyIcon() {
        List<String> missing = new ArrayList<>();
        for (Icon i : Icon.values()) {
            Path png = DEFAULT.resolve("icons/64").resolve(i.name().toLowerCase(Locale.ROOT) + ".png");
            if (!Files.isRegularFile(png)) missing.add(png.getFileName().toString());
        }
        assertTrue(missing.isEmpty(), "icons missing: " + missing);
    }

    @Test
    void theThemeHasItsFiles() {
        for (String f : new String[]{"theme.json", "colors.json", "fog.png"}) {
            assertTrue(Files.isRegularFile(DEFAULT.resolve(f)), Theme.DEFAULT + "/" + f);
        }
    }

    @Test
    void theThemeIsOnlyANameNoShapes() throws IOException {
        JsonObject theme = JsonParser.parseString(Files.readString(DEFAULT.resolve("theme.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(Set.of("name"), theme.keySet());
        assertEquals("teyvatcartographia.theme.default", theme.get("name").getAsString());
    }

    @Test
    void theGenshinPackIsGoneAndItsIdMeansTheDefaultTheme() {
        assertFalse(Files.exists(RES.resolve("resourcepacks")), "no built-in resource packs");
        assertTrue(Theme.LEGACY.containsAll(Set.of("minecraft", "genshin")));
        assertFalse(Theme.LEGACY.contains(Theme.DEFAULT));
    }
}
