package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.world.level.biome.Biome;
import net.tapacywka.teyvatcartographia.client.Keybinds;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.gui.panel.DimensionPanel;
import net.tapacywka.teyvatcartographia.gui.panel.MarkerListPanel;
import net.tapacywka.teyvatcartographia.gui.panel.PinPanel;
import net.tapacywka.teyvatcartographia.gui.panel.SettingsPanel;
import net.tapacywka.teyvatcartographia.gui.panel.SidePanel;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.CaveScanner;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.TileStore;
import net.tapacywka.teyvatcartographia.marker.AutoMarkers;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;
import net.tapacywka.teyvatcartographia.marker.MarkerVisibility;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.nav.Route;
import net.tapacywka.teyvatcartographia.render.Backdrop;
import net.tapacywka.teyvatcartographia.render.CachedLayer;
import net.tapacywka.teyvatcartographia.render.DistanceText;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.EdgeFade;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.MapLayerRenderer;
import net.tapacywka.teyvatcartographia.render.MarkerRenderer;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen world map in the style of the references: fog-of-war backdrop, painted map regions, biome labels,
 * markers, player arrow and off-screen indicator, top info pills and close button, zoom slider on the left,
 * settings/list buttons bottom-left, area name and explored counter bottom-right, and side panels on the right.
 * <p>
 * Controls: drag (LMB) to pan, wheel to zoom at the cursor, click a pin to open it (overlapping pins: a pick list),
 * click empty ground to create a pin, Space to centre on the player, Esc/M to close. The right button does nothing.
 */
public final class WorldMapScreen extends Screen {
    private final MapView view = new MapView();
    private double centerX, centerZ, scale;
    private double targetX, targetZ, targetScale;
    private boolean zoomAnchored;
    // A flight to a search result (startFlight): from where, how far into it, and how far out it zooms on the way.
    private boolean flying;
    private double flyFromX, flyFromZ, flyFromScale, flyBump;
    private float flyT, flyDuration;
    // The place a search result flew to: rings go out from it for a few seconds after arriving.
    private ResourceKey<Level> foundDim;
    private double foundX, foundZ;
    private float foundAt = Float.NEGATIVE_INFINITY;
    private static final float FOUND_PULSES = 3;
    private boolean foundPlace;
    /** The search of the pin list; kept while the map is open, so a closed and reopened list shows it again. */
    private final MapSearch search = new MapSearch();
    private double anchorSX, anchorSY, anchorWX, anchorWZ;

    private ResourceKey<Level> viewedDim;
    /** The cave band shown (in the player's dimension), or null for the surface. */
    private Integer viewedBand;
    private SidePanel panel;
    private Marker selected;
    private Double selX, selZ;
    private Marker hovered;

    private boolean dragging, dragMoved, sliderDrag;
    /** The scale was set on the zoom slider: its thumb shows the target until the map has eased there. */
    private boolean thumbAtTarget;
    private double dragStartX, dragStartY;
    private long lastFrameNs;
    private float time;

    // View the panel backdrop was last blurred at, and when (seconds of {@link #time}).
    private double blurX = Double.NaN, blurZ, blurScale;
    private float blurTime;

    private final MapPicture picture = new MapPicture();

