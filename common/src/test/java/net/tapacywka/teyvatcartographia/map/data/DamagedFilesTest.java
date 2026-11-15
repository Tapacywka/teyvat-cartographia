package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.map.MapView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Damaged files of a map folder: what cannot be read is kept beside the map as {@code .broken} instead of being written
 * over or deleted, and one damaged part of a file does not take the rest with it.
 */
class DamagedFilesTest {
    private static void pixels(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".png"), "pixels");
    }

    private static void metadata(Path dir, String name, int version) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dir.resolve(name + ".dat"))))) {
            out.writeInt(0x54594D31);
            out.writeInt(version);
        }
    }

    @Test
    void metadataThatIsNotCompressedIsSetAside(@TempDir Path dir) throws IOException {
        pixels(dir, "r.0.0");
        Files.writeString(dir.resolve("r.0.0.dat"), "not a map");
        assertNull(TileStore.readOrSetAside(dir, 0, 0, MapStorage::load));
        assertTrue(Files.exists(dir.resolve("r.0.0.png.broken")));
        assertTrue(Files.exists(dir.resolve("r.0.0.dat.broken")));
        assertFalse(Files.exists(dir.resolve("r.0.0.png")), "the region mapped anew does not write over the old pixels");
    }

    @Test
    void metadataCutShortInItsHeaderIsSetAside(@TempDir Path dir) throws IOException {
        pixels(dir, "r.1.-1");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dir.resolve("r.1.-1.dat"))))) {
            out.writeShort(0x5459);
        }
        assertNull(TileStore.readOrSetAside(dir, 1, -1, MapStorage::load));
        assertTrue(Files.exists(dir.resolve("r.1.-1.png.broken")));
    }

    @Test
    void anotherKindOfFileIsDamageToo(@TempDir Path dir) throws IOException {
        pixels(dir, "r.2.2");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dir.resolve("r.2.2.dat"))))) {
            out.writeInt(0x12345678);
            out.writeInt(MapStorage.VERSION);
        }
        assertThrows(IOException.class, () -> MapStorage.load(dir, 2, 2));
    }

    @Test
    void aRegionOfAnOlderFormatIsMappedAgainNotSetAside(@TempDir Path dir) throws IOException {
        pixels(dir, "r.0.0");
        metadata(dir, "r.0.0", MapStorage.VERSION - 1);
        assertNull(TileStore.readOrSetAside(dir, 0, 0, MapStorage::load));
        assertTrue(Files.exists(dir.resolve("r.0.0.png")));
        assertFalse(Files.exists(dir.resolve("r.0.0.png.broken")));
    }

    @Test
    void theFormatCheckLeavesADamagedRegionToBeSetAside(@TempDir Path dir) throws IOException {
        pixels(dir, "r.5.5");
        Files.writeString(dir.resolve("r.5.5.dat"), "not a map");
        pixels(dir, "r.6.6");
        metadata(dir, "r.6.6", MapStorage.VERSION - 1);
        assertEquals(1, MapStorage.dropOutdated(dir), "only the region of an older format goes");
        assertTrue(Files.exists(dir.resolve("r.5.5.png")));
        assertTrue(Files.exists(dir.resolve("r.5.5.dat")));
        assertFalse(Files.exists(dir.resolve("r.6.6.png")));
    }

    @Test
    void aDamagedLineOfTheIndexIsLeftOutAlone(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("index.txt"), "0 0 5\n1 x 3\n7\n-2 4 7\n\n3 3 1");
        Map<Long, Integer> counts = MapStorage.readIndex(dir);
        assertEquals(3, counts.size());
        assertEquals(5, counts.get(MapView.tileKey(0, 0)));
        assertEquals(7, counts.get(MapView.tileKey(-2, 4)));
        assertEquals(1, counts.get(MapView.tileKey(3, 3)), "a last line without its line end still counts");
    }
}
