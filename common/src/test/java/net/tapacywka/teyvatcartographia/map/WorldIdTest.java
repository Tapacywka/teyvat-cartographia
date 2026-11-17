package net.tapacywka.teyvatcartographia.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The folder names of worlds: short ones as before, long ones apart by a hash, and their old folder taken over once. */
class WorldIdTest {
    private static final String START = "My very long world name that goes on and on past every reasonable length and then some more";
    private static final String LONG_A = START + ", part one";
    private static final String LONG_B = START + ", part two";

    @Test
    void shortNamesStayAsBefore() {
        assertEquals("new_world", WorldId.folderName("New World"));
        assertEquals(WorldId.sanitize("play.example.org:25565"), WorldId.folderName("play.example.org:25565"));
        assertEquals("_", WorldId.folderName(""));
    }

    @Test
    void longNamesThatBeginAlikeGetFoldersOfTheirOwn() {
        assertEquals(WorldId.sanitize(LONG_A), WorldId.sanitize(LONG_B), "before: one folder for both");
        String a = WorldId.folderName(LONG_A), b = WorldId.folderName(LONG_B);
        assertNotEquals(a, b);
        assertEquals(80, a.length());
        assertTrue(a.startsWith(WorldId.sanitize(LONG_A).substring(0, 71)), "keeps the start of the name");
        assertEquals(a, WorldId.folderName(LONG_A), "the same every time");
    }

    @Test
    void theOldFolderOfALongNameIsTakenOver(@TempDir Path root) throws IOException {
        String old = "sp_" + WorldId.sanitize(LONG_A), id = "sp_" + WorldId.folderName(LONG_A);
        Files.createDirectories(root.resolve(old).resolve("minecraft_overworld"));
        Files.writeString(root.resolve(old).resolve("markers.json"), "{}");
        WorldId.takeOldFolder(root, old, id);
        assertTrue(Files.exists(root.resolve(id).resolve("markers.json")), "the map and the pins come along");
        assertFalse(Files.exists(root.resolve(old)));
    }

    @Test
    void aFolderAlreadyThereIsNotReplaced(@TempDir Path root) throws IOException {
        String old = "sp_" + WorldId.sanitize(LONG_A), id = "sp_" + WorldId.folderName(LONG_A);
        Files.createDirectories(root.resolve(old));
        Files.createDirectories(root.resolve(id));
        Files.writeString(root.resolve(id).resolve("markers.json"), "mine");
        WorldId.takeOldFolder(root, old, id);
        assertEquals("mine", Files.readString(root.resolve(id).resolve("markers.json")));
        assertTrue(Files.exists(root.resolve(old)), "the old one stays as it was");
        WorldId.takeOldFolder(root, "sp_none", "sp_other");
        assertFalse(Files.exists(root.resolve("sp_other")), "no old folder: nothing made");
    }
}
