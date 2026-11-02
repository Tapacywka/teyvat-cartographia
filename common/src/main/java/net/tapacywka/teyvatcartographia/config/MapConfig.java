package net.tapacywka.teyvatcartographia.config;

import net.tapacywka.aacs.api.BoolValue;
import net.tapacywka.aacs.api.ConfigFile;
import net.tapacywka.aacs.api.DoubleValue;
import net.tapacywka.aacs.api.EnumValue;
import net.tapacywka.aacs.api.IntValue;
import net.tapacywka.aacs.api.ListValue;
import net.tapacywka.aacs.api.StringValue;
import net.tapacywka.aacs.api.Value;


/**
 * Client-side user settings (UI only). Stored in {@code config/teyvatcartographia-client.json} (AACS {@link ConfigFile}).
 * Explored map data and markers are stored separately under {@code <gameDir>/teyvatcartographia/}.
 */
public final class MapConfig {
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public enum Orientation { NORTH_UP, PLAYER_UP }

    /** Shape of the minimap, apart from the rest of the interface. */
    public enum MinimapShape { SQUARE, ROUND }

    /** Look of the panels, buttons, tiles and pills: flat rectangles, or circles, capsules and rounded corners. */
    public enum InterfaceStyle { RECTANGULAR, ROUNDED }

    /** How often the minimap is redrawn while the player moves or turns. */
    public enum UpdateRate {
        RATE_10(10), RATE_20(20), RATE_30(30), RATE_60(60), UNLIMITED(0);

        /** Minimum time between two redraws, 0 for every frame. */
        public final long intervalNs;

        UpdateRate(int perSecond) {
            intervalNs = perSecond == 0 ? 0 : 1_000_000_000L / perSecond;
        }
    }

    public static final ConfigFile FILE;

    // Appearance
    public static final StringValue THEME;
    public static final EnumValue<MinimapShape> MINIMAP_SHAPE;
    public static final EnumValue<InterfaceStyle> INTERFACE_STYLE;

    // Minimap
    public static final BoolValue MINIMAP_ENABLED;
    public static final IntValue MINIMAP_RADIUS;
    public static final EnumValue<Corner> MINIMAP_CORNER;
    public static final IntValue MINIMAP_OFFSET_X;
    public static final IntValue MINIMAP_OFFSET_Y;
    public static final DoubleValue MINIMAP_OPACITY;
    /** Gap between the frame and the map inside it (GUI units). */
    public static final IntValue MINIMAP_PADDING;
    /** The faded edge of the map: its width (percent of the radius) and how transparent the very edge gets (percent). */
    public static final IntValue MINIMAP_FADE_WIDTH;
    public static final IntValue MINIMAP_FADE_STRENGTH;
    /** Gap between the map and the info pills under or over it (GUI units). */
    public static final IntValue MINIMAP_INFO_GAP;
    /** Size of the text under the minimap: 0 "Auto", else screen pixels per font pixel. */
    public static final IntValue MINIMAP_TEXT_SIZE;
    public static final IntValue MINIMAP_PILL_OPACITY;
    public static final DoubleValue MINIMAP_ZOOM;
    public static final EnumValue<Orientation> MINIMAP_ORIENTATION;
    public static final EnumValue<UpdateRate> MINIMAP_UPDATE_RATE;
    public static final BoolValue MINIMAP_SHOW_COORDS;
    public static final BoolValue MINIMAP_SHOW_BIOME;
    public static final BoolValue MINIMAP_SHOW_TIME;
    public static final EnumValue<TimeSource> MINIMAP_TIME_SOURCE;
    public static final EnumValue<TimeFormat> MINIMAP_TIME_FORMAT;
    public static final BoolValue MINIMAP_SHOW_DAY;

    // World map
    public static final BoolValue MAP_SHOW_LABELS;
    public static final BoolValue MAP_SHOW_GRID;
    public static final DoubleValue MAP_DEFAULT_ZOOM;

    /**
     * The three kinds of pins, each with its own settings: the players' own (custom), the operators' signs,
     * parties and structures (interactive), and the automatic world spawn and last death (system).
     */
    public enum PinKind {
        CUSTOM("custom"), INTERACTIVE("interactive"), SYSTEM("system");

