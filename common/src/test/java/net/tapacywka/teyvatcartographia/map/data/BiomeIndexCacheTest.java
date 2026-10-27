package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.tapacywka.teyvatcartographia.map.MapView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The biome index of a map folder: built from the regions' metadata files, kept in {@code biomes.idx}, brought up to
 * date by the files' times (a new region joins, a removed one leaves, an unchanged one is not read again).
 */
class BiomeIndexCacheTest {
    private static final ResourceKey<Biome> PLAINS = ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("plains"));
    private static final ResourceKey<Biome> DESERT = ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("desert"));
    private static final int N = MapTile.CHUNKS;

    @TempDir
    Path dir;

    private static int storageInt(String name) throws ReflectiveOperationException {
        Field f = MapStorage.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    /** A region's metadata file as the map writes it, with every chunk in the given biome. */
    private void writeRegion(int tx, int tz, ResourceKey<Biome> biome) throws Exception {
        int pixels = MapTile.SIZE * MapTile.SIZE;
        Path dat = dir.resolve("r." + tx + "." + tz + ".dat");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(dat))))) {
            out.writeInt(storageInt("MAGIC"));
            out.writeInt(storageInt("VERSION"));
            for (int i = 0; i < pixels; i++) out.writeShort(64);
            out.write(new byte[pixels]); // flags
            out.write(new byte[N * N]); // chunk states
            for (int i = 0; i < N * N; i++) out.writeShort(0);
            MapStorage.writePalette(out, List.of(biome));
            out.write(new byte[pixels]); // light
        }
    }

    /** A fresh index of the folder, as after a restart (the shared instance of {@link BiomeIndex#of} is kept per path). */
    private BiomeIndex fresh() throws ReflectiveOperationException {
        Constructor<BiomeIndex> c = BiomeIndex.class.getDeclaredConstructor(Path.class);
        c.setAccessible(true);
        return c.newInstance(dir);
    }

    private static BiomeIndex.Snapshot refreshed(BiomeIndex index) throws InterruptedException {
        index.refresh();
        for (int i = 0; i < 500 && index.busy(); i++) Thread.sleep(10);
        assertFalse(index.busy(), "the refresh finished");
        return index.snapshot();
    }

    private static BiomeIndex.Region region(BiomeIndex.Snapshot s, int tx, int tz) {
        return s.regions().get(MapView.tileKey(tx, tz));
    }

    @Test
    void theRegionsOfTheFolderAreIndexed() throws Exception {
        writeRegion(0, 0, PLAINS);
        writeRegion(1, 0, DESERT);
        BiomeIndex.Snapshot s = refreshed(fresh());
        assertEquals(2, s.regions().size());
        assertTrue(s.palette().contains(PLAINS) && s.palette().contains(DESERT));
        assertEquals(PLAINS, s.palette().get(region(s, 0, 0).biome()[0]));
        assertEquals(DESERT, s.palette().get(region(s, 1, 0).biome()[N * N - 1]));
        assertTrue(Files.exists(dir.resolve("biomes.idx")), "the index is kept next to the map");
    }

    @Test
    void anUnchangedFolderKeepsTheSnapshot() throws Exception {
        writeRegion(0, 0, PLAINS);
        BiomeIndex index = fresh();
        BiomeIndex.Snapshot first = refreshed(index);
        assertSame(first, refreshed(index), "nothing changed: the same snapshot (the search compares them by identity)");
    }

    @Test
    void aNewRegionJoinsAndARemovedOneLeaves() throws Exception {
        writeRegion(0, 0, PLAINS);
        BiomeIndex index = fresh();
        assertEquals(1, refreshed(index).regions().size());
        writeRegion(5, -3, DESERT);
        BiomeIndex.Snapshot grown = refreshed(index);
        assertEquals(2, grown.regions().size());
        assertNotNull(region(grown, 5, -3));
        Files.delete(dir.resolve("r.0.0.dat"));
        BiomeIndex.Snapshot shrunk = refreshed(index);
        assertEquals(1, shrunk.regions().size());
        assertNotNull(region(shrunk, 5, -3));
    }

    @Test
    void afterARestartTheCacheIsUsedAndUnchangedRegionsAreNotReadAgain() throws Exception {
        writeRegion(0, 0, PLAINS);
        refreshed(fresh());
        // Spoil the region file but keep its time: a fresh index must take the region from biomes.idx.
        Path dat = dir.resolve("r.0.0.dat");
        FileTime time = Files.getLastModifiedTime(dat);
        Files.write(dat, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(dat, time);
        BiomeIndex.Snapshot s = refreshed(fresh());
        assertEquals(1, s.regions().size());
        assertEquals(PLAINS, s.palette().get(region(s, 0, 0).biome()[0]));
    }

    @Test
    void aChangedRegionIsReadAgain() throws Exception {
        writeRegion(0, 0, PLAINS);
        BiomeIndex index = fresh();
        refreshed(index);
        writeRegion(0, 0, DESERT);
        Path dat = dir.resolve("r.0.0.dat");
        Files.setLastModifiedTime(dat, FileTime.fromMillis(Files.getLastModifiedTime(dat).toMillis() + 5000));
        BiomeIndex.Snapshot s = refreshed(index);
        assertEquals(DESERT, s.palette().get(region(s, 0, 0).biome()[0]));
    }

    @Test
    void aDamagedRegionIsLeftOut() throws Exception {
        writeRegion(0, 0, PLAINS);
        Files.write(dir.resolve("r.1.1.dat"), new byte[]{0, 1, 2});
        BiomeIndex.Snapshot s = refreshed(fresh());
        assertEquals(1, s.regions().size());
    }

    @Test
    void anEmptyOrMissingFolderGivesAnEmptyIndex() throws Exception {
        assertTrue(refreshed(fresh()).regions().isEmpty());
        Files.delete(dir);
        assertTrue(refreshed(fresh()).regions().isEmpty());
    }
}
