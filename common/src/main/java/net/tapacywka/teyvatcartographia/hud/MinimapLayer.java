package net.tapacywka.teyvatcartographia.hud;

import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.gui.Ui;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.MapTile;
import net.tapacywka.teyvatcartographia.map.data.TileStore;
import net.tapacywka.teyvatcartographia.marker.AutoMarkers;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;
import net.tapacywka.teyvatcartographia.marker.MarkerVisibility;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.nav.Route;
import net.tapacywka.teyvatcartographia.render.DayLight;
import net.tapacywka.teyvatcartographia.render.CachedLayer;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.FogTexture;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.MapLayerRenderer;
import net.tapacywka.teyvatcartographia.render.MarkerRenderer;
import net.tapacywka.teyvatcartographia.render.Theme;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.TimeZone;

/**
 * The HUD minimap (reference: minimap.png): the map fading out towards a thin frame (a circle, or a square with
 * the square minimap shape), "N" inside the frame, player arrow with a view cone, pins inside the frame and the navigation target held
 * at the edge when outside. Uses the same {@link MapView} transform and {@link TileStore} as the world map.
 */
public final class MinimapLayer implements LayeredDraw.Layer {
    /** Info pills under/over the minimap: one text line with 4 units of padding = 16. */
    private static final int PILL_H = 16;
    private static final float ARROW = 16;
    /** Smallest radius a minimap shrunk to fit the screen gets (the setting's own minimum). */
    private static final int MIN_RADIUS = 24;
    /** The pills' height at the last drawing, for the largest radius the settings offer. */
    private int lastTextBlock;

    /** The largest minimap radius that fits a {@code guiW} x {@code guiH} screen with its pills (at most 110). */
    public static int maxRadius(int guiW, int guiH) {
        return MinimapFit.radius(110, guiW, guiH, instance == null ? 0 : instance.lastTextBlock, MIN_RADIUS);
    }

    private final MapView view = new MapView();

