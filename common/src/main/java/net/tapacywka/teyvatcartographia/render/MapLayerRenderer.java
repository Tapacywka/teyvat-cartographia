package net.tapacywka.teyvatcartographia.render;

import net.minecraft.Util;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.resources.ResourceKey;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.LodStore;
import net.tapacywka.teyvatcartographia.map.data.MapTile;
import net.tapacywka.teyvatcartographia.map.data.TileStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage 5: draws the visible map regions straight from their textures. Used by the world map every frame and by
 * the minimap only when its cached picture needs refreshing; only a few dirty regions are uploaded to the GPU per call.
 */
public final class MapLayerRenderer {
    private MapLayerRenderer() {}

    private static final int UPLOADS_PER_CALL = 4;
    /** More visible regions than this (a very large screen far out) are drawn from LOD tiles too: they would not fit the region cache. */
    private static final int MAX_REGIONS = 96;

    /**
     * Half a texel of a region or LOD texture (both 256 wide). With smoothing the texture coordinates stay that far
     * inside the edges: sampling at the very edge also took the opposite side where the clamp to the edge is not
     * honoured, as under VulkanMod (thin lines along every region border, tester report 26.10.2026). The picture
     * shrinks by one texel in 256, at most half a screen pixel where smoothing is on.
     */
    private static final float EDGE = 0.5f / MapTile.SIZE;

    /** Texture coordinate {@code t} (0..1 across the texture) moved {@code edge} inside both edges. */
    private static float in(float t, float edge) {
        return edge + t * (1 - 2 * edge);
    }

