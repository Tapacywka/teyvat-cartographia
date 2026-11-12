package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.map.MapView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A region's file put in place while something reads the old one ({@link MapStorage#replace}, 09.11.2026): a move that
 * fails for a moment is tried again, one that never works is reported.
 */
class ReplaceFileTest {
    @TempDir
    Path dir;

    @Test
    void replacesTheFile() throws IOException {
        Path file = dir.resolve("r.0.0.dat"), tmp = dir.resolve("r.0.0.dat.tmp");
        Files.writeString(file, "old");
        Files.writeString(tmp, "new");
        MapStorage.replace(tmp, file);
        assertEquals("new", Files.readString(file));
        assertFalse(Files.exists(tmp));
    }

    @Test
    void triesAgainWhileTheFileIsHeld() throws IOException {
        Path file = dir.resolve("r.0.0.dat"), tmp = dir.resolve("r.0.0.dat.tmp");
        Files.writeString(file, "old");
        Files.writeString(tmp, "new");
        int[] calls = {0};
        MapStorage.replace(tmp, file, (from, to) -> {
            if (++calls[0] < 3) throw new AccessDeniedException(to.toString());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        });
        assertEquals(3, calls[0]);
        assertEquals("new", Files.readString(file));
    }

    @Test
    void anUnreadableRegionIsSetAsideNotLost() throws IOException {
        Files.writeString(dir.resolve("r.3.-2.png"), "picture");
        Files.writeString(dir.resolve("r.3.-2.dat"), "data");
        Files.writeString(dir.resolve("r.3.-1.png"), "neighbour");
        MapStorage.setAside(dir, 3, -2);
        assertFalse(Files.exists(dir.resolve("r.3.-2.png")));
        assertFalse(Files.exists(dir.resolve("r.3.-2.dat")));
        assertEquals("picture", Files.readString(dir.resolve("r.3.-2.png.broken")));
        assertEquals("data", Files.readString(dir.resolve("r.3.-2.dat.broken")));
        assertEquals("neighbour", Files.readString(dir.resolve("r.3.-1.png")));
        // The region's listing reads only r.<x>.<z>.png: the set-aside files are not taken for regions.
        assertTrue(MapStorage.listTiles(dir).stream().noneMatch(k -> k == MapView.tileKey(3, -2)));
    }

    @Test
    void givesUpAfterItsTries() {
        Path file = dir.resolve("r.0.0.dat"), tmp = dir.resolve("r.0.0.dat.tmp");
        int[] calls = {0};
        assertThrows(AccessDeniedException.class, () -> MapStorage.replace(tmp, file, (from, to) -> {
            calls[0]++;
            throw new AccessDeniedException(to.toString());
        }));
        assertEquals(MapStorage.REPLACE_TRIES, calls[0]);
    }
}
