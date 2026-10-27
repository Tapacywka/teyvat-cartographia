package net.tapacywka.teyvatcartographia.config;


/**
 * Client-side user settings (UI only). Stored in {@code config/teyvatcartographia-client.json} ({@link ConfigSpec}).
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

    public static final ConfigSpec SPEC;

    // Appearance
    public static final ConfigSpec.ConfigValue<String> THEME;
    public static final ConfigSpec.EnumValue<MinimapShape> MINIMAP_SHAPE;
    public static final ConfigSpec.EnumValue<InterfaceStyle> INTERFACE_STYLE;

    // Minimap
    public static final ConfigSpec.BooleanValue MINIMAP_ENABLED;
    public static final ConfigSpec.IntValue MINIMAP_RADIUS;
    public static final ConfigSpec.EnumValue<Corner> MINIMAP_CORNER;
    public static final ConfigSpec.IntValue MINIMAP_OFFSET_X;
    public static final ConfigSpec.IntValue MINIMAP_OFFSET_Y;
    public static final ConfigSpec.DoubleValue MINIMAP_OPACITY;
    /** Gap between the frame and the map inside it (GUI units). */
    public static final ConfigSpec.IntValue MINIMAP_PADDING;
    /** The faded edge of the map: its width (percent of the radius) and how transparent the very edge gets (percent). */
    public static final ConfigSpec.IntValue MINIMAP_FADE_WIDTH;
    public static final ConfigSpec.IntValue MINIMAP_FADE_STRENGTH;
    /** Gap between the map and the info pills under or over it (GUI units). */
    public static final ConfigSpec.IntValue MINIMAP_INFO_GAP;
    /** Size of the text under the minimap: 0 "Auto", else screen pixels per font pixel. */
    public static final ConfigSpec.IntValue MINIMAP_TEXT_SIZE;
    public static final ConfigSpec.IntValue MINIMAP_PILL_OPACITY;
    public static final ConfigSpec.DoubleValue MINIMAP_ZOOM;
    public static final ConfigSpec.EnumValue<Orientation> MINIMAP_ORIENTATION;
    public static final ConfigSpec.EnumValue<UpdateRate> MINIMAP_UPDATE_RATE;
    public static final ConfigSpec.BooleanValue MINIMAP_SHOW_COORDS;
    public static final ConfigSpec.BooleanValue MINIMAP_SHOW_BIOME;
    public static final ConfigSpec.BooleanValue MINIMAP_SHOW_TIME;
    public static final ConfigSpec.EnumValue<TimeSource> MINIMAP_TIME_SOURCE;
    public static final ConfigSpec.EnumValue<TimeFormat> MINIMAP_TIME_FORMAT;
    public static final ConfigSpec.BooleanValue MINIMAP_SHOW_DAY;

    // World map
    public static final ConfigSpec.BooleanValue MAP_SHOW_LABELS;
    public static final ConfigSpec.BooleanValue MAP_SHOW_GRID;
    public static final ConfigSpec.DoubleValue MAP_DEFAULT_ZOOM;

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
        public final ConfigSpec.BooleanValue enabled, map, minimap, marker, beam, path, pathMap, pathMinimap, pathWorld;

        private PinOptions(ConfigSpec.Builder b, PinKind kind) {
            b.push(kind.id);
            enabled = b.comment("Show these pins").translation("teyvatcartographia.config.pins." + kind.id).define("enabled", true);
            map = b.comment("On the world map").translation("teyvatcartographia.config.pins.map").define("map", true);
            minimap = b.comment("On the minimap").translation("teyvatcartographia.config.pins.minimap").define("minimap", true);
            marker = b.comment("The marker over a tracked pin of this kind in the world (its glyph and distance)").translation("teyvatcartographia.config.pins.marker").define("marker", true);
            beam = b.comment("A beam of light over a tracked pin in the world").translation("teyvatcartographia.config.pins.beam").define("beam", true);
            path = b.comment("Show the path to a tracked pin of this kind").translation("teyvatcartographia.config.path." + kind.id).define("path", true);
            pathMap = b.comment("The path on the world map (dash-dot line)").translation("teyvatcartographia.config.path.map").define("pathMap", true);
            pathMinimap = b.comment("The path on the minimap (dash-dot line)").translation("teyvatcartographia.config.path.minimap").define("pathMinimap", true);
            pathWorld = b.comment("The path in the world (stars)").translation("teyvatcartographia.config.path.world").define("pathWorld", true);
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
            for (ConfigSpec.BooleanValue v : new ConfigSpec.BooleanValue[]{enabled, map, minimap, marker, beam, path, pathMap, pathMinimap, pathWorld}) resetValue(v);
        }
    }
    private static final PinOptions[] PINS = new PinOptions[PinKind.values().length];

    public static PinOptions pins(PinKind kind) {
        return PINS[kind.ordinal()];
    }

    // Cartography
    public static final ConfigSpec.IntValue CHUNKS_PER_TICK;
    public static final ConfigSpec.IntValue SCAN_RADIUS;
    public static final ConfigSpec.IntValue MAX_CACHED_TILES;
    public static final ConfigSpec.EnumValue<Detail> DETAIL;
    public static final ConfigSpec.BooleanValue CAVES_ENABLED;
    public static final ConfigSpec.BooleanValue MAP_PLANTS;
    public static final ConfigSpec.BooleanValue PLAYER_CONE;
    public static final ConfigSpec.ConfigValue<java.util.List<? extends String>> PLANTS_HIDDEN;
    public static final ConfigSpec.IntValue WATER_DEPTH;
    public static final ConfigSpec.IntValue SHALLOW_WATER;

    // Compatibility with other mods (shown in the settings only while that mod is there).
    public static final ConfigSpec.BooleanValue SIFT_ICHOR_SHIMMER;
    public static final ConfigSpec.BooleanValue SIFT_SNOW_SHIMMER;
    public static final ConfigSpec.BooleanValue SIFT_FOG;
    public static final ConfigSpec.BooleanValue AETHER_FOG;
    public static final ConfigSpec.BooleanValue NETHER_FOG;
    public static final ConfigSpec.BooleanValue END_FOG;
    public static final ConfigSpec.EnumValue<net.tapacywka.teyvatcartographia.marker.MarkerType> XAERO_PIN_TYPE;
    public static final ConfigSpec.BooleanValue XAERO_HIDDEN;
    public static final ConfigSpec.BooleanValue XAERO_DEATHS;

    public enum Detail { LOW, MEDIUM, HIGH }

    /**
     * How the path to a tracked pin goes (user decision 26.10.2026): on foot round deep water, or by boat across it; or
     * straight to it whatever lies between (user request 27.10.2026, for flying), {@link net.tapacywka.teyvatcartographia.nav.DirectPath}.
     */
    public enum Route { LAND, WATER, AIR }

    public static final ConfigSpec.EnumValue<Route> PATH_ROUTE;

    /** Clock under the minimap: the time of day in the game world, or the real time of the player's computer. */
    public enum TimeSource { GAME_TIME, REAL_TIME, BOTH }

    /** How the clock under the minimap writes the time: 14:35, 2:35 or 2:35 PM. */
    public enum TimeFormat { HOURS_24, HOURS_12, HOURS_12_AM_PM }

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();

        b.push("appearance");
        THEME = b.comment("Theme (colours, icons, Fog of War): the id of a folder in assets/teyvatcartographia/themes/ (built in: default; enabled resource packs may add more)")
                .translation("teyvatcartographia.config.theme").define("theme", "default", o -> o instanceof String s && s.matches("[a-z0-9_.-]+"));
        MINIMAP_SHAPE = b.comment("Shape of the minimap: SQUARE or ROUND").translation("teyvatcartographia.config.minimap_shape").defineEnum("minimapShape", MinimapShape.SQUARE);
        INTERFACE_STYLE = b.comment("Look of the panels, buttons and tiles: RECTANGULAR (square corners) or ROUNDED (circles, capsules, rounded corners)").translation("teyvatcartographia.config.interface_style").defineEnum("interfaceStyle", InterfaceStyle.RECTANGULAR);
        b.pop();

        b.push("minimap");
        MINIMAP_ENABLED = b.comment("Show the minimap on the HUD").translation("teyvatcartographia.config.minimap_enabled").define("enabled", true);
        MINIMAP_RADIUS = b.comment("Minimap radius in GUI pixels").translation("teyvatcartographia.config.minimap_radius").defineInRange("radius", 48, 24, 110);
        MINIMAP_CORNER = b.comment("Screen corner the minimap is anchored to").translation("teyvatcartographia.config.minimap_corner").defineEnum("corner", Corner.TOP_LEFT);
        MINIMAP_OFFSET_X = b.comment("Horizontal distance from the anchored corner").translation("teyvatcartographia.config.minimap_offset_x").defineInRange("offsetX", 4, 0, 64);
        MINIMAP_OFFSET_Y = b.comment("Vertical distance from the anchored corner").translation("teyvatcartographia.config.minimap_offset_y").defineInRange("offsetY", 4, 0, 64);
        MINIMAP_OPACITY = b.comment("Minimap opacity").translation("teyvatcartographia.config.minimap_opacity").defineInRange("opacity", 0.85, 0.2, 1.0);
        MINIMAP_PADDING = b.comment("Gap between the frame and the map inside it, in GUI pixels").translation("teyvatcartographia.config.minimap_padding").defineInRange("padding", 0, 0, 16);
        MINIMAP_FADE_WIDTH = b.comment("Width of the faded edge of the map, in percent of its radius (0: a sharp edge)").translation("teyvatcartographia.config.minimap_fade_width").defineInRange("fadeWidth", 15, 0, 50);
        MINIMAP_FADE_STRENGTH = b.comment("How transparent the very edge of the map gets, in percent (0: no fading)").translation("teyvatcartographia.config.minimap_fade_strength").defineInRange("fadeStrength", 100, 0, 100);
        MINIMAP_INFO_GAP = b.comment("Gap between the minimap and the lines under it (coordinates, biome, clock, day) and between those lines, in GUI pixels").translation("teyvatcartographia.config.minimap_info_gap").defineInRange("infoGap", 0, 0, 8);
        MINIMAP_TEXT_SIZE = b.comment("Size of the text under the minimap and of its compass letters: 0 = Auto (a step below the GUI scale), else 1 to 4 screen pixels per font pixel, at most the GUI scale").translation("teyvatcartographia.config.minimap_text_size").defineInRange("textSize", 0, 0, 4);
        MINIMAP_PILL_OPACITY = b.comment("Opacity of the background of the lines under the minimap, in percent (0: text only)").translation("teyvatcartographia.config.minimap_pill_opacity").defineInRange("pillOpacity", 0, 0, 100);
        MINIMAP_ZOOM = b.comment("Minimap scale in GUI pixels per block: 1/32, 1/16, 1/8, 1/4, 1/2, 1, 2, 4, 8, 16 or 32 (other values snap to the nearest). At 1/16 and 1/32 only the tracked target keeps its pin").translation("teyvatcartographia.config.minimap_zoom").defineInRange("zoom", 1.0, 1 / 32.0, 32.0);
        MINIMAP_ORIENTATION = b.comment("NORTH_UP: north is always up. PLAYER_UP: the map rotates with the view direction").translation("teyvatcartographia.config.minimap_orientation").defineEnum("orientation", Orientation.NORTH_UP);
        MINIMAP_UPDATE_RATE = b.comment("How many times per second the minimap is redrawn while you move or turn: RATE_10, RATE_20, RATE_30, RATE_60 or UNLIMITED (every frame). Lower gives more FPS").translation("teyvatcartographia.config.minimap_rate").defineEnum("updateRate", UpdateRate.RATE_30);
        MINIMAP_SHOW_COORDS = b.comment("Show player coordinates under the minimap").translation("teyvatcartographia.config.minimap_coords").define("showCoordinates", true);
        MINIMAP_SHOW_BIOME = b.comment("Show the biome you are in under the minimap").translation("teyvatcartographia.config.minimap_biome").define("showBiome", true);
        MINIMAP_SHOW_TIME = b.comment("Show a clock under the minimap").translation("teyvatcartographia.config.minimap_time").define("showTime", true);
        MINIMAP_TIME_SOURCE = b.comment("Clock under the minimap: GAME_TIME = time of day in the world, REAL_TIME = your computer's clock, BOTH = both side by side (game on the left)").translation("teyvatcartographia.config.minimap_time_source").defineEnum("timeSource", TimeSource.GAME_TIME);
        MINIMAP_TIME_FORMAT = b.comment("How the clock writes the time, game and real: HOURS_24 = 14:35, HOURS_12 = 2:35, HOURS_12_AM_PM = 2:35 PM").translation("teyvatcartographia.config.minimap_time_format").defineEnum("timeFormat", TimeFormat.HOURS_24);
        MINIMAP_SHOW_DAY = b.comment("Show the day of the world under the minimap, with the game time when the clock shows it").translation("teyvatcartographia.config.minimap_day").define("showDay", true);
        b.pop();

        // Below scale 1 the maps show only the tracked target; the tracked target is always shown on them.
        b.push("pins");
        for (PinKind kind : PinKind.values()) PINS[kind.ordinal()] = new PinOptions(b, kind);
        PATH_ROUTE = b.comment("LAND: the path walks round deep water (swimming across up to 16 blocks of it). WATER: it goes by boat across deep water. AIR: a straight line to the pin, whatever lies between (for flying)").translation("teyvatcartographia.config.path_route").defineEnum("route", Route.LAND);
        b.pop();

        b.push("worldmap");
        MAP_SHOW_LABELS = b.comment("Show biome name labels").translation("teyvatcartographia.config.map_labels").define("showLabels", false);
        MAP_SHOW_GRID = b.comment("Show the chunk/region grid when zoomed in").translation("teyvatcartographia.config.map_grid").define("showGrid", false);

        MAP_DEFAULT_ZOOM = b.comment("Initial world map scale (GUI pixels per block): 1/32, 1/16, 1/8, 1/4, 1/2, 1, 2, 4, 8, 16 or 32 (other values snap to the nearest)").translation("teyvatcartographia.config.map_zoom").defineInRange("defaultZoom", 1.0, 1 / 32.0, 32.0);
        b.pop();

        b.push("cartography");
        CHUNKS_PER_TICK = b.comment("Maximum number of chunks sampled per client tick").translation("teyvatcartographia.config.chunks_per_tick").defineInRange("chunksPerTick", 6, 1, 32);
        SCAN_RADIUS = b.comment("Maximum mapping radius around the player, in chunks (limited by the render distance)").translation("teyvatcartographia.config.scan_radius").defineInRange("scanRadius", 16, 2, 32);
        MAX_CACHED_TILES = b.comment("Maximum number of 256x256 map regions kept in memory").translation("teyvatcartographia.config.max_tiles").defineInRange("maxCachedTiles", 160, 32, 1024);
        DETAIL = b.comment("Terrain detail: LOW = flat colours, MEDIUM = relief shading, HIGH = relief + contour lines").translation("teyvatcartographia.config.detail").defineEnum("detail", Detail.HIGH);
        CAVES_ENABLED = b.comment("Map the caves around you while underground, in layers of 16 blocks; the world map and the minimap switch to the layer you are in").translation("teyvatcartographia.config.caves").define("caves", true);
        MAP_PLANTS = b.comment("Show plants on the map: every plant but those of plantsHidden").translation("teyvatcartographia.config.plants").define("plants", true);
        PLAYER_CONE = b.comment("The view cone and the glow under the player arrow, on the world map and the minimap").translation("teyvatcartographia.config.player_cone").define("playerCone", true);
        // The plants switched off (user decision 24.10.2026: every plant on by default, also those of mods added later);
        // until then the list held the ones switched on (plantsShown, cactus, dead bush and chorus by default).
        PLANTS_HIDDEN = b.comment("The plants the map leaves out, by block id (Settings > Map > Plants, its list): looked through to the ground; every other plant is shown").translation("teyvatcartographia.config.plants_list")
                .defineListAllowEmpty("plantsHidden", java.util.List.of(), () -> "", o -> o instanceof String);
        WATER_DEPTH = b.comment("How much deep water darkens, in percent (0: all water the same colour)").translation("teyvatcartographia.config.water_depth").defineInRange("waterDepthShading", 100, 0, 100);
        SHALLOW_WATER = b.comment("How much of the ground shows through shallow water, in percent (0: none)").translation("teyvatcartographia.config.shallow_water").defineInRange("shallowWater", 0, 0, 100);
        b.pop();

        b.push("compat");
        b.push("minecraft");
        NETHER_FOG = b.comment("The Nether: its own Fog of War, smoke and embers (else the theme fog)").translation("teyvatcartographia.config.compat.nether_fog").define("netherFog", true);
        END_FOG = b.comment("The End: its own Fog of War, a starry void the islands cast a shadow on (else the theme fog)").translation("teyvatcartographia.config.compat.end_fog").define("endFog", true);
        b.pop();
        b.push("aether");
        AETHER_FOG = b.comment("The Aether: its own Fog of War, a sea of clouds the islands cast a shadow on (else the theme fog)").translation("teyvatcartographia.config.compat.aether_fog").define("fog", true);
        b.pop();
        b.push("the_sift");
        SIFT_ICHOR_SHIMMER = b.comment("The Sift: its rainbow ichor shimmers on the map (else the average colour of its texture)").translation("teyvatcartographia.config.compat.sift_ichor").define("ichorShimmer", true);
        SIFT_SNOW_SHIMMER = b.comment("The Sift: its ichor snow shimmers on the map (else the average colour of its texture)").translation("teyvatcartographia.config.compat.sift_snow").define("snowShimmer", true);
        SIFT_FOG = b.comment("The Sift: its own Fog of War, a pearly haze in the colours of its ichor (else the theme fog)").translation("teyvatcartographia.config.compat.sift_fog").define("fog", true);
        b.pop();
        b.push("xaero");
        XAERO_PIN_TYPE = b.comment("Xaero's Minimap: the pin type imported waypoints get (a custom type)").translation("teyvatcartographia.config.compat.xaero_type").defineEnum("pinType", net.tapacywka.teyvatcartographia.marker.MarkerType.STAR, net.tapacywka.teyvatcartographia.marker.MarkerType.customTypes());
        XAERO_HIDDEN = b.comment("Xaero's Minimap: import its hidden (disabled) waypoints too").translation("teyvatcartographia.config.compat.xaero_hidden").define("hidden", true);
        XAERO_DEATHS = b.comment("Xaero's Minimap: import its death points too").translation("teyvatcartographia.config.compat.xaero_deaths").define("deaths", false);
        b.pop();
        b.pop();

        SPEC = b.build();
    }

    private MapConfig() {}

    public static void save() {
        SPEC.save();
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
        save();
    }

    private static <T> void resetValue(ConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
    }

    /** Safe read: config values throw if read before the config file was loaded. */
    public static <T> T get(ConfigSpec.ConfigValue<T> value) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return value.getDefault();
        }
    }
}
