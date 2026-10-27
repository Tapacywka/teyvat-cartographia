package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.resources.ResourceKey;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One 256x256-block map region: rendered pixels (NativeImage, RGBA), per-column heights and flags used for
 * relief shading across chunk borders, and per-chunk metadata (biome, which neighbours were known when the
 * chunk was shaded). Main/render thread only.
 */
public final class MapTile {
    public static final int SIZE = MapView.TILE_SIZE;
    public static final int CHUNKS = MapView.CHUNKS_PER_TILE;
    /** About the memory of a region: per pixel its colour, height, flags, light, and the colour again on the GPU. */
    public static final int BYTES = SIZE * SIZE * 12;
    public static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;

    public static final byte FLAG_KNOWN = 1;
    public static final byte FLAG_WATER = 2;
    /** Not walkable: water two blocks deep or more, or lava (for the far path; regions mapped before it lack it). */
    public static final byte FLAG_IMPASSABLE = 4;

    public final int tx, tz;
    public final long key;

    final NativeImage image;
    final short[] heights;
    final byte[] flags;
    /** Per column: real block light (0..15) above the surface. */
    final byte[] light;
    /** Lit pixels only (tile colour, alpha from the light level); created when the tile has any light. */
    private NativeImage lightImage;
    private DynamicTexture lightTexture;
    private boolean lightDirty;
    private int litColumns;
    /** Per chunk: bit 0..3 = neighbour N/S/W/E was known when the chunk was shaded; bit 7 = chunk present. */
    final byte[] chunkState;
    /** Per chunk: index into {@link #biomePalette} (-1 = unknown). */
    final short[] chunkBiome;
    final List<ResourceKey<Biome>> biomePalette = new ArrayList<>();

    // Cached biome label (computed lazily by the renderer, invalidated when the chunk count changes).
    public ResourceKey<Biome> labelBiome;
    public double labelX, labelZ;
    public int labelForCount = -1;

    private DynamicTexture texture;
    boolean gpuDirty = true;
    boolean diskDirty;
    long lastUsedMs = System.currentTimeMillis();
    int chunkCount;

    public MapTile(int tx, int tz) {
        this(tx, tz, new NativeImage(SIZE, SIZE, true), newHeights(), new byte[SIZE * SIZE], new byte[SIZE * SIZE], new byte[CHUNKS * CHUNKS], newBiomes());
    }

    MapTile(int tx, int tz, NativeImage image, short[] heights, byte[] flags, byte[] light, byte[] chunkState, short[] chunkBiome) {
        this.tx = tx;
        this.tz = tz;
        this.key = MapView.tileKey(tx, tz);
        this.image = image;
        this.heights = heights;
        this.flags = flags;
        this.light = light;
        for (byte l : light) if (l > 0) litColumns++;
        this.chunkState = chunkState;
        this.chunkBiome = chunkBiome;
        for (byte s : chunkState) if ((s & 0x80) != 0) chunkCount++;
    }

    private static short[] newHeights() {
        short[] h = new short[SIZE * SIZE];
        Arrays.fill(h, UNKNOWN_HEIGHT);
        return h;
    }

    private static short[] newBiomes() {
        short[] b = new short[CHUNKS * CHUNKS];
        Arrays.fill(b, (short) -1);
        return b;
    }

    public int blockX0() {
        return tx << MapView.TILE_BITS;
    }

