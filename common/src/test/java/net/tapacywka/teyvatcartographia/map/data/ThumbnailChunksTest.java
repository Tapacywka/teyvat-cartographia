package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A LOD cell redrawn for its changed chunks only ({@link MapStorage#thumbnailChunks}, profile 09.11.2026) is the same as
 * the region's whole thumbnail made again, here by a plain average of each 4 x 4 block written apart from the mod's.
 */
class ThumbnailChunksTest {
    private static final int SIZE = MapTile.SIZE, THUMB = MapStorage.THUMB;

    /** The thumbnail as {@link MapStorage#thumbnail} defines it: explored pixels averaged, alpha over all 16. */
    private static int[] reference(int[] picture) {
        int[] out = new int[THUMB * THUMB];
        for (int y = 0; y < THUMB; y++) {
            for (int x = 0; x < THUMB; x++) {
                int r = 0, g = 0, b = 0, a = 0, n = 0;
                for (int k = 0; k < 16; k++) {
                    int c = picture[(y * 4 + k / 4) * SIZE + x * 4 + k % 4];
                    if (c >>> 24 == 0) continue;
                    r += c & 0xFF;
                    g += c >> 8 & 0xFF;
                    b += c >> 16 & 0xFF;
                    a += c >>> 24;
                    n++;
                }
                out[y * THUMB + x] = n == 0 ? 0 : (a / 16) << 24 | (b / n) << 16 | (g / n) << 8 | r / n;
            }
        }
        return out;
    }

    /** Chunks of a picture held in an array, into a cell held in another. */
    private static MapStorage.ChunkPixels pixels(int[] picture, int[] cell) {
        return new MapStorage.ChunkPixels() {
            @Override
            public void read(int lcx, int lcz, int[] into) {
                for (int y = 0; y < 16; y++) System.arraycopy(picture, (lcz * 16 + y) * SIZE + lcx * 16, into, y * 16, 16);
            }

            @Override
            public void write(int lcx, int lcz, int[] thumb) {
                int side = MapStorage.CHUNK_THUMB;
                for (int y = 0; y < side; y++) System.arraycopy(thumb, y * side, cell, (lcz * side + y) * THUMB + lcx * side, side);
            }
        };
    }

    private static int[] run(int[] picture, int[] cell, long[] bits, int[] done) {
        done[0] = MapStorage.thumbnailChunks(bits, pixels(picture, cell), new int[MapStorage.CHUNK_PIXELS], new int[MapStorage.CHUNK_THUMB * MapStorage.CHUNK_THUMB]);
        return cell;
    }

    /** ABGR, some pixels unexplored (alpha 0), some see-through. */
    private static int pixel(Random r) {
        int a = switch (r.nextInt(4)) {
            case 0 -> 0;
            case 1 -> 128 + r.nextInt(128);
            default -> 255;
        };
        return a << 24 | r.nextInt(1 << 24);
    }

    @Test
    void changedChunksGiveTheWholeThumbnail() {
        Random r = new Random(9112026);
        int[] picture = new int[SIZE * SIZE];
        for (int i = 0; i < picture.length; i++) picture[i] = pixel(r);
        int[] cell = reference(picture);

        long[] bits = new long[MapTile.CHUNKS * MapTile.CHUNKS / 64];
        int changed = 0;
        for (int k = 0; k < 40; k++) {
            int lcx = r.nextInt(MapTile.CHUNKS), lcz = r.nextInt(MapTile.CHUNKS), i = lcz * MapTile.CHUNKS + lcx;
            if ((bits[i >> 6] & 1L << (i & 63)) == 0) changed++;
            bits[i >> 6] |= 1L << (i & 63);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) picture[(lcz * 16 + z) * SIZE + lcx * 16 + x] = pixel(r);
            }
        }
        int[] done = new int[1];
        run(picture, cell, bits, done);

        assertEquals(changed, done[0], "every changed chunk once");
        assertArrayEquals(reference(picture), cell, "the cell as the whole thumbnail of the changed picture");
    }

    @Test
    void everyChunkIsTheWholeThumbnail() {
        Random r = new Random(7);
        int[] picture = new int[SIZE * SIZE];
        for (int i = 0; i < picture.length; i++) picture[i] = pixel(r);
        long[] all = new long[MapTile.CHUNKS * MapTile.CHUNKS / 64];
        Arrays.fill(all, -1L);
        int[] done = new int[1];

        int[] cell = run(picture, new int[THUMB * THUMB], all, done);
        assertEquals(MapTile.CHUNKS * MapTile.CHUNKS, done[0]);
        assertArrayEquals(reference(picture), cell);
    }
}
