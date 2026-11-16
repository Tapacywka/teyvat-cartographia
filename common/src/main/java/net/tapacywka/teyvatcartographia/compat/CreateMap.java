package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.tapacywka.aacs.api.EnumValue;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.gui.TeyvatConfigScreen;
import net.tapacywka.teyvatcartographia.gui.Ui;
import net.tapacywka.teyvatcartographia.gui.WorldMapScreen;
import net.tapacywka.teyvatcartographia.hud.EdgeClip;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.radar.RadarRules;
import net.tapacywka.teyvatcartographia.render.DistanceText;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Create's railways on the maps: the tracks as rails with sleepers (one line far out), the stations with their names
 * and the signals, every train of the world on the world map (its carriages near, its badge far, the first carriage or
 * the badge's ring in the colour of what it is doing) with a tooltip, and on the minimap the carriages near the player.
 * The data is {@link CreateBridge}'s; the rules {@link CreateRules}'. Main thread only; the arrays are kept, so a frame
 * allocates nothing but what a tooltip needs.
 */
public final class CreateMap {
    private CreateMap() {}

    private static final String K = "teyvatcartographia.create.";
    /** GUI units round a badge or a station within which the pointer points at it. */
    private static final float BADGE_R = 6, STATION_R = 4;
    private static final int MAX_QUADS = 2048;

    private static float[] pts = new float[256], rail = new float[256];
    private static final float[] quads = new float[MAX_QUADS * 8];
    private static final int[] colours = new int[MAX_QUADS];
    private static int runN;
    private static float runStart;
    private static final List<CreateBridge.Carriage> near = new ArrayList<>();
    private static boolean asked;

    public static boolean loaded() {
        return CreateBridge.loaded();
    }

    /** The sub-section of Compatibility for Create (shown only with it): where its tracks, stations and trains show. */
    public static void settings() {
        TeyvatConfigScreen.compatPart("compat_create", CreateMap::loaded, t -> {
            TeyvatConfigScreen.radarRow(t, "compat.create_tracks", MapConfig.CREATE_TRACKS);
            TeyvatConfigScreen.radarRow(t, "compat.create_stations", MapConfig.CREATE_STATIONS);
            TeyvatConfigScreen.radarRow(t, "compat.create_trains", MapConfig.CREATE_TRAINS);
        });
    }

    private static boolean on(EnumValue<RadarRules.Show> v, boolean minimap) {
        return MapConfig.get(v).on(minimap);
    }

    /**
     * Once a tick: the graphs read again when they changed; while the world map is open and shows trains, Create's
     * train map is asked for them, and no longer once it closes; the carriages near the player for the minimap.
     */
    public static void tick(Minecraft mc) {
        if (!loaded()) return;
        boolean tracks = on(MapConfig.CREATE_TRACKS, false) || on(MapConfig.CREATE_TRACKS, true)
                || on(MapConfig.CREATE_STATIONS, false) || on(MapConfig.CREATE_STATIONS, true);
        boolean trains = mc.screen instanceof WorldMapScreen && on(MapConfig.CREATE_TRAINS, false);
        CreateBridge.tick(mc, tracks, trains);
        if (asked && !trains) CreateBridge.stopTrains();
        asked = trains;
        if (on(MapConfig.CREATE_TRAINS, true)) CreateBridge.nearbyCarriages(mc, 160, near);
        else near.clear();
    }

    /** How many carriages near the player the minimap has to show (the in-game tests). */
    public static int nearCount() {
        return near.size();
    }

    /** Part of the minimap's cache key: what changed in the railways, and where the carriages near the player are. */
    public static long signature() {
        if (!loaded()) return 0;
        long h = CreateBridge.generation();
        for (CreateBridge.Carriage c : near) {
            h = h * 31 + Math.round(c.x() * 2);
            h = h * 31 + Math.round(c.z() * 2);
            h = h * 31 + Math.round(c.yaw());
        }
        return h;
    }

    /**
     * Draws the railways over a map: the minimap (inside {@code edge} of its centre, a circle or a square), or the world
     * map ({@code minimap} false, the screen {@code w} by {@code h}). Returns what the pointer is on, for the world map's
     * tooltip ({@link #tooltip}), or null.
     */
    public static Object draw(GuiGraphics g, MapView view, ResourceKey<Level> dim, boolean minimap, float cx, float cy, float edge,
                              boolean square, float alpha, float w, float h, float mx, float my) {
        if (!loaded()) return null;
        if (!minimap) {
            // The screen as a square round its middle, a little larger, for the same clipping as the minimap.
            cx = w / 2;
            cy = h / 2;
            edge = Math.max(w, h) / 2 + 16;
            square = true;
        }
        if (on(MapConfig.CREATE_TRACKS, minimap)) drawTracks(g, view, dim, minimap, cx, cy, edge, square, alpha);
        Object pointed = null;
        if (on(MapConfig.CREATE_STATIONS, minimap)) pointed = drawPoints(g, view, dim, minimap, cx, cy, edge, square, alpha, mx, my);
        if (on(MapConfig.CREATE_TRAINS, minimap)) {
            if (minimap) drawNear(g, view, cx, cy, edge, square, alpha);
            else {
                Object train = drawTrains(g, view, dim, alpha, mx, my);
                if (train != null) pointed = train;
            }
        }
        return pointed;
    }

    // ---------------------------------------------------------------- tracks

    private static void drawTracks(GuiGraphics g, MapView view, ResourceKey<Level> dim, boolean minimap, float cx, float cy, float edge,
                                   boolean square, float alpha) {
        double scale = view.scale;
        boolean rails = CreateRules.rails(scale);
        // The world's box on screen, to leave out the tracks far off it (the world map does not turn).
        double x0 = view.worldX(cx - edge, cy - edge), z0 = view.worldZ(cx - edge, cy - edge);
        double x1 = view.worldX(cx + edge, cy + edge), z1 = view.worldZ(cx + edge, cy + edge);
        double minX = Math.min(x0, x1), maxX = Math.max(x0, x1), minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);
        double reach = minimap ? edge * 1.5 / scale : 0; // a turning minimap: its corners reach farther
        for (CreateBridge.Track t : CreateBridge.tracks()) {
            if (t.dimension() != dim) continue;
            if (t.maxX() < minX - reach || t.minX() > maxX + reach || t.maxZ() < minZ - reach || t.minZ() > maxZ + reach) continue;
            float[] xz = t.xz();
            int n = xz.length / 2;
            runN = 0;
            float along = 0;
            float ax = view.screenX(xz[0], xz[1]), ay = view.screenY(xz[0], xz[1]);
            for (int i = 0; i + 1 < n; i++) {
                float bx = view.screenX(xz[2 * i + 2], xz[2 * i + 3]), by = view.screenY(xz[2 * i + 2], xz[2 * i + 3]);
                float len = (float) Math.hypot(bx - ax, by - ay);
                if (!EdgeClip.clip(ax, ay, bx, by, cx, cy, edge, square)) {
                    endRun(g, rails, scale, alpha);
                } else {
                    float t0 = EdgeClip.t0(), t1 = EdgeClip.t1();
                    if (runN == 0 || t0 > 0) {
                        endRun(g, rails, scale, alpha);
                        runStart = along + len * t0;
                        add(ax + (bx - ax) * t0, ay + (by - ay) * t0);
                    }
                    add(ax + (bx - ax) * t1, ay + (by - ay) * t1);
                    if (t1 < 1) endRun(g, rails, scale, alpha);
                }
                along += len;
                ax = bx;
                ay = by;
            }
            endRun(g, rails, scale, alpha);
        }
    }

    private static void add(float x, float y) {
        if (runN * 2 + 2 > pts.length) {
            pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            rail = new float[pts.length];
        }
        pts[2 * runN] = x;
        pts[2 * runN + 1] = y;
        runN++;
    }

    /** Draws the run of points collected: a dark bed, the sleepers and two rails, or far out one line on its edge. */
    private static void endRun(GuiGraphics g, boolean rails, double scale, float alpha) {
        int n = runN;
        runN = 0;
        if (n < 2) return;
        int edgeColour = Theme.withAlpha(Theme.CREATE_TRACK_EDGE, alpha), railColour = Theme.withAlpha(Theme.CREATE_RAIL, alpha);
        if (!rails) {
            Draw.polyline(g, pts, n, 2.6f, edgeColour);
            Draw.polyline(g, pts, n, 1.2f, railColour);
            return;
        }
        float gauge = CreateRules.gauge(scale), half = gauge / 2, step = CreateRules.sleeperStep(scale);
        // A faint bed under the track, the ground showing between the sleepers.
        Draw.polyline(g, pts, n, gauge + 2.4f, Theme.withAlpha(Theme.CREATE_TRACK_BED, alpha));
        // The sleepers stay where they are on the track as the map moves: counted from the track's start.
        float phase = (step - (runStart % step)) % step;
        float thick = Math.max(0.8f, Math.min(1.8f, step * 0.3f));
        int q = CreateRules.sleepers(pts, n, step, phase, half + 1f, thick, quads, 0, MAX_QUADS);
        java.util.Arrays.fill(colours, 0, q, Theme.withAlpha(Theme.CREATE_SLEEPER, alpha));
        Draw.quads(g, quads, colours, q);
        float railW = Math.max(0.8f, Math.min(1.6f, gauge * 0.14f));
        CreateRules.offset(pts, n, half, rail);
        Draw.polyline(g, rail, n, railW, railColour);
        CreateRules.offset(pts, n, -half, rail);
        Draw.polyline(g, rail, n, railW, railColour);
    }

    // ---------------------------------------------------------------- stations and signals

    private static boolean inside(float x, float y, float cx, float cy, float edge, boolean square) {
        return square ? Math.abs(x - cx) <= edge && Math.abs(y - cy) <= edge : Math.hypot(x - cx, y - cy) <= edge;
    }

    private static Object drawPoints(GuiGraphics g, MapView view, ResourceKey<Level> dim, boolean minimap, float cx, float cy, float edge,
                                     boolean square, float alpha, float mx, float my) {
        Object pointed = null;
        if (CreateRules.rails(view.scale)) {
            for (CreateBridge.Point s : CreateBridge.signals()) {
                if (s.dimension() != dim) continue;
                float x = view.screenX(s.x(), s.z()), y = view.screenY(s.x(), s.z());
                if (!inside(x, y, cx, cy, edge - 2, square)) continue;
                Draw.circle(g, x, y, 2.4f, Theme.withAlpha(Theme.CREATE_TRACK_EDGE, alpha));
                Draw.circle(g, x, y, 1.6f, Theme.withAlpha(Theme.CREATE_SIGNAL, alpha));
            }
        }
        boolean names = !minimap && view.scale >= CreateRules.RAILS_FROM;
        for (CreateBridge.Point s : CreateBridge.stations()) {
            if (s.dimension() != dim) continue;
            float x = view.screenX(s.x(), s.z()), y = view.screenY(s.x(), s.z());
            if (!inside(x, y, cx, cy, edge - STATION_R, square)) continue;
            station(g, x, y, alpha);
            if (names && s.name() != null && !s.name().isBlank()) {
                // On the side of the track its block stands: above a track running up and down it lay on the rails.
                Component name = Component.literal(s.name());
                int white = Theme.withAlpha(Theme.WHITE, alpha);
                float middle = Draw.snap(y - DrawText.LINE / 2f + 1);
                switch (CreateRules.labelSide(view.screenX(s.blockX(), s.blockZ()) - x, view.screenY(s.blockX(), s.blockZ()) - y)) {
                    case ABOVE -> DrawText.textCentered(g, name, Draw.snap(x), Draw.snap(y - STATION_R - 2 - DrawText.LINE), white, true);
                    case BELOW -> DrawText.textCentered(g, name, Draw.snap(x), Draw.snap(y + STATION_R + 3), white, true);
                    case RIGHT -> DrawText.text(g, name, Draw.snap(x + STATION_R + 3), middle, white, true);
                    case LEFT -> DrawText.textRight(g, name, Draw.snap(x - STATION_R - 3), middle, white, true);
                }
            }
            if (!minimap && Math.hypot(mx - x, my - y) <= STATION_R + 2) pointed = s;
        }
        return pointed;
    }

    /** A station: a light plate with the outline of a train on it. */
    private static void station(GuiGraphics g, float x, float y, float alpha) {
        float r = STATION_R;
        Draw.roundRect(g, x - r - 0.8f, y - r - 0.8f, 2 * r + 1.6f, 2 * r + 1.6f, 1.6f, Theme.withAlpha(Theme.CREATE_TRACK_EDGE, alpha));
        Draw.roundRect(g, x - r, y - r, 2 * r, 2 * r, 1.2f, Theme.withAlpha(Theme.CREATE_STATION, alpha));
        engine(g, x, y, r * 0.75f, Theme.withAlpha(Theme.CREATE_STATION_MARK, alpha));
    }

    /** A small engine seen from the side, {@code s} half as wide: body, window and two wheels. */
    private static void engine(GuiGraphics g, float x, float y, float s, int argb) {
        Draw.roundRect(g, x - s, y - s * 0.75f, 2 * s, s * 1.15f, s * 0.25f, argb);
        Draw.circle(g, x - s * 0.5f, y + s * 0.6f, s * 0.32f, argb);
        Draw.circle(g, x + s * 0.5f, y + s * 0.6f, s * 0.32f, argb);
    }

    // ---------------------------------------------------------------- trains

    private static int lookColour(CreateRules.Look look) {
        return switch (look) {
            case MOVING -> Theme.CREATE_MOVING;
            case WAITING -> Theme.CREATE_WAITING;
            case TROUBLE -> Theme.CREATE_TROUBLE;
            case STOPPED -> Theme.CREATE_STOPPED;
        };
    }

    private static Object drawTrains(GuiGraphics g, MapView view, ResourceKey<Level> dim, float alpha, float mx, float my) {
        Object pointed = null;
        float f = CreateBridge.between();
        boolean carriages = CreateRules.carriages(view.scale);
        float half = (float) Math.max(1.8f, 0.8 * view.scale);
        for (CreateBridge.Train t : CreateBridge.trains()) {
            if (t.dimension != dim || t.carriages <= 0) continue;
            float[] a = t.before, b = t.now;
            int look = Theme.withAlpha(lookColour(t.look), alpha);
            if (carriages) {
                int q = 0;
                for (int c = 0; c < t.carriages && q + 2 <= MAX_QUADS; c++) {
                    float lx = lerp(a, b, 4 * c, f), lz = lerp(a, b, 4 * c + 1, f), tx = lerp(a, b, 4 * c + 2, f), tz = lerp(a, b, 4 * c + 3, f);
                    float sx0 = view.screenX(lx, lz), sy0 = view.screenY(lx, lz), sx1 = view.screenX(tx, tz), sy1 = view.screenY(tx, tz);
                    q = carriage(sx0, sy0, sx1, sy1, half + 0.8f, 0.8f, Theme.withAlpha(Theme.CREATE_TRACK_EDGE, alpha), q);
                    q = carriage(sx0, sy0, sx1, sy1, half, 0, c == 0 ? look : Theme.withAlpha(Theme.CREATE_CARRIAGE, alpha), q);
                    if (CreateRules.distanceToSegment(mx, my, sx0, sy0, sx1, sy1) <= half + 2) pointed = t;
                }
                Draw.quads(g, quads, colours, q);
            } else {
                float lx = lerp(a, b, 0, f), lz = lerp(a, b, 1, f), tx = lerp(a, b, 2, f), tz = lerp(a, b, 3, f);
                float x = view.screenX((lx + tx) / 2, (lz + tz) / 2), y = view.screenY((lx + tx) / 2, (lz + tz) / 2);
                float heading = (float) Math.atan2(view.screenY(lx, lz) - view.screenY(tx, tz), view.screenX(lx, lz) - view.screenX(tx, tz));
                badge(g, x, y, heading, look, alpha);
                if (Math.hypot(mx - x, my - y) <= BADGE_R + 1) pointed = t;
            }
        }
        return pointed;
    }

    private static float lerp(float[] a, float[] b, int i, float f) {
        return a.length > i ? a[i] + (b[i] - a[i]) * f : b[i];
    }

    /** A carriage from (x0, y0) to (x1, y1) as a box {@code half} to each side, longer by {@code grow} at each end. */
    private static int carriage(float x0, float y0, float x1, float y1, float half, float grow, int argb, int q) {
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.hypot(dx, dy);
        if (len < 1e-4f) {
            dx = 1;
            dy = 0;
            len = 1;
        }
        float ux = dx / len, uy = dy / len, nx = -uy * half, ny = ux * half;
        x0 -= ux * grow;
        y0 -= uy * grow;
        x1 += ux * grow;
        y1 += uy * grow;
        int o = q * 8;
        quads[o] = x0 + nx;
        quads[o + 1] = y0 + ny;
        quads[o + 2] = x1 + nx;
        quads[o + 3] = y1 + ny;
        quads[o + 4] = x1 - nx;
        quads[o + 5] = y1 - ny;
        quads[o + 6] = x0 - nx;
        quads[o + 7] = y0 - ny;
        colours[q] = argb;
        return q + 1;
    }

    /** A train far out: a dark disc in a ring of its state's colour, an engine on it and a pointer where it heads. */
    private static void badge(GuiGraphics g, float x, float y, float heading, int look, float alpha) {
        float r = BADGE_R, ux = (float) Math.cos(heading), uy = (float) Math.sin(heading);
        // The pointer: a small triangle on the ring, ahead.
        float tipX = x + ux * (r + 3.2f), tipY = y + uy * (r + 3.2f), bx = x + ux * (r - 0.5f), by = y + uy * (r - 0.5f);
        quads[0] = tipX;
        quads[1] = tipY;
        quads[2] = bx - uy * 2.6f;
        quads[3] = by + ux * 2.6f;
        quads[4] = bx + uy * 2.6f;
        quads[5] = by - ux * 2.6f;
        quads[6] = tipX;
        quads[7] = tipY;
        colours[0] = look;
        Draw.quads(g, quads, colours, 1);
        Draw.circle(g, x, y, r + 0.8f, Theme.withAlpha(Theme.CREATE_TRACK_EDGE, alpha));
        Draw.circle(g, x, y, r, look);
        Draw.circle(g, x, y, r - 1.4f, Theme.withAlpha(Theme.CREATE_STATION_MARK, alpha));
        engine(g, x, y, r * 0.55f, Theme.withAlpha(Theme.WHITE, alpha));
    }

    /** The minimap: the carriages near the player, as Create's own entities stand. */
    private static void drawNear(GuiGraphics g, MapView view, float cx, float cy, float edge, boolean square, float alpha) {
        int q = 0;
        float half = (float) Math.max(1.4f, 0.75 * view.scale);
        for (CreateBridge.Carriage c : near) {
            float x = view.screenX(c.x(), c.z()), y = view.screenY(c.x(), c.z());
            if (!inside(x, y, cx, cy, edge - 3, square) || q + 2 > MAX_QUADS) continue;
            double yaw = Math.toRadians(c.yaw());
            double hx = -Math.sin(yaw) * c.length() / 2, hz = Math.cos(yaw) * c.length() / 2;
            float sx0 = view.screenX(c.x() - hx, c.z() - hz), sy0 = view.screenY(c.x() - hx, c.z() - hz);
            float sx1 = view.screenX(c.x() + hx, c.z() + hz), sy1 = view.screenY(c.x() + hx, c.z() + hz);
            q = carriage(sx0, sy0, sx1, sy1, half + 0.8f, 0.8f, Theme.withAlpha(Theme.CREATE_TRACK_EDGE, alpha), q);
            q = carriage(sx0, sy0, sx1, sy1, half, 0, Theme.withAlpha(Theme.CREATE_CARRIAGE, alpha), q);
        }
        Draw.quads(g, quads, colours, q);
    }

    // ---------------------------------------------------------------- the settings' pictures

    /** The scale of the sample railway, screen units a block: near enough for rails and carriages. */
    public static final float SAMPLE_SCALE = 2;

    /**
     * A made-up railway in the middle of a square picture of side {@code s} at ({@code x}, {@code y}), its top left
     * corner: a track coming from the left and turning up; {@code part} 0 the track alone, 1 with a station and a
     * signal, 2 with a train (on the minimap only its carriages, as the minimap shows them).
     */
    public static void sample(GuiGraphics g, float x, float y, float s, int part, boolean minimap) {
        float y0 = y + s * 0.62f, r = s * 0.3f, turnX = x + s * 0.5f, cy = y0 - r;
        runN = 0;
        runStart = 0;
        add(x - 4, y0);
        for (int i = 0; i <= 12; i++) {
            double a = Math.PI / 2 * (1 - i / 12.0);
            add(turnX + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a)));
        }
        add(turnX + r, y - 4);
        endRun(g, true, SAMPLE_SCALE, 1f);
        if (part == 1) {
            station(g, x + s * 0.25f, y0, 1f);
            float sx = turnX + r, sy = cy - s * 0.12f;
            Draw.circle(g, sx, sy, 2.4f, Theme.CREATE_TRACK_EDGE);
            Draw.circle(g, sx, sy, 1.6f, Theme.CREATE_SIGNAL);
        } else if (part == 2) {
            float half = minimap ? 1.5f : 1.8f, len = 4.5f * SAMPLE_SCALE, gap = 1.2f;
            int q = 0;
            for (int c = 0; c < 3; c++) {
                float x1 = turnX - s * 0.04f - c * (len + gap), x0 = x1 - len;
                q = carriage(x0, y0, x1, y0, half + 0.8f, 0.8f, Theme.CREATE_TRACK_EDGE, q);
                q = carriage(x0, y0, x1, y0, half, 0, c == 0 && !minimap ? Theme.CREATE_MOVING : Theme.CREATE_CARRIAGE, q);
            }
            Draw.quads(g, quads, colours, q);
        }
    }

    // ---------------------------------------------------------------- tooltip

    /** The world map's tooltip for what {@link #draw} said the pointer is on. */
    public static void tooltip(GuiGraphics g, Object pointed, int mx, int my, int w, int h) {
        if (pointed instanceof CreateBridge.Point s) {
            Ui.tooltip(g, s.name() == null || s.name().isBlank() ? Component.translatable(K + "station").getString() : s.name(),
                    Component.translatable(K + "station").getString(), mx, my, w, h);
            return;
        }
        if (!(pointed instanceof CreateBridge.Train t)) return;
        String title = t.name != null ? t.name : Component.translatable(K + "train").getString();
        List<String> lines = new ArrayList<>(4);
        List<Integer> cols = new ArrayList<>(4);
        if (t.owner != null && !t.owner.isBlank()) {
            lines.add(Component.translatable(K + "owner", t.owner).getString());
            cols.add(Theme.CREAM);
        }
        if (t.target != null && !t.target.isBlank()) {
            // Create names the station a standing train is at as its target, 0 blocks away.
            lines.add((t.targetDistance < 1 ? Component.translatable(K + "at", t.target)
                    : Component.translatable(K + "going_to", t.target, DistanceText.blocks(t.targetDistance))).getString());
            cols.add(Theme.CREAM);
        }
        lines.add(Component.translatable(K + "state", Component.translatable(K + "look." + t.look.name().toLowerCase(java.util.Locale.ROOT))).getString());
        cols.add(lookColour(t.look));
        lines.add(Component.translatable(K + "carriages." + DistanceText.pluralForm(t.carriages, DistanceText.language()), t.carriages).getString());
        cols.add(Theme.CREAM);
        int[] c = new int[cols.size()];
        for (int i = 0; i < c.length; i++) c[i] = cols.get(i);
        Ui.tooltip(g, title, lines, c, mx, my, w, h);
    }
}
