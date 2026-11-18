package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.render.EdgeShader;
import net.tapacywka.teyvatcartographia.render.FogTexture;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The outline of a {@link TileStore}'s pictures ("Map edges", {@link MapOutline}): which regions wait for theirs, the
 * regions with nothing explored beside explored ones that get the part coming into them ({@link EdgeOnly}), the work
 * handed to one thread of its own and taken back. It follows the setting and whether the shader draws the outline
 * ({@link EdgeShader#inUse}); its store's far tiles ({@link LodStore}) ask it too. Main thread, but the thread's work.
 */
final class EdgeQueue {
    /** Worked out away from the frames, one picture at a time: a few milliseconds each. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TeyvatCartographia-Edges");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    /** Pictures handed to the thread at a time. */
    private static final int MAX_JOBS = 8;

    private record Done(EdgeMask mask, long[] grid, MapOutline.Picture picture, MapOutline.Field field, boolean shader) {}

    private final TileStore store;
    private boolean on;
    /** Whether the outlines are made for {@link EdgeShader} ({@link EdgeShader#inUse}). */
    private boolean byShader;
    /** Regions whose outline is to be worked out. */
    private final LongOpenHashSet stale = new LongOpenHashSet();
    /** Regions with nothing explored beside explored ones: the outline that comes into them ({@link EdgeOnly}), and those to work out. */
    private final Long2ObjectOpenHashMap<EdgeOnly> edgeOnly = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet edgeOnlyStale = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();
    private int jobs;

    /** {@code on}: whether the store starts with the outline (an outlined store and the setting on). */
    EdgeQueue(TileStore store, boolean on) {
        this.store = store;
        this.on = on;
    }

    /** Whether the pictures of the store have the outline now. */
    boolean on() {
        return on;
    }

    /** Whether nothing waits or is being worked out (the regions' own pictures and the far tiles are asked by the store). */
    boolean idle() {
        return stale.isEmpty() && edgeOnlyStale.isEmpty() && jobs == 0;
    }

    /** The outline that comes into a region with nothing explored from the land around it, or null ({@link EdgeOnly}). */
    EdgeOnly edgeOnly(int tx, int tz) {
        if (edgeOnly.isEmpty()) return null;
        EdgeOnly e = edgeOnly.get(MapView.tileKey(tx, tz));
        return e != null && e.shows() ? e : null;
    }

    /** A region came into memory: not shown before its outline is there. */
    void newRegion(MapTile t) {
        if (!on) return;
        t.edge.waitForFirst();
        stale.add(t.key);
        edgeOnlyAround(t.tx, t.tz);
    }

    /** The regions with nothing explored around a region have the outline that may come into them worked out. */
    private void edgeOnlyAround(int tx, int tz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) edgeOnlyMaybe(MapView.tileKey(tx + dx, tz + dz));
        }
    }

    private void edgeOnlyMaybe(long key) {
        // Over floating land only land gets the line, and no glow: nothing to show where nothing is explored.
        if (!store.inMemory(key) && !store.onDisk(key) && !FogTexture.shadow(store.dimension)) edgeOnlyStale.add(key);
    }

    /** Frees every {@link EdgeOnly}. */
    void dropEdgeOnly() {
        for (EdgeOnly e : edgeOnly.values()) e.close();
        edgeOnly.clear();
        edgeOnlyStale.clear();
    }

    /** A chunk came onto the map: the pictures it reaches have their outline worked out again (a field's border reaches a chunk farther). */
    void around(int cx, int cz) {
        if (!on) return;
        for (int tz = MapView.chunkToTile(cz - MapOutline.FIELD_RING); tz <= MapView.chunkToTile(cz + MapOutline.FIELD_RING); tz++) {
            for (int tx = MapView.chunkToTile(cx - MapOutline.FIELD_RING); tx <= MapView.chunkToTile(cx + MapOutline.FIELD_RING); tx++) {
                long key = MapView.tileKey(tx, tz);
                if (store.inMemory(key)) stale.add(key);
                else edgeOnlyMaybe(key);
            }
        }
        store.lod().edgesAround(cx, cz);
    }

    /** Follows the setting and whether the shader draws the outline. Main thread, an outlined store open. */
    void follow() {
        boolean want = MapConfig.get(MapConfig.MAP_OUTLINE);
        if (want != on) switchTo(want);
        // The shader came or went (the resources were loaded again, or Map edges went between animated and not): every
        // outline is made again for the other way.
        if (on && EdgeShader.inUse() != byShader) switchTo(true);
    }

    /** Takes in what the thread finished and hands it what waits. Main thread, after {@link #follow}. */
    void update() {
        Done d;
        while ((d = done.poll()) != null) {
            jobs--;
            if (!on) d.mask.ignored();
            else if (d.mask.set(d.grid, d.picture, d.field, d.shader)) store.changed();
        }
        if (!on || !store.opened()) return;
        for (LongIterator it = stale.iterator(); it.hasNext(); ) {
            MapTile t = store.tile(it.nextLong());
            if (t == null) {
                it.remove();
            } else if (!t.edge.busy()) {
                if (!ask(t.edge, t.tx * MapTile.CHUNKS, t.tz * MapTile.CHUNKS, MapTile.CHUNKS, 16, false)) return;
                it.remove();
            }
        }
        for (LongIterator it = edgeOnlyStale.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            int tx = MapView.tileKeyX(key), tz = MapView.tileKeyZ(key);
            EdgeOnly e = edgeOnly.get(key);
            if (store.inMemory(key) || store.onDisk(key) || !besideInMemory(tx, tz)) {
                it.remove();
            } else {
                if (e == null) edgeOnly.put(key, e = new EdgeOnly(tx, tz));
                if (e.edge.busy()) continue;
                if (!ask(e.edge, tx * MapTile.CHUNKS, tz * MapTile.CHUNKS, MapTile.CHUNKS, 16, true)) return;
                it.remove();
            }
        }
        // Those that show nothing, now have a region of their own, or no region beside them in memory any more go.
        if (!edgeOnly.isEmpty()) {
            for (var it = edgeOnly.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                EdgeOnly e = entry.getValue();
                long key = entry.getLongKey();
                if (e.edge.busy() || edgeOnlyStale.contains(key)) continue;
                if (e.shows() && !store.inMemory(key) && !store.onDisk(key) && besideInMemory(e.tx, e.tz)) continue;
                if (e.shows()) store.changed();
                e.close();
                it.remove();
            }
        }
        store.lod().askEdges();
    }

    /** Whether a region beside (tx, tz), the corners too, is in memory. */
    private boolean besideInMemory(int tx, int tz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) if ((dx != 0 || dz != 0) && store.inMemory(MapView.tileKey(tx + dx, tz + dz))) return true;
        }
        return false;
    }

    private void switchTo(boolean want) {
        on = want;
        byShader = EdgeShader.inUse();
        for (MapTile t : store.tiles()) {
            if (want) {
                t.edge.again();
                stale.add(t.key);
                edgeOnlyAround(t.tx, t.tz);
            } else {
                t.edge.clear();
            }
        }
        if (want) {
            for (EdgeOnly e : edgeOnly.values()) e.edge.again();
        } else {
            stale.clear();
            dropEdgeOnly();
        }
        store.lod().switchEdges(want);
        store.changed();
    }

    /**
     * Has the outline of a picture worked out if the chunks around it changed; {@code empty}: a region with nothing
     * explored ({@link EdgeOnly}), which has an outline only where it comes in.
     *
     * @return false while the thread has enough to do: the picture stays waiting
     */
    boolean ask(EdgeMask mask, int cx0, int cz0, int chunks, int pixels, boolean empty) {
        mask.landOnly(FogTexture.shadow(store.dimension));
        // The shader's field has a border from the chunks next to the picture: its grid has a ring a chunk wider.
        boolean shader = EdgeShader.inUse();
        int ring = shader ? MapOutline.FIELD_RING : MapOutline.RING;
        long[] grid = grid(cx0, cz0, chunks, ring);
        if (mask.madeFrom(grid)) return true;
        // Land all around is the most of a map, and explored land on one side only the most beside an empty region:
        // nothing to wait for.
        if (MapOutline.whole(grid, chunks + 2 * ring) || empty && MapOutline.oneSide(grid, chunks, ring)) {
            if (mask.set(grid, null, null, shader)) store.changed();
            return true;
        }
        if (jobs >= MAX_JOBS) return false;
        jobs++;
        mask.started();
        WORKER.execute(() -> {
            MapOutline.Picture picture = null;
            MapOutline.Field field = null;
            try {
                if (shader) {
                    field = MapOutline.field(cx0, cz0, chunks, pixels, grid);
                    if (empty && field != null && !field.reaches()) field = null;
                } else {
                    picture = MapOutline.picture(cx0, cz0, chunks, pixels, grid);
                }
            } catch (RuntimeException e) {
                TeyvatCartographia.LOGGER.warn("Could not work out the outline at chunk {},{}", cx0, cz0, e);
            }
            done.add(new Done(mask, grid, picture, field, shader));
        });
        return true;
    }

    /**
     * The explored chunks of a square from (cx0, cz0) and the {@code ring} around it, as {@link MapOutline#picture}
     * ({@link MapOutline#RING}) or {@link MapOutline#field} ({@link MapOutline#FIELD_RING}) takes them.
     */
    private long[] grid(int cx0, int cz0, int chunks, int ring) {
        int side = chunks + 2 * ring;
        long[] grid = MapOutline.grid(side);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if (store.explored(cx0 + x - ring, cz0 + z - ring)) MapOutline.set(grid, z * side + x);
            }
        }
        return grid;
    }
}
