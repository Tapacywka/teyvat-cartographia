package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * Level of detail for far zoom: one 256 x 256 texture per 4 x 4 regions (1024 x 1024 blocks, one pixel per 4 x 4
 * blocks), built from the regions' thumbnails ({@link MapStorage#loadThumbnail}). Far out the map draws a few of these
 * instead of hundreds of full regions, so it neither loads every region from disk nor fills the GPU.
 * Thumbnails of regions on disk are read by the IO thread; regions in memory (and regions that change while mapped)
 * are downsampled on the main thread, a few per frame. Main/render thread only, apart from the IO tasks.
 */
public final class LodStore {
    /** Regions per side of one LOD tile. */
    public static final int REGIONS = 4;
    /** Blocks per side of one LOD tile. */
    public static final int BLOCKS = MapTile.SIZE * REGIONS;
    private static final int CELL = MapStorage.THUMB;
    private static final int MAX_TILES = 96;
    private static final int MEMORY_CELLS_PER_FRAME = 4;

    private final TileStore store;
    private final ExecutorService io;
    private final Long2ObjectOpenHashMap<LodTile> tiles = new Long2ObjectOpenHashMap<>();
    /** Thumbnails read by the IO thread: {LodTile, cell index, NativeImage or null}. */
    private final ConcurrentLinkedQueue<Object[]> loaded = new ConcurrentLinkedQueue<>();
    /** Regions (tile keys) whose cell is to be redrawn from the region in memory. */
    private final LongArrayFIFOQueue memoryQueue = new LongArrayFIFOQueue();
    private final LongOpenHashSet memoryQueued = new LongOpenHashSet();
    private volatile boolean closed;

    LodStore(TileStore store, ExecutorService io) {
        this.store = store;
        this.io = io;
    }

    /** One LOD texture covering {@link #REGIONS} x {@link #REGIONS} regions. */
    public static final class LodTile {
        public final int lx, lz;
        final long key;
        final NativeImage image = new NativeImage(MapTile.SIZE, MapTile.SIZE, true);
        private DynamicTexture texture;
        boolean gpuDirty = true;
        /** Bit per cell (region) still waiting for its thumbnail. */
        int pending;
        long lastUsedMs = System.currentTimeMillis();

        LodTile(int lx, int lz) {
            this.lx = lx;
            this.lz = lz;
            this.key = MapView.tileKey(lx, lz);
        }

        public int blockX0() {
            return lx * BLOCKS;
        }

        public int blockZ0() {
            return lz * BLOCKS;
        }

        /** Whether every existing region of this tile is in the picture. */
        public boolean complete() {
            return pending == 0;
        }

        public boolean needsUpload() {
            return texture == null || gpuDirty;
        }

