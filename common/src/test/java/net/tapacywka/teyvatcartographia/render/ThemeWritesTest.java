package net.tapacywka.teyvatcartographia.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The theme's colours are public fields, read every frame without a call; only the theme sets them
 * ({@link Theme#apply}, from {@link ThemeLoader}). This holds it: no other source of the mod writes {@code Theme.X},
 * through an assignment, a static import or reflection.
 */
class ThemeWritesTest {
    private static final Path ROOT = Path.of(System.getProperty("teyvatcartographia.projectDir", ".")).getParent();
    /** {@code Theme.X = ...}, {@code Theme.X += ...}, {@code Theme.X++}, {@code ++Theme.X} and the like (not {@code ==}). */
    private static final Pattern WRITE = Pattern.compile(
            "\\bTheme\\.[A-Z][A-Z0-9_]*\\s*(=(?!=)|[-+*/|&^]=|<<=|>>>?=|\\+\\+|--)|(\\+\\+|--)\\s*Theme\\.[A-Z]");
    /** A static import of the theme (its fields could then be written by their names alone). */
    private static final Pattern STATIC_IMPORT = Pattern.compile("import\\s+static\\s+[\\w.]*\\bTheme\\b");
    /** A field of the theme set by reflection. */
    private static final Pattern REFLECTED = Pattern.compile("Theme\\.class\\s*\\.\\s*get(Declared)?Fields?\\([^)]*\\)\\s*\\.\\s*set");

    @Test
    void onlyTheThemeSetsItsColours() throws IOException {
        List<String> found = new ArrayList<>();
        for (String module : new String[] {"common", "fabric", "neoforge"}) {
            Path src = ROOT.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    if (f.getFileName().toString().equals("Theme.java")) continue;
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    for (Pattern p : new Pattern[] {WRITE, STATIC_IMPORT, REFLECTED}) {
                        Matcher m = p.matcher(text);
                        while (m.find()) found.add(src.relativize(f) + ": " + m.group());
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), "the theme's colours written outside Theme: " + found);
    }

    /** The check sees a write when there is one (so an empty result above means something). */
    @Test
    void theCheckSeesAWrite() {
        assertTrue(WRITE.matcher("Theme.GOLD = 0xFF000000;").find());
        assertTrue(WRITE.matcher("Theme.MAP_OUTLINE |= 1;").find());
        assertTrue(WRITE.matcher("++Theme.WHITE;").find());
        assertTrue(!WRITE.matcher("if (c == Theme.GOLD) x = Theme.WHITE;").find(), "reading is no write");
        assertTrue(STATIC_IMPORT.matcher("import static net.tapacywka.teyvatcartographia.render.Theme.*;").find());
        assertTrue(REFLECTED.matcher("Theme.class.getField(name).setInt(null, 1)").find());
        assertTrue(!REFLECTED.matcher("Theme.class.getField(name).getInt(null)").find(), "reading is no write");
    }
}
