package net.tapacywka.teyvatcartographia.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.MapOutline;

/**
 * The world border on the maps (user decisions 07.11.2026, variant H of the mockups): a rough red line drawn over the
 * explored land and the Fog of War alike, from corner to corner of the border, and everything beyond it darker. Drawn on
 * the map's picture, not in its textures, so the line keeps its width at every zoom and follows a moving border. Only the
 * dimension the player is in: the game's border of another one is not known here. The view must have rotation 0.
 */
public final class BorderLine {
    private BorderLine() {}

    /** GUI units between the points of the line, at least. */
    private static final float STEP = 3;
    /** Width of the line in screen pixels. */
    private static final float WIDTH = 3;
    private static final int MAX_POINTS = 2048;
    private static final float[] XY = new float[MAX_POINTS * 2];
    /** The smallest and the largest screen coordinate across the side, of the points {@link #points} made last. */
    private static float least, most;

    /** The border to draw for {@code dimension}, null: none. */
    private static WorldBorder border(ResourceKey<Level> dimension) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null || level.dimension() != dimension || !MapConfig.get(MapConfig.WORLD_BORDER) ? null : level.getWorldBorder();
    }

    /** What changes when the line does: the setting and where the border stands. */
    public static int stamp(ResourceKey<Level> dimension) {
        WorldBorder b = border(dimension);
        if (b == null) return 0;
        return 1 + Double.hashCode(b.getMinX()) * 31 + Double.hashCode(b.getMaxX()) * 17 + Double.hashCode(b.getMinZ()) * 7 + Double.hashCode(b.getMaxZ());
    }

    private static double rough(double x, double z) {
        return MapOutline.BORDER_ROUGH * MapOutline.rough(x, z);
    }

    /** Draws the border over the map shown in the rectangle. */
    public static void draw(GuiGraphics g, ResourceKey<Level> dimension, MapView view, float x0, float y0, float x1, float y1) {
        WorldBorder b = border(dimension);
        if (b == null) return;
        double minX = b.getMinX(), maxX = b.getMaxX(), minZ = b.getMinZ(), maxZ = b.getMaxZ();
        double left = view.worldX(x0, y0), top = view.worldZ(x0, y0), right = view.worldX(x1, y1), bottom = view.worldZ(x1, y1);
        double reach = MapOutline.BORDER_ROUGH + 1;
        if (left > minX + reach && right < maxX - reach && top > minZ + reach && bottom < maxZ - reach) return;
        // A power of two of blocks, so the points stay on their blocks while the map moves: the line does not crawl.
        double blocks = Math.max(1, Math.pow(2, Math.ceil(Math.log(STEP / view.scale) / Math.log(2))));
        // The roughness at the corners: two sides meet where each has moved by it, along and across.
        double nw = rough(minX, minZ), ne = rough(maxX, minZ), sw = rough(minX, maxZ), se = rough(maxX, maxZ);
        // The dark, each part once: east and west go on straight past the corners to the end of the rectangle, north and
        // south lie between them, and each pair shares that straight side exactly.
        beyond(g, view, maxX, minZ, maxZ, ne, se, top, bottom, blocks, true, 1, x1);
        beyond(g, view, minX, minZ, maxZ, nw, sw, top, bottom, blocks, true, -1, x0);
        beyond(g, view, maxZ, minX, maxX, sw, se, left, right, blocks, false, 1, y1);
        beyond(g, view, minZ, minX, maxX, nw, ne, left, right, blocks, false, -1, y0);
        line(g, view, maxX, minZ, maxZ, ne, se, top, bottom, blocks, true);
        line(g, view, minX, minZ, maxZ, nw, sw, top, bottom, blocks, true);
        line(g, view, maxZ, minX, maxX, sw, se, left, right, blocks, false);
        line(g, view, minZ, minX, maxX, nw, ne, left, right, blocks, false);
    }

    /** Point {@code n} of {@link #XY}; returns the next. */
    private static int add(MapView view, int n, double across, double along, boolean upright) {
        double wx = upright ? across : along, wz = upright ? along : across;
        float sx = view.screenX(wx, wz), sy = view.screenY(wx, wz), at = upright ? sx : sy;
        XY[2 * n] = sx;
        XY[2 * n + 1] = sy;
        least = Math.min(least, at);
        most = Math.max(most, at);
        return n + 1;
    }

    /**
     * The points of a side of the border inside the view, into {@link #XY}: at {@code at} on one axis, from {@code lo} to
     * {@code hi} on the other, each end moved on by the roughness at that corner.
     *
     * @param upright the side runs along z
     * @param onward  it goes on straight from its corners up to the ends of the view
     * @return how many
     */
    private static int points(MapView view, double at, double lo, double hi, double cornerLo, double cornerHi, double viewLo, double viewHi, double blocks,
                              boolean upright, boolean onward) {
        least = Float.MAX_VALUE;
        most = -Float.MAX_VALUE;
        int n = 0;
        double endLo = lo + cornerLo, endHi = hi + cornerHi;
        if (onward && viewLo < endLo) {
            n = add(view, n, at + cornerLo, viewLo, upright);
            n = add(view, n, at + cornerLo, Math.min(endLo, viewHi), upright);
        }
        double from = Math.max(lo, viewLo), to = Math.min(hi, viewHi), start = Math.floor(from / blocks) * blocks;
        for (int i = 0; from < to && n < MAX_POINTS - 2; i++) {
            double along = Math.max(from, Math.min(to, start + i * blocks));
            boolean corner = along == lo || along == hi;
            // A point past where its corner moved to would turn the line back on itself: a strip dark twice.
            if (corner || along > endLo && along < endHi) {
                n = add(view, n, at + rough(upright ? at : along, upright ? along : at), along == lo ? endLo : along == hi ? endHi : along, upright);
            }
            if (along >= to) break;
        }
        if (onward && viewHi > endHi) {
            n = add(view, n, at + cornerHi, Math.max(endHi, viewLo), upright);
            n = add(view, n, at + cornerHi, viewHi, upright);
        }
        return n;
    }

    /**
     * What lies beyond one side, up to the end of the rectangle.
     *
     * @param out   1 where beyond lies at higher coordinates, -1 at lower ones
     * @param limit where the rectangle ends on the screen on that side
     */
    private static void beyond(GuiGraphics g, MapView view, double at, double lo, double hi, double cornerLo, double cornerHi, double viewLo, double viewHi, double blocks,
                               boolean upright, int out, float limit) {
        int n = points(view, at, lo, hi, cornerLo, cornerHi, viewLo, viewHi, blocks, upright, upright);
        if (n < 2 || (out > 0 ? least > limit : most < limit)) return;
        float reach = out > 0 ? limit - least + 2 : limit - most - 2;
        Draw.band(g, XY, n, upright ? reach : 0, upright ? 0 : reach, Theme.WORLD_BORDER_BEYOND);
    }

    private static void line(GuiGraphics g, MapView view, double at, double lo, double hi, double cornerLo, double cornerHi, double viewLo, double viewHi, double blocks, boolean upright) {
        Draw.polyline(g, XY, points(view, at, lo, hi, cornerLo, cornerHi, viewLo, viewHi, blocks, upright, false), WIDTH * Draw.px(), Theme.WORLD_BORDER_LINE);
    }
}