        /** The GL texture id (linear filtering), uploading pending changes if {@code allowUpload}; -1 if not available yet. */
        public int textureId(boolean allowUpload) {
            RenderSystem.assertOnRenderThread();
            if (texture == null) {
                if (!allowUpload) return -1;
                texture = new DynamicTexture(image) {
                    @Override
                    public void close() {
                        releaseId(); // the image belongs to the tile
                    }
                };
                texture.setFilter(true, false);
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

        void release() {
            if (texture != null) texture.close();
            texture = null;
            image.close();
        }
    }

    private static int lodOf(int t) {
        return Math.floorDiv(t, REGIONS);
    }

    /** Whether any region of LOD tile (lx, lz) has map data. */
    public boolean any(int lx, int lz) {
        for (int i = 0; i < REGIONS * REGIONS; i++) {
            if (store.exists(lx * REGIONS + i % REGIONS, lz * REGIONS + i / REGIONS)) return true;
        }
        return false;
    }

    /** The LOD tile, created (and filled in the background) on first use. */
    public LodTile get(int lx, int lz) {
        long key = MapView.tileKey(lx, lz);
        LodTile t = tiles.get(key);
        if (t == null) {
            t = new LodTile(lx, lz);
            tiles.put(key, t);
            for (int i = 0; i < REGIONS * REGIONS; i++) {
                int tx = lx * REGIONS + i % REGIONS, tz = lz * REGIONS + i / REGIONS;
                if (store.exists(tx, tz)) requestCell(t, tx, tz);
            }
        }
        t.lastUsedMs = System.currentTimeMillis();
        return t;
    }

    private static int cellIndex(int tx, int tz) {
        return Math.floorMod(tz, REGIONS) * REGIONS + Math.floorMod(tx, REGIONS);
    }

    /** Fills one cell: from the region in memory if loaded (next frames), else from its thumbnail on disk. */
    private void requestCell(LodTile t, int tx, int tz) {
        int cell = cellIndex(tx, tz);
        t.pending |= 1 << cell;
        if (store.peek(tx, tz) != null) {
            long rk = MapView.tileKey(tx, tz);
            if (memoryQueued.add(rk)) memoryQueue.enqueue(rk);
            return;
        }
        io.execute(() -> {
            NativeImage thumb = null;
            try {
                thumb = MapStorage.loadThumbnail(store.dir, tx, tz);
            } catch (Throwable e) {
                TeyvatCartographia.LOGGER.debug("No map thumbnail for region {},{}", tx, tz, e);
            }
            if (closed) {
                if (thumb != null) thumb.close();
                return;
            }
            loaded.add(new Object[]{t, cell, thumb});
        });
    }

    /** A region in memory changed: its cell is redrawn if its LOD tile exists. */
    void regionChanged(int tx, int tz) {
        LodTile t = tiles.get(MapView.tileKey(lodOf(tx), lodOf(tz)));
        if (t == null) return;
        t.pending |= 1 << cellIndex(tx, tz);
        long rk = MapView.tileKey(tx, tz);
        if (memoryQueued.add(rk)) memoryQueue.enqueue(rk);
    }

    /** Applies thumbnails read by the IO thread and redraws a few cells from regions in memory. Once per frame. */
    public void update() {
        Object[] e;
        while ((e = loaded.poll()) != null) {
            LodTile t = (LodTile) e[0];
            NativeImage thumb = (NativeImage) e[2];
            if (!closed && tiles.get(t.key) == t) blit(t, (Integer) e[1], thumb);
            if (thumb != null) thumb.close();
        }
        for (int n = 0; n < MEMORY_CELLS_PER_FRAME && !memoryQueue.isEmpty(); n++) {
            long rk = memoryQueue.dequeueLong();
            memoryQueued.remove(rk);
            int tx = MapView.tileKeyX(rk), tz = MapView.tileKeyZ(rk);
            LodTile t = tiles.get(MapView.tileKey(lodOf(tx), lodOf(tz)));
            if (t == null) continue;
            MapTile region = store.peek(tx, tz);
            if (region == null) {
                requestCell(t, tx, tz); // evicted meanwhile: its thumbnail is on disk now
                continue;
            }
            try (NativeImage thumb = MapStorage.thumbnail(region.image)) {
                blit(t, cellIndex(tx, tz), thumb);
            }
        }
    }

    private static void blit(LodTile t, int cell, NativeImage thumb) {
        int x0 = (cell % REGIONS) * CELL, y0 = (cell / REGIONS) * CELL;
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) t.image.setPixelRGBA(x0 + x, y0 + y, thumb == null ? 0 : thumb.getPixelRGBA(x, y));
        }
        t.pending &= ~(1 << cell);
        t.gpuDirty = true;
    }

    /** Drops least recently used LOD tiles above the limit. */
    void evict() {
        if (tiles.size() <= MAX_TILES) return;
        List<LodTile> sorted = new ArrayList<>(tiles.values());
        sorted.sort((a, b) -> Long.compare(a.lastUsedMs, b.lastUsedMs));
        long now = System.currentTimeMillis();
        for (int i = 0; i < sorted.size() - MAX_TILES; i++) {
            LodTile t = sorted.get(i);
            if (now - t.lastUsedMs < 2000) break;
            tiles.remove(t.key);
            t.release();
        }
    }

    void close() {
        closed = true;
        Object[] e;
        while ((e = loaded.poll()) != null) if (e[2] != null) ((NativeImage) e[2]).close();
        for (LodTile t : tiles.values()) t.release();
        tiles.clear();
        memoryQueue.clear();
        memoryQueued.clear();
    }
}
