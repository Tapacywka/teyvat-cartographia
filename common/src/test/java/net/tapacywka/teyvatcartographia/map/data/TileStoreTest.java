package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import java.io.UncheckedIOException;

/**
 * A store reads its folder on the IO thread, behind the writes queued before it: read at once, it missed a region the
 * store closed just before was still saving, started that region anew and overwrote it (audit 06.11.2026).
 */
class TileStoreTest {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final CountDownLatch gate = new CountDownLatch(1);

    @AfterEach
    void stop() throws InterruptedException {
        gate.countDown();
        io.shutdownNow();
        // Before JUnit removes the temporary folder: a store still opening writes into it.
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
    }

    /** Holds the IO thread until {@link #gate} opens, as a long save would. */
    private void holdIo() {
        io.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void finishIo() throws Exception {
        gate.countDown();
        io.submit(() -> {}).get();
    }

    private static void writeRegion(Path dir, int tx, int tz, int chunks) throws IOException {
        Files.createDirectories(dir);
        String base = "r." + tx + "." + tz;
        Files.writeString(dir.resolve(base + ".png"), "pixels");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dir.resolve(base + ".dat"))))) {
            out.writeInt(0x54594D31);
            out.writeInt(MapStorage.VERSION);
        }
        Files.writeString(dir.resolve("index.txt"), tx + " " + tz + " " + chunks + "\n");
    }

    @Test
    void aRegionStillBeingSavedIsKnownOnceTheStoreIsOpen(@TempDir Path root) throws Exception {
        Path dir = root.resolve("minecraft_overworld");
        holdIo();
        io.execute(() -> {
            try {
                writeRegion(dir, 2, -3, 5); // the save of the store closed just before
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        TileStore store = new TileStore(DIMENSION, dir, io, 1);
        store.drainLoaded();
        assertFalse(store.opened());
        assertFalse(store.exists(2, -3));

        finishIo();
        store.drainLoaded();
        assertTrue(store.opened());
        assertTrue(store.exists(2, -3));
        assertEquals(5, store.exploredChunks());
        assertEquals("minecraft:overworld", Files.readString(dir.resolve("dimension.txt")));
    }

    @Test
    void chunksWaitForTheStoreToOpen(@TempDir Path root) {
        holdIo();
        TileStore store = new TileStore(DIMENSION, root.resolve("map"), io, 1);
        ChunkShader.Result chunk = new ChunkShader.Result(0, 0, new int[256], new short[256], new byte[256], new byte[256], 0, null, false);
        assertEquals(0, store.apply(chunk));
        assertEquals(0, store.regions());
    }

    @Test
    void aStoreClosedBeforeItOpensLeavesItsFolderAlone(@TempDir Path root) throws Exception {
        Path dir = root.resolve("map");
        holdIo();
        TileStore store = new TileStore(DIMENSION, dir, io, 1);
        store.close();
        finishIo();
        store.drainLoaded();
        assertFalse(store.opened());
        assertFalse(Files.exists(dir));
    }

    @Test
    void aMapFolderIsNamedAfterItsDimension(@TempDir Path root) throws IOException {
        Path dir = root.resolve("new").resolve("minecraft_overworld");
        MapStorage.writeDimension(dir, DIMENSION);
        assertEquals("minecraft:overworld", Files.readString(dir.resolve("dimension.txt")));
    }
}