    public int blockZ0() {
        return tz << MapView.TILE_BITS;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public void touch() {
        lastUsedMs = System.currentTimeMillis();
    }

    // ------------------------------------------------------------ column data

    static int idx(int lx, int lz) {
        return lz * SIZE + lx;
    }

    public short height(int lx, int lz) {
        return heights[idx(lx, lz)];
    }

    public byte flags(int lx, int lz) {
        return flags[idx(lx, lz)];
    }

    public boolean hasChunk(int lcx, int lcz) {
        return (chunkState[lcz * CHUNKS + lcx] & 0x80) != 0;
    }

    /** A chunk is shaded again when a neighbour is on the map now that was not when it was shaded (masks as {@link #neighbourMask}). */
    static boolean needsRedo(int neighboursNow, int neighboursShaded) {
        return (neighboursNow & ~neighboursShaded) != 0;
    }

    public int neighbourMask(int lcx, int lcz) {
        return chunkState[lcz * CHUNKS + lcx] & 0x0F;
    }

    public ResourceKey<Biome> biomeOfChunk(int lcx, int lcz) {
        short i = chunkBiome[lcz * CHUNKS + lcx];
        return i < 0 || i >= biomePalette.size() ? null : biomePalette.get(i);
    }

    /**
     * The sides of the chunk written last whose edge columns changed height or kind (bits: 1 north, 2 south, 4 west,
     * 8 east); 0 for a chunk new to the map (its neighbours are redone for their missing edge). The neighbours on those
     * sides were shaded with the old edge (relief, contours, coast rim) and are mapped again.
     */
    int changedEdges;

    /** The sides of a chunk that a column at (x, z) within it lies on (see {@link #changedEdges}). */
    static int edgeBits(int x, int z) {
        return (z == 0 ? 1 : 0) | (z == 15 ? 2 : 0) | (x == 0 ? 4 : 0) | (x == 15 ? 8 : 0);
    }

    /**
     * Writes a shaded chunk (16x16 ABGR pixels + heights + flags) into this tile. Returns whether anything changed:
     * chunks around the player are re-sampled every second, and an unchanged one must not trigger a texture upload,
     * a light rebuild, a disk save or a minimap refresh. With {@code merge} only the known columns of the chunk are
     * written.
     */
    boolean applyChunk(int lcx, int lcz, int[] abgr, short[] h, byte[] f, byte[] l, int neighbourMask, ResourceKey<Biome> biome, boolean merge) {
        int x0 = lcx << 4, z0 = lcz << 4;
        boolean pixels = false, lit = false, data = false;
        boolean known = (chunkState[lcz * CHUNKS + lcx] & 0x80) != 0;
        changedEdges = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x, ti = idx(x0 + x, z0 + z);
                if (merge && (f[i] & FLAG_KNOWN) == 0) continue;
                if (image.getPixelRGBA(x0 + x, z0 + z) != abgr[i]) {
                    image.setPixelRGBA(x0 + x, z0 + z, abgr[i]);
                    pixels = true;
                }
                if (heights[ti] != h[i] || flags[ti] != f[i]) {
                    if (known) changedEdges |= edgeBits(x, z);
                    heights[ti] = h[i];
                    flags[ti] = f[i];
                    data = true;
                }
                if (light[ti] != l[i]) {
                    if (light[ti] > 0) litColumns--;
                    light[ti] = l[i];
                    if (l[i] > 0) litColumns++;
                    lit = true;
                }
            }
        }
        int ci = lcz * CHUNKS + lcx;
        if ((chunkState[ci] & 0x80) == 0) chunkCount++;
        byte state = (byte) (0x80 | (neighbourMask & 0x0F));
        if (chunkState[ci] != state) {
            chunkState[ci] = state;
            data = true;
        }
        if (biome != null) {
            int bi = biomePalette.indexOf(biome);
            if (bi < 0) {
                biomePalette.add(biome);
                bi = biomePalette.size() - 1;
            }
            if (chunkBiome[ci] != bi) {
                chunkBiome[ci] = (short) bi;
                data = true;
            }
        }
        if (pixels) gpuDirty = true;
        if (pixels || lit) lightDirty = true; // the light layer is tinted with the tile colours
        boolean changed = pixels || lit || data;
        if (changed) diskDirty = true;
        return changed;
    }

    // ------------------------------------------------------------ GPU

    /** Returns the GL texture id, uploading pending changes if {@code allowUpload}. -1 if not yet available. */
    public int textureId(boolean allowUpload) {
        RenderSystem.assertOnRenderThread();
        if (texture == null) {
            if (!allowUpload) return -1;
            texture = new DynamicTexture(image) {
                @Override
                public void close() {
                    // The NativeImage is owned by the tile; only release the GL texture here.
                    releaseId();
                }
            };
            // Clamp, otherwise samples at the very edge wrap to the opposite (often unexplored) side
            // and show up as 1px seams between regions.
            texture.bind();
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            gpuDirty = false;
        } else if (gpuDirty && allowUpload) {
            texture.upload();
            gpuDirty = false;
        }
        return texture.getId();
    }

    public boolean needsUpload() {
        return texture == null || gpuDirty;
    }

    public void setFilter(boolean blur) {
        if (texture != null) texture.setFilter(blur, false);
    }

    public boolean hasLight() {
        return litColumns > 0;
    }

    /** Whether {@link #lightTextureId} would build or upload the light texture. */
    public boolean lightNeedsUpload() {
        return litColumns > 0 && (lightTexture == null || lightDirty);
    }

    /**
     * Texture of the lit pixels: tile colour with alpha from the block light level, slightly warmed. Drawn over the
     * night-darkened map it brings back the day colours around torches, lava etc. -1 if none / not yet uploaded.
     */
    public int lightTextureId(boolean allowUpload) {
        RenderSystem.assertOnRenderThread();
        if (litColumns <= 0) return -1;
        if (lightTexture != null && !lightDirty) return lightTexture.getId();
        if (!allowUpload) return lightTexture == null ? -1 : lightTexture.getId();
        if (lightImage == null) lightImage = new NativeImage(SIZE, SIZE, true);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int lv = light[idx(x, z)];
                if (lv <= 0) {
                    lightImage.setPixelRGBA(x, z, 0);
                    continue;
                }
                int c = image.getPixelRGBA(x, z); // ABGR
                float a = LIGHT_ALPHA[Math.min(15, lv)] * ((c >>> 24) / 255f);
                int r = c & 0xFF, gr = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
                float warm = 0.26f * a; // torch-light warmth
                r = (int) (r + (255 - r) * warm);
                gr = (int) (gr + (214 - gr) * warm * 0.8f);
                b = (int) (b * (1 - warm * 0.5f));
                lightImage.setPixelRGBA(x, z, ((int) (a * 255) << 24) | (b << 16) | (gr << 8) | r);
            }
        }
        if (lightTexture == null) {
            lightTexture = new DynamicTexture(lightImage) {
                @Override
                public void close() {
                    releaseId();
                }
            };
            lightTexture.bind();
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } else {
            lightTexture.upload();
        }
        lightDirty = false;
        return lightTexture.getId();
    }

    /** Light level 0..15 to overlay opacity: gentle falloff, like in-game (precomputed; the overlay has 65536 pixels). */
    private static final float[] LIGHT_ALPHA = new float[16];

    static {
        for (int lv = 0; lv < 16; lv++) LIGHT_ALPHA[lv] = (float) Math.pow(Math.min(1f, lv / 12f), 0.9f);
    }

    public void setLightFilter(boolean blur) {
        if (lightTexture != null) lightTexture.setFilter(blur, false);
    }

    /** Frees GPU and native memory. The tile must not be used afterwards. */
    void release() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (lightTexture != null) {
            lightTexture.close();
            lightTexture = null;
        }
        if (lightImage != null) {
            lightImage.close();
            lightImage = null;
        }
        image.close();
    }

    /** Deep copy of the persistent data for asynchronous saving. */
    TileSnapshot snapshot() {
        NativeImage copy = new NativeImage(SIZE, SIZE, false);
        copy.copyFrom(image);
        return new TileSnapshot(tx, tz, copy, heights.clone(), flags.clone(), light.clone(), chunkState.clone(), chunkBiome.clone(),
                java.util.Collections.unmodifiableList(new ArrayList<>(biomePalette)));
    }

    record TileSnapshot(int tx, int tz, NativeImage image, short[] heights, byte[] flags, byte[] light, byte[] chunkState,
                        short[] chunkBiome, List<ResourceKey<Biome>> biomePalette) {}
}
