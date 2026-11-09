package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reads of regions apart from writes ({@link IoOrder}, 09.11.2026): a region the map asks for no longer waits behind
 * every save, but one whose own save is queued is still read after it (a read before its save lost what was new).
 */
class IoOrderTest {
    private final ExecutorService writes = Executors.newSingleThreadExecutor(), reads = Executors.newSingleThreadExecutor();
    private final CountDownLatch gate = new CountDownLatch(1);
    private final List<String> done = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() throws InterruptedException {
        gate.countDown();
        writes.shutdownNow();
        reads.shutdownNow();
        assertTrue(writes.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(reads.awaitTermination(10, TimeUnit.SECONDS));
    }

    /** A long save of region {@code key}: it ends when {@link #gate} opens. */
    private void slowWrite(IoOrder order, long key) {
        order.write(key, () -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.add("write " + key);
        });
    }

    @Test
    void anotherRegionIsReadWhileSavesWait() throws Exception {
        IoOrder order = new IoOrder(writes, reads);
        slowWrite(order, 1);
        CountDownLatch read = new CountDownLatch(1);
        order.read(2, () -> {
            done.add("read 2");
            read.countDown();
        });
        assertTrue(read.await(5, TimeUnit.SECONDS), "region 2 was read while region 1 was still being saved");
        assertEquals(List.of("read 2"), done);
    }

    @Test
    void aRegionBeingSavedIsReadAfterItsSave() throws Exception {
        IoOrder order = new IoOrder(writes, reads);
        slowWrite(order, 1);
        assertTrue(order.writing(1));
        CountDownLatch read = new CountDownLatch(1);
        order.read(1, () -> {
            done.add("read 1");
            read.countDown();
        });
        assertFalse(read.await(300, TimeUnit.MILLISECONDS), "not read before its save ended");
        gate.countDown();
        assertTrue(read.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("write 1", "read 1"), done);
    }

    @Test
    void theCountGoesWithTheLastWrite() throws Exception {
        IoOrder order = new IoOrder(writes, reads);
        slowWrite(order, 7);
        slowWrite(order, 7);
        assertTrue(order.writing(7));
        gate.countDown();
        writes.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertFalse(order.writing(7), "both saves done: reads go to the reading thread again");
    }

    @Test
    void aFailedWriteStillEnds() throws Exception {
        IoOrder order = new IoOrder(writes, reads);
        order.write(3, () -> {
            throw new IllegalStateException("disk full");
        });
        writes.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertFalse(order.writing(3));
    }

    @Test
    void oneThreadForBothIsTheOldOrder() throws Exception {
        IoOrder order = new IoOrder(writes, writes);
        slowWrite(order, 1);
        order.read(2, () -> done.add("read 2"));
        gate.countDown();
        writes.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("write 1", "read 2"), done);
    }
}
