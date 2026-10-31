package net.tapacywka.teyvatcartographia.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.platform.Platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sodium Extra's FPS overlay (user request 31.10.2026): with "Show FPS" or "Show Coordinates" on, it writes its lines
 * in a corner of the screen; a minimap in the same corner moves clear of them (down from a top corner, up from a bottom
 * one). Its settings are read from its own file, {@code config/sodium-extra-options.json}, again when the file changes
 * (looked at once a second): no build dependency.
 */
public final class SodiumExtraOverlay {
    private SodiumExtraOverlay() {}

    /** Its mod id on NeoForge and on Fabric. */
    private static final String NEOFORGE_ID = "sodium_extra", FABRIC_ID = "sodium-extra";

    /** What the overlay shows: its corner ("TOP_LEFT" ...), its lines, and whether they sit on a background. */
    record Settings(String corner, int lines, boolean background) {
        static final Settings NONE = new Settings("TOP_LEFT", 0, false);
    }

    private static Settings settings = Settings.NONE;
    private static long checkedAt, fileTime = -1;
    /** Whether Sodium Extra is in the game, asked once (the minimap asks every frame). */
    private static Boolean loaded;

    /** Whether Sodium Extra is in the game. */
    public static boolean loaded() {
        if (loaded == null && Platform.get() != null)
            loaded = Platform.get().isModLoaded(NEOFORGE_ID) || Platform.get().isModLoaded(FABRIC_ID);
        return loaded != null && loaded;
    }

    /**
     * How far (GUI units) a minimap in {@code corner} moves away from the screen's edge to clear the overlay: 0 when
     * Sodium Extra is not in the game, shows nothing, or writes in another corner.
     */
    public static int clearance(MapConfig.Corner corner) {
        if (!loaded()) return 0;
        long now = System.currentTimeMillis();
        if (now - checkedAt > 1000) {
            checkedAt = now;
            settings = read(settings);
        }
        return clearance(settings, corner);
    }

    /**
     * The rule: the overlay starts 2 units from the edge, a line 9 high every 11; the edge of its last line (one more
     * with the background behind it) is how far the minimap moves.
     */
    static int clearance(Settings s, MapConfig.Corner corner) {
        if (s.lines() <= 0 || !s.corner().equals(corner.name())) return 0;
        return 11 * s.lines() + (s.background() ? 1 : 0);
    }

    /** The settings from the file when it changed since the last read; {@code last} otherwise or when it cannot be read. */
    private static Settings read(Settings last) {
        try {
            Path file = Platform.get().configDir().resolve("sodium-extra-options.json");
            if (!Files.isRegularFile(file)) {
                fileTime = -1;
                return Settings.NONE;
            }
            long time = Files.getLastModifiedTime(file).toMillis();
            if (time == fileTime) return last;
            fileTime = time;
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return last;
        }
    }

    /** The overlay's settings from the text of Sodium Extra's file (its "extra_settings"); none when they are missing. */
    static Settings parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !(root.getAsJsonObject().get("extra_settings") instanceof JsonObject extra)) return Settings.NONE;
        int lines = (bool(extra, "show_fps") ? 1 : 0) + (bool(extra, "show_coords") ? 1 : 0);
        String corner = extra.has("overlay_corner") ? extra.get("overlay_corner").getAsString() : "TOP_LEFT";
        boolean background = extra.has("text_contrast") && "BACKGROUND".equals(extra.get("text_contrast").getAsString());
        return new Settings(corner, lines, background);
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }
}
