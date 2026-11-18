package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The cave layers' folders and the chunks a scan reaches, without a game. */
class CaveLayersTest {
    @Test
    void bandFolders() {
        assertEquals(4, CaveLayers.bandOf("b4"));
        assertEquals(-4, CaveLayers.bandOf("b-4"));
        assertEquals(-4, CaveLayers.bandOf(CaveLayers.folder(-4)), "a band's folder reads back as the band");
        assertNull(CaveLayers.bandOf("b"), "no number");
        assertNull(CaveLayers.bandOf("b4.tmp"), "something after the number");
        assertNull(CaveLayers.bandOf("region"), "another folder");
        assertNull(CaveLayers.bandOf("B4"), "upper case is not written");
    }

    @Test
    void savedBandsAreListedHighestFirst() {
        CaveLayers caves = new CaveLayers(band -> {
            throw new AssertionError("listing saved bands opens no store");
        });
        caves.listSaved(List.of("b-4", "junk", "b2", "b0", "b-1"));
        assertEquals(List.of(2, 0, -1, -4), List.copyOf(caves.bands()));
        assertNull(caves.current(), "nobody underground yet");
    }

    @Test
    void chunksWithin32Blocks() {
        // At (8, 8), the middle of chunk 0: 32 blocks either way is -24 to 40, chunks -2 to 2.
        assertArrayEquals(new int[]{-2, 2, -2, 2}, CaveLayers.chunksAround(8, 8));
        // At a chunk corner (0, 0): -32 is chunk -2 exactly, 32 the first block of chunk 2.
        assertArrayEquals(new int[]{-2, 2, -2, 2}, CaveLayers.chunksAround(0, 0));
        // Just west of it: -32.5 reaches into chunk -3.
        assertArrayEquals(new int[]{-3, 1, -3, 1}, CaveLayers.chunksAround(-0.5, -0.5));
        // Far away, negative: -199.5 gives -231.5 to -167.5, chunks -15 to -11; z 0.5 gives -31.5 (chunk -2) to 32.5.
        assertArrayEquals(new int[]{-15, -11, -2, 2}, CaveLayers.chunksAround(-199.5, 0.5));
    }
}
