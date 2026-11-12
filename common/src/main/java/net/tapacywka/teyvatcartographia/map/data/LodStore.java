package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Level of detail for far zoom: one 256 x 256 texture per 4 x 4 regions (1024 x 1024 blocks, one pixel per 4 x 4
 * blocks), built from the regions' thumbnails ({@link MapStorage#loadThumbnail}). Far out the map draws a few of these
 * instead of hundreds of full regions, so it neither loads every region from disk nor fills the GPU.
 * Thumbnails of regions on disk are read by the IO thread; regions in memory are downsampled on the main thread, a few
 * per frame, and of a region that changes while mapped only its changed chunks. Main/render thread only, apart from the
 * IO tasks.
 */
public final class LodStore {
    /** Regions per side of one LOD tile. */
    public static final int REGIONS = 4;
    /** Blocks per side of one LOD tile. */
    public static final int BLOCKS = MapTile.SIZE * REGIONS;
    private static final int CELL = MapStorage.THUMB;
    private static final int MAX_TILES = 96;
    /** Chunks redrawn a frame from regions in memory: four whole cells (0.15 ms each), or many more changed chunks. */
    private static final int MEMORY_CHUNKS_PER_FRAME = 4 * MapTile.CHUNKS * MapTile.CHUNKS;
    private static final int CHUNK_WORDS = MapTile.CHUNKS * MapTile.CHUNKS / 64;

    private final TileStore store;
    private final Long2ObjectOpenHashMap<LodTile> tiles = new Long2ObjectOpenHashMap<>();
    /** Thumbnails read by the IO thread ({@code image} null when there is none). */
    private record Thumbnail(LodTile tile, int cell, NativeImage image) {}

    private final ConcurrentLinkedQueue<Thumbnail> loaded = new ConcurrentLinkedQueue<>();
    /** Regions (tile keys) whose cell is to be redrawn from the region in memory, in turn. */
    private final LongArrayFIFOQueue memoryQueue = new LongArrayFIFOQueue();
    /**
     * Their chunks to redraw, a bit each ({@link MapStorage#thumbnailChunks}), all of them for a whole cell: a changed
     * chunk redrew the region's whole thumbnail until 09.11.2026, 2% of the game's thread while mapping at far zoom.
     */
    private final Long2ObjectOpenHashMap<long[]> memoryDirty = new Long2ObjectOpenHashMap<>();
    private final CellCopy cellCopy = new CellCopy();

    /** Reads a region's picture and writes its cell of a LOD tile; one, reused with its buffers, so a frame allocates nothing. */
    private static final class CellCopy implements MapStorage.ChunkPixels {
        final int[] chunk = new int[MapStorage.CHUNK_PIXELS], thumb = new int[MapStorage.CHUNK_THUMB * MapStorage.CHUNK_THUMB];
        NativeImage src, dst;
        int x0, y0;

        @Override
        public void read(int lcx, int lcz, int[] into) {
            MapStorage.readChunk(src, lcx, lcz, into);
        }

        @Override
        public void write(int lcx, int lcz, int[] thumb) {
            int side = MapStorage.CHUNK_THUMB, px = x0 + lcx * side, py = y0 + lcz * side;
            for (int ty = 0; ty < side; ty++) {
                for (int tx = 0; tx < side; tx++) dst.setPixelRGBA(px + tx, py + ty, thumb[ty * side + tx]);
            }
        }
    }

    /** The chunk bits of region {@code rk} waiting to be redrawn, the region queued if it was not. */
    private long[] memoryBits(long rk) {
        long[] bits = memoryDirty.get(rk);
        if (bits == null) {
            memoryDirty.put(rk, bits = new long[CHUNK_WORDS]);
            memoryQueue.enqueue(rk);
        }
        return bits;
    }
    /** Tiles whose outline is to be worked out. */
    private final LongOpenHashSet edgeStale = new LongOpenHashSet();
    private volatile boolean closed;

    /** Thumbnails on disk are read through the store's {@link TileStore#order}, behind a write of their region. */
    LodStore(TileStore store) {
        this.store = store;
    }

    /** One LOD texture covering {@link #REGIONS} x {@link #REGIONS} regions. */
    public static final class LodTile {
        public final int lx, lz;
        final long key;
        final NativeImage image = new NativeImage(MapTile.SIZE, MapTile.SIZE, true);
        private DynamicTexture texture;
        boolean gpuDirty = true;
        /** Its outline: the saved thumbnails know nothing of it. */
        final EdgeMask edge = new EdgeMask(() -> gpuDirty = true);
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
            return pending == 0 && edge.ready();
        }

        public boolean needsUpload() {
            return texture == null || gpuDirty;
        }

