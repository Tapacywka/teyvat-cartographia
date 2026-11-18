package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The detail preview lays each chunk's 16 x 16 pixels at its place in the picture, rows unchanged, nothing beside. */
class DetailPreviewTest {
    @Test
    void eachChunkAtItsPlace() {
        // A picture 3 chunks wide and 2 high; every pixel of a chunk names its chunk and its own place.
        int side = 3, rows = 2;
        int[] picture = new int[256 * side * rows];
        for (int cz = 0; cz < rows; cz++) {
            for (int cx = 0; cx < side; cx++) {
                int[] chunk = new int[256];
                for (int i = 0; i < 256; i++) chunk[i] = (cz * side + cx + 1) << 16 | i;
                DetailPreview.place(chunk, picture, side, cx * 16, cz * 16);
            }
        }
        for (int y = 0; y < 16 * rows; y++) {
            for (int x = 0; x < 16 * side; x++) {
                int want = ((y / 16) * side + x / 16 + 1) << 16 | (y % 16) * 16 + x % 16;
                assertEquals(want, picture[y * 16 * side + x], "pixel " + x + ", " + y);
            }
        }
    }

    @Test
    void aMissingChunkLeavesItsPlaceClear() {
        int side = 3;
        int[] picture = new int[256 * side * 3];
        int[] chunk = new int[256];
        java.util.Arrays.fill(chunk, 0xFF00FF00);
        DetailPreview.place(chunk, picture, side, 16, 16);
        int filled = 0;
        for (int i = 0; i < picture.length; i++) {
            int x = i % (16 * side), y = i / (16 * side);
            boolean inside = x >= 16 && x < 32 && y >= 16 && y < 32;
            assertEquals(inside ? 0xFF00FF00 : 0, picture[i], "pixel " + x + ", " + y);
            if (inside) filled++;
        }
        assertEquals(256, filled);
    }
}
