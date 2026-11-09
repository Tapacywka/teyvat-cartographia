package net.tapacywka.teyvatcartographia.map.data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Which thread reads a region of a store (or a file of it): the reading thread, unless a write of that region is queued
 * or running on the writing thread; then that one, behind the write. One IO thread did both until 09.11.2026, so a
 * region the map asked for waited behind every save queued before it (15 ms a region, half a second for 36, profile
 * 09.11.2026). A region is written only while it is in memory and read only while it is not, so a read sent to the
 * reading thread never meets a write of its own; a read sent while a write waits goes behind it, as on one thread (a
 * region read before its save was done lost what was new, audit 06.11.2026). Everything else of the folder (opening it,
 * the index) stays on the writing thread, behind the writes.
 */
final class IoOrder {
    private final Executor writes, reads;
    /** Writes queued or running, by region. */
    private final ConcurrentHashMap<Long, Integer> writing = new ConcurrentHashMap<>();

    IoOrder(Executor writes, Executor reads) {
        this.writes = writes;
        this.reads = reads;
    }

    /** Queues a write of region {@code key} on the writing thread. */
    void write(long key, Runnable task) {
        writing.merge(key, 1, Integer::sum);
        try {
            writes.execute(() -> {
                try {
                    task.run();
                } finally {
                    done(key);
                }
            });
        } catch (RuntimeException e) {
            done(key); // not queued (the thread is shut down)
            throw e;
        }
    }

    /** Queues a read of region {@code key}: behind its writes when there are any, else on the reading thread. */
    void read(long key, Runnable task) {
        (writing.containsKey(key) ? writes : reads).execute(task);
    }

    /** Whether a write of region {@code key} is queued or running. */
    boolean writing(long key) {
        return writing.containsKey(key);
    }

    private void done(long key) {
        writing.computeIfPresent(key, (k, n) -> n > 1 ? n - 1 : null);
    }
}