    public WorldMapScreen() {
        super(Component.translatable("teyvatcartographia.map.title"));
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            centerX = targetX = p.getX();
            centerZ = targetZ = p.getZ();
            viewedDim = p.level().dimension();
            viewedBand = MapDataManager.get().undergroundBand(); // opens on the layer the player is in
        }
        scale = targetScale = clampScale(MapConfig.get(MapConfig.MAP_DEFAULT_ZOOM));
    }

    /**
     * The map opened by the quick pin key: the new pin editor at the player's block, on the layer they are in (it
     * used to add a star pin at once; user request 24.10.2026).
     */
    public static WorldMapScreen newPinHere() {
        WorldMapScreen s = new WorldMapScreen();
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) s.newPinAt(p.getBlockX(), p.getBlockY(), p.getBlockZ());
        return s;
    }

    /** The new pin editor at a block of the viewed dimension, the spot marked on the map. */
    private void newPinAt(int bx, int y, int bz) {
        selected = null;
        selX = bx + 0.5;
        selZ = bz + 0.5;
        openPanel(new PinPanel(this, viewedDim, bx, y, bz));
    }

    // ================================================================== helpers

    /** Dimension names; asked several times per frame, built once (a translatable name follows the language). */
    private static final java.util.Map<ResourceKey<Level>, Component> DIMENSION_NAMES = new java.util.HashMap<>();

    public static Component dimensionName(ResourceKey<Level> dimension) {
        return DIMENSION_NAMES.computeIfAbsent(dimension, WorldMapScreen::buildDimensionName);
    }

    /** The mod's name for a dimension, else its id's path made readable ("the_aether" as "The Aether"). */
    private static Component buildDimensionName(ResourceKey<Level> dimension) {
        String key = net.minecraft.Util.makeDescriptionId("teyvatcartographia.dimension", dimension.location());
        if (net.minecraft.client.resources.language.I18n.exists(key)) return Component.translatable(key);
        String pretty = readableName(dimension.location().getPath());
        return Component.literal(pretty.isEmpty() ? dimension.location().toString() : pretty);
    }

    /** An id's path as a title: words split at "_", "/" and "-", each with a capital letter ("the_aether": "The Aether"). */
    static String readableName(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.split("[_/\\-]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    public ResourceKey<Level> viewedDimension() {
        return viewedDim;
    }

    private boolean viewingPlayerDim() {
        LocalPlayer p = Minecraft.getInstance().player;
        return viewedDim != null && p != null && viewedDim == p.level().dimension();
    }

    private TileStore store() {
        if (viewedDim == null) return null;
        if (viewedBand != null && viewingPlayerDim() && MapDataManager.get().cavesSupported()) return MapDataManager.get().caveStore(viewedBand);
        return MapDataManager.get().viewStore(viewedDim);
    }

    /** Snaps any scale to the nearest zoom step (MapView.ZOOM_STEPS: wheel, keys, buttons and slider move between them). */
    private static double clampScale(double s) {
        return MapView.snapZoom(s);
    }

    /** Markers of the viewed dimension: all of them, or only those drawn at the current scale (MarkerVisibility). */
    public List<Marker> visibleMarkers(boolean all) {
        List<Marker> out = new ArrayList<>();
        if (viewedDim == null) return out;
        for (Marker m : MarkerStore.inDimension(viewedDim)) if (all || MarkerVisibility.shown(m, scale, false)) out.add(m);
        for (Marker m : AutoMarkers.forDimension(viewedDim)) if (all || MarkerVisibility.shown(m, scale, false)) out.add(m);
        return out;
    }

    // ================================================================== panel API

    /** Open list of overlapping pins to choose from, or null. */
    private MarkerPickList pick;

    /** Open context menu of a right click on the map, or null. */
    private MapContextMenu menu;

    /** The pin list a pin panel was opened from: closing that pin panel goes back to it (same search and scroll). */
    private MarkerListPanel backToList;

    private void openPanel(SidePanel p) {
        pick = null;
        backToList = null;
        if (panel != null) panel.onClose();
        panel = p;
        if (p != null) p.layout(width, height);
    }

    /** Opens the pin list (the list button, Ctrl+F); a pin panel it replaces takes its highlight along. */
    private MarkerListPanel openList() {
        if (panel instanceof PinPanel) clearSelection();
        MarkerListPanel list = new MarkerListPanel(this);
        openPanel(list);
        return list;
    }

    /**
     * Closes the side panel (the cross, Esc, a click on the map, a pin saved or deleted). A pin panel opened from the
     * pin list gives way to that list again; anything else leaves the bare map.
     */
    public void closePanel() {
        openPanel(panel instanceof PinPanel ? backToList : null);
        clearSelection(); // the highlight belongs to the open pin panel; it goes away together with it
    }

    private void clearSelection() {
        selected = null;
        selX = selZ = null;
    }

    private void selectMarker(Marker m) {
        selected = m;
        selX = selZ = null;
        openPanel(new PinPanel(this, m));
    }

    public void focusMarker(Marker m) {
        flyTo(m.centerX(), m.centerZ());
        if (MarkerVisibility.pinsHidden(scale)) {
            targetScale = 1;
            thumbAtTarget = false;
        }
        selectMarker(m);
    }

    public MapSearch search() {
        return search;
    }

    /** A pin found by the search: flies there (in its dimension), opens its panel and sends rings out from it. */
    public void showMarker(Marker m) {
        MarkerListPanel list = panel instanceof MarkerListPanel l ? l : null;
        viewDimension(m.dimension);
        startFlight(m.centerX(), m.centerZ(), Math.max(targetScale, 1));
        found(m.dimension, m.centerX(), m.centerZ(), false);
        selectMarker(m);
        backToList = list;
    }

    /** A biome place found by the search: flies there (in its dimension) and sends rings out from it. */
    public void showPlace(ResourceKey<Level> dim, double x, double z) {
        viewDimension(dim);
        startFlight(x, z, targetScale);
        found(dim, x, z, true);
    }

    private void found(ResourceKey<Level> dim, double x, double z, boolean place) {
        foundPlace = place;
        foundDim = dim;
        foundX = x;
        foundZ = z;
        foundAt = time + flyDuration;
    }

    /**
     * Smooth flight to a point, ending at {@code endScale}: eased in and out, and when the point is more than a
     * screen away, zoomed out on the way (the further, the more) so the map does not just smear past.
     */
    private void startFlight(double x, double z, double endScale) {
        zoomAnchored = false;
        endScale = clampScale(endScale);
        // The point ends in the middle of the map left uncovered by the side panel.
        if (panel != null) x += panel.width() / 2.0 / endScale;
        double screens = Math.hypot(x - centerX, z - centerZ) * scale / Math.max(1, width);
        flyBump = Math.max(0, Math.min(3, Math.log(Math.max(1, screens))));
        flyDuration = 0.45f + 0.15f * (float) flyBump;
        flyFromX = centerX;
        flyFromZ = centerZ;
        flyFromScale = scale;
        flyT = 0;
        flying = true;
        targetX = x;
        targetZ = z;
        targetScale = endScale;
        thumbAtTarget = false;
    }

    public void viewDimension(ResourceKey<Level> dim) {
        if (dim == viewedDim) return;
        viewedDim = dim;
        clearSelection();
        viewedBand = null;
        if (viewingPlayerDim()) {
            viewedBand = MapDataManager.get().undergroundBand();
            LocalPlayer p = Minecraft.getInstance().player;
            jumpTo(p.getX(), p.getZ());
        } else {
            Marker first = visibleMarkers(true).stream().findFirst().orElse(null);
            jumpTo(first != null ? first.centerX() : 0, first != null ? first.centerZ() : 0);
        }
    }

    private void flyTo(double x, double z) {
        zoomAnchored = false;
        flying = false;
        targetX = x;
        targetZ = z;
    }

    private void jumpTo(double x, double z) {
        zoomAnchored = false;
        flying = false;
        centerX = targetX = x;
        centerZ = targetZ = z;
    }

    private void recenterOnPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        if (!viewingPlayerDim()) viewDimension(p.level().dimension());
        flyTo(p.getX(), p.getZ());
    }

    // ================================================================== lifecycle

    @Override
    protected void init() {
        if (panel != null) panel.layout(width, height);
        lastFrameNs = System.nanoTime();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        Cursors.reset();
        Navigation.setPathWanted(Navigation.PATH_MAP, false);
        MapDataManager.get().releaseViewStores();
        chrome.destroy();
        picture.destroy();
        EdgeFade.dispose(); // the pin list's and pick list's dissolving edges
    }

    @Override
    public void tick() {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) onClose();
    }

    // ================================================================== animation

    private void animate(float dt) {
        if (flying) {
            flyT += dt;
            float t = Math.min(1f, flyT / flyDuration);
            double e = t * t * (3 - 2 * t);
            centerX = flyFromX + (targetX - flyFromX) * e;
            centerZ = flyFromZ + (targetZ - flyFromZ) * e;
            double ls = Math.log(flyFromScale), lt = Math.log(targetScale);
            scale = Math.exp(ls + (lt - ls) * e - flyBump * Math.sin(Math.PI * e));
            if (t >= 1f) {
                flying = false;
                centerX = targetX;
                centerZ = targetZ;
                scale = targetScale;
            }
            return;
        }
        float k = (float) (1 - Math.exp(-dt * 14));
        double ls = Math.log(scale), lt = Math.log(targetScale);
        if (Math.abs(lt - ls) < 1e-4) scale = targetScale;
        else scale = Math.exp(ls + (lt - ls) * k);
        if (zoomAnchored) {
            centerX = anchorWX - (anchorSX - width / 2.0) / scale;
            centerZ = anchorWZ - (anchorSY - height / 2.0) / scale;
            targetX = centerX;
            targetZ = centerZ;
            if (scale == targetScale) zoomAnchored = false;
        } else {
            centerX += (targetX - centerX) * k;
            centerZ += (targetZ - centerZ) * k;
            if (Math.abs(targetX - centerX) < 1e-3) centerX = targetX;
            if (Math.abs(targetZ - centerZ) < 1e-3) centerZ = targetZ;
        }
    }

    private void zoomAt(double mx, double my, double factor) {
        anchorSX = mx;
        anchorSY = my;
        anchorWX = view.worldX(mx, my);
        anchorWZ = view.worldZ(mx, my);
        flying = false;
        // One step in the direction of the factor (factor > 1 zooms in, < 1 zooms out).
        targetScale = MapView.stepZoom(targetScale, factor > 1 ? 1 : factor < 1 ? -1 : 0);
        thumbAtTarget = false;
        zoomAnchored = true;
    }

    // ================================================================== rendering

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // The map covers the whole screen; no vanilla blur/dirt background.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrameNs) / 1e9f);
        lastFrameNs = now;
        time += dt;
        Cursors.begin();
        animate(dt);
        view.set(centerX, centerZ, scale, 0, width / 2f, height / 2f);

        TileStore store = store();
        Navigation.setPathWanted(Navigation.PATH_MAP, pathShown());
        // 1-2. Fog-of-war backdrop with gentle parallax (follows panning, stays still while zooming) and the map
        // regions lit by the time of day, cached together while the view and the map data stay the same
        picture.draw(g, store, view, centerX, centerZ, scale, width, height, pt);
        // 3. Grid
        if (MapConfig.get(MapConfig.MAP_SHOW_GRID)) drawGrid(g);
        // 4. Labels
        if (MapConfig.get(MapConfig.MAP_SHOW_LABELS)) {
            MapLayerRenderer.drawBiomeLabels(g, store, view, 0, 0, width, height, panel == null ? uiRects(store, mx, my) : List.of());
        }
        // 5. Path to the navigation target
        drawRoute(g);
        // 6. Markers
        hovered = null;
        drawMarkers(g, mx, my);
        if (panel == null) drawFound(g);
        // 7. Off-screen indicator of the tracked pin, then the player
        drawTargetIndicator(g, mx, my);
        drawPlayer(g, mx, my, pt);
        // Outlined map text writes depth (slightly in front of z = 0), which would cut holes into UI text drawn
        // later at the same place. Everything below is UI on top of the map, so start it with a clean depth buffer.
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        // 8. Panel (dims the map behind it, like the references; the panel itself is frosted glass)
        if (panel != null) {
            // Re-blur only when the map behind the panel moved, and a few times a second for its animations.
            boolean moved = centerX != blurX || centerZ != blurZ || scale != blurScale;
            Backdrop.capture(g, pt, moved || time - blurTime > 0.25f, this);
            if (moved || time - blurTime > 0.25f) {
                blurX = centerX;
                blurZ = centerZ;
                blurScale = scale;
                blurTime = time;
            }
            Draw.rect(g, 0, 0, width, height, Theme.SCREEN_DIM);
            if (selected != null) drawSelectedAgain(g);
            if (selX != null) drawSelectedPoint(g);
            drawFound(g);
            panel.render(g, mx, my, dt, width);
            Backdrop.release();
        }

        // 9. Chrome (the references hide the top bar while a side panel is open)
        drawChrome(g, mx, my, store);
        if (pick != null) pick.render(g, mx, my, time);
        else if (menu != null) menu.render(g, mx, my, time);
        else if (panel == null && !dragging && !drawLayerTooltip(g, mx, my)) {
            if (hovered != null) drawTooltip(g, hovered, mx, my);
            // The sea icon where a crossing of the path begins (user requests 25 and 26.10.2026).
            else if (overSeaIcon(mx, my)) Ui.tooltip(g, Component.translatable("teyvatcartographia.nav.sea").getString(), null, mx, my, width, height);
        }
        pointer(mx, my);
    }

    /**
     * The mouse pointer of this frame (the panels and the pick list asked for theirs while drawing): moving the map,
     * the zoom slider, the buttons of the map, a pin; the arrow with an hourglass while the search reads the biomes.
     */
    private void pointer(int mx, int my) {
        int hover = chromeHover(mx, my, panel != null, viewingPlayerDim());
        float sx = leftColumnX();
        if (sliderDrag || (hover & 4) != 0 || Draw.inRect(mx, my, sx - 9, sliderTop() - 4, 18, sliderBottom() - sliderTop() + 8)) {
            Cursors.want(Cursors.Shape.RESIZE_V);
        } else if (hover != 0) {
            Cursors.want(Cursors.Shape.HAND);
        }
        if (panel == null && pick == null) {
            if (hovered != null || layerArrowAt(mx, my) != 0
                    || !Float.isNaN(indicatorX) && Draw.inCircle(mx, my, indicatorX, indicatorY, INDICATOR_R + 2)
                    || !Float.isNaN(targetIndicatorX) && Draw.inCircle(mx, my, targetIndicatorX, targetIndicatorY, INDICATOR_R + 2)) {
                Cursors.want(Cursors.Shape.HAND);
            }
        }
        if (panel instanceof MarkerListPanel && search.searchingBiomes()) Cursors.want(Cursors.Shape.LOADING);
        if (dragging && dragMoved) Cursors.force(Cursors.Shape.MOVE);
        if (sliderDrag) Cursors.force(Cursors.Shape.RESIZE_V);
        Cursors.apply();
    }

    // Grid lines, collected and drawn in one call.
    private float[] gridRects = new float[256];
    private int[] gridColors = new int[64];

    private void drawGrid(GuiGraphics g) {
        // Region lines from 1/8 like the pins and the biome names, chunk lines from 2 (closer, they fill the map).
        if (scale < MapView.DETAIL_FROM - 1e-6) return;
        int step = scale >= 2 ? 16 : 256;
        double wx0 = view.worldX(0, 0), wz0 = view.worldZ(0, 0), wx1 = view.worldX(width, height), wz1 = view.worldZ(width, height);
        int n = 0;
        for (long x = (long) Math.floor(wx0 / step) * step; x <= wx1; x += step) {
            float sx = view.screenX(x, 0);
            n = gridLine(n, sx, 0, sx + 0.5f, height, x % 256 == 0 ? Theme.GRID_LINE_MAJOR : Theme.GRID_LINE_MINOR);
        }
        for (long z = (long) Math.floor(wz0 / step) * step; z <= wz1; z += step) {
            float sy = view.screenY(0, z);
            n = gridLine(n, 0, sy, width, sy + 0.5f, z % 256 == 0 ? Theme.GRID_LINE_MAJOR : Theme.GRID_LINE_MINOR);
        }
        Draw.rects(g, gridRects, gridColors, n);
    }

    private int gridLine(int n, float x0, float y0, float x1, float y1, int color) {
        if (n == gridColors.length) {
            gridColors = java.util.Arrays.copyOf(gridColors, n * 2);
            gridRects = java.util.Arrays.copyOf(gridRects, n * 8);
        }
        gridRects[n * 4] = x0;
        gridRects[n * 4 + 1] = y0;
        gridRects[n * 4 + 2] = x1;
        gridRects[n * 4 + 3] = y1;
        gridColors[n] = color;
        return n + 1;
    }

    // Screen points of the path to the target and which of them lie in the fog and on deep water, reused every frame.
    private float[] routeXY = new float[1024];
    private boolean[] routeFog = new boolean[512], routeWater = new boolean[512];

    /** Whether the settings of the tracked pin's kind show its path on the world map. */
    private static boolean pathShown() {
        MapConfig.PinOptions o = MarkerVisibility.targetOptions();
        return o != null && o.pathOnMap();
    }

    /**
     * The path to the tracked target ({@link Route}), if its kind shows it on the map, as a dash-dot line: golden, grey
     * through the fog, in the colour of the sea over deep water (user decision 26.10.2026), with the sea icon where each
     * crossing begins.
     */
    private void drawRoute(GuiGraphics g) {
        seaIcons = 0;
        Navigation.Target t = Navigation.target();
        if (!pathShown() || t == null || t.dimension() != viewedDim || !viewingPlayerDim()) return;
        int n = Route.collect();
        LocalPlayer p = Minecraft.getInstance().player;
        if (n < 2 && Route.oldCount() < 2 || p == null) return;
        // The walked part is gone: the line starts at its point nearest the player.
        net.minecraft.world.phys.Vec3 me = p.getPosition(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));
        int first = Route.cut(me.x, me.z);
        int color = Theme.worldMarkerPulse();
        // The line shown before the search, wiped from its start meanwhile.
        int on = Route.oldCount();
        if (on >= 2) {
            float from = Route.oldFrom();
            lineN = 0;
            for (int i = 1; i < on; i++) {
                float a0 = Route.oldAlong(i - 1), a1 = Route.oldAlong(i);
                if (a1 <= from) continue;
                if (lineN == 0) {
                    float f = a1 > a0 ? Math.max(0, (from - a0) / (a1 - a0)) : 0;
                    linePoint(Route.oldX(i - 1) + (Route.oldX(i) - Route.oldX(i - 1)) * f, Route.oldZ(i - 1) + (Route.oldZ(i) - Route.oldZ(i - 1)) * f,
                            Route.oldFog(i - 1), Route.oldWater(i - 1));
                }
                linePoint(Route.oldX(i), Route.oldZ(i), Route.oldFog(i), Route.oldWater(i));
            }
            drawLine(g, color, (float) (from * scale));
        }
        // After a new search the line is drawn in from the player up to shownTo (user request 26.10.2026).
        float to = Route.shownTo(), pa = 0, px = 0, pz = 0;
        lineN = 0;
        for (int i = first; i < n; i++) {
            float wx = i == first ? Route.cutX() : Route.x(i), wz = i == first ? Route.cutZ() : Route.z(i);
            float a = i == first ? Route.cutAlong() : Route.along(i);
            if (a > to) {
                if (lineN > 0 && a > pa) {
                    linePoint(px + (wx - px) * (to - pa) / (a - pa), pz + (wz - pz) * (to - pa) / (a - pa), Route.fog(i), Route.water(i));
                }
                break;
            }
            linePoint(wx, wz, Route.fog(i), Route.water(i));
            pa = a;
            px = wx;
            pz = wz;
        }
        drawLine(g, color, (float) (Route.cutAlong() * scale));
        // The sea icon where each crossing begins, once the line has come so far.
        for (int i = first; i < n; i++) {
            if (!Route.crossingAt(i) || Route.along(i) > to) continue;
            float sx = view.screenX(Route.x(i), Route.z(i)), sy = view.screenY(Route.x(i), Route.z(i));
            Draw.circle(g, sx, sy, SEA_BADGE, Theme.ROUTE_SEA_BADGE);
            Draw.icon(g, Icon.SEA, sx, sy, SEA_BADGE * 1.6f, Theme.ROUTE_SEA);
            if (seaIcons < SEA_ICONS.length / 2) {
                SEA_ICONS[2 * seaIcons] = sx;
                SEA_ICONS[2 * seaIcons++ + 1] = sy;
            }
        }
    }

    /** The line collected by {@link #linePoint}, with its shadow, the pattern starting {@code phase} GUI units in. */
    private void drawLine(GuiGraphics g, int color, float phase) {
        Draw.route(g, routeXY, routeFog, routeWater, lineN, 2.4f, Theme.ROUTE_SHADOW, Theme.ROUTE_SHADOW, Theme.ROUTE_SHADOW, phase);
        Draw.route(g, routeXY, routeFog, routeWater, lineN, 1.4f, color, Theme.ROUTE_FOG, Theme.ROUTE_SEA, phase);
    }

    /** Points of the line being drawn: {@code lineN} screen points in routeXY, which lie in the fog and on deep water. */
    private int lineN;

    /** Adds the world point (x, z) to the line being drawn. */
    private void linePoint(float x, float z, boolean fog, boolean water) {
        if (lineN == routeFog.length) {
            routeXY = java.util.Arrays.copyOf(routeXY, 4 * lineN + 4);
            routeFog = java.util.Arrays.copyOf(routeFog, 2 * lineN + 2);
            routeWater = java.util.Arrays.copyOf(routeWater, 2 * lineN + 2);
        }
        routeXY[2 * lineN] = view.screenX(x, z);
        routeXY[2 * lineN + 1] = view.screenY(x, z);
        routeFog[lineN] = fog;
        routeWater[lineN++] = water;
    }

    /** Radius of the badge under the sea icon on the world map (GUI units). */
    private static final float SEA_BADGE = 7;
    /** The sea icons drawn this frame (x, y), for their tooltip. */
    private static final float[] SEA_ICONS = new float[32];
    private int seaIcons;

    /** Whether (mx, my) is on a sea icon of the path drawn this frame. */
    private boolean overSeaIcon(int mx, int my) {
        for (int i = 0; i < seaIcons; i++) {
            if (Math.hypot(mx - SEA_ICONS[2 * i], my - SEA_ICONS[2 * i + 1]) <= SEA_BADGE) return true;
        }
        return false;
    }
    // ------------------------------------------------------------------ layout (all positions on the grid)

    private static final int M = Grid.MARGIN, R = Grid.BUTTON / 2;

    /**
     * Diameter of the three corner buttons (close, settings, dimensions/compass): as tall as the two text lines
     * beside the compass. They share size, light style and icon size.
     */
    private static final int COMPASS_D = 28, BIG_R = COMPASS_D / 2;
    /** Icon size in the corner buttons; the compass glyph is drawn larger because its star is thin. */
    private static final int BIG_ICON = Grid.ICON_L, COMPASS_ICON = 22;
    /** Top of the pills in the top bar: vertically centred on the close button. */
    private static final int BAR_Y = M + (COMPASS_D - Grid.PILL_H) / 2;

    /** Centre X of the zoom slider (same column as the settings button). */
    private float leftColumnX() {
        return M + BIG_R;
    }

    private float closeX() {
        return width - M - BIG_R;
    }

    private float closeY() {
        return M + BIG_R;
    }

    /** Bottom-left row, left to right: settings (large), pin list, locate player; centred on one line. */
    private float rowButtonX(int index) {
        return index == 0 ? M + BIG_R : M + COMPASS_D + Grid.GAP + R + (index - 1) * (Grid.BUTTON + Grid.GAP);
    }

    private float rowButtonY() {
        return height - M - BIG_R;
    }

    private float compassX() {
        return width - M - COMPASS_D / 2f;
    }

    private float compassY() {
        return height - M - COMPASS_D / 2f;
    }

    /**
     * The slider is centred exactly on the screen's vertical middle. Its half-length is 56 units, shortened
     * symmetrically on small screens so it never reaches the top bar or the bottom button row.
     */
    private float sliderHalfSpan() {
        float mid = sliderMid();
        float roomTop = mid - (M + COMPASS_D + Grid.PAD + 9);
        float roomBottom = (height - M - COMPASS_D - Grid.PAD - 9) - mid;
        return Math.max(20, Math.min(56, Math.min(roomTop, roomBottom)));
    }

    private float sliderMid() {
        return Draw.snap(height / 2f);
    }

    private float sliderPlusY() {
        return sliderMid() - sliderHalfSpan();
    }

    private float sliderMinusY() {
        return sliderMid() + sliderHalfSpan();
    }

    private float sliderTop() {
        return sliderPlusY() + 12;
    }

    private float sliderBottom() {
        return sliderMinusY() - 12;
    }

    private float markerSize() {
        return scale >= 0.5 ? MarkerRenderer.SIZE : 16;
    }

    // ------------------------------------------------------------------ map content

    /** The layer shown while the pins are drawn (pins on other layers get an arrow). */
    private Integer shownBand;

    private void drawMarkers(GuiGraphics g, int mx, int my) {
        float size = markerSize();
        shownBand = viewedBand();
        boolean overUi = panel != null && panel.contains(mx, my) || pick != null && pick.contains(mx, my) || menu != null && menu.contains(mx, my);
        // No list building per frame: custom pins straight from the store, then the automatic points. The pin whose
        // panel is open stays visible whatever the filters.
        if (viewedDim != null) {
            for (int i = 0, n = MarkerStore.count(); i < n; i++) {
                Marker m = MarkerStore.get(i);
                if (m.dimension == viewedDim && (MarkerVisibility.shown(m, scale, false) || isSelected(m))) drawMarker(g, m, size, mx, my, overUi);
            }
            List<Marker> auto = AutoMarkers.forDimension(viewedDim);
            for (int i = 0; i < auto.size(); i++) {
                Marker m = auto.get(i);
                if (MarkerVisibility.shown(m, scale, false) || isSelected(m)) drawMarker(g, m, size, mx, my, overUi);
            }
        }
        if (selX != null && panel == null) drawSelectedPoint(g);
    }

    /** Rings from a search result for {@link #FOUND_PULSES} seconds after the flight; a biome place also gets a point. */
    private void drawFound(GuiGraphics g) {
        float since = time - foundAt;
        if (foundDim != viewedDim || since < 0 || since >= FOUND_PULSES) return;
        float sx = view.screenX(foundX, foundZ), sy = view.screenY(foundX, foundZ);
        MarkerRenderer.pulse(g, sx, sy, markerSize(), since % MarkerRenderer.PULSE_LIFE, Theme.MARKER_PULSE, 1f);
        if (foundPlace) {
            float a = Math.min(1f, (FOUND_PULSES - since) * 2f);
            Draw.diamond(g, sx, sy, 5, Theme.withAlpha(Theme.PING_CORE, a));
            Draw.diamond(g, sx, sy, 3.5f, Theme.withAlpha(Theme.PLAYER_CYAN, a));
        }
    }

    private boolean isSelected(Marker m) {
        return selected != null && selected.id.equals(m.id);
    }

    private void drawMarker(GuiGraphics g, Marker m, float size, int mx, int my, boolean overUi) {
        float sx = view.screenX(m.centerX(), m.centerZ()), sy = view.screenY(m.centerX(), m.centerZ());
        if (sx < -size || sy < -size || sx > width + size || sy > height + size) return;
        boolean hover = !overUi && !dragging && Draw.inCircle(mx, my, sx, sy, size * 0.6f);
        if (hover) hovered = m;
        boolean sel = selected != null && selected.id.equals(m.id);
        boolean tracked = Navigation.isTracking(m);
        if (tracked) MarkerRenderer.pulse(g, sx, sy, size, pulsePhase(), Theme.MARKER_PULSE, 1f);
        MarkerRenderer.draw(g, m.type, sx, sy, size, sel, hover, 1f, MarkerVisibility.layerRelation(m, shownBand));
    }

    /** Seconds into the current pulse period of a tracked pin (a pulse starts every 2.5 s). */
    private float pulsePhase() {
        float p = (float) (time / MarkerRenderer.PULSE_PERIOD);
        return (p - (float) Math.floor(p)) * MarkerRenderer.PULSE_PERIOD;
    }

    private void drawSelectedAgain(GuiGraphics g) {
        float sx = view.screenX(selected.centerX(), selected.centerZ()), sy = view.screenY(selected.centerX(), selected.centerZ());
        MarkerRenderer.draw(g, selected.type, sx, sy, markerSize(), true, false, 1f, MarkerVisibility.layerRelation(selected, viewedBand()));
    }

    private void drawSelectedPoint(GuiGraphics g) {
        float sx = view.screenX(selX, selZ), sy = view.screenY(selX, selZ);
        float pulse = 1f + (float) Math.sin(time * 5) * 0.08f;
        Draw.ring(g, sx, sy, 0, 14 * pulse, Theme.PING_GLOW, Theme.withAlpha(Theme.PING_GLOW, 0f));
        Draw.circleOutline(g, sx, sy, 10 * pulse, 1.5f, Theme.PING_RING);
        Draw.diamond(g, sx, sy, 5, Theme.PING_CORE);
        Draw.diamond(g, sx, sy, 3.5f, Theme.PLAYER_CYAN);
    }

    private float indicatorX = Float.NaN, indicatorY;
    private float targetIndicatorX = Float.NaN, targetIndicatorY;
    private static final float INDICATOR_R = 12;
    /**
     * Distance of an off-screen indicator's centre from the screen edges (like Genshin Impact): just inside the
     * corner buttons and the top bar, so the pointer clears the zoom slider and the bar.
     */
    private static final float INDICATOR_EDGE = M + COMPASS_D + Grid.GAP + INDICATOR_R;
    /** A point counts as on screen while its symbol is still (mostly) visible. */
    private static final float ON_SCREEN_MARGIN = 10;
    private final float[] edgePoint = new float[2];

    /** Whether the player / a pin at (sx, sy) is on screen (left of an open panel); otherwise it gets an indicator. */
    private boolean inArea(float sx, float sy) {
        float m = ON_SCREEN_MARGIN;
        return sx >= m && sy >= m && sx <= (panel != null ? panel.left() : width) - m && sy <= height - m;
    }

    /** Where the line from the screen centre to (sx, sy) leaves the indicator area; result in {@link #edgePoint}. */
    private void clampToEdge(float sx, float sy) {
        float inset = INDICATOR_EDGE;
        float left = inset, top = inset;
        float right = (panel != null ? panel.left() : width) - inset, bottom = height - inset;
        float cx = width / 2f, cy = height / 2f;
        float dx = sx - cx, dy = sy - cy;
        float tx = dx == 0 ? Float.MAX_VALUE : ((dx > 0 ? right : left) - cx) / dx;
        float ty = dy == 0 ? Float.MAX_VALUE : ((dy > 0 ? bottom : top) - cy) / dy;
        float t = Math.min(1f, Math.min(Math.abs(tx), Math.abs(ty)));
        edgePoint[0] = cx + dx * t;
        edgePoint[1] = cy + dy * t;
    }

    /**
     * Off-screen indicator at (ix, iy): disc with {@code icon} (rotated by {@code angle}), its frame, glow and the pointer
     * towards the real position (sx, sy) in {@code accent} (the player's green, the tracked pin's gold)
     * (reference: landscape examples).
     */
    private void drawIndicator(GuiGraphics g, float ix, float iy, float sx, float sy, Icon icon, int tint, int accent, float angle, int mx, int my) {
        boolean hover = Draw.inCircle(mx, my, ix, iy, INDICATOR_R + 2);
        int glow = Theme.withAlpha(accent, 0.25f);
        Draw.ring(g, ix, iy, INDICATOR_R - 1, INDICATOR_R + 6, glow, Theme.withAlpha(glow, 0f));
        if (Theme.Style.squareUi) {
            Draw.rect(g, ix - INDICATOR_R, iy - INDICATOR_R, ix + INDICATOR_R, iy + INDICATOR_R, Theme.INDICATOR);
            Draw.frame(g, ix - INDICATOR_R, iy - INDICATOR_R, 2 * INDICATOR_R, 2 * INDICATOR_R, hover ? 2 : 1, accent);
        } else {
            Draw.circle(g, ix, iy, INDICATOR_R, Theme.INDICATOR);
            Draw.circleOutline(g, ix, iy, INDICATOR_R - 0.75f, hover ? 2f : 1.5f, accent);
        }
        Draw.icon(g, icon, ix, iy, 16, tint, angle);
        float dx = sx - ix, dy = sy - iy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return;
        float ux = dx / len, uy = dy / len;
        float ax = ix + ux * (INDICATOR_R + 4), ay = iy + uy * (INDICATOR_R + 4);
        Draw.triangle(g, ax + ux * 5, ay + uy * 5, ax - uy * 4, ay + ux * 4, ax + uy * 4, ay - ux * 4, accent);
    }

    private void drawPlayer(GuiGraphics g, int mx, int my, float pt) {
        indicatorX = Float.NaN;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !viewingPlayerDim()) return;
        Vec3 pos = p.getPosition(pt);
        float sx = view.screenX(pos.x, pos.z), sy = view.screenY(pos.x, pos.z);
        float heading = (float) view.headingOnScreen(p.getViewYRot(pt));
        if (inArea(sx, sy)) {
            if (MapConfig.get(MapConfig.PLAYER_CONE)) Draw.sector(g, sx, sy, 32, heading, 0.7f, Theme.VIEW_CONE, Theme.withAlpha(Theme.VIEW_CONE, 0f));
            MarkerRenderer.player(g, sx, sy, 20, heading, 1f);
            return;
        }
        clampToEdge(sx, sy);
        indicatorX = edgePoint[0];
        indicatorY = edgePoint[1];
        drawIndicator(g, indicatorX, indicatorY, sx, sy, Icon.PLAYER, Theme.INDICATOR_ICON, Theme.PLAYER_INDICATOR, heading, mx, my);
    }

    /** The tracked pin (or automatic point), or null for none / another dimension. */
    private Marker trackedMarker() {
        Navigation.Target t = Navigation.target();
        if (t == null || t.dimension() != viewedDim) return null;
        if (t.markerId() != null) return MarkerStore.find(t.markerId());
        List<Marker> auto = AutoMarkers.forDimension(viewedDim);
        for (int i = 0; i < auto.size(); i++) if (Navigation.isTracking(auto.get(i))) return auto.get(i);
        return null;
    }

    /** Off-screen indicator for the tracked pin, like the player's (Genshin shows both). Click: fly to the pin. */
    private void drawTargetIndicator(GuiGraphics g, int mx, int my) {
        targetIndicatorX = Float.NaN;
        Marker m = trackedMarker();
        if (m == null) return;
        float sx = view.screenX(m.centerX(), m.centerZ()), sy = view.screenY(m.centerX(), m.centerZ());
        if (inArea(sx, sy)) return;
        clampToEdge(sx, sy);
        targetIndicatorX = edgePoint[0];
        targetIndicatorY = edgePoint[1];
        Icon icon = m.type.icon;
        drawIndicator(g, targetIndicatorX, targetIndicatorY, sx, sy, icon, Theme.INDICATOR_ICON, Theme.worldMarkerPulse(), 0f, mx, my);
    }

    // ------------------------------------------------------------------ chrome

    // Texts shared by drawing and by the UI exclusion zones for map labels.

    // Texts are rebuilt only when the numbers they show change.
    private long cursorBX = Long.MIN_VALUE, cursorBZ;
    private Component cursorLine;
    private int playerBX = Integer.MIN_VALUE, playerBY, playerBZ;
    private Component playerLine;
    private TileStore exploredStore;
    private int exploredVersion = -1;
    private Component exploredLine;

    private Component cursorText(int mx, int my) {
        if (panel != null && panel.contains(mx, my)) return null;
        if (mx < 0 || my < 0 || mx >= width || my >= height) return null; // the pointer is off the window
        long x = (long) Math.floor(view.worldX(mx, my)), z = (long) Math.floor(view.worldZ(mx, my));
        if (cursorLine == null || x != cursorBX || z != cursorBZ) {
            cursorBX = x;
            cursorBZ = z;
            cursorLine = Component.literal(x + ", " + z);
        }
        return cursorLine;
    }

    private Component playerText() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return null;
        if (playerLine == null || p.getBlockX() != playerBX || p.getBlockY() != playerBY || p.getBlockZ() != playerBZ) {
            playerBX = p.getBlockX();
            playerBY = p.getBlockY();
            playerBZ = p.getBlockZ();
            playerLine = Component.literal(playerBX + ", " + playerBY + ", " + playerBZ);
        }
        return playerLine;
    }

    /**
     * Area under the mouse cursor: its biome name, or the dimension name over unexplored ground or when the cursor
     * is over a panel.
     */
    private Component areaText(TileStore store, int mx, int my) {
        // A cave layer has no biomes of its own: the biome comes from the surface map.
        if (store != null && store.cave && viewedDim != null) store = MapDataManager.get().viewStore(viewedDim);
        boolean overMap = panel == null || !panel.contains(mx, my);
        ResourceKey<Biome> biome = store == null || !overMap ? null
                : store.biomeAt((int) Math.floor(view.worldX(mx, my)), (int) Math.floor(view.worldZ(mx, my)));
        return biome != null ? MapLayerRenderer.biomeName(biome) : dimensionName(viewedDim == null ? Level.OVERWORLD : viewedDim);
    }

    /** "Explored chunks: N", recounted only when the map data changed (counting walks every region). */
    private Component exploredText(TileStore store) {
        int version = store == null ? -1 : store.version();
        if (exploredLine == null || store != exploredStore || version != exploredVersion || Theme.generation() != exploredTheme) {
            exploredStore = store;
            exploredVersion = version;
            exploredTheme = Theme.generation();
            int explored = store == null ? 0 : store.exploredChunks();
            exploredLine = Component.translatable("teyvatcartographia.map.explored", Component.literal(String.valueOf(explored)).withColor(Theme.YELLOW & 0xFFFFFF));
        }
        return exploredLine;
    }

    private int exploredTheme = -1;
    /** Width reserved for the bottom-right texts in the label exclusion zones (fits typical biome names). */
    private static final int AREA_TEXT_RESERVE = 140;

    /**
     * Screen rectangles (x0, y0, x1, y1) occupied by the map UI, with a small margin. Map labels are not placed there,
     * so they never collide with the top bar, the zoom slider, the button row or the area text.
     */
    private List<float[]> uiRects(TileStore store, int mx, int my) {
        float pad = Grid.GAP;
        // Top bar: pills + close button
        float barLeft = closeX() - BIG_R - Grid.PAD;
        Component cursor = cursorText(mx, my), player = playerText();
        if (cursor != null) barLeft -= Ui.pillWidth(cursor, true) + Grid.GAP;
        if (player != null) barLeft -= Ui.pillWidth(player, true);
        setRect(0, barLeft - pad, 0, width, M + COMPASS_D + pad);
        // Zoom slider column
        setRect(1, 0, sliderPlusY() - 9 - pad, leftColumnX() + ZOOM_LABEL_GAP + ZOOM_LABEL_W + pad, sliderMinusY() + 9 + pad); // with the zoom label
        // Bottom-left button row
        setRect(2, 0, height - M - COMPASS_D - pad, rowButtonX(2) + R + pad, height);
        // Bottom-right: area name, explored line, compass button
        // The area name follows the cursor; a fixed minimum width keeps map labels from jumping as it changes.
        float textW = Math.max(AREA_TEXT_RESERVE, Math.max(DrawText.width(areaText(store, mx, my)), DrawText.width(exploredText(store))));
        setRect(3, compassX() - COMPASS_D / 2f - Grid.PAD - textW - pad, height - M - COMPASS_D - pad, width, height);
        // Layer buttons on the right (empty when not shown)
        if (layersShown()) {
            float top = layerItemY(layerShownFrom()) - LAYER_BIG_SLOT / 2f, bottom = layerItemY(layerShownTo() - 1) + LAYER_BIG_SLOT / 2f;
            setRect(4, layerX() - BIG_R - pad, top - 2 * LAYER_ARROW - pad, width, bottom + 2 * LAYER_ARROW + pad);
        } else {
            setRect(4, 0, 0, 0, 0);
        }
        return uiRectList;
    }

    // The UI rectangles, reused every frame.
    private final float[][] uiRectArr = new float[5][4];
    private final List<float[]> uiRectList = List.of(uiRectArr);

    private void setRect(int i, float x0, float y0, float x1, float y1) {
        float[] r = uiRectArr[i];
        r[0] = x0;
        r[1] = y0;
        r[2] = x1;
        r[3] = y1;
    }

    // ------------------------------------------------------------------ chrome cache

    // The chrome (zoom slider, top bar, button rows, bottom-right texts) is built from smooth many-vertex shapes that
    // rarely change, so it lives in a screen-sized layer redrawn only when something on it changes: a hovered button,
    // the scale, a text, the panel, the screen size or the theme. Every frame it is one textured quad.
    private final CachedLayer chrome = new CachedLayer();
    private boolean chromeValid, kPanelOpen, kLocate;
    private int kHover, kWidth, kHeight, kChromeTheme;
    private double kScale, kGuiScale;
    private Component kCursor, kPlayer, kArea, kExplored;
    private ResourceKey<Level> kDim;
    private Integer kBand;
    private int kBandCount, kLayerFirst;
    private boolean kLayersOpen;

    private void drawChrome(GuiGraphics g, int mx, int my, TileStore store) {
        boolean panelOpen = panel != null, locate = viewingPlayerDim();
        Component cursor = null, player = null, area = null, explored = null;
        if (!panelOpen) {
            cursor = cursorText(mx, my);
            player = playerText();
            area = areaText(store, mx, my);
            explored = exploredText(store);
        }
        int hover = chromeHover(mx, my, panelOpen, locate);
        Window w = Minecraft.getInstance().getWindow();
        double guiScale = w.getGuiScale();
        boolean dirty = !chrome.exists() || !chromeValid || hover != kHover || scale != kScale || panelOpen != kPanelOpen
                || locate != kLocate || width != kWidth || height != kHeight || guiScale != kGuiScale || Theme.generation() != kChromeTheme
                || cursor != kCursor || player != kPlayer || explored != kExplored || !java.util.Objects.equals(area, kArea)
                || viewedDim != kDim || !java.util.Objects.equals(viewedBand, kBand)
                || MapDataManager.get().caveBands().size() != kBandCount || layerShownFrom() != kLayerFirst || layersOpen != kLayersOpen;
        if (dirty) {
            chrome.bind(g, w.getWidth(), w.getHeight(), false);
            Draw.beginLayer();
            try {
                drawZoomSlider(g, mx, my);
                if (!panelOpen) {
                    drawTopBar(g, mx, my);
                    drawBottomLeft(g, mx, my);
                    drawBottomRight(g, mx, my, store);
                    drawLayers(g, mx, my);
                }
                g.flush();
            } finally {
                Draw.endLayer();
                CachedLayer.unbind();
            }
            chromeValid = true;
            kHover = hover;
            kScale = scale;
            kPanelOpen = panelOpen;
            kLocate = locate;
            kWidth = width;
            kHeight = height;
            kGuiScale = guiScale;
            kChromeTheme = Theme.generation();
            kCursor = cursor;
            kPlayer = player;
            kArea = area;
            kExplored = explored;
            kDim = viewedDim;
            kBand = viewedBand;
            kBandCount = MapDataManager.get().caveBands().size();
            kLayerFirst = layerShownFrom();
            kLayersOpen = layersOpen;
            // Composited only where the chrome is (the UI rectangles, which include a margin), not over the whole
            // screen: the rest of the layer is empty.
            List<float[]> rects = uiRects(store, mx, my);
            chromeRectCount = 0;
            for (int i = 0; i < rects.size(); i++) {
                if (panelOpen && i != 1) continue; // with a panel open only the zoom slider (rectangle 1) is shown
                System.arraycopy(rects.get(i), 0, chromeRects[chromeRectCount++], 0, 4);
            }
        }
        for (int i = 0; i < chromeRectCount; i++) {
            float[] r = chromeRects[i];
            float x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]), x1 = Math.min(width, r[2]), y1 = Math.min(height, r[3]);
            if (x1 <= x0 || y1 <= y0) continue;
            Draw.texturedQuadPremultiplied(g, chrome.textureId(), x0, y0, x1, y1,
                    x0 / width, 1f - y0 / height, x1 / width, 1f - y1 / height);
        }
    }

    // Where the cached chrome was drawn, composited every frame.
    private final float[][] chromeRects = new float[5][4];
    private int chromeRectCount;

    /** Which chrome controls are under the mouse, one bit each, with the same hit tests as their drawing. */
    private int chromeHover(int mx, int my, boolean panelOpen, boolean locate) {
        float x = leftColumnX();
        float ty = (float) (sliderBottom() - scaleToT(scale) * (sliderBottom() - sliderTop()));
        int h = 0;
        if (Draw.inCircle(mx, my, x, sliderPlusY(), 9)) h |= 1;
        if (Draw.inCircle(mx, my, x, sliderMinusY(), 9)) h |= 2;
        if (sliderDrag || Draw.inCircle(mx, my, x, ty, 9)) h |= 4;
        if (panelOpen) return h;
        if (Ui.buttonHit(closeX(), closeY(), COMPASS_D, mx, my)) h |= 8;
        if (Ui.buttonHit(rowButtonX(0), rowButtonY(), COMPASS_D, mx, my)) h |= 16;
        if (Ui.iconButtonHit(rowButtonX(1), rowButtonY(), mx, my)) h |= 32;
        if (locate && Ui.iconButtonHit(rowButtonX(2), rowButtonY(), mx, my)) h |= 64;
        if (Ui.buttonHit(compassX(), compassY(), COMPASS_D, mx, my)) h |= 128;
        if (layerButtonHit(mx, my)) h |= 256;
        int row = layerRowAt(mx, my);
        if (row >= 0) h |= 512 << Math.min(20, row - layerShownFrom());
        return h;
    }

    private void drawTopBar(GuiGraphics g, int mx, int my) {
        Ui.iconButton(g, closeX(), closeY(), COMPASS_D, BIG_ICON, Icon.CLOSE, true, mx, my);
        float right = closeX() - BIG_R - Grid.PAD;
        float y = BAR_Y;
        Component cursor = cursorText(mx, my), player = playerText();
        if (DrawText.probe != null && cursor != null && player != null) {
            // Both pills side by side, up to the screen's left margin (nothing else is up there).
            DrawText.room(player.getString() + cursor.getString(),
                    right - Grid.MARGIN - Ui.pillWidth(player, true) - Ui.pillWidth(cursor, true) - Grid.GAP
                            + DrawText.width(player) + DrawText.width(cursor));
        }
        if (cursor != null) right -= Ui.pill(g, right, y, cursor, Icon.LOCATE, Theme.GOLD) + Grid.GAP;
        if (player != null) Ui.pill(g, right, y, player, Icon.PLAYER, Theme.WHITE);
    }

    /** Slider position 0..1: steps are evenly spaced; in between (during the zoom animation) it interpolates. */
    private static double scaleToT(double s) {
        int n = MapView.ZOOM_STEPS.length - 1;
        if (s <= MapView.ZOOM_STEPS[0]) return 0;
        for (int i = 0; i < n; i++) {
            double a = MapView.ZOOM_STEPS[i], b = MapView.ZOOM_STEPS[i + 1];
            if (s <= b) return (i + (s - a) / (b - a)) / n;
        }
        return 1;
    }

    /** Nearest zoom step for a slider position. */
    private static double tToScale(double t) {
        return MapView.ZOOM_STEPS[(int) Math.round(Math.max(0, Math.min(1, t)) * (MapView.ZOOM_STEPS.length - 1))];
    }

    private void sliderEnd(GuiGraphics g, float x, float y, Icon icon, boolean hover) {
        if (Theme.Style.squareUi) {
            Draw.rect(g, x - 8, y - 8, x + 8, y + 8, Theme.ZOOM_BUTTON);
            Draw.frame(g, x - 8, y - 8, 16, 16, 1, hover ? Theme.ZOOM_BUTTON_OUTLINE_HOVER : Theme.ZOOM_BUTTON_OUTLINE);
        } else {
            Draw.diamond(g, x, y, 8, Theme.ZOOM_BUTTON);
            Draw.diamondOutline(g, x, y, 8, 1, hover ? Theme.ZOOM_BUTTON_OUTLINE_HOVER : Theme.ZOOM_BUTTON_OUTLINE);
        }
        Draw.icon(g, icon, x, y, 8, Theme.ZOOM_BUTTON_ICON);
    }

    /** Half the thumb along the zoom track: the square handle, or the diamond (the larger one while hovered). */
    private static float thumbHalf() {
        return Theme.Style.squareUi ? 4 : 8;
    }

    private void drawZoomSlider(GuiGraphics g, int mx, int my) {
        float x = leftColumnX(), top = sliderTop(), bottom = sliderBottom();
        sliderEnd(g, x, sliderPlusY(), Icon.PLUS, Draw.inCircle(mx, my, x, sliderPlusY(), 9));
        sliderEnd(g, x, sliderMinusY(), Icon.MINUS, Draw.inCircle(mx, my, x, sliderMinusY(), 9));
        // The thumb stays inside the track: its edge, not its centre, meets the track ends at 1/16 and 16.
        float ty = (float) (bottom - thumbHalf() - scaleToT(thumbAtTarget ? targetScale : scale) * (bottom - top - 2 * thumbHalf()));
        boolean thumbHover = sliderDrag || Draw.inCircle(mx, my, x, ty, 9);
        if (Theme.Style.squareUi) {
            // As the rounded slider with square shapes: a framed track, a thumb with its core.
            Draw.rect(g, x - 3, top, x + 3, bottom, Theme.ZOOM_TRACK);
            Draw.frame(g, x - 3, top, 6, bottom - top, 1, Theme.ZOOM_TRACK_OUTLINE);
            float tw = thumbHover ? 9 : 8;
            Draw.rect(g, x - tw, ty - 4, x + tw, ty + 4, Theme.ZOOM_THUMB);
            Draw.rect(g, x - 4, ty - 2, x + 4, ty + 2, Theme.ZOOM_THUMB_CORE);
        } else {
            Draw.capsule(g, x - 3, top, 6, bottom - top, Theme.ZOOM_TRACK);
            Draw.capsuleOutline(g, x - 3, top, 6, bottom - top, 1f, Theme.ZOOM_TRACK_OUTLINE);
            Draw.diamond(g, x, ty, thumbHover ? 9 : 8, Theme.ZOOM_THUMB);
            Draw.diamond(g, x, ty, 4, Theme.ZOOM_THUMB_CORE);
        }
        // The zoom beside the thumb, moving with it (user request 25.10.2026): "1/4", "1", "8", outlined like the
        // biome names so it reads over any land.
        String label = MapView.zoomLabel(thumbAtTarget ? targetScale : scale);
        if (!label.equals(zoomLabel)) {
            zoomLabel = label;
            zoomText = Component.literal(label);
        }
        DrawText.room(zoomText, ZOOM_LABEL_W);
        float lw = DrawText.width(zoomText);
        DrawText.textOutlined(g, zoomText, x + ZOOM_LABEL_GAP + lw / 2f, ty, Theme.LABEL_TEXT, Theme.LABEL_OUTLINE, false);
    }

    /** The zoom label beside the slider's thumb: its gap from the track's centre and the room it may take ("1/32"). */
    private static final float ZOOM_LABEL_GAP = 13, ZOOM_LABEL_W = 24;
    private String zoomLabel;
    private Component zoomText;

    private void drawBottomLeft(GuiGraphics g, int mx, int my) {
        float y = rowButtonY();
        Ui.iconButton(g, rowButtonX(0), y, COMPASS_D, BIG_ICON, Icon.GEAR, true, mx, my);
        Ui.iconButton(g, rowButtonX(1), y, Icon.LIST, false, mx, my);
        if (viewingPlayerDim()) Ui.iconButton(g, rowButtonX(2), y, Icon.LOCATE, false, mx, my);
    }

    private void drawBottomRight(GuiGraphics g, int mx, int my, TileStore store) {
        Ui.iconButton(g, compassX(), compassY(), COMPASS_D, COMPASS_ICON, Icon.COMPASS, true, mx, my);
        float right = compassX() - COMPASS_D / 2f - Grid.PAD;
        // Two lines (8 + 8 glyph height, 8 gap) span the compass button's height: cy - 12 .. cy + 12.
        float cy = compassY();
        // Room: up to the bottom-left buttons, on the same height.
        float room = right - (rowButtonX(2) + Grid.BUTTON / 2f + Grid.PAD);
        Component area = areaText(store, mx, my), explored = exploredText(store);
        DrawText.room(area, room);
        DrawText.room(explored, room);
        DrawText.textRight(g, area, right, cy - 12, Theme.WHITE, true);
        DrawText.textRight(g, explored, right, cy + 4, Theme.WHITE, true);
    }

    // ------------------------------------------------------------------ cave layers

    /**
     * The layer field at the right edge (reference: the layer buttons of the Genshin Impact map), always centred on
     * the screen's height, all with the layers glyph. A large button stands for the surface; clicked, it opens the cave layers as small buttons
     * on a thin line, in height order: the bands above the sea level over it, the others under it (the large button
     * moves with them). A second click on it goes back to the surface; a click on it while the surface is shown closes the field. At most
     * {@code LAYER_SMALL_MAX} small buttons show; with more, the wheel (or a click on the small arrow over or under the
     * field, shown where it can go on) scrolls it by one button. Names and heights are in the tooltips.
     */
    private static final int LAYER_STEP = 28, LAYER_BIG_SLOT = COMPASS_D + 2 * Grid.GAP, LAYER_SMALL_MAX = 5;
    /** Marks the surface (the large button) among the bands of {@link #layerItems}. */
    private static final int SURFACE_ITEM = Integer.MIN_VALUE;
    private boolean layersOpen;
    /** First item of the field shown (scrolled by the wheel). */
    private int layerFirst;
    /** The cave bands, and the field's items from top to bottom (bands with the surface between them). */
    private int[] layerBands = new int[0], layerItems = new int[]{SURFACE_ITEM};

    /** The items of the field, rebuilt when a band is added (drawing does not iterate the set). */
    private int[] items() {
        java.util.NavigableSet<Integer> set = MapDataManager.get().caveBands();
        if (set.size() != layerBands.length) {
            layerBands = new int[set.size()];
            int i = 0, sea = Minecraft.getInstance().level == null ? 63 : Minecraft.getInstance().level.getSeaLevel();
            for (int b : set) layerBands[i++] = b; // highest first
            List<Integer> order = CaveScanner.layerOrder(set, sea);
            layerItems = new int[order.size()];
            for (int k = 0; k < order.size(); k++) layerItems[k] = order.get(k) == null ? SURFACE_ITEM : order.get(k);
        }
        return layerItems;
    }

    /** The field is there in the player's dimension once it has cave layers, while no side panel is open. */
    private boolean layersShown() {
        return panel == null && viewingPlayerDim() && MapDataManager.get().cavesSupported() && !MapDataManager.get().caveBands().isEmpty();
    }

    private float layerX() {
        return width - M - BIG_R;
    }

    /** One past the last item shown from {@code first}: as far as {@code LAYER_SMALL_MAX} small buttons reach. */
    private int layerEnd(int first) {
        int[] it = items();
        int end = first, small = 0;
        while (end < it.length && (it[end] == SURFACE_ITEM || small < LAYER_SMALL_MAX)) {
            if (it[end] != SURFACE_ITEM) small++;
            end++;
        }
        return end;
    }

    /** The largest first item that still fills the field to the end of the list. */
    private int layerMaxFirst() {
        int n = items().length, first = 0;
        while (first < n - 1 && layerEnd(first) < n) first++;
        return first;
    }

    /** Items shown: only the large button while the field is closed. */
    private int layerShownFrom() {
        return layersOpen ? Math.max(0, Math.min(layerMaxFirst(), layerFirst)) : surfaceIndex();
    }

    private int layerShownTo() {
        return layersOpen ? layerEnd(layerShownFrom()) : surfaceIndex() + 1;
    }

    private int surfaceIndex() {
        int[] it = items();
        for (int i = 0; i < it.length; i++) if (it[i] == SURFACE_ITEM) return i;
        return 0;
    }

    private float slotHeight(int item) {
        return items()[item] == SURFACE_ITEM ? LAYER_BIG_SLOT : LAYER_STEP;
    }

    /** Centre Y of a shown item: the shown items are centred on the screen's height together. */
    private float layerItemY(int item) {
        int from = layerShownFrom(), to = layerShownTo();
        float total = 0, before = 0;
        for (int i = from; i < to; i++) {
            if (i < item) before += slotHeight(i);
            total += slotHeight(i);
        }
        return Draw.snap(height / 2f - total / 2f) + before + slotHeight(item) / 2f;
    }

    /** Opens the field with the shown layer in view (the scroll position puts it near the middle). */
    private void openLayers() {
        layersOpen = true;
        int[] it = items();
        int focus = surfaceIndex();
        for (int i = 0; viewedBand != null && i < it.length; i++) if (it[i] == viewedBand) focus = i;
        layerFirst = Math.max(0, Math.min(layerMaxFirst(), focus - LAYER_SMALL_MAX / 2));
    }

    private boolean layerButtonHit(double mx, double my) {
        if (!layersShown()) return false;
        int s = surfaceIndex();
        return s >= layerShownFrom() && s < layerShownTo() && Ui.buttonHit(layerX(), layerItemY(s), COMPASS_D, mx, my);
    }

    /** The small button (cave band) under the mouse, as an item index, or -1. */
    private int layerRowAt(double mx, double my) {
        if (!layersShown() || !layersOpen) return -1;
        int[] it = items();
        for (int i = layerShownFrom(), to = layerShownTo(); i < to; i++) {
            if (it[i] != SURFACE_ITEM && Ui.iconButtonHit(layerX(), layerItemY(i), mx, my)) return i;
        }
        return -1;
    }

    /** Whether the mouse is over the open field (the wheel scrolls it there). */
    private boolean overLayers(double mx, double my) {
        if (!layersShown() || !layersOpen) return false;
        float top = layerItemY(layerShownFrom()) - LAYER_STEP / 2f, bottom = layerItemY(layerShownTo() - 1) + LAYER_STEP / 2f;
        return mx >= layerX() - BIG_R - Grid.GAP && my >= top && my <= bottom;
    }

    private void drawLayers(GuiGraphics g, int mx, int my) {
        if (!layersShown()) return;
        int[] it = items();
        int from = layerShownFrom(), to = layerShownTo();
        float x = layerX();
        if (to - from > 1) Draw.rect(g, x - 0.75f, layerItemY(from), x + 0.75f, layerItemY(to - 1), Theme.PANEL_LINE);
        for (int i = from; i < to; i++) {
            float y = layerItemY(i);
            // The shown layer is a light button (like the large one on the Genshin Impact map), the others dark.
            if (it[i] == SURFACE_ITEM) Ui.iconButton(g, x, y, COMPASS_D, BIG_ICON, Icon.LAYERS, viewedBand == null, mx, my);
            else Ui.iconButton(g, x, y, Icon.LAYERS, viewedBand != null && it[i] == viewedBand, mx, my);
        }
        // Arrows where the field can be scrolled: over the first button shown, under the last.
        if (layersOpen && from > 0) MarkerRenderer.levelArrow(g, x, layerArrowY(true), LAYER_ARROW, true, Theme.WORLD_MARKER_LEVEL, 1f);
        if (layersOpen && to < it.length) MarkerRenderer.levelArrow(g, x, layerArrowY(false), LAYER_ARROW, false, Theme.WORLD_MARKER_LEVEL, 1f);
    }

    /** Width of the scroll arrows of the layer field. */
    private static final float LAYER_ARROW = 6;

    /** Centre Y of the scroll arrow over the first button shown ({@code up}) or under the last one. */
    private float layerArrowY(boolean up) {
        int item = up ? layerShownFrom() : layerShownTo() - 1;
        float half = items()[item] == SURFACE_ITEM ? BIG_R : R;
        return up ? layerItemY(item) - half - LAYER_ARROW : layerItemY(item) + half + LAYER_ARROW;
    }

    /** The scroll arrow under the mouse: -1 the upper one, 1 the lower one, 0 none (a click scrolls by one button). */
    private int layerArrowAt(double mx, double my) {
        if (!layersShown() || !layersOpen || Math.abs(mx - layerX()) > R) return 0;
        if (layerShownFrom() > 0 && Math.abs(my - layerArrowY(true)) <= LAYER_ARROW) return -1;
        if (layerShownTo() < items().length && Math.abs(my - layerArrowY(false)) <= LAYER_ARROW) return 1;
        return 0;
    }

    private void scrollLayers(int by) {
        layerFirst = Math.max(0, Math.min(layerMaxFirst(), layerShownFrom() + by));
    }

    /**
     * Tooltip of the layer field under the mouse, left of it: "Layers" on the closed large button, "Surface" on the
     * open one, the band's name on a small one. Returns whether one was drawn.
     */
    private boolean drawLayerTooltip(GuiGraphics g, int mx, int my) {
        float right = layerX() - BIG_R - Grid.GAP;
        if (layerButtonHit(mx, my)) {
            String key = layersOpen ? "teyvatcartographia.map.layer.surface" : "teyvatcartographia.map.layers";
            Ui.tooltipLeftOf(g, Component.translatable(key).getString(), right, layerItemY(surfaceIndex()));
            return true;
        }
        int row = layerRowAt(mx, my);
        if (row < 0) return false;
        Ui.tooltipLeftOf(g, cavesText(items()[row]).getString(), right, layerItemY(row));
        return true;
    }

    /** The cave band shown, or null for the surface (also in other dimensions); new pins belong to it. */
    public Integer viewedBand() {
        TileStore s = store();
        return s != null && s.cave ? viewedBand : null;
    }
    // Labels of the bands, built once each.
    private final java.util.Map<Integer, Component> cavesTexts = new java.util.HashMap<>();

    /** "Height 64 to 79" over the surface, "Height 63 to 48" under it: the tooltip of a cave layer's button. */
    private Component cavesText(int band) {
        return cavesTexts.computeIfAbsent(band, b -> {
            int[] h = CaveScanner.heights(b, Minecraft.getInstance().level == null ? 63 : Minecraft.getInstance().level.getSeaLevel());
            return Component.translatable("teyvatcartographia.map.caves", h[0], h[1]);
        });
    }

    private void drawTooltip(GuiGraphics g, Marker m, int mx, int my) {
        LocalPlayer p = Minecraft.getInstance().player;
        // Second line: distance from the player (when in the same dimension).
        String sub = null;
        if (p != null && viewingPlayerDim()) {
            sub = DistanceText.blocks(Math.round(Navigation.distance(p, m.centerX(), m.y, m.centerZ()))).getString();
        }
        Ui.tooltip(g, m.name, sub, mx, my, width, height);
    }

    // ================================================================== input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (menu != null) {
            // Any click closes the context menu; a left click on an enabled row runs it, a right click elsewhere opens
            // the menu there.
            boolean inside = menu.contains(mx, my);
            MapContextMenu.Item it = button == 0 ? menu.rowAt(mx, my) : null;
            closeMenu();
            if (it != null) it.action().run();
            if (button != 1 || inside) return true;
        }
        if (button == 1) {
            if (pick != null || panel != null && panel.contains(mx, my) || overChrome(mx, my)) return true;
            if (panel != null) closePanel();
            openMenuAt(mx, my);
            return true;
        }
        if (button != 0) return true; // otherwise the map screen reacts to the left and right buttons only
        if (pick != null) {
            // Any click closes the pick list; a click on a row selects that pin.
            Marker m = pick.rowAt(mx, my);
            pick = null;
            if (m != null) selectMarker(m);
            return true;
        }
        if (panel != null && panel.mouseClicked(mx, my, button)) return true;
        if (panel == null && Ui.buttonHit(closeX(), closeY(), COMPASS_D, mx, my)) {
            onClose();
            return true;
        }
        // Zoom slider
        float sx = leftColumnX();
        if (Draw.inCircle(mx, my, sx, sliderPlusY(), 9)) {
            zoomAt(width / 2.0, height / 2.0, 2);
            return true;
        }
        if (Draw.inCircle(mx, my, sx, sliderMinusY(), 9)) {
            zoomAt(width / 2.0, height / 2.0, 0.5); // one step out
            return true;
        }
        if (Draw.inRect(mx, my, sx - 9, sliderTop() - 4, 18, sliderBottom() - sliderTop() + 8)) {
            sliderDrag = true;
            setSliderFrom(my);
            return true;
        }
        if (panel == null) {
            if (Ui.buttonHit(rowButtonX(0), rowButtonY(), COMPASS_D, mx, my)) {
                openPanel(new SettingsPanel(this));
                return true;
            }
            if (Ui.iconButtonHit(rowButtonX(1), rowButtonY(), mx, my)) {
                openList();
                return true;
            }
            if (viewingPlayerDim() && Ui.iconButtonHit(rowButtonX(2), rowButtonY(), mx, my)) {
                recenterOnPlayer();
                return true;
            }
            if (Ui.buttonHit(compassX(), compassY(), COMPASS_D, mx, my)) {
                openPanel(new DimensionPanel(this));
                return true;
            }
            if (layerButtonHit(mx, my)) {
                // Closed: open the field. Open on a cave band: back to the surface. Open on the surface: close it.
                if (!layersOpen) openLayers();
                else if (viewedBand != null) viewedBand = null;
                else layersOpen = false;
                return true;
            }
            int row = layerRowAt(mx, my);
            if (row >= 0) {
                viewedBand = items()[row];
                return true;
            }
            int arrow = layerArrowAt(mx, my);
            if (arrow != 0) {
                scrollLayers(arrow);
                return true;
            }
        }
        if (!Float.isNaN(indicatorX) && Draw.inCircle(mx, my, indicatorX, indicatorY, INDICATOR_R + 2)) {
            recenterOnPlayer();
            return true;
        }
        if (!Float.isNaN(targetIndicatorX) && Draw.inCircle(mx, my, targetIndicatorX, targetIndicatorY, INDICATOR_R + 2)) {
            Marker m = trackedMarker();
            if (m != null) {
                closePanel();
                focusMarker(m);
            }
            return true;
        }
        if (panel != null) {
            // Clicking the dimmed map closes the panel; it never starts a drag. Dark panels (settings, pin list,
            // dimensions) only close. With a pin panel open, a click on another pin opens that pin, and a click on
            // empty ground only closes the panel (no new pin editor; back to the pin list if the pin came from it).
            boolean consume = panel.consumesOutsideClick();
            closePanel();
            if (!consume) openPinsAt(mx, my);
            return true;
        }
        dragging = true;
        dragMoved = false;
        dragStartX = mx;
        dragStartY = my;
        return true;
    }

    /** Opens the pin under the point, or the pick list when several overlap. Returns false on empty ground. */
    private boolean openPinsAt(double mx, double my) {
        List<Marker> hits = markersAt(mx, my);
        if (hits.size() == 1) selectMarker(hits.get(0));
        else if (hits.size() > 1) pick = new MarkerPickList(hits, width, height, INDICATOR_EDGE - INDICATOR_R); // like Genshin Impact
        return !hits.isEmpty();
    }

    /** Markers whose symbols are under the given screen point, nearest first (on clicks only: lists are fine here). */
    private List<Marker> markersAt(double mx, double my) {
        float r = markerSize() * 0.6f;
        List<Marker> hits = new ArrayList<>();
        for (Marker m : visibleMarkers(false)) {
            if (screenDistance(m, mx, my) <= r) hits.add(m);
        }
        hits.sort(java.util.Comparator.comparingDouble(m -> screenDistance(m, mx, my)));
        return hits;
    }

    private double screenDistance(Marker m, double mx, double my) {
        return Math.hypot(mx - view.screenX(m.centerX(), m.centerZ()), my - view.screenY(m.centerX(), m.centerZ()));
    }
    private void setSliderFrom(double my) {
        double t = (sliderBottom() - thumbHalf() - my) / (sliderBottom() - sliderTop() - 2 * thumbHalf());
        t = Math.max(0, Math.min(1, t));
        double s = tToScale(t);
        // Keep the screen centre fixed; the map eases to the new scale like the wheel and the buttons (the thumb follows
        // the cursor at once, drawn at the target).
        targetScale = clampScale(s);
        thumbAtTarget = true;
        zoomAnchored = false;
        flying = false;
        targetX = centerX;
        targetZ = centerZ;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (sliderDrag) {
            setSliderFrom(my);
            return true;
        }
        if (dragging && button == 0) {
            if (!dragMoved && Math.hypot(mx - dragStartX, my - dragStartY) > 3) dragMoved = true;
            if (dragMoved) {
                zoomAnchored = false;
                flying = false;
                centerX -= dx / scale;
                centerZ -= dy / scale;
                targetX = centerX;
                targetZ = centerZ;
                targetScale = scale;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (sliderDrag) {
            sliderDrag = false;
            return true;
        }
        if (dragging && button == 0) {
            dragging = false;
            if (!dragMoved) leftClick(mx, my);
            return true;
        }
        return false;
    }

    /** A click on the map: opens the pin under it. Empty ground does nothing (new pins come from the context menu). */
    private void leftClick(double mx, double my) {
        // Hit-test now: the hover state is suspended while the button is held (potential drag).
        openPinsAt(mx, my);
    }

    // ================================================================== context menu (right click)

    /**
     * The context menu at the point: on a pin (the nearest of those under it) "Edit", or "Open" for an automatic
     * point, and "Track" or "Stop tracking"; on empty ground "New pin" and, for operators (cheats in single player),
     * "Teleport" (unexplored ground too, see teleportTo). The spot is marked while it is open.
     */
    private void openMenuAt(double mx, double my) {
        if (viewedDim == null) return;
        List<MapContextMenu.Item> items = new ArrayList<>(2);
        List<Marker> hits = markersAt(mx, my);
        if (!hits.isEmpty()) {
            Marker m = hits.get(0);
            items.add(new MapContextMenu.Item(m.automatic ? Icon.VIEW : Icon.PENCIL,
                    Component.translatable(m.automatic ? "teyvatcartographia.map.menu.open" : "teyvatcartographia.map.menu.edit"), () -> selectMarker(m)));
            items.add(new MapContextMenu.Item(Icon.LOCATE,
                    Component.translatable(Navigation.isTracking(m) ? "teyvatcartographia.pin.untrack" : "teyvatcartographia.pin.track"), () -> {
                if (Navigation.isTracking(m)) Navigation.clear();
                else Navigation.track(m);
            }));
        } else {
            int bx = (int) Math.floor(view.worldX(mx, my)), bz = (int) Math.floor(view.worldZ(mx, my));
            TileStore s = store();
            int h = s == null ? Integer.MIN_VALUE : s.heightAt(bx, bz), y = h == Integer.MIN_VALUE ? h : h + 1;
            selected = null;
            selX = bx + 0.5;
            selZ = bz + 0.5;
            items.add(new MapContextMenu.Item(Icon.NEW_PIN, Component.translatable("teyvatcartographia.map.menu.new_pin"), () -> newPinAt(bx, y, bz)));
            if (net.tapacywka.teyvatcartographia.marker.MarkerType.isOperator()) {
                items.add(new MapContextMenu.Item(Icon.TARGET, Component.translatable("teyvatcartographia.map.menu.teleport"),
                        () -> teleportTo(viewedDim, bx, y, bz)));
            }
        }
        pick = null;
        menu = new MapContextMenu(items, mx, my, width, height);
    }

    private void closeMenu() {
        menu = null;
        if (panel == null) clearSelection(); // the spot marked for the menu
    }

    /**
     * The vanilla /tp, in the viewed dimension; the server checks the rights again. The map closes. Where the map does
     * not know the ground (unexplored, {@code y} is {@link Integer#MIN_VALUE}) the server finds it: in the Nether (under
     * its roof) and the End /spreadplayers (it refuses the void and lava, then the player stays); elsewhere the player
     * goes up to {@link #TELEPORT_HIGH} over the spot first (the server loads the chunk; "positioned over" needs it
     * loaded) and down onto its top block once the chunk has reached this client ({@link #tickTeleport}).
     */
    private static void teleportTo(ResourceKey<Level> dim, int bx, int y, int bz) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !net.tapacywka.teyvatcartographia.marker.MarkerType.isOperator()) return; // re-checked: /deop meanwhile
        String in = "execute in " + dim.location() + " ";
        if (y != Integer.MIN_VALUE) p.connection.sendCommand(in + "run tp @s " + bx + ".5 " + y + " " + bz + ".5");
        else if (dim == Level.NETHER) p.connection.sendCommand(in + "run spreadplayers " + bx + ".5 " + bz + ".5 0 8 under 120 false @s");
        else if (dim == Level.END) p.connection.sendCommand(in + "run spreadplayers " + bx + ".5 " + bz + ".5 0 8 false @s");
        else {
            p.connection.sendCommand(in + "run tp @s " + bx + ".5 " + TELEPORT_HIGH + " " + bz + ".5");
            landDim = dim;
            landX = bx;
            landZ = bz;
            landTicks = LAND_WAIT;
        }
        Minecraft.getInstance().setScreen(null);
    }

    /** Height of the first step of a teleport to unexplored ground, and how long the second step waits (ticks). */
    private static final int TELEPORT_HIGH = 320, LAND_WAIT = 200;
    private static ResourceKey<Level> landDim;
    private static int landX, landZ, landTicks;

    /** Client tick: the second step of a teleport to unexplored ground, onto the top block, once its chunk is here. */
    public static void tickTeleport(Minecraft mc) {
        if (landTicks <= 0) return;
        landTicks--;
        LocalPlayer p = mc.player;
        if (p == null || p.level().dimension() != landDim || !p.level().hasChunk(landX >> 4, landZ >> 4)
                || Math.abs(p.getX() - landX - 0.5) > 1 || Math.abs(p.getZ() - landZ - 0.5) > 1) return;
        landTicks = 0;
        p.connection.sendCommand("execute in " + landDim.location() + " positioned " + landX + ".5 0 " + landZ + ".5 positioned over motion_blocking run tp @s ~ ~ ~");
    }

    /** Over a button, the zoom slider, the layer field or an off-screen indicator (a right click there opens no menu). */
    private boolean overChrome(double mx, double my) {
        float sx = leftColumnX();
        if (Draw.inRect(mx, my, sx - 9, sliderPlusY() - 9, 18, sliderMinusY() - sliderPlusY() + 18)) return true;
        if (panel == null && (Ui.buttonHit(closeX(), closeY(), COMPASS_D, mx, my) || Ui.buttonHit(rowButtonX(0), rowButtonY(), COMPASS_D, mx, my)
                || Ui.iconButtonHit(rowButtonX(1), rowButtonY(), mx, my) || Ui.iconButtonHit(rowButtonX(2), rowButtonY(), mx, my)
                || Ui.buttonHit(compassX(), compassY(), COMPASS_D, mx, my) || overLayers(mx, my))) return true;
        return !Float.isNaN(indicatorX) && Draw.inCircle(mx, my, indicatorX, indicatorY, INDICATOR_R + 2)
                || !Float.isNaN(targetIndicatorX) && Draw.inCircle(mx, my, targetIndicatorX, targetIndicatorY, INDICATOR_R + 2);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (menu != null) closeMenu(); // the map moves under it
        if (pick != null) {
            if (pick.scroll(mx, my, scrollY)) return true;
            pick = null; // the map moves under it
        }
        if (panel != null && panel.mouseScrolled(mx, my, scrollY)) return true;
        if (scrollY != 0 && panel == null && overLayers(mx, my)) {
            scrollLayers(-(int) Math.signum(scrollY));
            return true;
        }
        if (scrollY != 0) {
            zoomAt(mx, my, scrollY > 0 ? 2 : 0.5); // one step per wheel notch
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (panel != null && panel.keyPressed(key, scan, mods)) return true;
        if (key == GLFW.GLFW_KEY_F && (mods & GLFW.GLFW_MOD_CONTROL) != 0 && (panel == null || !panel.capturesKeyboard())) {
            // Ctrl+F: the pin list with the cursor in its search field.
            MarkerListPanel list = panel instanceof MarkerListPanel l ? l : openList();
            list.focusSearch();
            return true;
        }
        boolean typing = panel != null && panel.capturesKeyboard();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (menu != null) closeMenu();
            else if (pick != null) pick = null;
            else if (panel != null) closePanel();
            else onClose();
            return true;
        }
        if (typing) return true;
        if (Keybinds.OPEN_MAP.matches(key, scan)) {
            onClose();
            return true;
        }
        pick = null; // keys move or zoom the map under it
        switch (key) {
            case GLFW.GLFW_KEY_SPACE -> recenterOnPlayer();
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoomAt(width / 2.0, height / 2.0, 1.5);
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> zoomAt(width / 2.0, height / 2.0, 1 / 1.5);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> flyTo(targetX - 80 / scale, targetZ);
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> flyTo(targetX + 80 / scale, targetZ);
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> flyTo(targetX, targetZ - 80 / scale);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> flyTo(targetX, targetZ + 80 / scale);
            default -> {
                return super.keyPressed(key, scan, mods);
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (panel != null && panel.charTyped(c, mods)) return true;
        return super.charTyped(c, mods);
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        if (panel != null) panel.layout(w, h);
    }
}
