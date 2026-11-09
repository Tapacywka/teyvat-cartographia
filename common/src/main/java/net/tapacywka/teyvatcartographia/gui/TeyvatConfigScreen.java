package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.tapacywka.aacs.api.AacsScreen;
import net.tapacywka.aacs.api.BoolValue;
import net.tapacywka.aacs.api.Palette;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.config.StepChoice;
import net.tapacywka.teyvatcartographia.hud.MinimapLayer;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.Plants;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.FogTexture;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.tapacywka.teyvatcartographia.render.ThemeLoader;
import net.tapacywka.teyvatcartographia.version.Game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The settings screen, laid out by Actually Amazing Config Screen (AACS) over the values of {@link MapConfig}: six
 * tabs, each answering one question: how the map looks and what it shows (Map), the minimap, the pins, how much work
 * the mapping may take (System), what was made for Minecraft's dimensions and other mods (Compatibility), and what is
 * still being tried (Experimental). AACS scrolls the tabs between two arrows, as they do not fit in the panel. The
 * pictures of the card beside the list stay here ({@link Pictures}, {@link SettingsPreview}).
 */
public final class TeyvatConfigScreen {
    private TeyvatConfigScreen() {}

    private static final String K = "teyvatcartographia.config.";
    private static AacsScreen spec;

    /** A new settings screen over {@code parent}. */
    public static Screen open(Screen parent) {
        if (spec == null) spec = build();
        return spec.open(parent);
    }

    private static final String[] CLOCK_STEPS = {K + "step.off", K + "step.game", K + "step.real", K + "step.both"};
    private static final String[] EDGE_STEPS = {K + "step.off", K + "step.edges_still", K + "step.edges_breathing", K + "step.edges_surf"};
    private static final String[] PIN_STEPS ={K + "step.off", K + "step.map", K + "step.maps", K + "step.world", K + "step.everywhere"};
    private static final String[] PATH_STEPS = {K + "step.off", K + "step.map", K + "step.maps", K + "step.everywhere"};

    /** Sets an on/off setting and its three parts from a step (see {@link StepChoice}); off leaves the parts as they are. */
    private static void setSteps(int step, BoolValue on, BoolValue map, BoolValue minimap, BoolValue world) {
        on.set(StepChoice.on(step));
        if (!StepChoice.on(step)) return;
        map.set(StepChoice.map(step));
        minimap.set(StepChoice.minimap(step));
        world.set(StepChoice.world(step));
    }

    /** Where a kind of pins shows: off, the map, both maps, in the world with the marker, everywhere with the beam. */
    public static void pinRow(AacsScreen.Tab t, MapConfig.PinKind kind) {
        MapConfig.PinOptions o = MapConfig.pins(kind);
        t.steps(K + "pins." + kind.id, PIN_STEPS,
                () -> StepChoice.level(o.enabled.get(), o.map.get(), o.minimap.get(), o.marker.get(), o.beam.get()),
                s -> {
                    setSteps(Math.min(s, StepChoice.IN_WORLD), o.enabled, o.map, o.minimap, o.marker);
                    if (StepChoice.on(s)) o.beam.set(StepChoice.beam(s));
                });
    }

    /** Where the path to a tracked pin of a kind shows: off, the map, both maps, everywhere with the stars. */
    public static void pathRow(AacsScreen.Tab t, MapConfig.PinKind kind) {
        MapConfig.PinOptions o = MapConfig.pins(kind);
        t.steps(K + "path." + kind.id, PATH_STEPS,
                () -> StepChoice.level(o.path.get(), o.pathMap.get(), o.pathMinimap.get(), o.pathWorld.get()),
                s -> setSteps(s, o.path, o.pathMap, o.pathMinimap, o.pathWorld));
    }


