package net.tapacywka.teyvatcartographia;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The NeoForge metadata takes the loader's version range from gradle.properties, which defines it. */
class LoaderMetadataTest {
    private static final Path ROOT = Path.of(System.getProperty("teyvatcartographia.projectDir", ".")).getParent();

    @Test
    void theLoaderRangeComesFromTheProperties() throws IOException {
        String toml = Files.readString(ROOT.resolve("neoforge/src/main/resources/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        assertTrue(toml.contains("loaderVersion=\"${loader_version_range}\""), "loaderVersion is not taken from the properties");
        Properties p = new Properties();
        try (var in = Files.newInputStream(ROOT.resolve("gradle.properties"))) {
            p.load(in);
        }
        assertTrue(p.getProperty("loader_version_range", "").startsWith("["), "gradle.properties has no loader_version_range");
    }

    /**
     * Both loaders ask for the settings library from one property, a released number (NeoForge orders a -dev after a
     * -beta and refused 0.9.0-beta.1), never above the version the mod is built against.
     */
    @Test
    void theSettingsLibrarysRangeComesFromOneReleasedNumber() throws IOException {
        String toml = Files.readString(ROOT.resolve("neoforge/src/main/resources/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        String json = Files.readString(ROOT.resolve("fabric/src/main/resources/fabric.mod.json"), StandardCharsets.UTF_8);
        assertTrue(toml.contains("versionRange=\"[${aacs_min_version},)\""), "neoforge.mods.toml does not take aacs_min_version");
        assertTrue(json.contains("\"aacs\": \">=${aacs_min_version}\""), "fabric.mod.json does not take aacs_min_version");
        Properties p = new Properties();
        try (var in = Files.newInputStream(ROOT.resolve("gradle.properties"))) {
            p.load(in);
        }
        String min = p.getProperty("aacs_min_version", ""), built = p.getProperty("aacs_version", "");
        assertTrue(min.matches("\\d+\\.\\d+\\.\\d+"), "aacs_min_version is not a released number: " + min);
        assertTrue(compare(min, built.replaceFirst("-.*", "")) <= 0, "aacs_min_version " + min + " is above aacs_version " + built);
    }

    /** Compares two versions x.y.z by their numbers. */
    private static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int d = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (d != 0) return d;
        }
        return 0;
    }

    /** The mod is the client's only: a server with its JAR must not ask for the settings library either. */
    @Test
    void theSettingsLibraryIsAskedForOnTheClientOnly() throws IOException {
        String toml = Files.readString(ROOT.resolve("neoforge/src/main/resources/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        int aacs = toml.indexOf("modId=\"aacs\"");
        assertTrue(aacs > 0, "no AACS dependency");
        int next = toml.indexOf("[[", aacs);
        String block = next < 0 ? toml.substring(aacs) : toml.substring(aacs, next);
        assertTrue(block.contains("side=\"CLIENT\""), "AACS is not asked for on the client only: " + block);
    }
}
