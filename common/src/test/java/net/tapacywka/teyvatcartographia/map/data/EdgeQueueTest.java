package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outline's queue follows the setting "Map edges" for the surface only, and with nothing to do it is idle. */
class EdgeQueueTest {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld"));

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @AfterEach
    void stop() throws InterruptedException {
        MapConfig.MAP_OUTLINE.reset();
        io.shutdownNow();
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void followsTheSettingOnTheSurface(@TempDir Path root) {
        MapConfig.MAP_OUTLINE.set(false);
        TileStore store = new TileStore(DIMENSION, root.resolve("map"), io, 1);
        assertFalse(store.outlines(), "off as the setting");
        assertTrue(store.edgesDone(), "nothing to work out");

        MapConfig.MAP_OUTLINE.set(true);
        int before = store.version();
        store.updateEdges();
        assertTrue(store.outlines(), "on once the setting is");
        assertNotEquals(before, store.version(), "every picture is drawn again with its outline");
        assertTrue(store.edgesDone(), "no region in memory: nothing waits");

        MapConfig.MAP_OUTLINE.set(false);
        store.updateEdges();
        assertFalse(store.outlines(), "off again");
        store.close();
    }

    @Test
    void caveLayersAndFarLandHaveNoOutline(@TempDir Path root) {
        MapConfig.MAP_OUTLINE.set(true);
        TileStore cave = new TileStore(DIMENSION, root.resolve("cave"), io, 1, true);
        TileStore far = new TileStore(DIMENSION, root.resolve("far"), io, 1, false, true);
        TileStore surface = new TileStore(DIMENSION, root.resolve("surface"), io, 1);
        int caveVersion = cave.version();
        cave.updateEdges();
        far.updateEdges();
        assertFalse(cave.outlines());
        assertFalse(far.outlines());
        assertEquals(caveVersion, cave.version(), "the setting changes nothing there");
        assertTrue(surface.outlines(), "the surface starts with it when the setting is on");
        cave.close();
        far.close();
        surface.close();
    }
}