    private static AacsScreen build() {
        Runnable remap = () -> MapDataManager.get().remapLoaded(); // the chunks around mapped again in the new way
        AacsScreen.Builder b = AacsScreen.builder("teyvatcartographia", MapConfig.FILE)
                .palette(TeyvatConfigScreen::palette)
                .rounded(() -> MapConfig.get(MapConfig.INTERFACE_STYLE) == MapConfig.InterfaceStyle.ROUNDED)
                .background((g, w, h, pt) -> FogTexture.draw(g, 0, 0, w, h, 0, 0, Theme.WHITE))
                .modList(false) // each loader's entry point registers it (Mod Menu, the mod list of NeoForge)
                .button(K + "keybinds", Identifier.fromNamespaceAndPath("teyvatcartographia", "themes/default/icons/64/keyboard.png"),
                        s -> Game.setScreen(new KeyBindsScreen(s, Minecraft.getInstance().options)))
                .onReset(() -> {
                    Theme.Style.update();
                    ThemeLoader.applyNow();
                    remap.run();
                });
        // Map: the look of the interface, what is drawn on both maps, the world map itself.
        b.tab(K + "tab.map", t -> {
            t.defaultPreview(new Pictures(K + "tab.map"));
            t.section(K + "section.appearance");
            // Only the built-in theme: nothing to choose until a theme pack is enabled.
            t.choice(K + "theme", MapConfig.THEME, ThemeLoader::available, ThemeLoader::displayName, c -> c.activeWhen(() -> ThemeLoader.available().size() > 1))
                    .then(ThemeLoader::applyNow).noPreview();
            t.choice(K + "interface_style", MapConfig.INTERFACE_STYLE, c -> {}).then(Theme.Style::update).noPreview();
            t.section(K + "section.map_content");
            t.toggleList(K + "plants", MapConfig.MAP_PLANTS, MapConfig.PLANTS_HIDDEN, TeyvatConfigScreen::plants, remap).then(remap);
            t.toggle(K + "caves", MapConfig.CAVES_ENABLED);
            t.toggle(K + "player_cone", MapConfig.PLAYER_CONE);
            // How the map is coloured: relief, water.
            t.section(K + "section.colours");
            // A choice with arrows, not a slider (user request 24.10.2026: three steps made it the one short slider).
            t.choice(K + "detail", MapConfig.DETAIL, c -> {}).then(remap);
            t.slider(K + "water_depth", MapConfig.WATER_DEPTH, s -> s.unit("%")).then(remap);
            t.slider(K + "shallow_water", MapConfig.SHALLOW_WATER, s -> s.unit("%")).then(remap);
            t.section(K + "section.worldmap");
            t.slider(K + "map_zoom", MapConfig.MAP_DEFAULT_ZOOM, TeyvatConfigScreen::zoom);
            t.toggle(K + "map_labels", MapConfig.MAP_SHOW_LABELS);
            t.toggle(K + "map_grid", MapConfig.MAP_SHOW_GRID);
        });
        // Minimap: whether and how it shows, its size and place, its look, the lines under it.
        b.tab(K + "tab.minimap", t -> {
            t.defaultPreview(new Pictures(K + "tab.minimap"));
            t.section(K + "subsection.minimap_display");
            t.toggle(K + "minimap_enabled", MapConfig.MINIMAP_ENABLED);
            t.choice(K + "minimap_shape", MapConfig.MINIMAP_SHAPE, c -> {}).then(Theme.Style::update);
            t.slider(K + "minimap_zoom", MapConfig.MINIMAP_ZOOM, TeyvatConfigScreen::zoom);
            t.choice(K + "minimap_orientation", MapConfig.MINIMAP_ORIENTATION, c -> {});
            t.section(K + "subsection.minimap_layout");
            // No larger than the screen holds with the lines under it, at this GUI scale.
            t.slider(K + "minimap_radius", MapConfig.MINIMAP_RADIUS, s -> s.max(() -> {
                var w = Minecraft.getInstance().getWindow();
                return MinimapLayer.maxRadius(w.getGuiScaledWidth(), w.getGuiScaledHeight());
            }));
            // The corners clockwise from the top left, as the arrows go round the screen (user request 31.10.2026).
            t.choice(K + "minimap_corner", MapConfig.MINIMAP_CORNER, c -> c.only(List.of(MapConfig.Corner.TOP_LEFT,
                    MapConfig.Corner.TOP_RIGHT, MapConfig.Corner.BOTTOM_RIGHT, MapConfig.Corner.BOTTOM_LEFT)));
            t.slider(K + "minimap_offset_x", MapConfig.MINIMAP_OFFSET_X, s -> {});
            t.slider(K + "minimap_offset_y", MapConfig.MINIMAP_OFFSET_Y, s -> {});
            t.section(K + "section.minimap_look");
            t.slider(K + "minimap_opacity", MapConfig.MINIMAP_OPACITY, s -> {});
            t.slider(K + "minimap_padding", MapConfig.MINIMAP_PADDING, s -> {});
            t.slider(K + "minimap_fade_width", MapConfig.MINIMAP_FADE_WIDTH, s -> s.unit("%"));
            t.slider(K + "minimap_fade_strength", MapConfig.MINIMAP_FADE_STRENGTH, s -> s.unit("%"));
            // What the lines under it show first, then how they look (user decision 27.10.2026).
            t.section(K + "subsection.minimap_info");
            t.toggle(K + "minimap_coords", MapConfig.MINIMAP_SHOW_COORDS);
            t.toggle(K + "minimap_biome", MapConfig.MINIMAP_SHOW_BIOME);
            // The clock as one choice: off, game time, real time, both.
            t.steps(K + "minimap_time", CLOCK_STEPS,
                    () -> StepChoice.clock(MapConfig.MINIMAP_SHOW_TIME.get(), MapConfig.MINIMAP_TIME_SOURCE.get()),
                    s -> {
                        MapConfig.MINIMAP_SHOW_TIME.set(s > 0);
                        if (s > 0) MapConfig.MINIMAP_TIME_SOURCE.set(StepChoice.clockSource(s));
                    });
            // The time format only matters while the clock shows a time.
            t.choice(K + "minimap_time_format", MapConfig.MINIMAP_TIME_FORMAT, c -> c.activeWhen(MapConfig.MINIMAP_SHOW_TIME::get));
            t.toggle(K + "minimap_day", MapConfig.MINIMAP_SHOW_DAY);
            t.slider(K + "minimap_text_size", MapConfig.MINIMAP_TEXT_SIZE, s -> s.label(d -> Math.round(d) == 0
                    ? Component.translatable(K + "value.auto").getString() : Integer.toString((int) Math.round(d))));
            t.slider(K + "minimap_pill_opacity", MapConfig.MINIMAP_PILL_OPACITY, s -> s.unit("%"));
            t.slider(K + "minimap_info_gap", MapConfig.MINIMAP_INFO_GAP, s -> {});
        });
        // Pins: each kind as one choice (off, the map, both maps, in the world with the marker, everywhere with the beam);
        // then the path to a tracked pin of each kind (off, the map, both maps, everywhere with the stars). The
        // interactive pins are the stones of Waystones: their two rows are in Compatibility > Waystones (user decision
        // 31.10.2026), so this tab is the same with the mod or without it.
        b.tab(K + "tab.pins", t -> {
            t.defaultPreview(new Pictures(K + "tab.pins"));
            List<MapConfig.PinKind> kinds = List.of(MapConfig.PinKind.CUSTOM, MapConfig.PinKind.SYSTEM);
            t.section(K + "subsection.pins_display");
            for (MapConfig.PinKind kind : kinds) pinRow(t, kind);
            t.section(K + "subsection.pins_path");
            for (MapConfig.PinKind kind : kinds) pathRow(t, kind);
            // How the path goes: on land round deep water, or by boat across it (user decision 26.10.2026); a new search.
            t.choice(K + "path_route", MapConfig.PATH_ROUTE, c -> {}).then(Navigation::requestPath);
        });
        // System: how much work the mapping and the minimap may take.
        b.tab(K + "tab.system", t -> {
            t.defaultPreview(new Pictures(K + "tab.system"));
            t.section(K + "section.performance");
            t.slider(K + "scan_radius", MapConfig.SCAN_RADIUS, s -> {});
            t.slider(K + "chunks_per_tick", MapConfig.CHUNKS_PER_TICK, s -> {});
            t.slider(K + "max_tiles", MapConfig.MAX_CACHED_TILES, s -> {});
            t.choice(K + "minimap_rate", MapConfig.MINIMAP_UPDATE_RATE, c -> {});
        });
        // Compatibility: Minecraft first (the fogs of its own dimensions, always there, user decision 24.10.2026), then a
        // sub-section per mod in the game, by name: the mod's own ones (compatPart) and those other mods add, open to
        // them as "compatibility" (user decision 02.11.2026); everything made for one mod is there, its fog of war too.
        b.tab(K + "tab.compat", t -> {
            t.openTo(COMPATIBILITY).defaultPreview(COMPAT_PICTURES);
            t.subSection(K + "subsection.compat_minecraft")
                    .toggle(K + "compat.nether_fog", MapConfig.NETHER_FOG).then(Theme.Style::update)
                    .toggle(K + "compat.end_fog", MapConfig.END_FOG).then(Theme.Style::update);
        });
        // Experimental (user request 08.11.2026): what is not checked to the end yet, or wants more work, a faster or
        // another way; the far land, the map edges and the world border first. None of them is in the map's quick settings
        // (user decision 08.11.2026): a setting goes there only once its feature is out in full.
        b.tab(K + "tab.experimental", t -> {
            t.defaultPreview(new Pictures(K + "tab.experimental"));
            t.section(K + "section.map_content");
            t.toggle(K + "far_land", MapConfig.FAR_LAND);
            // One slider (user request 08.11.2026): off, on and still, breathing, breathing with waves.
            t.steps(K + "map_outline", EDGE_STEPS,
                    () -> MapConfig.MAP_OUTLINE.get() ? MapConfig.MAP_OUTLINE_MOTION.get().ordinal() + 1 : 0,
                    s -> {
                        MapConfig.MAP_OUTLINE.set(s > 0);
                        if (s > 0) MapConfig.MAP_OUTLINE_MOTION.set(MapConfig.EdgeMotion.values()[s - 1]);
                    });
            t.toggle(K + "world_border", MapConfig.WORLD_BORDER);
        });
        return b.build();
    }

