package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** An old map is deleted once when its folder is opened (user decision 24.10.2026), a current one is kept. */
class MapFormatTest {
    private static void region(Path dir, String name, int version) throws IOException {
        Files.writeString(dir.resolve(name + ".png"), "pixels");
        Files.writeString(dir.resolve(name + ".lod.png"), "thumb");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(dir.resolve(name + ".dat"))))) {
            out.writeInt(0x54594D31);
            out.writeInt(version);
        }
    }

    @Test
    void regionsOfAnOlderFormatGoWithTheIndexAndTheBiomeCache(@TempDir Path dir) throws IOException {
        region(dir, "r.0.0", MapStorage.VERSION - 1);
        region(dir, "r.-1.2", MapStorage.VERSION);
        Files.writeString(dir.resolve("index.txt"), "0 0 5\n");
        Files.writeString(dir.resolve("biomes.idx"), "cache");
        Files.writeString(dir.resolve("dimension.txt"), "minecraft:overworld");

        assertEquals(1, MapStorage.dropOutdated(dir));
        assertFalse(Files.exists(dir.resolve("r.0.0.png")));
        assertFalse(Files.exists(dir.resolve("r.0.0.dat")));
        assertFalse(Files.exists(dir.resolve("r.0.0.lod.png")));
        assertFalse(Files.exists(dir.resolve("index.txt")));
        assertFalse(Files.exists(dir.resolve("biomes.idx")));
        assertTrue(Files.exists(dir.resolve("r.-1.2.png")));
        assertTrue(Files.exists(dir.resolve("r.-1.2.lod.png")));
        assertTrue(Files.exists(dir.resolve("dimension.txt")));
        assertEquals(Integer.toString(MapStorage.VERSION), Files.readString(dir.resolve("format.txt")));
    }

    @Test
    void aCheckedFolderIsNotReadAgain(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("format.txt"), Integer.toString(MapStorage.VERSION));
        region(dir, "r.3.3", MapStorage.VERSION - 1); // written after the check: left alone
        assertEquals(0, MapStorage.dropOutdated(dir));
        assertTrue(Files.exists(dir.resolve("r.3.3.png")));
    }

    @Test
    void aNewFolderIsCreatedAndMarked(@TempDir Path root) throws IOException {
        Path dir = root.resolve("minecraft_overworld");
        assertEquals(0, MapStorage.dropOutdated(dir));
        assertTrue(Files.exists(dir.resolve("format.txt")));
    }
}