        public final String id;

        PinKind(String id) {
            this.id = id;
        }
    }

    /**
     * Settings of one kind of pins: the kind as a whole ({@code enabled}) and where its pins are drawn (the tracked pin
     * is always drawn on the maps), and the path to a tracked pin of this kind
     * ({@code path}) and where it is drawn. The nested values count only while their switch is on: read them through
     * the methods.
     */
    public static final class PinOptions {
        public final BoolValue enabled, map, minimap, marker, beam, path, pathMap, pathMinimap, pathWorld;

        private PinOptions(ConfigFile.Builder b, PinKind kind) {
            b.push(kind.id);
            enabled = b.bool("enabled", true);
            map = b.bool("map", true);
            minimap = b.bool("minimap", true);
            marker = b.bool("marker", true);
            beam = b.bool("beam", true);
            path = b.bool("path", true);
            pathMap = b.bool("pathMap", true);
            pathMinimap = b.bool("pathMinimap", true);
            pathWorld = b.bool("pathWorld", true);
            b.pop();
        }

        public boolean onMap() {
            return get(enabled) && get(map);
        }

        public boolean onMinimap() {
            return get(enabled) && get(minimap);
        }

        public boolean marker() {
            return get(enabled) && get(marker);
        }

        /** The beam of light over the marker: only with the marker. */
        public boolean beam() {
            return marker() && get(beam);
        }

        public boolean pathOnMap() {
            return get(path) && get(pathMap);
        }

        public boolean pathOnMinimap() {
            return get(path) && get(pathMinimap);
        }

        public boolean pathInWorld() {
            return get(path) && get(pathWorld);
        }

        private void reset() {
            for (BoolValue v : new BoolValue[]{enabled, map, minimap, marker, beam, path, pathMap, pathMinimap, pathWorld}) resetValue(v);
        }
    }
    private static final PinOptions[] PINS = new PinOptions[PinKind.values().length];

    public static PinOptions pins(PinKind kind) {
        return PINS[kind.ordinal()];
    }

    // Cartography
    public static final IntValue CHUNKS_PER_TICK;
    public static final IntValue SCAN_RADIUS;
    public static final IntValue MAX_CACHED_TILES;
    public static final EnumValue<Detail> DETAIL;
    public static final BoolValue CAVES_ENABLED;
    public static final BoolValue MAP_PLANTS;
    public static final BoolValue PLAYER_CONE;
    public static final ListValue PLANTS_HIDDEN;
    public static final IntValue WATER_DEPTH;
    public static final IntValue SHALLOW_WATER;

    // Compatibility with other mods (shown in the settings only while that mod is there).
    public static final BoolValue SIFT_ICHOR_SHIMMER;
    public static final BoolValue SIFT_SNOW_SHIMMER;
    public static final BoolValue SIFT_FOG;
    public static final BoolValue AETHER_FOG;
    public static final BoolValue NETHER_FOG;
    public static final BoolValue END_FOG;
    public static final EnumValue<net.tapacywka.teyvatcartographia.marker.MarkerType> XAERO_PIN_TYPE;
    public static final BoolValue XAERO_HIDDEN;
    public static final BoolValue XAERO_DEATHS;
    public static final BoolValue WAYSTONES_SHOW, WAYSTONES_SHARESTONES, WAYSTONES_WARP_PLATES, WAYSTONES_INACTIVE;

    public enum Detail { LOW, MEDIUM, HIGH }

    /**
     * How the path to a tracked pin goes (user decision 26.10.2026): on foot round deep water, or by boat across it; or
     * straight to it whatever lies between (user request 27.10.2026, for flying), {@link net.tapacywka.teyvatcartographia.nav.DirectPath}.
     */
    public enum Route { LAND, WATER, AIR }

    public static final EnumValue<Route> PATH_ROUTE;

    /** Clock under the minimap: the time of day in the game world, or the real time of the player's computer. */
    public enum TimeSource { GAME_TIME, REAL_TIME, BOTH }

    /** How the clock under the minimap writes the time: 14:35, 2:35 or 2:35 PM. */
    public enum TimeFormat { HOURS_24, HOURS_12, HOURS_12_AM_PM }

