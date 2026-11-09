package net.tapacywka.teyvatcartographia.map.data;

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