    /** The name the Compatibility tab is open to other mods under. */
    public static final String COMPATIBILITY = "compatibility";
    private static final Pictures COMPAT_PICTURES = new Pictures(K + "tab.compat");

    /**
     * A sub-section of Compatibility for a supported mod ({@code id}: its title {@code subsection.<id>}), shown only
     * while {@code loaded}; added the way other mods add theirs, its values in the mod's own settings file.
     */
    public static void compatPart(String id, java.util.function.BooleanSupplier loaded, Consumer<AacsScreen.Tab> rows) {
        AacsScreen.extend("teyvatcartographia", COMPATIBILITY, "teyvatcartographia", MapConfig.FILE, t -> {
            t.defaultPreview(COMPAT_PICTURES);
            rows.accept(t);
        }).title(K + "subsection." + id).shownIf(loaded);
    }

    /** The chunks around mapped again in the new way (a setting of the map's colours changed). */
    public static void remap() {
        MapDataManager.get().remapLoaded();
    }

    /** A zoom setting: the steps of MapView.ZOOM_STEPS, shown as "1/4", "1", "8". */
    private static void zoom(AacsScreen.Slider s) {
        s.steps(MapView.ZOOM_STEPS).label(MapView::zoomLabel);
    }

    /**
     * The plant's colour on the map (user request 30.10.2026: instead of its item): its texture colour, grass and foliage
     * tinted with the game's default tints, as no biome is at hand (also in the title screen). The list's texts are the
     * mod's own (the library reads them by the row's key): teyvatcartographia.config.plants.find,
     * teyvatcartographia.config.plants.count, teyvatcartographia.config.plants.none.
     */
    private static int plantColour(net.minecraft.world.level.block.Block b) {
        var c = net.tapacywka.teyvatcartographia.map.data.TextureColors.get(b);
        if (c == null) return Theme.PANEL_LINE;
        return switch (c.tint()) {
            case GRASS -> net.tapacywka.teyvatcartographia.map.data.TextureColors.multiply(c.argb(), net.minecraft.world.level.GrassColor.getDefaultColor());
            case FOLIAGE -> net.tapacywka.teyvatcartographia.map.data.TextureColors.multiply(c.argb(), net.minecraft.world.level.FoliageColor.FOLIAGE_DEFAULT);
            default -> c.argb();
        };
    }

