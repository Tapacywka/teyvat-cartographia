package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queue of chunks to map and its sweep, without a game. */
class ScanQueueTest {
    private static long k(int cx, int cz) {
        return ChunkPos.pack(cx, cz);
    }

    private static List<Long> drain(ScanQueue q) {
        List<Long> out = new ArrayList<>();
        while (!q.isEmpty()) out.add(q.next());
        return out;
    }

    @Test
    void eachChunkOnceFirstOnesBeforeTheOthers() {
        ScanQueue q = new ScanQueue();
        q.add(1, 0, false);
        q.add(2, 0, false);
        q.add(1, 0, false);
        q.add(1, 0, true);
        q.add(3, 0, true);
        assertEquals(3, q.size(), "a chunk already waiting is not added again, even as a first one");
        assertEquals(List.of(k(3, 0), k(1, 0), k(2, 0)), drain(q));
        q.add(1, 0, false);
        assertEquals(1, q.size(), "taken out, it can come in again");
    }

    @Test
    void sweepTakesTheLoadedNotMappedNearestFirst() {
        ScanQueue q = new ScanQueue();
        q.sweep(10, 10, 2, 1_000, c -> true);
        List<Long> got = drain(q);
        assertEquals(13, got.size(), "the circle of radius 2 has 13 chunks");
        assertEquals(k(10, 10) , got.get(got.size() - 1), "the cursor starts past the middle, which comes once round");
        int last = -1;
        for (int i = 0; i < got.size() - 1; i++) {
            int dx = ChunkPos.getX(got.get(i)) - 10, dz = ChunkPos.getZ(got.get(i)) - 10;
            assertTrue(dx * dx + dz * dz >= last, "nearest first");
            last = dx * dx + dz * dz;
        }
    }

    @Test
    void sweepSkipsUnloadedAndFreshAndTakesStale() {
        ScanQueue q = new ScanQueue();
        long now = 1_000_000;
        // Everything mapped just now but (1, 0), mapped longer ago than STALE_MS; (0, 1) not loaded.
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) q.scanned(k(dx, dz), now);
        q.scanned(k(1, 0), now - ScanQueue.STALE_MS - 1);
        q.failed(k(-1, 0));
        q.sweep(0, 0, 1, now, c -> c != k(0, 1));
        List<Long> got = drain(q);
        assertTrue(got.contains(k(1, 0)), "stale");
        assertTrue(got.contains(k(-1, 0)), "could not be read, so not mapped");
        assertEquals(2, got.size(), "fresh ones and the unloaded one stay out: " + got);
        q.remapAll();
        q.sweep(0, 0, 1, now, c -> c != k(0, 1));
        assertEquals(4, q.size(), "after remapAll every loaded one of the 5 comes again");
    }

    @Test
    void sweepWaitsWhileTheQueueIsLong() {
        ScanQueue q = new ScanQueue();
        for (int i = 0; i <= ScanQueue.SWEEP_WHILE_UNDER; i++) q.add(1000 + i, 0, false);
        int before = q.size();
        q.sweep(0, 0, 3, 1_000, c -> true);
        assertEquals(before, q.size());
    }

    @Test
    void pruneForgetsOnlyFarChunks() {
        ScanQueue q = new ScanQueue();
        long now = 1_000_000;
        q.scanned(k(0, 0), now);
        q.scanned(k(50, 0), now);
        q.prune(0, 0, 10);
        q.sweep(0, 0, 0, now, c -> true);
        assertEquals(0, q.size(), "the near one is still known as fresh");
        q.sweep(50, 0, 0, now, c -> true);
        assertEquals(List.of(k(50, 0)), drain(q), "the far one was forgotten, so it counts as not mapped");
    }

    @Test
    void clearForgetsEverything() {
        ScanQueue q = new ScanQueue();
        q.add(5, 5, false);
        q.scanned(k(0, 0), 1_000);
        q.clear();
        assertEquals(0, q.size());
        q.sweep(0, 0, 0, 1_000, c -> true);
        assertEquals(1, q.size(), "the chunk mapped before is not known after clear");
    }
}