        /** The GL texture id (linear filtering), uploading pending changes if {@code allowUpload}; -1 if not available yet. */
        public int textureId(boolean allowUpload) {
            RenderSystem.assertOnRenderThread();
            if (!edge.ready()) allowUpload = false;
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
                if (edge.baked()) edge.over(image).upload(0, 0, 0, false);
                edge.uploadField();
                gpuDirty = false;
            } else if (gpuDirty && allowUpload) {
                if (edge.baked()) {
                    texture.bind();
                    edge.over(image).upload(0, 0, 0, false);
                } else {
                    texture.upload();
                }
                edge.uploadField();
                gpuDirty = false;
            }
            return texture.getId();
        }

        /** Whether {@link net.tapacywka.teyvatcartographia.render.EdgeShader} draws this tile's outline, from {@link #edgeFieldId}. */
        public boolean edgeByShader() {
            return edge.byShader();
        }

        public int edgeFieldId() {
            return edge.fieldId();
        }

        /** What {@link net.tapacywka.teyvatcartographia.render.EdgeShader} draws of the outline. */
        public int edgeMode() {
            return edge.shaderMode();
        }

        void release() {
            if (texture != null) texture.close();
            texture = null;
            edge.free();
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
            if (store.outlines()) {
                t.edge.waitForFirst();
                edgeStale.add(key);
            }
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
            Arrays.fill(memoryBits(MapView.tileKey(tx, tz)), -1L); // the whole cell
            return;
        }
        store.order.read(MapView.tileKey(tx, tz), () -> {
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
            loaded.add(new Thumbnail(t, cell, thumb));
            // Closed while this one was read: nobody drains the queue any more, so its image is freed here.
            if (closed) discardLoaded();
        });
    }

    /** A chunk of a region in memory changed: its part of the region's cell is redrawn if the LOD tile exists. */
    void chunkChanged(int cx, int cz) {
        int tx = MapView.chunkToTile(cx), tz = MapView.chunkToTile(cz);
        LodTile t = tiles.get(MapView.tileKey(lodOf(tx), lodOf(tz)));
        if (t == null) return;
        t.pending |= 1 << cellIndex(tx, tz);
        int i = (cz & (MapTile.CHUNKS - 1)) * MapTile.CHUNKS + (cx & (MapTile.CHUNKS - 1));
        memoryBits(MapView.tileKey(tx, tz))[i >> 6] |= 1L << (i & 63);
    }

    // ------------------------------------------------------------------ outline

    /** The tiles a chunk reaches with its outline have theirs worked out again (a field's border reaches a chunk farther). */
    void edgesAround(int cx, int cz) {
        int n = REGIONS * MapTile.CHUNKS;
        for (int lz = Math.floorDiv(cz - MapOutline.FIELD_RING, n); lz <= Math.floorDiv(cz + MapOutline.FIELD_RING, n); lz++) {
            for (int lx = Math.floorDiv(cx - MapOutline.FIELD_RING, n); lx <= Math.floorDiv(cx + MapOutline.FIELD_RING, n); lx++) {
                long key = MapView.tileKey(lx, lz);
                if (tiles.containsKey(key)) edgeStale.add(key);
            }
        }
    }

    /** The outline went on or off: every tile follows. */
    void switchEdges(boolean on) {
        for (LodTile t : tiles.values()) {
            if (on) {
                t.edge.again();
                edgeStale.add(t.key);
            } else {
                t.edge.clear();
            }
        }
        if (!on) edgeStale.clear();
    }

    /** A tile not drawn for this long keeps its outline waiting: exploring with the map closed, nothing needs it. */
    private static final long SHOWN_MS = 2000;

    private static boolean shown(LodTile t) {
        return System.currentTimeMillis() - t.lastUsedMs < SHOWN_MS;
    }

    /** Whether no tile on the map waits for its outline. */
    boolean edgesDone() {
        for (LongIterator it = edgeStale.iterator(); it.hasNext(); ) {
            LodTile t = tiles.get(it.nextLong());
            if (t != null && shown(t)) return false;
        }
        return true;
    }

    /** Hands the tiles waiting to the thread that works the edges out, as many as it takes. */
    void askEdges() {
        int n = REGIONS * MapTile.CHUNKS;
        for (LongIterator it = edgeStale.iterator(); it.hasNext(); ) {
            LodTile t = tiles.get(it.nextLong());
            if (t == null) {
                it.remove();
            } else if (!t.edge.busy() && shown(t)) {
                if (!store.askEdge(t.edge, t.lx * n, t.lz * n, n, MapStorage.THUMB / MapTile.CHUNKS)) return;
                it.remove();
            }
        }
    }

    /** Applies thumbnails read by the IO thread and redraws a few cells from regions in memory. Once per frame. */
    public void update() {
        Thumbnail e;
        while ((e = loaded.poll()) != null) {
            LodTile t = e.tile();
            NativeImage thumb = e.image();
            if (!closed && tiles.get(t.key) == t) blit(t, e.cell(), thumb);
            if (thumb != null) thumb.close();
        }
        for (int budget = MEMORY_CHUNKS_PER_FRAME; budget > 0 && !memoryQueue.isEmpty(); ) {
            long rk = memoryQueue.dequeueLong();
            long[] bits = memoryDirty.remove(rk);
            int tx = MapView.tileKeyX(rk), tz = MapView.tileKeyZ(rk);
            LodTile t = tiles.get(MapView.tileKey(lodOf(tx), lodOf(tz)));
            if (t == null || bits == null) continue;
            MapTile region = store.peek(tx, tz);
            if (region == null) {
                requestCell(t, tx, tz); // evicted meanwhile: its thumbnail is on disk now
                continue;
            }
            int cell = cellIndex(tx, tz);
            CellCopy copy = cellCopy;
            copy.src = region.image;
            copy.dst = t.image;
            copy.x0 = (cell % REGIONS) * CELL;
            copy.y0 = (cell / REGIONS) * CELL;
            budget -= Math.max(1, MapStorage.thumbnailChunks(bits, copy, copy.chunk, copy.thumb));
            copy.src = copy.dst = null;
            t.pending &= ~(1 << cell);
            t.gpuDirty = true;
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

    /** Frees the thumbnails nobody will take in any more (also from the IO thread, after the store closed). */
    private void discardLoaded() {
        Thumbnail e;
        while ((e = loaded.poll()) != null) if (e.image() != null) e.image().close();
    }

    void close() {
        closed = true;
        discardLoaded();
        for (LodTile t : tiles.values()) t.release();
        tiles.clear();
        memoryQueue.clear();
        memoryDirty.clear();
        edgeStale.clear();
    }
}
