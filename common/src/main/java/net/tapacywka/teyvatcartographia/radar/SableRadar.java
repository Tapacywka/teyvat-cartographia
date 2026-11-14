package net.tapacywka.teyvatcartographia.radar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.gui.TeyvatConfigScreen;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.TextureColors;
import net.tapacywka.teyvatcartographia.platform.Platform;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sable's sub-levels on the radar (user decision 12.11.2026, docs/RADAR_SPEC.md; Sable by ryanhcode, 1.21.1 only):
 * the moving ships and contraptions of its physics, each drawn as the silhouette of its own blocks seen from above,
 * turned and moved by its pose (variant C), or as a ship icon with a golden arrow of its course while the silhouette
 * is not read yet or too small to tell (A). Read by reflection, no build dependency (as {@code compat/WaystonesBridge}):
 * the sub-levels of the client's level ({@code SubLevelContainer.getContainer(level).getAllSubLevels()}), their name,
 * pose and the chunks of their plot, where their blocks lie far out in the world. Small sub-levels without a name
 * (falling trees, debris) are left out ({@link RadarRules#shipShown}).
 */
public final class SableRadar {
    private SableRadar() {}

    public static final String MOD_ID = "sable";
    /** How often a silhouette is read again from the sub-level's blocks (ticks): ships are built on while they sail. */
    private static final int REREAD_TICKS = 100;
    /** How often one with no blocks read yet is asked again: its chunks come to the client after the sub-level itself. */
    private static final int RETRY_TICKS = 10;
    /** At most this many columns are read per sub-level (a larger one shows as its icon). */
    private static final int MAX_COLUMNS = 16384;

    /** One sub-level: its silhouette (plot x, z and colour per column, top down) and where it was last tick. */
    private static final class Ship {
        Object handle;
        String name;
        int[] cx = new int[0], cz = new int[0], colour = new int[0];
        int columns, minX, minZ, maxX, maxZ, top;
        int readAt;
        boolean neverRead = true;
        double lastX = Double.NaN, lastZ, vx, vz;
        boolean seen;
    }

    private static final Map<UUID, Ship> ships = new HashMap<>();
    private static final List<Ship> shown = new ArrayList<>();
    private static int ticks;
    private static boolean broken;

    // The reflection, found once.
    private static Method getContainer, getAll, getUniqueId, getName, renderPose, getPlot, isRemoved, loadedChunks, holderChunk;
    private static Method position, orientation, rotationPoint, scale;

    public static boolean loaded() {
        return Platform.get().isModLoaded(MOD_ID);
    }

    /** The sub-section of Compatibility for Sable (shown only with it): where its sub-levels show. */
    public static void settings() {
        TeyvatConfigScreen.compatPart("compat_sable", SableRadar::loaded,
                t -> TeyvatConfigScreen.radarRow(t, "compat.sable_radar", MapConfig.SABLE_RADAR));
    }

    private static boolean bind(Object container, Object subLevel) throws ReflectiveOperationException {
        if (getAll == null) getAll = container.getClass().getMethod("getAllSubLevels");
        if (subLevel != null && getUniqueId == null) {
            Class<?> c = subLevel.getClass();
            getUniqueId = c.getMethod("getUniqueId");
            getName = c.getMethod("getName");
            renderPose = c.getMethod("renderPose", float.class);
            getPlot = c.getMethod("getPlot");
            isRemoved = c.getMethod("isRemoved");
        }
        return true;
    }

    /** Once a tick: the sub-levels of the level, their silhouettes read again now and then. */
    public static void tick(Minecraft mc) {
        shown.clear();
        ticks++;
        if (broken || mc.level == null || mc.player == null || !Radar.on(false) && !Radar.on(true)
                || MapConfig.get(MapConfig.SABLE_RADAR) == RadarRules.Show.OFF || !loaded()) {
            if (mc.level == null) ships.clear();
            return;
        }
        try {
            if (getContainer == null) {
                getContainer = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer").getMethod("getContainer", ClientLevel.class);
            }
            Object container = getContainer.invoke(null, mc.level);
            if (container == null) return;
            bind(container, null);
            List<?> all = (List<?>) getAll.invoke(container);
            for (Ship s : ships.values()) s.seen = false;
            int range = Radar.rangeBlocks();
            for (Object sub : all) {
                bind(container, sub);
                if ((Boolean) isRemoved.invoke(sub)) continue;
                UUID id = (UUID) getUniqueId.invoke(sub);
                Ship s = ships.computeIfAbsent(id, k -> new Ship());
                s.seen = true;
                s.handle = sub;
                s.name = (String) getName.invoke(sub);
                Object pose = renderPose.invoke(sub, 1f);
                bindPose(pose);
                Vector3dc p = (Vector3dc) position.invoke(pose);
                if (!Double.isNaN(s.lastX)) {
                    s.vx = p.x() - s.lastX;
                    s.vz = p.z() - s.lastZ;
                }
                s.lastX = p.x();
                s.lastZ = p.z();
                if (s.neverRead || ticks - s.readAt >= (s.columns == 0 ? RETRY_TICKS : REREAD_TICKS)) {
                    s.neverRead = false;
                    s.readAt = ticks;
                    read(s, getPlot.invoke(sub));
                }
                if (!RadarRules.shipShown(s.name != null && !s.name.isBlank(), s.columns)) continue;
                if (!RadarRules.inRange(p.x() - mc.player.getX(), p.z() - mc.player.getZ(), range)) continue;
                shown.add(s);
            }
            ships.values().removeIf(s -> !s.seen);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Another version of Sable: the radar goes on without its sub-levels.
            broken = true;
            shown.clear();
            TeyvatCartographia.LOGGER.warn("Sable's sub-levels cannot be read, the radar leaves them out", e);
        }
    }

    private static void bindPose(Object pose) throws ReflectiveOperationException {
        if (position != null) return;
        Class<?> c = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc", false, pose.getClass().getClassLoader());
        position = c.getMethod("position");
        orientation = c.getMethod("orientation");
        rotationPoint = c.getMethod("rotationPoint");
        scale = c.getMethod("scale");
    }

    /** Reads the silhouette of a sub-level: the top block of every column of its plot's chunks, with its map colour. */
    private static void read(Ship s, Object plot) throws ReflectiveOperationException {
        if (loadedChunks == null) loadedChunks = plot.getClass().getMethod("getLoadedChunks");
        Collection<?> holders = (Collection<?>) loadedChunks.invoke(plot);
        int n = 0, top = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int[] cx = s.cx, cz = s.cz, colour = s.colour;
        int[] ys = new int[Math.max(16, cx.length)];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Object holder : holders) {
            if (holderChunk == null) holderChunk = holder.getClass().getMethod("getChunk");
            LevelChunk chunk = (LevelChunk) holderChunk.invoke(holder);
            if (chunk == null) continue;
            LevelChunkSection[] sections = chunk.getSections();
            int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    BlockState found = null;
                    int fy = 0;
                    for (int si = sections.length - 1; si >= 0 && found == null; si--) {
                        LevelChunkSection sec = sections[si];
                        if (sec == null || sec.hasOnlyAir()) continue;
                        for (int ly = 15; ly >= 0; ly--) {
                            BlockState st = sec.getBlockState(lx, ly, lz);
                            if (!st.isAir()) {
                                found = st;
                                fy = chunk.getSectionYFromSectionIndex(si) * 16 + ly;
                                break;
                            }
                        }
                    }
                    if (found == null) continue;
                    if (n == MAX_COLUMNS) {
                        s.columns = 0; // too large: its icon
                        return;
                    }
                    if (n == cx.length) {
                        int len = Math.max(64, n * 2);
                        cx = java.util.Arrays.copyOf(cx, len);
                        cz = java.util.Arrays.copyOf(cz, len);
                        colour = java.util.Arrays.copyOf(colour, len);
                        ys = java.util.Arrays.copyOf(ys, len);
                    } else if (n == ys.length) {
                        ys = java.util.Arrays.copyOf(ys, cx.length);
                    }
                    int x = x0 + lx, z = z0 + lz;
                    pos.set(x, fy, z);
                    cx[n] = x;
                    cz[n] = z;
                    colour[n] = colourOf(found, chunk, pos);
                    ys[n] = fy;
                    top = Math.max(top, fy);
                    minX = Math.min(minX, x);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x + 1);
                    maxZ = Math.max(maxZ, z + 1);
                    n++;
                }
            }
        }
        // Lower columns a little darker, as the map shades its relief.
        for (int i = 0; i < n; i++) colour[i] = RadarRules.shade(colour[i], top - ys[i]);
        s.cx = cx;
        s.cz = cz;
        s.colour = colour;
        s.columns = n;
        s.minX = minX;
        s.minZ = minZ;
        s.maxX = maxX;
        s.maxZ = maxZ;
        s.top = top + 1;
    }

    /** The colour of a block seen from above, as the map colours it (its texture, grass and leaves with the default tint). */
    private static int colourOf(BlockState state, LevelChunk chunk, BlockPos pos) {
        TextureColors.Entry e = TextureColors.get(state);
        if (e == null) return 0xFF000000 | state.getMapColor(chunk, pos).col;
        return 0xFF000000 | switch (e.tint()) {
            case GRASS -> TextureColors.multiply(e.argb(), GrassColor.getDefaultColor());
            case FOLIAGE -> TextureColors.multiply(e.argb(), FoliageColor.getDefaultColor());
            default -> e.argb();
        };
    }

    /** The player left the world. */
    public static void leave() {
        ships.clear();
        shown.clear();
    }

    public static boolean any() {
        return !shown.isEmpty();
    }

    // ------------------------------------------------------------------ drawing

    private static float[] quads = new float[8 * 256];
    private static int[] quadColours = new int[256];
    /** The pose of the sub-level drawn now: its rotation's rows for the world's x and z, its scale and origins. */
    private static double m00, m01, m02, m20, m21, m22, px, pz, rx, ry, rz, sx, sy, sz;

    private static boolean pose(Ship s, float pt) {
        try {
            Object pose = renderPose.invoke(s.handle, pt);
            Vector3dc p = (Vector3dc) position.invoke(pose), r = (Vector3dc) rotationPoint.invoke(pose), sc = (Vector3dc) scale.invoke(pose);
            Quaterniondc q = (Quaterniondc) orientation.invoke(pose);
            double x = q.x(), y = q.y(), z = q.z(), w = q.w();
            m00 = 1 - 2 * (y * y + z * z);
            m01 = 2 * (x * y - z * w);
            m02 = 2 * (x * z + y * w);
            m20 = 2 * (x * z - y * w);
            m21 = 2 * (y * z + x * w);
            m22 = 1 - 2 * (x * x + y * y);
            px = p.x();
            pz = p.z();
            rx = r.x();
            ry = r.y();
            rz = r.z();
            sx = sc.x();
            sy = sc.y();
            sz = sc.z();
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** World x and z of a point of the plot (its y: the top of the silhouette), turned and moved by the pose. */
    private static double worldX(double x, double y, double z) {
        return px + m00 * (x - rx) * sx + m01 * (y - ry) * sy + m02 * (z - rz) * sz;
    }

    private static double worldZ(double x, double y, double z) {
        return pz + m20 * (x - rx) * sx + m21 * (y - ry) * sy + m22 * (z - rz) * sz;
    }

    /** Part of the minimap's cache key: where the sub-levels shown on it are and how they are turned. */
    public static long signature(MapView view, float pt, float cx, float cy, float r, boolean square) {
        if (!MapConfig.get(MapConfig.SABLE_RADAR).on(true)) return 0;
        long h = 0;
        for (int i = 0; i < shown.size(); i++) {
            Ship s = shown.get(i);
            if (!pose(s, pt)) continue;
            float x = view.screenX(px, pz), y = view.screenY(px, pz);
            h = h * 31 + Math.round(x * 2);
            h = h * 31 + Math.round(y * 2);
            h = h * 31 + Math.round(Math.atan2(m20, m00) * 60);
            h = h * 31 + s.columns;
        }
        return h;
    }

    /**
     * Draws the sub-levels over a map: the minimap (inside {@code r} of its centre) or the world map ({@code r} 0, with
     * their names under them).
     */
    public static void draw(GuiGraphics g, MapView view, float pt, boolean minimap, float cx, float cy, float r, boolean square, float alpha) {
        if (!MapConfig.get(MapConfig.SABLE_RADAR).on(minimap)) return;
        for (int i = 0; i < shown.size(); i++) {
            Ship s = shown.get(i);
            if (!pose(s, pt)) continue;
            float ox = view.screenX(px, pz), oy = view.screenY(px, pz);
            if (r > 0 && !inside(ox, oy, cx, cy, r + 8, square)) continue;
            double topY = s.columns > 0 ? s.top : ry;
            // How large it is on screen: the diagonal of its plot's box.
            float ax = view.screenX(worldX(s.minX, topY, s.minZ), worldZ(s.minX, topY, s.minZ));
            float ay = view.screenY(worldX(s.minX, topY, s.minZ), worldZ(s.minX, topY, s.minZ));
            float bx = view.screenX(worldX(s.maxX, topY, s.maxZ), worldZ(s.maxX, topY, s.maxZ));
            float by = view.screenY(worldX(s.maxX, topY, s.maxZ), worldZ(s.maxX, topY, s.maxZ));
            float size = (float) Math.hypot(bx - ax, by - ay);
            float labelY;
            if (RadarRules.shipSilhouette(s.columns, size)) {
                silhouette(g, view, s, topY, minimap, cx, cy, r, square, alpha);
                labelY = Math.max(ay, by) + 3;
            } else {
                icon(g, view, s, ox, oy, minimap, alpha);
                labelY = oy + (minimap ? 6 : 9) + 2;
            }
            if (!minimap && s.name != null && !s.name.isBlank()) {
                DrawText.textCentered(g, Component.literal(s.name), Draw.snap(ox), Draw.snap(labelY), Theme.withAlpha(Theme.WHITE, alpha), true);
            }
        }
    }

    private static void silhouette(GuiGraphics g, MapView view, Ship s, double y, boolean minimap, float cx, float cy, float r, boolean square, float alpha) {
        int n = s.columns;
        if (quadColours.length < n * 2) {
            quadColours = new int[n * 2];
            quads = new float[n * 16];
        }
        // Its shadow on the ground first (down and right, as the islands of floating dimensions), then its blocks.
        float shadow = minimap ? 1.5f : 3f;
        int count = 0;
        int dark = Theme.withAlpha(Theme.RADAR_SHIP_SHADOW, alpha);
        for (int pass = 0; pass < 2; pass++) {
            float off = pass == 0 ? shadow : 0;
            for (int i = 0; i < n; i++) {
                int o = count * 8;
                double x0 = s.cx[i], z0 = s.cz[i];
                corner(view, x0, y, z0, off, o);
                corner(view, x0 + 1, y, z0, off, o + 2);
                corner(view, x0 + 1, y, z0 + 1, off, o + 4);
                corner(view, x0, y, z0 + 1, off, o + 6);
                if (r > 0) {
                    float mx = (quads[o] + quads[o + 4]) / 2, my = (quads[o + 1] + quads[o + 5]) / 2;
                    if (!inside(mx, my, cx, cy, r, square)) continue;
                }
                quadColours[count++] = pass == 0 ? dark : Theme.withAlpha(s.colour[i], alpha);
            }
        }
        Draw.quads(g, quads, quadColours, count);
    }

    private static void corner(MapView view, double x, double y, double z, float off, int o) {
        double wx = worldX(x, y, z), wz = worldZ(x, y, z);
        quads[o] = view.screenX(wx, wz) + off;
        quads[o + 1] = view.screenY(wx, wz) + off;
    }

    /** Variant A: a dark disc with the ship icon, a golden arrow of its course while it moves. */
    private static void icon(GuiGraphics g, MapView view, Ship s, float x, float y, boolean minimap, float alpha) {
        float rad = minimap ? 6 : 9;
        Draw.circle(g, x, y, rad + 0.8f, Theme.withAlpha(Theme.RADAR_OUTLINE, alpha));
        Draw.circle(g, x, y, rad, Theme.withAlpha(Theme.INDICATOR, alpha));
        Draw.icon(g, Icon.SHIP, x, y, rad * 1.5f, Theme.withAlpha(Theme.INDICATOR_ICON, alpha));
        double speed = Math.hypot(s.vx, s.vz);
        if (speed < 0.02) return;
        // The course on the map as it is turned: where a point ahead of it lies on screen.
        float ax = view.screenX(px + s.vx / speed * 16, pz + s.vz / speed * 16) - x, ay = view.screenY(px + s.vx / speed * 16, pz + s.vz / speed * 16) - y;
        float len = (float) Math.hypot(ax, ay);
        if (len < 1e-3f) return;
        float dx = ax / len, dy = ay / len;
        float tx = x + dx * (rad + 2), ty = y + dy * (rad + 2);
        int gold = Theme.withAlpha(Theme.worldMarkerPulse(), alpha);
        Draw.triangle(g, tx + dx * 4, ty + dy * 4, tx - dy * 3, ty + dx * 3, tx + dy * 3, ty - dx * 3, gold);
    }

    private static boolean inside(float sx, float sy, float cx, float cy, float r, boolean square) {
        float dx = sx - cx, dy = sy - cy;
        return square ? Math.abs(dx) <= r && Math.abs(dy) <= r : dx * dx + dy * dy <= r * r;
    }
}