    private static boolean blurFor(MapView view) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return view.scale * guiScale < 0.999;
    }

    /** Draws regions covering the screen rectangle (view must have rotation 0). */
    public static boolean drawRect(GuiGraphics g, TileStore store, MapView view, float x0, float y0, float x1, float y1, int tint) {
        return drawRect(g, store, view, x0, y0, x1, y1, tint, 0f);
    }

    /**
     * As above; {@code lightAlpha} (0..1, the strength of the night) draws each region's light-source texture on top,
     * so areas lit by torches, lava etc. keep their day colours at night.
     *
     * @return {@code false} if some visible region is still loading or waiting for its texture upload, i.e. the
     *         picture is incomplete and should be drawn again (the minimap cache relies on this)
     */
    public static boolean drawRect(GuiGraphics g, TileStore store, MapView view, float x0, float y0, float x1, float y1, int tint, float lightAlpha) {
        if (store == null) return true;
        int tx0 = MapView.blockToTile((int) Math.floor(view.worldX(x0, y0)));
        int tz0 = MapView.blockToTile((int) Math.floor(view.worldZ(x0, y0)));
        int tx1 = MapView.blockToTile((int) Math.floor(view.worldX(x1, y1)));
        int tz1 = MapView.blockToTile((int) Math.floor(view.worldZ(x1, y1)));
        if (useLod(view) || (long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1) > MAX_REGIONS) return drawLod(g, store, view, x0, y0, x1, y1, tint);
        boolean complete = true, lodUpdated = false;
        int lightColor = Theme.withAlpha(Theme.WHITE, lightAlpha);
        int uploads = UPLOADS_PER_CALL;
        boolean blur = blurFor(view);
        float edge = blur ? EDGE : 0f;
        if (!store.cave && FogTexture.shadow(store.dimension)) {
            // Floating land: its shadow on the fog below, the regions drawn dark and shifted down-right first.
            for (int tz = tz0; tz <= tz1; tz++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    MapTile t = store.exists(tx, tz) ? store.peek(tx, tz) : null;
                    int id = t == null ? -1 : t.textureId(false);
                    if (id >= 0) shadow(g, id, view, t.blockX0(), t.blockZ0(), MapTile.SIZE, x0, y0, x1, y1, edge, edge, 1 - edge, 1 - edge);
                }
            }
        }
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (!store.exists(tx, tz)) continue;
                MapTile t = store.get(tx, tz);
                boolean allow = t != null && (!t.needsUpload() || uploads > 0);
                if (t != null && t.needsUpload() && allow) uploads--;
                int id = t == null ? -1 : t.textureId(allow);
                if (t == null || !allow) complete = false;
                if (id < 0) {
                    // Still loading (e.g. just zoomed in from the LOD scales): its quarter-size copy fills the hole meanwhile.
                    if (!lodUpdated) {
                        store.lod().update();
                        lodUpdated = true;
                    }
                    uploads = drawLodCell(g, store.lod(), view, tx, tz, x0, y0, x1, y1, tint, uploads);
                    continue;
                }
                t.setFilter(blur);
                float sx0 = view.screenX(t.blockX0(), t.blockZ0()), sy0 = view.screenY(t.blockX0(), t.blockZ0());
                float sx1 = view.screenX(t.blockX0() + MapTile.SIZE, t.blockZ0() + MapTile.SIZE);
                float sy1 = view.screenY(t.blockX0() + MapTile.SIZE, t.blockZ0() + MapTile.SIZE);
                // Clip to the target rectangle so panels/edges stay clean.
                float cx0 = Math.max(sx0, x0), cy0 = Math.max(sy0, y0), cx1 = Math.min(sx1, x1), cy1 = Math.min(sy1, y1);
                if (cx1 <= cx0 || cy1 <= cy0) continue;
                float w = sx1 - sx0, h = sy1 - sy0;
                Draw.texturedQuad(g, id, cx0, cy0, cx1, cy1,
                        in((cx0 - sx0) / w, edge), in((cy0 - sy0) / h, edge), in((cx1 - sx0) / w, edge), in((cy1 - sy0) / h, edge), tint);
                if (lightAlpha > 0.01f && t.hasLight()) {
                    // Only a real upload uses the budget: counting every lit region used it up for good in lit places
                    // (the Nether, towns at night), and regions later in the loop then never got their textures.
                    boolean lightUpload = t.lightNeedsUpload(), allowLight = !lightUpload || uploads > 0;
                    if (lightUpload && allowLight) uploads--;
                    int lid = t.lightTextureId(allowLight);
                    if (lid >= 0) {
                        t.setLightFilter(blur);
                        Draw.texturedQuad(g, lid, cx0, cy0, cx1, cy1,
                                in((cx0 - sx0) / w, edge), in((cy0 - sy0) / h, edge), in((cx1 - sx0) / w, edge), in((cy1 - sy0) / h, edge), lightColor);
                    } else {
                        complete = false;
                    }
                }
            }
        }
        return complete;
    }

    /** How far the land's shadow falls on the fog (GUI units, down and right) and how dark it is. */
    private static final float SHADOW_OFFSET = 4f;
    private static final int SHADOW = 0x50000000, SHADOW_SOFT = 0x28000000;

    /**
     * The shadow of one texture (a region or a LOD tile, {@code size} blocks from block (bx0, bz0), texture part u0..u1,
     * v0..v1): drawn black with its alpha, twice (a softer, farther copy for a blurred edge), clipped to the rectangle.
     */
    private static void shadow(GuiGraphics g, int id, MapView view, int bx0, int bz0, int size, float x0, float y0, float x1, float y1,
                               float u0, float v0, float u1, float v1) {
        float sx0 = view.screenX(bx0, bz0), sy0 = view.screenY(bx0, bz0);
        float sx1 = view.screenX(bx0 + size, bz0 + size), sy1 = view.screenY(bx0 + size, bz0 + size);
        float w = sx1 - sx0, h = sy1 - sy0;
        for (int pass = 0; pass < 2; pass++) {
            float d = SHADOW_OFFSET * (pass == 0 ? 1f : 1.6f);
            float ax0 = sx0 + d, ay0 = sy0 + d, ax1 = sx1 + d, ay1 = sy1 + d;
            float cx0 = Math.max(ax0, x0), cy0 = Math.max(ay0, y0), cx1 = Math.min(ax1, x1), cy1 = Math.min(ay1, y1);
            if (cx1 <= cx0 || cy1 <= cy0) continue;
            Draw.texturedQuad(g, id, cx0, cy0, cx1, cy1, u0 + (u1 - u0) * (cx0 - ax0) / w, v0 + (v1 - v0) * (cy0 - ay0) / h,
                    u0 + (u1 - u0) * (cx1 - ax0) / w, v0 + (v1 - v0) * (cy1 - ay0) / h, pass == 0 ? SHADOW : SHADOW_SOFT);
        }
    }

    /** Draws region (tx, tz) from its cell of the LOD tile, clipped to the rectangle; returns the uploads left. */
    private static int drawLodCell(GuiGraphics g, LodStore lod, MapView view, int tx, int tz, float x0, float y0, float x1, float y1,
                                   int tint, int uploads) {
        LodStore.LodTile t = lod.get(Math.floorDiv(tx, LodStore.REGIONS), Math.floorDiv(tz, LodStore.REGIONS));
        boolean allow = !t.needsUpload() || uploads > 0;
        if (t.needsUpload() && allow) uploads--;
        int id = t.textureId(allow);
        if (id < 0) return uploads;
        int bx0 = tx * MapTile.SIZE, bz0 = tz * MapTile.SIZE;
        float sx0 = view.screenX(bx0, bz0), sy0 = view.screenY(bx0, bz0);
        float sx1 = view.screenX(bx0 + MapTile.SIZE, bz0 + MapTile.SIZE), sy1 = view.screenY(bx0 + MapTile.SIZE, bz0 + MapTile.SIZE);
        float cx0 = Math.max(sx0, x0), cy0 = Math.max(sy0, y0), cx1 = Math.min(sx1, x1), cy1 = Math.min(sy1, y1);
        if (cx1 <= cx0 || cy1 <= cy0) return uploads;
        float w = sx1 - sx0, h = sy1 - sy0, n = LodStore.REGIONS;
        float col = Math.floorMod(tx, LodStore.REGIONS), row = Math.floorMod(tz, LodStore.REGIONS);
        Draw.texturedQuad(g, id, cx0, cy0, cx1, cy1, in((col + (cx0 - sx0) / w) / n, EDGE), in((row + (cy0 - sy0) / h) / n, EDGE),
                in((col + (cx1 - sx0) / w) / n, EDGE), in((row + (cy1 - sy0) / h) / n, EDGE), tint);
        return uploads;
    }

    /**
     * Far out (half a screen pixel per block or less) a LOD texel (4 x 4 blocks) is at most 2 screen pixels, so the
     * small copies look the same as full regions while needing a sixteenth of the textures and no region loads.
     */
    private static boolean useLod(MapView view) {
        return view.scale * Minecraft.getInstance().getWindow().getGuiScale() <= 0.5;
    }

    /** Draws the LOD tiles covering the rectangle (no light overlay: torches are invisible that far out). */
    private static boolean drawLod(GuiGraphics g, TileStore store, MapView view, float x0, float y0, float x1, float y1, int tint) {
        LodStore lod = store.lod();
        lod.update();
        boolean complete = true;
        int uploads = UPLOADS_PER_CALL;
        int lx0 = Math.floorDiv((int) Math.floor(view.worldX(x0, y0)), LodStore.BLOCKS);
        int lz0 = Math.floorDiv((int) Math.floor(view.worldZ(x0, y0)), LodStore.BLOCKS);
        int lx1 = Math.floorDiv((int) Math.floor(view.worldX(x1, y1)), LodStore.BLOCKS);
        int lz1 = Math.floorDiv((int) Math.floor(view.worldZ(x1, y1)), LodStore.BLOCKS);
        if ((long) (lx1 - lx0 + 1) * (lz1 - lz0 + 1) > 1024) return true; // safety: absurd zoom-out
        if (!store.cave && FogTexture.shadow(store.dimension)) {
            for (int lz = lz0; lz <= lz1; lz++) {
                for (int lx = lx0; lx <= lx1; lx++) {
                    if (!lod.any(lx, lz)) continue;
                    LodStore.LodTile t = lod.get(lx, lz);
                    int id = t.textureId(false);
                    if (id >= 0) shadow(g, id, view, t.blockX0(), t.blockZ0(), LodStore.BLOCKS, x0, y0, x1, y1, EDGE, EDGE, 1 - EDGE, 1 - EDGE);
                }
            }
        }
        for (int lz = lz0; lz <= lz1; lz++) {
            for (int lx = lx0; lx <= lx1; lx++) {
                if (!lod.any(lx, lz)) continue;
                LodStore.LodTile t = lod.get(lx, lz);
                if (!t.complete()) complete = false;
                boolean allow = !t.needsUpload() || uploads > 0;
                if (t.needsUpload() && allow) uploads--;
                if (!allow) complete = false;
                int id = t.textureId(allow);
                if (id < 0) continue;
                float sx0 = view.screenX(t.blockX0(), t.blockZ0()), sy0 = view.screenY(t.blockX0(), t.blockZ0());
                float sx1 = view.screenX(t.blockX0() + LodStore.BLOCKS, t.blockZ0() + LodStore.BLOCKS);
                float sy1 = view.screenY(t.blockX0() + LodStore.BLOCKS, t.blockZ0() + LodStore.BLOCKS);
                float cx0 = Math.max(sx0, x0), cy0 = Math.max(sy0, y0), cx1 = Math.min(sx1, x1), cy1 = Math.min(sy1, y1);
                if (cx1 <= cx0 || cy1 <= cy0) continue;
                float w = sx1 - sx0, h = sy1 - sy0;
                Draw.texturedQuad(g, id, cx0, cy0, cx1, cy1,
                        in((cx0 - sx0) / w, EDGE), in((cy0 - sy0) / h, EDGE), in((cx1 - sx0) / w, EDGE), in((cy1 - sy0) / h, EDGE), tint);
            }
        }
        return complete;
    }

    // ------------------------------------------------------------------ biome labels

    /** Biome names, built once each (asked every frame by the labels and the minimap). */
    private static final Map<ResourceKey<Biome>, Component> NAMES = new HashMap<>();

    /** The game's name of a biome: its translation key, as vanilla builds it ('/' in the path as '.'). */
    public static Component biomeName(ResourceKey<Biome> biome) {
        return NAMES.computeIfAbsent(biome, k -> Component.translatable(Util.makeDescriptionId("biome", k.location())));
    }

    // Labels placed this frame (reused: no allocation per frame).
    private static float[] placed = new float[64];
    private static ResourceKey<Biome>[] placedIds = newKeys(16);

    @SuppressWarnings("unchecked")
    private static ResourceKey<Biome>[] newKeys(int n) {
        return (ResourceKey<Biome>[]) new ResourceKey<?>[n];
    }

    /**
     * Draws one label per region (its dominant biome), skipping duplicates close to each other and labels whose box
     * intersects any of the {@code avoid} rectangles (x0, y0, x1, y1). All labels are flushed in one batch.
     */
    public static void drawBiomeLabels(GuiGraphics g, TileStore store, MapView view, float x0, float y0, float x1, float y1,
                                       java.util.List<float[]> avoid) {
        // Shown from 1/8 up (user request 25.10.2026, they were shown from 1/4 to 4): at 1/16 and 1/32 they crowd.
        if (store == null || view.scale < MapView.DETAIL_FROM - 1e-6) return;
        int count = 0;
        float minDist = (float) Math.max(90, MapTile.SIZE * view.scale * 0.9);
        for (MapTile t : store.loadedTiles()) {
            if (t.labelForCount != t.chunkCount()) computeLabel(t);
            if (t.labelBiome == null) continue;
            float sx = view.screenX(t.labelX, t.labelZ), sy = view.screenY(t.labelX, t.labelZ);
            if (sx < x0 - 50 || sx > x1 + 50 || sy < y0 - 10 || sy > y1 + 10) continue;
            Component name = biomeName(t.labelBiome);
            float hw = DrawText.width(name) / 2f + 6, hh = 7;
            boolean dup = false;
            for (float[] a : avoid) {
                if (sx + hw > a[0] && sx - hw < a[2] && sy + hh > a[1] && sy - hh < a[3]) {
                    dup = true;
                    break;
                }
            }
            for (int i = 0; i < count && !dup; i++) {
                float px = placed[i * 4], py = placed[i * 4 + 1];
                boolean overlap = Math.abs(px - sx) < placed[i * 4 + 2] + hw && Math.abs(py - sy) < placed[i * 4 + 3] + hh;
                float d = (px - sx) * (px - sx) + (py - sy) * (py - sy);
                if (overlap || (placedIds[i] == t.labelBiome && d < minDist * minDist)) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            if (count == placedIds.length) {
                placedIds = java.util.Arrays.copyOf(placedIds, count * 2);
                placed = java.util.Arrays.copyOf(placed, count * 8);
            }
            placed[count * 4] = sx;
            placed[count * 4 + 1] = sy;
            placed[count * 4 + 2] = hw;
            placed[count * 4 + 3] = hh;
            placedIds[count++] = t.labelBiome;
            DrawText.textOutlined(g, name, sx, sy, Theme.LABEL_TEXT, Theme.LABEL_OUTLINE, false);
        }
        g.flush();
    }

    private static void computeLabel(MapTile t) {
        t.labelForCount = t.chunkCount();
        t.labelBiome = null;
        if (t.chunkCount() < 12) return;
        Map<ResourceKey<Biome>, int[]> counts = new HashMap<>();
        for (int cz = 0; cz < MapTile.CHUNKS; cz++) {
            for (int cx = 0; cx < MapTile.CHUNKS; cx++) {
                if (!t.hasChunk(cx, cz)) continue;
                ResourceKey<Biome> b = t.biomeOfChunk(cx, cz);
                if (b == null) continue;
                int[] c = counts.computeIfAbsent(b, k -> new int[3]);
                c[0]++;
                c[1] += cx;
                c[2] += cz;
            }
        }
        ResourceKey<Biome> best = null;
        int bestN = 0;
        for (Map.Entry<ResourceKey<Biome>, int[]> e : counts.entrySet()) {
            if (e.getValue()[0] > bestN) {
                best = e.getKey();
                bestN = e.getValue()[0];
            }
        }
        if (best == null || bestN < 10) return;
        int[] c = counts.get(best);
        t.labelBiome = best;
        t.labelX = t.blockX0() + (c[1] / (double) c[0]) * 16 + 8;
        t.labelZ = t.blockZ0() + (c[2] / (double) c[0]) * 16 + 8;
    }
}
