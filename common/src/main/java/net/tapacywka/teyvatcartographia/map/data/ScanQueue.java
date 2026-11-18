package net.tapacywka.teyvatcartographia.map.data;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Which chunks wait to be mapped ({@link MapDataManager} takes them to the workers): each chunk at most once in the
 * queue, those around the player first when asked, and when each was last mapped. A rolling sweep over the loaded
 * circle around the player, nearest first, adds the chunks not mapped this session or mapped more than
 * {@link #STALE_MS} ago. Chunks by {@link ChunkPos#asLong}. No game, one thread.
 */
final class ScanQueue {
    /** A chunk mapped longer ago than this is mapped again when the sweep comes by. */
    static final long STALE_MS = 90_000;
    /** The sweep adds nothing while this many wait already. */
    static final int SWEEP_WHILE_UNDER = 256;
    /** Places of the circle the sweep looks at per tick, at most. */
    static final int SWEEP_CHECKS = 128;

    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final Long2LongOpenHashMap lastScan = new Long2LongOpenHashMap();
    private int sweepCursor;
    private int sweepRadius = -1;
    private int[] sweepOffsets = new int[0];

    /** Adds a chunk unless it waits already; {@code first} puts it before the others. */
    void add(int cx, int cz, boolean first) {
        long k = ChunkPos.asLong(cx, cz);
        if (!queued.add(k)) return;
        if (first) queue.enqueueFirst(k);
        else queue.enqueue(k);
    }

    int size() {
        return queue.size();
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Takes the next chunk out of the queue. */
    long next() {
        long k = queue.dequeueLong();
        queued.remove(k);
        return k;
    }

    /** The chunk was handed to a worker at {@code now}. */
    void scanned(long chunk, long now) {
        lastScan.put(chunk, now);
    }

    /** The chunk could not be read: the sweep takes it again. */
    void failed(long chunk) {
        lastScan.remove(chunk);
    }

    /** Every chunk counts as not mapped this session, the sweep starts again from the nearest. */
    void remapAll() {
        lastScan.clear();
        sweepCursor = 0;
    }

    /** Forgets everything (another world or dimension). */
    void clear() {
        queue.clear();
        queued.clear();
        lastScan.clear();
        sweepCursor = 0;
    }

    /** Forgets when chunks farther than {@code limit} chunks from the player were mapped, so the history stays small. */
    void prune(int pcx, int pcz, int limit) {
        lastScan.keySet().removeIf((long k) -> Math.abs(ChunkPos.getX(k) - pcx) > limit || Math.abs(ChunkPos.getZ(k) - pcz) > limit);
    }

    /**
     * One tick of the sweep over the circle of {@code radius} chunks around the player: up to {@link #SWEEP_CHECKS}
     * places from where it stopped, adding the loaded ones ({@code loaded}) not mapped this session or stale at
     * {@code now}.
     */
    void sweep(int pcx, int pcz, int radius, long now, LongPredicate loaded) {
        if (radius != sweepRadius) buildSweep(radius);
        int places = sweepOffsets.length / 2;
        if (places == 0 || queue.size() > SWEEP_WHILE_UNDER) return;
        int checks = Math.min(places, SWEEP_CHECKS);
        for (int i = 0; i < checks; i++) {
            sweepCursor = (sweepCursor + 1) % places;
            int cx = pcx + sweepOffsets[sweepCursor * 2], cz = pcz + sweepOffsets[sweepCursor * 2 + 1];
            long k = ChunkPos.asLong(cx, cz);
            long last = lastScan.getOrDefault(k, 0L);
            if (last != 0L && now - last < STALE_MS) continue;
            if (!loaded.test(k)) continue;
            add(cx, cz, false);
        }
    }

    /** The places of the circle, nearest first. */
    private void buildSweep(int radius) {
        sweepRadius = radius;
        List<int[]> list = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dz * dz <= radius * radius) list.add(new int[]{dx, dz});
        }
        list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        sweepOffsets = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            sweepOffsets[i * 2] = list.get(i)[0];
            sweepOffsets[i * 2 + 1] = list.get(i)[1];
        }
        sweepCursor = 0;
    }
}