    static {
        ConfigFile.Builder b = ConfigFile.builder();

        b.push("appearance");
        THEME = b.text("theme", "default", s -> s.matches("[a-z0-9_.-]+"));
        MINIMAP_SHAPE = b.choice("minimapShape", MinimapShape.SQUARE);
        INTERFACE_STYLE = b.choice("interfaceStyle", InterfaceStyle.RECTANGULAR);
        b.pop();

        b.push("minimap");
        MINIMAP_ENABLED = b.bool("enabled", true);
        MINIMAP_RADIUS = b.integer("radius", 48, 24, 110);
        MINIMAP_CORNER = b.choice("corner", Corner.TOP_LEFT);
        MINIMAP_OFFSET_X = b.integer("offsetX", 4, 0, 64);
        MINIMAP_OFFSET_Y = b.integer("offsetY", 4, 0, 64);
        MINIMAP_OPACITY = b.decimal("opacity", 0.85, 0.2, 1.0);
        MINIMAP_PADDING = b.integer("padding", 0, 0, 16);
        MINIMAP_FADE_WIDTH = b.integer("fadeWidth", 15, 0, 50);
        MINIMAP_FADE_STRENGTH = b.integer("fadeStrength", 100, 0, 100);
        MINIMAP_INFO_GAP = b.integer("infoGap", 0, 0, 8);
        MINIMAP_TEXT_SIZE = b.integer("textSize", 0, 0, 4);
        MINIMAP_PILL_OPACITY = b.integer("pillOpacity", 0, 0, 100);
        MINIMAP_ZOOM = b.decimal("zoom", 1.0, 1 / 32.0, 32.0);
        MINIMAP_ORIENTATION = b.choice("orientation", Orientation.NORTH_UP);
        MINIMAP_UPDATE_RATE = b.choice("updateRate", UpdateRate.RATE_30);
        MINIMAP_SHOW_COORDS = b.bool("showCoordinates", true);
        MINIMAP_SHOW_BIOME = b.bool("showBiome", true);
        MINIMAP_SHOW_TIME = b.bool("showTime", true);
        MINIMAP_TIME_SOURCE = b.choice("timeSource", TimeSource.GAME_TIME);
        MINIMAP_TIME_FORMAT = b.choice("timeFormat", TimeFormat.HOURS_24);
        MINIMAP_SHOW_DAY = b.bool("showDay", true);
        b.pop();

        // Below scale 1 the maps show only the tracked target; the tracked target is always shown on them.
        b.push("pins");
        for (PinKind kind : PinKind.values()) PINS[kind.ordinal()] = new PinOptions(b, kind);
        PATH_ROUTE = b.choice("route", Route.LAND);
        b.pop();

        b.push("worldmap");
        MAP_SHOW_LABELS = b.bool("showLabels", false);
        MAP_SHOW_GRID = b.bool("showGrid", false);

        MAP_DEFAULT_ZOOM = b.decimal("defaultZoom", 1.0, 1 / 32.0, 32.0);
        b.pop();

        b.push("cartography");
        CHUNKS_PER_TICK = b.integer("chunksPerTick", 6, 1, 32);
        SCAN_RADIUS = b.integer("scanRadius", 16, 2, 32);
        MAX_CACHED_TILES = b.integer("maxCachedTiles", 160, 32, 1024);
        DETAIL = b.choice("detail", Detail.HIGH);
        CAVES_ENABLED = b.bool("caves", true);
        MAP_PLANTS = b.bool("plants", true);
        PLAYER_CONE = b.bool("playerCone", true);
        // The plants switched off (user decision 24.10.2026: every plant on by default, also those of mods added later);
        // until then the list held the ones switched on (plantsShown, cactus, dead bush and chorus by default).
        PLANTS_HIDDEN = b.list("plantsHidden", java.util.List.of(), id -> true);
        WATER_DEPTH = b.integer("waterDepthShading", 100, 0, 100);
        SHALLOW_WATER = b.integer("shallowWater", 0, 0, 100);
        b.pop();

        b.push("compat");
        b.push("minecraft");
        NETHER_FOG = b.bool("netherFog", true);
        END_FOG = b.bool("endFog", true);
        b.pop();
        b.push("aether");
        AETHER_FOG = b.bool("fog", true);
        b.pop();
        b.push("the_sift");
        SIFT_ICHOR_SHIMMER = b.bool("ichorShimmer", true);
        SIFT_SNOW_SHIMMER = b.bool("snowShimmer", true);
        SIFT_FOG = b.bool("fog", true);
        b.pop();
        b.push("xaero");
        XAERO_PIN_TYPE = b.choice("pinType", net.tapacywka.teyvatcartographia.marker.MarkerType.STAR, java.util.List.copyOf(net.tapacywka.teyvatcartographia.marker.MarkerType.customTypes()));
        XAERO_HIDDEN = b.bool("hidden", true);
        XAERO_DEATHS = b.bool("deaths", false);
        b.pop();
        b.push("waystones");
        WAYSTONES_SHOW = b.bool("waystones", true);
        WAYSTONES_SHARESTONES = b.bool("sharestones", true);
        WAYSTONES_WARP_PLATES = b.bool("warpPlates", true);
        WAYSTONES_INACTIVE = b.bool("inactive", true);
        b.pop();
        b.pop();

        FILE = b.build();
    }