    /** The plants of the Plants row's list: every plant by name, its colour on the map. */
    private static List<AacsScreen.ListEntry> plants() {
        List<AacsScreen.ListEntry> out = new ArrayList<>();
        for (var block : Plants.all()) {
            out.add(new AacsScreen.ListEntry(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString(),
                    block.getName(), plantColour(block)));
        }
        out.sort(Comparator.comparing(e -> e.name().getString().toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ the colours

    private static Palette palette;
    private static int paletteTheme = -1;

    /** The theme's colours under their names in colors.json, the names of the library's palette too. */
    private static Palette palette() {
        if (palette == null || paletteTheme != Theme.generation()) {
            Palette p = Palette.DEFAULT;
            for (String name : Palette.DEFAULTS.keySet()) {
                try {
                    p = p.with(name, Theme.class.getField(name.toUpperCase(Locale.ROOT)).getInt(null));
                } catch (ReflectiveOperationException ignored) {
                    // A colour the theme does not have: the library's.
                }
            }
            palette = p;
            paletteTheme = Theme.generation();
        }
        return palette;
    }

    // ------------------------------------------------------------------ the pictures of the card

    /** The minimap's size and position: their preview is the picture of the screen alone. */
    private static final Set<String> PLACE_ROWS = Set.of(K + "minimap_radius", K + "minimap_corner", K + "minimap_offset_x", K + "minimap_offset_y");

    /** The minimap's look: their preview is the minimap alone, without the lines under it. */
    private static final Set<String> LOOK_ROWS = Set.of(K + "minimap_opacity", K + "minimap_padding", K + "minimap_fade_width", K + "minimap_fade_strength");

    /** The far land, the map edges and the world border: the map from farther out, where they show. */
    private static final Set<String> FAR_ROWS = Set.of(K + "far_land", K + "map_outline", K + "world_border");
    private static final double FAR_ZOOM = 1 / 8.0;

    /** A row of where a kind of pins or its path shows: the frames of the Pins tab, also for the stones of Waystones in Compatibility. */
    private static boolean pinKindRow(String rk) {
        return rk.startsWith(K + "pins.") || rk.startsWith(K + "path.");
    }

    /** The pictures of the settings of one tab on the card. */
    private static final class Pictures implements AacsScreen.Preview {
        private final String tab;
        private final MapView view = new MapView();
        /** The minimap's layers are up to date for this frame (its preview while it is on). */
        private boolean minimap;

        Pictures(String tab) {
            this.tab = tab;
        }

        /** No "name: value" line under the picture (user request 06.11.2026); the frames of the pins, caves and detail keep their own captions. */
        @Override
        public boolean caption(String rk) {
            return false;
        }

        @Override
        public void prepare(GuiGraphicsExtractor g, String rk, float w, float h, float pt) {
            // The minimap's layers are brought up to date before any clipping (they draw off screen).
            Minecraft mc = Minecraft.getInstance();
            // The rows of its look: as if Size were 110 (the picture is scaled up to the card, a large map stays sharp).
            minimap = mc.player != null && mc.level != null && tab.equals(K + "tab.minimap")
                    && MinimapLayer.preparePreview(g, mc.getDeltaTracker(), LOOK_ROWS.contains(rk) ? 110 : 0);
        }

        @Override
        public void draw(GuiGraphicsExtractor g, String rk, float cx, float cy, float cw, float ch, float pt) {
            Minecraft mc = Minecraft.getInstance();
            boolean world = mc.player != null && mc.level != null;
            if (rk.equals(K + "caves") && world) {
                SettingsPreview.caves(g, cx, cy, cw, ch, (g2, mx, my, mw, mh, band) -> map(g2, mx, my, mw, mh,
                        MapView.snapZoom(MapConfig.get(MapConfig.MAP_DEFAULT_ZOOM)), pt, band == null ? MapDataManager.get().active() : MapDataManager.get().caveStore(band)));
            } else if (rk.equals(K + "detail")) {
                if (world) SettingsPreview.detail(g, cx, cy, cw, ch);
                else note(g, cx, cy, cw, ch, K + "preview.no_world");
            } else if (tab.equals(K + "tab.map") || FAR_ROWS.contains(rk)) {
                if (world) map(g, cx, cy, cw, ch, FAR_ROWS.contains(rk) ? FAR_ZOOM : MapView.snapZoom(MapConfig.get(MapConfig.MAP_DEFAULT_ZOOM)), pt);
                else note(g, cx, cy, cw, ch, K + "preview.no_world");
            } else if (tab.equals(K + "tab.minimap")) {
                if (!world) note(g, cx, cy, cw, ch, K + "preview.no_world");
                else if (!minimap) note(g, cx, cy, cw, ch, K + "preview.minimap_off");
                // Its look: the minimap alone, without the lines under it; the rest (on or off too): with them, at its real size.
                else if (LOOK_ROWS.contains(rk)) SettingsPreview.minimap(g, cx, cy, cw, ch);
                // Size and position: only where it sits on the screen, the minimap alone on the picture, as large as it fits.
                else if (PLACE_ROWS.contains(rk)) {
                    int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
                    float pw = cw, ph = pw * height / width;
                    if (ph > ch) {
                        ph = ch;
                        pw = ph * width / height;
                    }
                    screenPicture(g, cx + (cw - pw) / 2f, cy + (ch - ph) / 2f, pw, ph, width, height);
                } else minimapPicture(g, cx, cy, cw, ch);
            } else if (rk.equals(K + "path_route")) {
                SettingsPreview.route(g, cx, cy, cw, ch);
            } else if (tab.equals(K + "tab.pins") || pinKindRow(rk)) {
                SettingsPreview.pins(g, cx, cy, cw, ch, (g2, mx, my, mw, mh, zoom) -> map(g2, mx, my, mw, mh, zoom, pt), rk);
            } else if (tab.equals(K + "tab.system")) {
                if (world) SettingsPreview.system(g, cx, cy, cw, ch);
                else note(g, cx, cy, cw, ch, K + "preview.no_world");
            } else {
                SettingsPreview.compatSample(g, cx, cy, cw, ch, rk, (g2, mx, my, mw, mh, zoom) -> map(g2, mx, my, mw, mh, zoom, pt)); // this setting's mod alone
            }
        }

        /**
         * The preview's regions while Map edges is drawn by its shader ({@link net.tapacywka.teyvatcartographia.render.EdgeShader}),
         * as large as the window; one for the game, kept once made.
         */
        private static final net.tapacywka.teyvatcartographia.render.CachedLayer PREVIEW_EDGES = new net.tapacywka.teyvatcartographia.render.CachedLayer();

        /** A short note in the middle of the area (no world, the minimap off, no preview yet). */
        private static void note(GuiGraphicsExtractor g, float x, float y, float w, float h, String key) {
            Component t = Component.literal(net.tapacywka.teyvatcartographia.render.DrawText.fit(Component.translatable(key).getString(), (int) w));
            net.tapacywka.teyvatcartographia.render.DrawText.textCentered(g, t, Draw.snap(x + w / 2f), Draw.snap(y + h / 2f - 4), Theme.TEXT_MUTED, false);
        }

        /**
         * The map around the player over the whole area, as the world map draws it at {@code zoom}: fog, regions, the
         * grid and the biome names when they are on, the player in the middle.
         */
        private void map(GuiGraphicsExtractor g, float x, float y, float w, float h, double zoom, float pt) {
            map(g, x, y, w, h, zoom, pt, MapDataManager.get().shownStore());
        }

        /** As above, drawing {@code store} (a cave layer for the cave map's preview). */
        private void map(GuiGraphicsExtractor g, float x, float y, float w, float h, double zoom, float pt, net.tapacywka.teyvatcartographia.map.data.TileStore store) {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.phys.Vec3 pos = mc.player.getPosition(pt);
            float mx = Draw.snap(x + w / 2f), my = Draw.snap(y + h / 2f);
            view.set(pos.x, pos.z, zoom, 0, mx, my);
            boolean cave = store != null && store.cave;
            g.enableScissor((int) x, (int) y, (int) (x + w), (int) (y + h));
            FogTexture.draw(g, store == null || cave ? null : store.dimension, x, y, x + w, y + h, (float) (pos.x * zoom), (float) (pos.z * zoom), Theme.WHITE);
            if (store != null && !cave) {
                net.tapacywka.teyvatcartographia.render.MapLayerRenderer.drawFar(g, MapDataManager.get().farLand(store.dimension), view, x, y, x + w, y + h, false);
            }
            int tint = cave ? 0xFFFFFFFF : net.tapacywka.teyvatcartographia.render.DayLight.tint(pt);
            float night = cave ? 0f : net.tapacywka.teyvatcartographia.render.DayLight.night(pt);
            if (store != null && !cave && MapConfig.get(MapConfig.MAP_OUTLINE) && net.tapacywka.teyvatcartographia.render.EdgeShader.inUse()) {
                // The edge's shader takes its values only in the mod's layers: the regions go into one, then onto the area.
                var window = mc.getWindow();
                PREVIEW_EDGES.bind(g, window.getWidth(), window.getHeight(), false);
                try {
                    net.tapacywka.teyvatcartographia.render.MapLayerRenderer.drawRect(g, store, view, x, y, x + w, y + h, tint, night);
                } finally {
                    PREVIEW_EDGES.unbind();
                }
                float gw = window.getGuiScaledWidth(), gh = window.getGuiScaledHeight();
                Draw.texturedQuadPremultiplied(g, PREVIEW_EDGES.tex(), x, y, x + w, y + h,
                        x / gw, net.tapacywka.teyvatcartographia.render.CachedLayer.v(1 - y / gh),
                        (x + w) / gw, net.tapacywka.teyvatcartographia.render.CachedLayer.v(1 - (y + h) / gh));
            } else {
                net.tapacywka.teyvatcartographia.render.MapLayerRenderer.drawRect(g, store, view, x, y, x + w, y + h, tint, night);
            }
            if (store != null) net.tapacywka.teyvatcartographia.render.BorderLine.draw(g, store.dimension, view, x, y, x + w, y + h);
            if (MapConfig.get(MapConfig.MAP_SHOW_GRID)) grid(g, x, y, w, h, zoom);
            if (MapConfig.get(MapConfig.MAP_SHOW_LABELS)) {
                net.tapacywka.teyvatcartographia.render.MapLayerRenderer.drawBiomeLabels(g, store, view, x, y, x + w, y + h, List.of());
            }
            float heading = (float) view.headingOnScreen(mc.player.getViewYRot(pt));
            if (MapConfig.get(MapConfig.PLAYER_CONE)) Draw.sector(g, mx, my, 24, heading, 0.7f, Theme.VIEW_CONE, Theme.withAlpha(Theme.VIEW_CONE, 0f));
            net.tapacywka.teyvatcartographia.render.MarkerRenderer.player(g, mx, my, 16, heading, 1f);
            g.disableScissor();
            Draw.frame(g, x, y, w, h, 1, Theme.PANEL_LINE);
        }

        /** The chunk and region grid, as the world map draws it (from zoom 1; chunks from 2). */
        private void grid(GuiGraphicsExtractor g, float x, float y, float w, float h, double zoom) {
            if (zoom < MapView.DETAIL_FROM - 1e-6) return; // like the world map
            int step = zoom >= 2 ? 16 : 256;
            double wx0 = view.worldX(x, y), wz0 = view.worldZ(x, y), wx1 = view.worldX(x + w, y + h), wz1 = view.worldZ(x + w, y + h);
            for (long gx = (long) Math.floor(wx0 / step) * step; gx <= wx1; gx += step) {
                float sx = view.screenX(gx, 0);
                Draw.rect(g, sx, y, sx + 0.5f, y + h, gx % 256 == 0 ? Theme.GRID_LINE_MAJOR : Theme.GRID_LINE_MINOR);
            }
            for (long gz = (long) Math.floor(wz0 / step) * step; gz <= wz1; gz += step) {
                float sy = view.screenY(0, gz);
                Draw.rect(g, x, sy, x + w, sy + 0.5f, gz % 256 == 0 ? Theme.GRID_LINE_MAJOR : Theme.GRID_LINE_MINOR);
            }
        }

        /**
         * The minimap with the lines under it as the HUD shows it, at its real size (smaller only without room for it),
         * centred in the area on both axes, on the pixel grid; no picture of the screen (user decision 24.10.2026).
         */
        private static void minimapPicture(GuiGraphicsExtractor g, float x, float y, float w, float h) {
            int[] b = MinimapLayer.preparedBounds();
            float bw = b[2] - b[0], bh = b[3] - b[1];
            float s = Math.min(1f, Math.min(w / bw, h / bh));
            g.pose().pushMatrix();
            g.pose().translate(Draw.snap(x + (w - bw * s) / 2f), Draw.snap(y + (h - bh * s) / 2f));
            g.pose().scale(s, s);
            g.pose().translate(-b[0], -b[1]);
            MinimapLayer.drawPrepared(g);
            g.pose().popMatrix();
        }

        /** A picture of the screen at (x, y), {@code w} x {@code h}, with the minimap alone (no lines under it) where it sits on it. */
        private static void screenPicture(GuiGraphicsExtractor g, float x, float y, float w, float h, int width, int height) {
            float sx = Draw.snap(x), sy = Draw.snap(y), sw = Draw.snap(w), sh = Draw.snap(h);
            Draw.rect(g, sx, sy, sx + sw, sy + sh, Theme.PANEL_DARK_HEADER);
            float[] m = MinimapLayer.preparedMap();
            float kx = sw / width, ky = sh / height;
            g.enableScissor((int) (sx + (m[0] - m[2]) * kx), (int) (sy + (m[1] - m[2]) * ky), (int) Math.ceil(sx + (m[0] + m[2]) * kx),
                    (int) Math.ceil(sy + (m[1] + m[2]) * ky));
            g.pose().pushMatrix();
            g.pose().translate(sx, sy);
            g.pose().scale(sw / width, sh / height);
            MinimapLayer.drawPrepared(g);
            g.pose().popMatrix();
            g.disableScissor();
            Draw.frame(g, sx, sy, sw, sh, 1, Theme.PANEL_LINE);
        }
    }
}