    /**
     * Whether the minimap is shown right now: enabled, HUD not hidden (F1), no menu open and no debug screen (F3).
     * The chat is not a menu.
     */
    private static boolean visible(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.options.hideGui || !MapConfig.get(MapConfig.MINIMAP_ENABLED)) return false;
        boolean menu = mc.screen != null && !(mc.screen instanceof ChatScreen);
        return !menu && !mc.getDebugOverlay().showDebugScreen();
    }
    /** North, east, south and west, clockwise. */
    private static final Component[] COMPASS = {Component.literal("N"), Component.literal("E"), Component.literal("S"), Component.literal("W")};
    /** Opacity of east, south and west against north. */
    private static final float SIDE_LETTER_ALPHA = 0.6f;
    /** The map turns with the camera (east, south and west are shown too); read every frame, in the layer key. */
    private boolean rotating, kRotating;

    /** The one minimap layer of the HUD, also drawn by the settings' preview. */
    private static MinimapLayer instance;

    public MinimapLayer() {
        instance = this;
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible(mc)) {
            shown = false;
            Navigation.setPathWanted(Navigation.PATH_MINIMAP, false);
            return;
        }
        if (prepare(g, delta, false)) drawPrepared(g);
    }

    /**
     * The settings' preview: brings the minimap up to date as the HUD would show it now (in the screen's GUI
     * coordinates), and returns false without a world or with the minimap switched off. Then {@link #drawPrepared}
     * draws it under any transform, and {@link #preparedBounds} tells where it is.
     */
    public static boolean preparePreview(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (instance == null || mc.player == null || mc.level == null || !MapConfig.get(MapConfig.MINIMAP_ENABLED)) return false;
        return instance.prepare(g, delta, true);
    }

    /** Draws the minimap prepared last (one textured quad, so the current pose may move and scale it). */
    public static void drawPrepared(GuiGraphics g) {
        MinimapLayer m = instance;
        Draw.texturedQuadPremultiplied(g, m.layer.textureId(), m.kBx0, m.kBy0, m.kBx1, m.kBy1, 0f, 1f, 1f, 0f);
    }

    /** The area (GUI units: left, top, right, bottom) of the minimap prepared last, with its pills. */
    public static int[] preparedBounds() {
        MinimapLayer m = instance;
        return new int[] {m.kBx0, m.kBy0, m.kBx1, m.kBy1};
    }

    /** The map of the minimap prepared last, with its frame: its centre and half its width (GUI units). */
    public static float[] preparedMap() {
        MinimapLayer m = instance;
        return new float[] {m.kCx, m.kCy, m.kR + 4};
    }

    /** Brings both cached layers up to date; {@code preview}: for the settings, not the HUD (the HUD area is not touched). */
    private boolean prepare(GuiGraphics g, DeltaTracker delta, boolean preview) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        float pt = delta.getGameTimeDeltaPartialTick(true);
        Vec3 pos = player.getPosition(pt);
        float yaw = player.getViewYRot(pt);
        int r = MapConfig.get(MapConfig.MINIMAP_RADIUS);
        // Inner padding, faded edge and the gap to the pills: read every frame (a few config reads), in the layer key.
        padding = MapConfig.get(MapConfig.MINIMAP_PADDING);
        fadeStart = 1 - MapConfig.get(MapConfig.MINIMAP_FADE_WIDTH) / 100f;
        fadeEdge = 1 - MapConfig.get(MapConfig.MINIMAP_FADE_STRENGTH) / 100f;
        infoGap = MapConfig.get(MapConfig.MINIMAP_INFO_GAP);
        pillOpacity = MapConfig.get(MapConfig.MINIMAP_PILL_OPACITY) / 100f;
        cone = MapConfig.get(MapConfig.PLAYER_CONE);
        float alpha = MapConfig.get(MapConfig.MINIMAP_OPACITY).floatValue();
        double zoom = MapView.snapZoom(MapConfig.get(MapConfig.MINIMAP_ZOOM));
        boolean rotate = MapConfig.get(MapConfig.MINIMAP_ORIENTATION) == MapConfig.Orientation.PLAYER_UP;
        rotating = rotate;
        // Info pills: coordinates, biome and clock, each only when enabled. The texts are rebuilt only when what they
        // show changes, so their identity tells when the layer must be redrawn.
        Component coordsLine = MapConfig.get(MapConfig.MINIMAP_SHOW_COORDS) ? coordsText(player) : null;
        Component biomeLine = MapConfig.get(MapConfig.MINIMAP_SHOW_BIOME) ? biomeText(player) : null;
        // The clock row: game or real time, or both side by side (game on the left). The day counter joins the game
        // time in one pill ("Day 12 · 14:35"), or stands in its own pill on the left without it.
        MapConfig.TimeSource source = MapConfig.get(MapConfig.MINIMAP_TIME_SOURCE);
        boolean clockOn = MapConfig.get(MapConfig.MINIMAP_SHOW_TIME);
        boolean game = clockOn && source != MapConfig.TimeSource.REAL_TIME, real = clockOn && source != MapConfig.TimeSource.GAME_TIME;
        Component timeLine, time2Line;
        if (MapConfig.get(MapConfig.MINIMAP_SHOW_DAY)) {
            timeLine = dayText(mc, game);
            time2Line = real ? realTimeText() : null;
        } else {
            timeLine = game ? gameTimeText(mc) : real ? realTimeText() : null;
            time2Line = game && real ? realTimeText() : null;
        }
        // Text size: whole screen pixels per font pixel (always sharp), "Auto" a step below the GUI scale.
        pillScale = MinimapFit.textScale(MapConfig.get(MapConfig.MINIMAP_TEXT_SIZE), mc.getWindow().getGuiScale());
        int textBlock = layoutPills(coordsLine, biomeLine, timeLine, time2Line, r);
        lastTextBlock = textBlock;
        // A minimap larger than the screen allows (with its pills, at a large GUI scale) is drawn smaller to fit; the
        // setting keeps its value. A smaller map may wrap the biome to more lines, so the fit is checked once more.
        for (int i = 0; i < 2; i++) {
            int fit = MinimapFit.radius(r, g.guiWidth(), g.guiHeight(), textBlock, MIN_RADIUS);
            if (fit == r) break;
            r = fit;
            textBlock = layoutPills(coordsLine, biomeLine, timeLine, time2Line, r);
        }
        screenW = g.guiWidth();
        boolean textBelow;

        int ox = MapConfig.get(MapConfig.MINIMAP_OFFSET_X), oy = MapConfig.get(MapConfig.MINIMAP_OFFSET_Y);
        float cx, cy;
        switch (MapConfig.get(MapConfig.MINIMAP_CORNER)) {
            case TOP_RIGHT -> {
                // Keep clear of the vanilla status-effect icons in the top right corner.
                int effectRows = player.getActiveEffects().isEmpty() ? 0 : 2;
                cx = g.guiWidth() - ox - r;
                cy = oy + r + effectRows * 25;
                textBelow = true;
            }
            case BOTTOM_LEFT -> {
                cx = ox + r;
                cy = g.guiHeight() - oy - r;
                textBelow = false;
            }
            case BOTTOM_RIGHT -> {
                cx = g.guiWidth() - ox - r;
                cy = g.guiHeight() - oy - r;
                textBelow = false;
            }
            default -> {
                cx = ox + r;
                cy = oy + r;
                textBelow = true;
            }
        }
        // Keep fully on screen whatever the GUI scale; centre on the physical pixel grid.
        cx = Draw.snap(Math.max(r + 3, Math.min(g.guiWidth() - r - 3, cx)));
        cy = Draw.snap(Math.max(r + 3 + (textBelow ? 0 : textBlock), Math.min(g.guiHeight() - r - 3 - (textBelow ? textBlock : 0), cy)));

        view.set(pos.x, pos.z, zoom, rotate ? MapView.rotationForYaw(yaw) : 0, cx, cy);

        // Map content (backdrop and regions) lives in a small north-up cached texture around the player that is
        // re-rendered only when the map, zoom, size or lighting changes or the player leaves its margin.
        // Underground: the cave layer the player is in, not lit by the sky.
        TileStore store = MapDataManager.get().shownStore();
        boolean cave = store != null && store.cave;
        refreshCache(g, mc, store, pos.x, pos.z, zoom, r, cave ? 0xFFFFFFFF : DayLight.tint(pt), cave ? 0f : DayLight.night(pt));

        // Everything else (faded disc, frame, markers, arrow, "N", pills) is composed into a second small layer
        // that is redrawn only when something visible changed; a still player costs one textured quad per frame.
        ResourceKey<Level> dim = player.level().dimension();
        Navigation.Target target = Navigation.target();
        int pinState = MarkerVisibility.state(zoom, true);
        // The path to the target, if the settings of its kind show it here: searched while shown, redrawn when it changes.
        MapConfig.PinOptions targetOptions = MarkerVisibility.targetOptions();
        boolean path = targetOptions != null && targetOptions.pathOnMinimap() && Navigation.inPlayerDimension(target, player);
        Navigation.setPathWanted(Navigation.PATH_MINIMAP, path);
        int route = path ? Route.version() : Integer.MIN_VALUE;
        // While a line is drawn in (3 s after a new search) the layer is redrawn every frame.
        if (path && Route.revealing()) route += 7919 * (int) (net.minecraft.Util.getMillis() / 8);
        // The layer spans the map and the pills (shifted inward at the screen edge).
        Component[] cl = coordsLine == null ? null : coordsLines, bl = biomeLine == null ? null : biomeLines, tl = timeLine == null ? null : timeLines;
        Component[] t2 = timeWrap && time2Line != null ? time2Lines : null;
        float left = Math.min(Math.min(cx - r - 4, pillLeft(t2, cx)), Math.min(pillLeft(cl, cx), Math.min(pillLeft(bl, cx), pillLeft(tl, cx))));
        float right = Math.max(Math.max(cx + r + 4, pillRight(t2, cx)), Math.max(pillRight(cl, cx), Math.max(pillRight(bl, cx), pillRight(tl, cx))));
        int bx0 = (int) Math.floor(left), bx1 = (int) Math.ceil(right);
        int by0 = (int) Math.floor(textBelow ? cy - r - 4 : cy - r - 4 - textBlock);
        int by1 = (int) Math.ceil(textBelow ? cy + r + 4 + textBlock : cy + r + 4);
        float heading = (float) view.headingOnScreen(yaw);
        if (!preview) {
            shown = true;
            shownX0 = bx0;
            shownY0 = by0;
            shownX1 = bx1;
            shownY1 = by1;
        }

        // Movement and turning (position, heading, the numbers in the pills and the map cache following the player)
        // redraw the layer at most at the chosen update rate; the old layer stays on screen in between, at its old
        // bounds. Any other change redraws at once.
        boolean changed = !layer.exists() || !layerValid
                || cx != kCx || cy != kCy || r != kR || alpha != kAlpha
                || MarkerStore.version() != kMarkers || AutoMarkers.version() != kAuto
                || target != kTarget || pinState != kPinState
                || dim != kDim || textBelow != kTextBelow || Theme.generation() != kTheme
                || padding != kPadding || rotating != kRotating || fadeStart != kFadeStart || fadeEdge != kFadeEdge || infoGap != kInfoGap
                || pillOpacity != kPillOpacity || cone != kCone
                || (coordsLine == null) != (kCoords == null) || (biomeLine == null) != (kBiome == null) || (timeLine == null) != (kTime == null)
                || (time2Line == null) != (kTime2 == null) || pillScale != kPillScale;
        boolean moved = pos.x != kX || pos.z != kZ || heading != kHeading || view.rotation != kRotation
                || cacheGeneration != kGeneration || coordsLine != kCoords || biomeLine != kBiome || timeLine != kTime || time2Line != kTime2
                || bx0 != kBx0 || by0 != kBy0 || bx1 != kBx1 || by1 != kBy1 || route != kRoute;
        long now = Util.getNanos();
        if (changed || moved && now - kDrawnNs >= MapConfig.get(MapConfig.MINIMAP_UPDATE_RATE).intervalNs) {
            kDrawnNs = now;
            Window w = mc.getWindow();
            int bw = bx1 - bx0, bh = by1 - by0;
            int pw = Math.max(1, (int) Math.ceil(bw * w.getGuiScale())), ph = Math.max(1, (int) Math.ceil(bh * w.getGuiScale()));
            layer.bind(g, pw, ph, false);
            float guiW = (float) (w.getWidth() / w.getGuiScale()), guiH = (float) (w.getHeight() / w.getGuiScale());
            g.pose().pushPose();
            g.pose().scale(guiW / bw, guiH / bh, 1f);
            g.pose().translate(-bx0, -by0, 0);
            Draw.beginLayer();
            try {
                drawContents(g, player, cx, cy, r, alpha, heading, dim, target, zoom, path, coordsLine, biomeLine, timeLine, textBelow, textBlock);
                g.flush();
            } finally {
                Draw.snapIcons(true);
                Draw.endLayer();
                g.pose().popPose();
                CachedLayer.unbind();
            }
            layerValid = true;
            kX = pos.x; kZ = pos.z; kHeading = heading; kRotation = view.rotation;
            kCx = cx; kCy = cy; kR = r; kAlpha = alpha; kBx0 = bx0; kBy0 = by0; kBx1 = bx1; kBy1 = by1;
            kGeneration = cacheGeneration; kMarkers = MarkerStore.version(); kAuto = AutoMarkers.version();
            kTarget = target; kPinState = pinState; kRoute = route; kCoords = coordsLine; kBiome = biomeLine; kTime = timeLine; kTime2 = time2Line; kPillScale = pillScale; kDim = dim; kTextBelow = textBelow; kTheme = Theme.generation();
            kPadding = padding; kRotating = rotating; kFadeStart = fadeStart; kFadeEdge = fadeEdge; kInfoGap = infoGap;
            kPillOpacity = pillOpacity;
            kCone = cone;
        }
        return true;
    }

    // Composite layer and the inputs it was drawn from.
    private final CachedLayer layer = new CachedLayer();
    private boolean layerValid, kTextBelow;
    private double kX, kZ, kRotation;
    private float kHeading, kCx, kCy, kAlpha;
    private int kR, kBx0, kBy0, kBx1, kBy1, kGeneration, kMarkers, kAuto, kTheme, kPinState, kRoute, kPadding, kInfoGap;
    private float kFadeStart, kFadeEdge;
    private long kDrawnNs;
    // The pill texts, minimap radius, text scale and gap they were laid out for, their lines; the GUI width the pills
    // keep inside. The clock row may hold two pills (game and real time): timeW is the whole row.
    private Component pillCoords, pillBiome, pillTime, pillTime2;
    private Component[] coordsLines, biomeLines, timeLines, time2Lines;
    private int pillR, pillGap = -1, screenW;
    private float coordsW, biomeW, timeW, time1W, time2W, pillScale = 1, laidScale;
    private boolean timeWrap; // the real time in a row of its own

    /** Width of one of the laid-out pill rows (measured in {@link #layoutPills}), in GUI units. */
    private float widthOf(Component[] lines) {
        return lines == coordsLines ? coordsW : lines == biomeLines ? biomeW : lines == time2Lines ? time2W : timeW;
    }

    /** Height of a pill with {@code lines} text lines at the text scale (0 for none), in GUI units. */
    private float heightOf(Component[] lines) {
        return pillHeight(lines) * pillScale;
    }

    /**
     * Lays out the pills for a minimap of radius {@code r} (the biome name wrapped to its width), only when a text, the
     * radius, the text scale or the gap changed; returns the height of the text block under or over the map. The gap
     * ({@code infoGap}) is the same from the map to the first pill, between the pills and between the two clocks.
     */
    private int layoutPills(Component coordsLine, Component biomeLine, Component timeLine, Component time2Line, int r) {
        if (coordsLine != pillCoords || biomeLine != pillBiome || timeLine != pillTime || time2Line != pillTime2 || r != pillR
                || pillScale != laidScale || infoGap != pillGap) {
            pillCoords = coordsLine;
            pillBiome = biomeLine;
            pillTime = timeLine;
            pillTime2 = time2Line;
            pillR = r;
            laidScale = pillScale;
            pillGap = infoGap;
            int room = (int) (2 * r / pillScale); // the minimap's width in font units
            coordsLines = pillLines(coordsLine, room, false);
            biomeLines = pillLines(biomeLine, room, true);
            timeLines = pillLines(timeLine, room, false);
            time2Lines = pillLines(time2Line, room, false);
            // Measured here, once per layout: the pills' positions are worked out every frame.
            coordsW = pillWidth(coordsLines) * pillScale;
            biomeW = pillWidth(biomeLines) * pillScale;
            time1W = pillWidth(timeLines) * pillScale;
            time2W = pillWidth(time2Lines) * pillScale;
            // Wider than the map, the real time goes to a row of its own under the clock row.
            timeWrap = time2Lines != null && MinimapFit.clockWraps(time1W, infoGap, time2W, 2 * r);
            timeW = time2Lines == null || timeWrap ? time1W : time1W + infoGap + time2W;
        }
        int pills = (coordsLine != null ? 1 : 0) + (biomeLine != null ? 1 : 0) + (timeLine != null ? 1 : 0) + (timeWrap ? 1 : 0);
        return pills == 0 ? 0 : (int) Math.ceil(infoGap + heightOf(coordsLines) + heightOf(biomeLines) + heightOf(timeLines)
                + (timeWrap ? heightOf(time2Lines) : 0) + (pills - 1) * infoGap);
    }

    /** Left edge of a pill: centred under the map, shifted inward at the screen edge; on the pixel grid. */
    private float pillX(Component[] lines, float cx) {
        return Draw.snap(MinimapFit.pillLeft(widthOf(lines), cx, screenW));
    }

    private float pillLeft(Component[] lines, float cx) {
        return lines == null ? Float.MAX_VALUE : pillX(lines, cx) - 2;
    }

    private float pillRight(Component[] lines, float cx) {
        return lines == null ? -Float.MAX_VALUE : pillX(lines, cx) + widthOf(lines) + 2;
    }

    /**
     * The area the minimap (with its pills) covered when last drawn, for the marker in the world to go around it.
     * {@link WorldMarkerLayer} is drawn just before the minimap, so it sees the previous frame's area.
     */
    static boolean shown;
    static int shownX0, shownY0, shownX1, shownY1;
    private Navigation.Target kTarget;
    private Component kCoords, kBiome, kTime, kTime2;
    private float kPillScale;
    private ResourceKey<Level> kDim;

    private void drawContents(GuiGraphics g, LocalPlayer player, float cx, float cy, int r, float alpha, float heading, ResourceKey<Level> dim,
                              Navigation.Target target, double zoom, boolean path, Component coordsLine, Component biomeLine, Component timeLine,
                              boolean textBelow, int textBlock) {
        // The map inside the frame, the padding away from it (the frame and its glow stay at r).
        float mapR = Math.max(8, r - padding);
        if (Theme.Style.squareMap) {
            // Square map with the same frame as the round one: a thin, soft line on the edge and a faint glow inside.
            drawSquareMap(g, cx, cy, mapR, alpha);
            Draw.frame(g, cx - r - 0.5f, cy - r - 0.5f, 2 * r + 1, 2 * r + 1, 1, Theme.withAlpha(Theme.MINIMAP_FRAME, alpha));
            Draw.squareRing(g, cx, cy, r - 5, r, Theme.withAlpha(Theme.MINIMAP_FRAME_GLOW, 0f), Theme.withAlpha(Theme.MINIMAP_FRAME_GLOW, alpha));
        } else {
            drawFadedDisc(g, cx, cy, mapR, alpha);
            // Thin, soft, semi-transparent frame
            Draw.circleOutline(g, cx, cy, r, 1.5f, Theme.withAlpha(Theme.MINIMAP_FRAME, alpha));
            Draw.ring(g, cx, cy, r - 5, r, Theme.withAlpha(Theme.MINIMAP_FRAME_GLOW, 0f), Theme.withAlpha(Theme.MINIMAP_FRAME_GLOW, alpha));
        }

        // Markers: kept inside the circle on which the "N" sits (never on or beyond the frame).
        float inner = innerRadius(mapR);
        // The path to the target under them, and the target itself when it is off the map, both held the same distance
        // inside the map's edge, like the "N", E, S and W (nearer the frame than the circle bounding the other pins).
        // With a faded edge (settings: fade width), in the middle of the fading band when that lies farther in (user
        // request 26.10.2026), so they end where the map is still half seen.
        float edge = fadeEdge >= 1f ? mapR - TARGET_EDGE : Math.min(mapR - TARGET_EDGE, mapR * (1 + fadeStart) / 2);
        if (path) drawRoute(g, cx, cy, edge, inner, alpha);
        // Pins by MarkerVisibility (none but the target below scale 1); the target itself is drawn just after. The map
        // under them moves smoothly between pixels, so they are not snapped to the pixel grid (snapped, they shook).
        Draw.snapIcons(false);
        if (!MarkerVisibility.pinsHidden(zoom)) {
            for (int i = 0, n = MarkerStore.count(); i < n; i++) {
                Marker m = MarkerStore.get(i);
                if (m.dimension == dim && MarkerVisibility.shown(m, zoom, true)) drawMarker(g, m, cx, cy, inner, alpha);
            }
            List<Marker> auto = AutoMarkers.forDimension(dim);
            for (int i = 0; i < auto.size(); i++) {
                if (MarkerVisibility.shown(auto.get(i), zoom, true)) drawMarker(g, auto.get(i), cx, cy, inner, alpha);
            }
        }
        if (target != null && target.dimension() == dim) drawTarget(g, target, cx, cy, edge, alpha);
        Draw.snapIcons(true);

        // View cone and player arrow
        if (cone) Draw.sector(g, cx, cy, r * 0.8f, heading, 0.72f, Theme.withAlpha(Theme.MINIMAP_VIEW_CONE, alpha), Theme.withAlpha(Theme.MINIMAP_VIEW_CONE, 0f));
        MarkerRenderer.player(g, cx, cy, ARROW, heading, alpha);

        // "N": inside the circle, no background, pointing to world north.
        // Vanilla-font letters in the frame's colour, with a faint dark outline so they stay readable on light ground
        // and stand apart from the (dark) pin tiles. Drawn last, above the markers. Left batched: the layer is flushed
        // once at the end (the pills below never overlap them). North only while north is up; on a map turning with
        // the camera also east, south and west, fainter, so north still stands out. At the text size of the lines under
        // the map (whole screen pixels per font pixel, so they stay sharp).
        for (int i = 0; i < (rotating ? 4 : 1); i++) {
            double angle = view.rotation + i * Math.PI / 2; // screen angle of the direction, clockwise from up
            float ns = (float) Math.sin(angle), nc = (float) Math.cos(angle);
            // At the edge the path ends at ({@code edge}), or (square map) on that square's side (user request 26.10.2026; it was the
            // inner circle of the other pins).
            float nk = Theme.Style.squareMap ? edge / Math.max(Math.abs(ns), Math.abs(nc)) : edge;
            float a = i == 0 ? alpha : alpha * SIDE_LETTER_ALPHA;
            float half = Math.max(0, DrawText.width(COMPASS[i]) - 1) / 2f; // half the visible glyph, font units
            g.pose().pushPose();
            g.pose().translate(Draw.snap(cx + ns * nk - half * pillScale), Draw.snap(cy - nc * nk - 4 * pillScale), 0);
            g.pose().scale(pillScale, pillScale, 1f);
            DrawText.textOutlined(g, COMPASS[i], half, 4, Theme.withAlpha(Theme.MINIMAP_NORTH, a),
                    Theme.withAlpha(Theme.MINIMAP_NORTH_OUTLINE, a), false);
            g.pose().popPose();
        }

        // Pills: coordinates, biome, clock, from the map outwards; over a map in a bottom corner mirrored (the
        // coordinates nearest to it, the clock on top).
        Component[][] order = pillOrder;
        order[0] = coordsLine == null ? null : coordsLines;
        order[1] = biomeLine == null ? null : biomeLines;
        order[2] = timeLine == null ? null : timeLines;
        order[3] = timeWrap && timeLine != null ? time2Lines : null;
        float ty = textBelow ? cy + r + 3 + infoGap : cy - r - 3 - textBlock + infoGap;
        for (int i = 0; i < order.length; i++) ty = pill(g, cx, ty, order[textBelow ? i : order.length - 1 - i], Theme.WHITE, alpha);
    }
    /** The pill rows in their order from the map outwards, reused. */
    private final Component[][] pillOrder = new Component[4][];

    /**
     * The path as a dash-dot line, golden, grey through the fog and in the colour of the sea over deep water (user
     * decision 26.10.2026), cut exactly where it leaves the map at {@code edge} (user report 25.10.2026: dropping the
     * points beyond the circle of the "N" ended it up to a point spacing short of that circle, far inside the frame),
     * with the sea icon where each crossing begins (inside the circle bounding the pins, {@code inner}).
     */
    private void drawRoute(GuiGraphics g, float cx, float cy, float edge, float inner, float alpha) {
        int n = Route.collect();
        if (n < 2 && Route.oldCount() < 2) return;
        pieceLine = Theme.withAlpha(Theme.worldMarkerPulse(), alpha);
        pieceFog = Theme.withAlpha(Theme.ROUTE_FOG, alpha);
        pieceSea = Theme.withAlpha(Theme.ROUTE_SEA, alpha);
        pieceShadow = Theme.withAlpha(Theme.ROUTE_SHADOW, alpha);
        pieceN = 0;
        // The line shown before the last search, wiped from its start while the new one is drawn in.
        int on = Route.oldCount();
        float from = Route.oldFrom();
        for (int i = 0; i + 1 < on; i++) {
            segment(g, view.screenX(Route.oldX(i), Route.oldZ(i)), view.screenY(Route.oldX(i), Route.oldZ(i)),
                    view.screenX(Route.oldX(i + 1), Route.oldZ(i + 1)), view.screenY(Route.oldX(i + 1), Route.oldZ(i + 1)),
                    Route.oldAlong(i), Route.oldAlong(i + 1), Route.oldFog(i), Route.oldFog(i + 1), Route.oldWater(i), Route.oldWater(i + 1),
                    cx, cy, edge, from, Float.POSITIVE_INFINITY);
        }
        endPiece(g);
        // The walked part is gone: from the line's point nearest the player (the map's centre) on, up to where it is drawn in.
        int first = Route.cut(view.centerX, view.centerZ);
        float to = Route.shownTo();
        for (int i = first; i + 1 < n; i++) {
            float ax = i == first ? Route.cutX() : Route.x(i), az = i == first ? Route.cutZ() : Route.z(i);
            segment(g, view.screenX(ax, az), view.screenY(ax, az), view.screenX(Route.x(i + 1), Route.z(i + 1)), view.screenY(Route.x(i + 1), Route.z(i + 1)),
                    i == first ? Route.cutAlong() : Route.along(i), Route.along(i + 1), Route.fog(i), Route.fog(i + 1), Route.water(i), Route.water(i + 1),
                    cx, cy, edge, Float.NEGATIVE_INFINITY, to);
        }
        endPiece(g);
        // The sea icons over the line, inside the map only.
        int badge = Theme.withAlpha(Theme.ROUTE_SEA_BADGE, alpha);
        for (int i = first; i < n; i++) {
            if (!Route.crossingAt(i) || Route.along(i) > to) continue;
            float sx = view.screenX(Route.x(i), Route.z(i)), sy = view.screenY(Route.x(i), Route.z(i));
            boolean in = Theme.Style.squareMap ? Math.abs(sx - cx) <= inner - SEA_BADGE && Math.abs(sy - cy) <= inner - SEA_BADGE
                    : Draw.inCircle(sx, sy, cx, cy, inner - SEA_BADGE);
            if (!in) continue;
            Draw.circle(g, sx, sy, SEA_BADGE, badge);
            Draw.icon(g, Icon.SEA, sx, sy, SEA_BADGE * 1.6f, pieceSea);
        }
    }

    /** Radius of the badge under the sea icon on the minimap (GUI units). */
    private static final float SEA_BADGE = 5;

    /**
     * The path lines end, and the tracked target off the map is held, this far inside the map's edge (GUI units; user
     * request 26.10.2026: the line ran up to the frame, the target sat on the inner circle, 10 units in; 8 tried first, 4
     * chosen), and the "N", E, S and W with them.
     */
    private static final float TARGET_EDGE = 4;

    // The piece of a path line being collected: pieceN screen points in routeXY, which lie in the fog (routeFog) and on
    // deep water (routeWater), its pattern starting pieceAlong GUI units in (its place along the whole line, so the
    // dashes stay put on the map as the player walks), and its colours.
    private float[] routeXY = new float[512];
    private boolean[] routeFog = new boolean[256], routeWater = new boolean[256];
    private int pieceN, pieceLine, pieceFog, pieceSea, pieceShadow;
    private float pieceAlong;

    /**
     * Adds the segment from (ax, ay) to (bx, by) (screen points; {@code aAlong} to {@code bAlong} blocks along the
     * line, its ends in the fog {@code fa}, {@code fb} and on deep water {@code wa}, {@code wb}) to the piece being
     * collected, cut to the map: a segment entering the map starts a new piece at the edge, one leaving it ends the
     * piece there.
     */
    private void segment(GuiGraphics g, float ax, float ay, float bx, float by, float aAlong, float bAlong, boolean fa, boolean fb,
                         boolean wa, boolean wb, float cx, float cy, float edge, float lo, float hi) {
        if (!EdgeClip.clip(ax, ay, bx, by, cx, cy, edge, Theme.Style.squareMap)) {
            endPiece(g);
            return;
        }
        float t0 = EdgeClip.t0(), t1 = EdgeClip.t1();
        // Only the part between lo and hi blocks along the line (drawn in, or wiped, after a new search).
        float len = bAlong - aAlong;
        if (len > 1e-6f) {
            t0 = Math.max(t0, (lo - aAlong) / len);
            t1 = Math.min(t1, (hi - aAlong) / len);
        }
        if (t0 >= t1) {
            endPiece(g);
            return;
        }
        if (pieceN == 0 || t0 > 0) {
            endPiece(g);
            pieceAlong = (float) ((aAlong + (bAlong - aAlong) * t0) * view.scale);
            point(ax + (bx - ax) * t0, ay + (by - ay) * t0, fa, wa);
        }
        point(ax + (bx - ax) * t1, ay + (by - ay) * t1, fb, wb);
        if (t1 < 1) endPiece(g);
    }

    private void point(float x, float y, boolean fog, boolean water) {
        if (pieceN == routeFog.length) {
            routeXY = java.util.Arrays.copyOf(routeXY, 4 * pieceN);
            routeFog = java.util.Arrays.copyOf(routeFog, 2 * pieceN);
            routeWater = java.util.Arrays.copyOf(routeWater, 2 * pieceN);
        }
        routeXY[2 * pieceN] = x;
        routeXY[2 * pieceN + 1] = y;
        routeFog[pieceN] = fog;
        routeWater[pieceN++] = water;
    }

    private void endPiece(GuiGraphics g) {
        if (pieceN >= 2) {
            Draw.route(g, routeXY, routeFog, routeWater, pieceN, 2f, pieceShadow, pieceShadow, pieceShadow, pieceAlong);
            Draw.route(g, routeXY, routeFog, routeWater, pieceN, 1f, pieceLine, pieceFog, pieceSea, pieceAlong);
        }
        pieceN = 0;
    }


    private void drawMarker(GuiGraphics g, Marker m, float cx, float cy, float inner, float alpha) {
        if (Navigation.isTracking(m)) return;
        float sx = view.screenX(m.centerX(), m.centerZ()), sy = view.screenY(m.centerX(), m.centerZ());
        if (Theme.Style.squareMap ? Math.abs(sx - cx) <= inner && Math.abs(sy - cy) <= inner : Draw.inCircle(sx, sy, cx, cy, inner)) {
            // Pins on another layer than the one shown (the cave band the player is in, or the surface) get an arrow.
            MarkerRenderer.draw(g, m.type, sx, sy, MarkerRenderer.SIZE_SMALL, false, false, alpha,
                    MarkerVisibility.layerRelation(m, MapDataManager.get().undergroundBand()));
        }
    }

    // Pill texts are rebuilt only when what they show changes.
    private int coordsX = Integer.MIN_VALUE, coordsY, coordsZ;
    private Component coords;

    private Component coordsText(LocalPlayer p) {
        int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
        if (coords == null || x != coordsX || y != coordsY || z != coordsZ) {
            coordsX = x;
            coordsY = y;
            coordsZ = z;
            coords = Component.literal(x + "  " + y + "  " + z);
        }
        return coords;
    }

    // The biome is looked up again when the player enters another block or another world or dimension, while the chunk
    // under the player was not loaded yet at the last look-up (after a teleport an empty chunk answers plains until the
    // real one arrives), and once a second in any case: right after joining a world the first look-up can still read
    // plains from a chunk that counts as loaded, and a player standing still would keep it (seen 21.10.2026).
    private final BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos(Integer.MIN_VALUE, 0, 0);
    private Holder<Biome> biomeHolder;
    private Component biome;
    private boolean biomeUnloaded;
    private Level biomeLevel;
    private long biomeCheckedNs;
    private static final long BIOME_RECHECK_NS = 1_000_000_000L;

    private Component biomeText(LocalPlayer p) {
        int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
        long now = Util.getNanos();
        if (biome == null || biomeUnloaded || p.level() != biomeLevel || now - biomeCheckedNs > BIOME_RECHECK_NS
                || x != biomePos.getX() || y != biomePos.getY() || z != biomePos.getZ()) {
            biomePos.set(x, y, z);
            biomeLevel = p.level();
            biomeCheckedNs = now;
            biomeUnloaded = !p.level().hasChunkAt(biomePos);
            Holder<Biome> h = p.level().getBiome(biomePos);
            if (h != biomeHolder || biome == null) {
                biomeHolder = h;
                biome = h.unwrapKey().map(MapLayerRenderer::biomeName).orElse(Component.empty());
            }
        }
        return biome;
    }

    // The clock texts change once a minute (of game and of real time), each kept apart.
    private int gameMinute = -1, realMinute = -1;
    private MapConfig.TimeFormat gameFormat, realFormat; // the clock format each text was made for
    private Component gameClock, realClock;
    private static final TimeZone ZONE = TimeZone.getDefault();

    /** The time of day in the world (tick 0 is 06:00), in the chosen format. */
    private Component gameTimeText(Minecraft mc) {
        long ticks = Math.floorMod(mc.level.getDayTime() + 6000, 24000L);
        int minute = (int) (ticks * 1440 / 24000);
        MapConfig.TimeFormat format = MapConfig.get(MapConfig.MINIMAP_TIME_FORMAT);
        if (gameClock == null || minute != gameMinute || format != gameFormat) {
            gameFormat = format;
            gameMinute = minute;
            gameClock = Component.literal(MinimapFit.clock(minute, format));
        }
        return gameClock;
    }

    private long dayShown = -1;
    private Component dayLine, dayClock, dayFor;

    /** "Day 12", or with the game time "Day 12 · 14:35": rebuilt only when the day or the minute changes. */
    private Component dayText(Minecraft mc, boolean withTime) {
        long day = MinimapFit.day(mc.level.getDayTime());
        if (day != dayShown) {
            dayShown = day;
            dayLine = Component.translatable("teyvatcartographia.minimap.day", day);
            dayClock = null;
        }
        if (!withTime) return dayLine;
        Component clock = gameTimeText(mc);
        if (dayClock == null || clock != dayFor) {
            dayFor = clock;
            dayClock = Component.translatable("teyvatcartographia.minimap.day_time", day, clock);
        }
        return dayClock;
    }

    /** "IRL 14:35": the computer's local time in the chosen format, marked apart from the game time. */
    private Component realTimeText() {
        long now = System.currentTimeMillis();
        int minute = (int) (Math.floorDiv(now + ZONE.getOffset(now), 60_000L) % 1440);
        MapConfig.TimeFormat format = MapConfig.get(MapConfig.MINIMAP_TIME_FORMAT);
        if (realClock == null || minute != realMinute || format != realFormat) {
            realFormat = format;
            realMinute = minute;
            realClock = Component.translatable("teyvatcartographia.minimap.real_time", MinimapFit.clock(minute, format));
        }
        return realClock;
    }

    private static float pillWidth(Component[] lines) {
        if (lines == null) return 0;
        int w = 0;
        for (Component line : lines) w = Math.max(w, DrawText.width(line));
        return PILL_H + w;
    }

    /** Height of a pill with {@code lines} text lines (0 for none). */
    private static int pillHeight(Component[] lines) {
        return lines == null ? 0 : PILL_H + (lines.length - 1) * DrawText.LINE;
    }

    /**
     * The text as the lines of its pill: the biome name wrapped at words so that its pill is no wider than the minimap
     * ({@code maxWidth}, never narrower than {@code MIN_WRAP}), the others as one line. Built only when the text or
     * the minimap size changes.
     */
    private static Component[] pillLines(Component text, int maxWidth, boolean wrap) {
        if (text == null) return null;
        int room = Math.max(MIN_WRAP, maxWidth - PILL_H);
        if (!wrap || DrawText.width(text) <= room) return new Component[]{text};
        java.util.List<net.minecraft.network.chat.FormattedText> parts =
                DrawText.font().getSplitter().splitLines(text, room, net.minecraft.network.chat.Style.EMPTY);
        Component[] lines = new Component[parts.size()];
        for (int i = 0; i < lines.length; i++) lines[i] = Component.literal(parts.get(i).getString());
        return lines;
    }

    /** A wrapped biome pill keeps at least this much room for its text (a tiny minimap would split every word). */
    private static final int MIN_WRAP = 60;

    /**
     * Pill row centred on cx at y, if there are lines; returns the y of the next row. The clock row holds the game
     * time and, when both are shown, the real time to its right.
     */
    private float pill(GuiGraphics g, float cx, float y, Component[] lines, int color, float alpha) {
        if (lines == null) return y;
        float x = pillX(lines, cx);
        if (lines == timeLines && time2Lines != null && !timeWrap) {
            pillAt(g, x, y, lines, time1W, color, alpha);
            pillAt(g, Draw.snap(x + time1W + infoGap), y, time2Lines, time2W, color, alpha);
        } else {
            pillAt(g, x, y, lines, widthOf(lines), color, alpha);
        }
        return y + heightOf(lines) + infoGap;
    }

    /** One pill {@code w} GUI units wide at (x, y), its text (each line centred) drawn at the text scale. */
    private void pillAt(GuiGraphics g, float x, float y, Component[] lines, float w, int color, float alpha) {
        float s = pillScale, w0 = w / s;
        int h0 = pillHeight(lines);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1f);
        int fill = Theme.withAlpha(Theme.INFO_BOX | 0xFF000000, alpha * pillOpacity);
        // Several lines: rounded like the one-line capsule, not a taller capsule.
        if (lines.length > 1 && !Theme.Style.squareUi) Draw.roundRect(g, 0, 0, w0, h0, PILL_H / 2f, fill);
        else Ui.pillShape(g, 0, 0, w0, h0, fill, 0);
        float ty = DrawText.textY(0, PILL_H);
        for (Component line : lines) {
            DrawText.text(g, line, (w0 - DrawText.width(line)) / 2f, ty, Theme.withAlpha(color, alpha), false);
            ty += DrawText.LINE;
        }
        g.pose().popPose();
    }
    private void drawTarget(GuiGraphics g, Navigation.Target t, float cx, float cy, float r, float alpha) {
        float sx = view.screenX(t.x(), t.z()), sy = view.screenY(t.x(), t.z());
        MarkerType type = t.auto();
        if (t.markerId() != null) {
            Marker m = MarkerStore.find(t.markerId());
            type = m != null ? m.type : MarkerType.STAR;
        }
        float size = MarkerRenderer.SIZE_SMALL;
        float dx = sx - cx, dy = sy - cy;
        // Distance in the shape's own metric: Euclidean for the round map, Chebyshev for the square one.
        float d = Theme.Style.squareMap ? Math.max(Math.abs(dx), Math.abs(dy)) : (float) Math.sqrt(dx * dx + dy * dy);
        if (d > r) {
            // Out of range: held at the edge the path line ends at ({@link #TARGET_EDGE}) in the target's direction.
            sx = cx + dx / d * r;
            sy = cy + dy / d * r;
        }
        MarkerRenderer.draw(g, type, sx, sy, size, false, false, alpha);
    }

    /** Radius of the inner circle bounding the markers: a marker's half-size inside the frame (the "N" sits at {@link #TARGET_EDGE}). */
    private static float innerRadius(float r) {
        return r - MarkerRenderer.SIZE_SMALL / 2f - 3;
    }

    // ------------------------------------------------------------------ cached map picture and faded disc

    /** GUI units of map kept around the visible disc, so walking does not re-render the cache every frame. */
    private static final int MARGIN = 24;
    /** The cache is rendered at twice the screen density, so sampling it rotated stays sharp. */
    private static final int SUPERSAMPLE = 2;
    /** Map content changes (newly scanned chunks) refresh the cache at most this often. */
    private static final long CONTENT_REFRESH_MS = 250;
    /** Disc tessellation: 48 segments keep the edge within a pixel of a true circle even at the largest size. */
    private static final int SEGMENTS = 48, BANDS = 6;
    private static final float[] COS = new float[SEGMENTS + 1], SIN = new float[SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = 2 * Math.PI * i / SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    private final MapView cacheView = new MapView();
    private final CachedLayer cache = new CachedLayer();
    private TileStore cacheStore;
    private int cacheVersion, cacheR, cacheSize, cacheTint, cacheNight, cacheTheme;
    private double cacheX, cacheZ, cacheZoom;
    private long cacheTime;
    private boolean cacheValid;
    /** Incremented whenever the cached map picture is re-rendered (part of the composite layer's key). */
    private int cacheGeneration;

    /** Re-renders the cached square (radius {@code r + MARGIN}, north up, centred near the player) when needed. */
    private void refreshCache(GuiGraphics g, Minecraft mc, TileStore store, double px, double pz, double zoom, int r, int tint, float night) {
        Window w = mc.getWindow();
        // The rotated view must stay inside the cache: a square map reaches r * sqrt(2) at its corners.
        int cR = (Theme.Style.squareMap ? (int) Math.ceil(r * 1.4143f) : r) + MARGIN;
        int size = Math.min(4096, (int) Math.ceil(2 * cR * w.getGuiScale() * SUPERSAMPLE));
        int nightQ = Math.round(night * 32);
        long now = Util.getMillis();
        boolean stale = !cacheValid || !cache.exists()
                || store != cacheStore || zoom != cacheZoom || cR != cacheR || size != cacheSize
                || tint != cacheTint || nightQ != cacheNight || Theme.generation() != cacheTheme
                || Math.hypot(px - cacheX, pz - cacheZ) * zoom > MARGIN - 1
                || (store != null && store.version() != cacheVersion && now - cacheTime >= CONTENT_REFRESH_MS);
        if (!stale) {
            touchTiles(store);
            return;
        }

        cache.bind(g, size, size, true);

        // The GUI projection spans the whole (now cache-sized) viewport; scale cache units (0..2cR) onto it.
        float guiW = (float) (w.getWidth() / w.getGuiScale()), guiH = (float) (w.getHeight() / w.getGuiScale());
        g.pose().pushPose();
        g.pose().scale(guiW / (2f * cR), guiH / (2f * cR), 1f);
        if (store != null && !store.cave && FogTexture.hasOwn(store.dimension)) {
            // A dimension with a fog of its own (the Aether's clouds): it fills the minimap too, fixed to the world (the cached
            // square moves with the map between redraws, so the fog must too).
            FogTexture.draw(g, store.dimension, 0, 0, 2 * cR, 2 * cR, (float) (px * zoom), (float) (pz * zoom), tint);
        } else {
            Draw.rect(g, 0, 0, 2 * cR, 2 * cR, store != null && store.cave ? Theme.MINIMAP_CAVE_BACKDROP : DayLight.apply(Theme.MINIMAP_BACKDROP, tint));
        }
        boolean complete = true;
        if (store != null) {
            cacheView.set(px, pz, zoom, 0, cR, cR);
            // The backdrop made the square opaque; keep its alpha so translucent layers only change colour.
            RenderSystem.colorMask(true, true, true, false);
            complete = MapLayerRenderer.drawRect(g, store, cacheView, 0, 0, 2 * cR, 2 * cR, tint, night);
            RenderSystem.colorMask(true, true, true, true);
        }
        g.pose().popPose();
        CachedLayer.unbind();

        cacheValid = complete;
        cacheGeneration++;
        cacheStore = store;
        cacheVersion = store == null ? 0 : store.version();
        cacheZoom = zoom;
        cacheR = cR;
        cacheSize = size;
        cacheTint = tint;
        cacheNight = nightQ;
        cacheTheme = Theme.generation();
        cacheX = px;
        cacheZ = pz;
        cacheTime = now;
    }

    /** Keeps the cached regions marked as used so the store's LRU does not evict what the minimap shows. */
    private void touchTiles(TileStore store) {
        if (store == null) return;
        double half = cacheR / cacheZoom;
        int tx0 = MapView.blockToTile((int) Math.floor(cacheX - half)), tx1 = MapView.blockToTile((int) Math.floor(cacheX + half));
        int tz0 = MapView.blockToTile((int) Math.floor(cacheZ - half)), tz1 = MapView.blockToTile((int) Math.floor(cacheZ + half));
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                MapTile t = store.peek(tx, tz);
                if (t != null) t.touch();
            }
        }
    }

    /**
     * The faded edge (settings): the fraction of the map's radius that stays fully opaque, and the share of the alpha
     * left at the very edge (0: fades out completely). Also the frame-to-map padding and the gap to the pills.
     */
    private float fadeStart = 0.8f, fadeEdge;
    private int padding, infoGap = Grid.GAP;
    // Opacity of the pills' background (the setting; the theme gives its colour), in the layer key.
    private float pillOpacity = 0.7f, kPillOpacity = -1;
    private boolean cone = true, kCone = true;

    /** Draws the cache as a disc: opaque centre, then an annulus whose alpha falls smoothly to 0 at radius r. */
    private void drawFadedDisc(GuiGraphics g, float cx, float cy, float r, float alpha) {
        if (!cache.exists()) return;
        Matrix4f m = g.pose().last().pose();
        int[] colors = bandColors;
        for (int k = 0; k <= BANDS; k++) colors[k] = Theme.withAlpha(Theme.WHITE, alpha * (fadeEdge + (1 - fadeEdge) * fade((float) k / BANDS)));
        float r0 = r * fadeStart;
        // Every grid point (ring k at segment i) is projected once; the triangles below only pick them up.
        for (int k = 0; k <= BANDS; k++) {
            float rk = r0 + (r - r0) * k / BANDS;
            for (int i = 0; i <= SEGMENTS; i++) gridPoint(k * (SEGMENTS + 1) + i, cx + COS[i] * rk, cy + SIN[i] * rk);
        }
        gridPoint(GRID - 1, cx, cy);
        BufferBuilder b = Draw.beginTex();
        for (int i = 0; i < SEGMENTS; i++) {
            // opaque centre
            gridVertex(b, m, GRID - 1, colors[0]);
            gridVertex(b, m, i, colors[0]);
            gridVertex(b, m, i + 1, colors[0]);
            // fade bands (smoothstep falloff)
            for (int k = 0; k < BANDS; k++) {
                int a0 = k * (SEGMENTS + 1) + i, b0 = a0 + SEGMENTS + 1;
                int ca = colors[k], cb = colors[k + 1];
                gridVertex(b, m, a0, ca);
                gridVertex(b, m, b0, cb);
                gridVertex(b, m, b0 + 1, cb);
                gridVertex(b, m, a0, ca);
                gridVertex(b, m, b0 + 1, cb);
                gridVertex(b, m, a0 + 1, ca);
            }
        }
        Draw.endTex(b, cache.textureId());
    }

    /** Disc grid: (BANDS + 1) rings of (SEGMENTS + 1) points, then the centre. Screen position and cache UV. */
    private static final int GRID = (BANDS + 1) * (SEGMENTS + 1) + 1;
    private final float[] gridX = new float[GRID], gridY = new float[GRID], gridU = new float[GRID], gridV = new float[GRID];

    private void gridPoint(int i, float x, float y) {
        double side = 2.0 * cacheR;
        gridX[i] = x;
        gridY[i] = y;
        gridU[i] = (float) ((cacheR + (view.worldX(x, y) - cacheX) * cacheZoom) / side);
        gridV[i] = (float) (1 - (cacheR + (view.worldZ(x, y) - cacheZ) * cacheZoom) / side);
    }

    private void gridVertex(BufferBuilder b, Matrix4f m, int i, int color) {
        Draw.vtx(b, m, gridX[i], gridY[i], gridU[i], gridV[i], color);
    }

    private final int[] bandColors = new int[BANDS + 1];

    /**
     * Square minimap shape: the cache as a square whose alpha fades out towards the frame, like the round minimap: an opaque
     * centre square, then {@link #BANDS} square rings (4 trapezoids each) with the same smoothstep falloff.
     */
    private void drawSquareMap(GuiGraphics g, float cx, float cy, float r, float alpha) {
        if (!cache.exists()) return;
        Matrix4f m = g.pose().last().pose();
        int[] colors = bandColors;
        for (int k = 0; k <= BANDS; k++) colors[k] = Theme.withAlpha(Theme.WHITE, alpha * (fadeEdge + (1 - fadeEdge) * fade((float) k / BANDS)));
        float r0 = r * fadeStart;
        BufferBuilder b = Draw.beginTex();
        int c = colors[0];
        discVertex(b, m, cx - r0, cy - r0, c); discVertex(b, m, cx - r0, cy + r0, c); discVertex(b, m, cx + r0, cy + r0, c);
        discVertex(b, m, cx - r0, cy - r0, c); discVertex(b, m, cx + r0, cy + r0, c); discVertex(b, m, cx + r0, cy - r0, c);
        for (int k = 0; k < BANDS; k++) {
            float a = r0 + (r - r0) * k / BANDS, o = r0 + (r - r0) * (k + 1) / BANDS;
            int ci = colors[k], co = colors[k + 1];
            // corners of the inner (a) and outer (o) squares, clockwise from top-left
            for (int side = 0; side < 4; side++) {
                float ix0 = cx + Draw.SQUARE_X[side] * a, iy0 = cy + Draw.SQUARE_Y[side] * a;
                float ix1 = cx + Draw.SQUARE_X[side + 1] * a, iy1 = cy + Draw.SQUARE_Y[side + 1] * a;
                float ox0 = cx + Draw.SQUARE_X[side] * o, oy0 = cy + Draw.SQUARE_Y[side] * o;
                float ox1 = cx + Draw.SQUARE_X[side + 1] * o, oy1 = cy + Draw.SQUARE_Y[side + 1] * o;
                discVertex(b, m, ix0, iy0, ci); discVertex(b, m, ox0, oy0, co); discVertex(b, m, ox1, oy1, co);
                discVertex(b, m, ix0, iy0, ci); discVertex(b, m, ox1, oy1, co); discVertex(b, m, ix1, iy1, ci);
            }
        }
        Draw.endTex(b, cache.textureId());
    }

    /** 1 at the start of the fade band, 0 at the frame, smooth at both ends. */
    private static float fade(float t) {
        float s = 1 - t;
        return s * s * (3 - 2 * s);
    }

    /** Screen point -> world (through the rotated view) -> position in the north-up cache texture. */
    private void discVertex(BufferBuilder b, Matrix4f m, float x, float y, int color) {
        double side = 2.0 * cacheR;
        float u = (float) ((cacheR + (view.worldX(x, y) - cacheX) * cacheZoom) / side);
        float v = (float) (1 - (cacheR + (view.worldZ(x, y) - cacheZ) * cacheZoom) / side);
        Draw.vtx(b, m, x, y, u, v, color);
    }
}