    private MapConfig() {}

    /** Reads the settings file (the TOML NeoForge wrote up to 0.4 taken over once) and writes it back complete. */
    public static void load(java.nio.file.Path json, java.nio.file.Path oldToml) {
        OldToml.takeOver(json, oldToml);
        FILE.load(json);
        FILE.save();
    }

    public static void save() {
        FILE.save();
    }

    /** Resets every value to its default and saves. */
    public static void resetAll() {
        resetValue(THEME); resetValue(MINIMAP_SHAPE); resetValue(INTERFACE_STYLE);
        resetValue(MINIMAP_ENABLED); resetValue(MINIMAP_RADIUS); resetValue(MINIMAP_CORNER);
        resetValue(MINIMAP_OFFSET_X); resetValue(MINIMAP_OFFSET_Y); resetValue(MINIMAP_OPACITY);
        resetValue(MINIMAP_PADDING); resetValue(MINIMAP_FADE_WIDTH); resetValue(MINIMAP_FADE_STRENGTH); resetValue(MINIMAP_INFO_GAP); resetValue(MINIMAP_TEXT_SIZE); resetValue(MINIMAP_PILL_OPACITY);
        resetValue(MINIMAP_ZOOM); resetValue(MINIMAP_ORIENTATION); resetValue(MINIMAP_UPDATE_RATE); resetValue(MINIMAP_SHOW_COORDS);
        resetValue(MINIMAP_SHOW_BIOME); resetValue(MINIMAP_SHOW_TIME); resetValue(MINIMAP_TIME_SOURCE); resetValue(MINIMAP_TIME_FORMAT); resetValue(MINIMAP_SHOW_DAY);
        for (PinOptions o : PINS) o.reset();
        resetValue(PATH_ROUTE);
        resetValue(MAP_SHOW_LABELS); resetValue(MAP_SHOW_GRID); resetValue(MAP_DEFAULT_ZOOM);
        resetValue(CHUNKS_PER_TICK); resetValue(SCAN_RADIUS); resetValue(MAX_CACHED_TILES); resetValue(DETAIL); resetValue(CAVES_ENABLED);
        resetValue(MAP_PLANTS); resetValue(PLAYER_CONE); resetValue(PLANTS_HIDDEN);
        resetValue(WATER_DEPTH); resetValue(SHALLOW_WATER);
        resetValue(NETHER_FOG); resetValue(END_FOG); resetValue(AETHER_FOG); resetValue(SIFT_ICHOR_SHIMMER); resetValue(SIFT_SNOW_SHIMMER); resetValue(SIFT_FOG);
        resetValue(XAERO_PIN_TYPE); resetValue(XAERO_HIDDEN); resetValue(XAERO_DEATHS);
        resetValue(WAYSTONES_SHOW); resetValue(WAYSTONES_SHARESTONES); resetValue(WAYSTONES_WARP_PLATES); resetValue(WAYSTONES_INACTIVE);
        save();
    }

    private static void resetValue(Value<?> value) {
        value.reset();
    }

    /** Safe read: config values throw if read before the config file was loaded. */
    public static <T> T get(Value<T> value) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return value.getDefault();
        }
    }
}
