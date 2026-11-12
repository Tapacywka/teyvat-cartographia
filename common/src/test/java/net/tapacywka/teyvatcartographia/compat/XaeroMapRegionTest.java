package net.tapacywka.teyvatcartographia.compat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

/** Xaero's World Map regions (format 6.8): written here byte by byte as Xaero writes them, then read back. */
class XaeroMapRegionTest {
    /** A writer of the format, column by column, enough for the cases below. */
    private static final class Writer {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);

        Writer() throws IOException {
            out.writeByte(0xFF);
            out.writeInt(XaeroMapRegion.VERSION);
        }

        void tile(int tx, int tz) throws IOException {
            out.writeByte(tx << 4 | tz);
        }

        void emptyChunk() throws IOException {
            out.writeInt(-1);
        }

        void chunkTail() throws IOException {
            out.writeByte(1);
            out.writeInt(0x7FFFFFFF);
            out.writeByte(30);
        }

        /** A column: {@code state} a new block (NBT) when not null, else the palette index {@code index}; the biome likewise. */
        void column(String state, Map<String, String> props, int index, int height, int light, String biome, int biomeIndex,
                    int top, boolean water, String overlayState) throws IOException {
            int p = 1 << 20 | (height & 0xFF) << 12 | light << 8;
            if (state != null || index >= 0) p |= 1;
            if (state != null) p |= 1 << 21;
            if (biome != null) p |= 1 << 22;
            if (top >= 0) p |= 1 << 24;
            if (water || overlayState != null) p |= 2;
            out.writeInt(p);
            if (state != null) nbt(state, props);
            else if (index >= 0) out.writeInt(index);
            if (top >= 0) out.writeByte(top);
            if (water || overlayState != null) {
                out.writeByte((water ? 1 : 0) + (overlayState != null ? 1 : 0));
                if (water) out.writeInt(0x3000);
                if (overlayState != null) {
                    out.writeInt(1 | 1 << 10);
                    nbt(overlayState, Map.of());
                }
            }
            if (biome != null) out.writeUTF(biome);
            else out.writeInt(biomeIndex);
        }

        void nbt(String name, Map<String, String> props) throws IOException {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(8);
            out.writeUTF("Name");
            out.writeUTF(name);
            if (!props.isEmpty()) {
                out.writeByte(10);
                out.writeUTF("Properties");
                for (var e : props.entrySet()) {
                    out.writeByte(8);
                    out.writeUTF(e.getKey());
                    out.writeUTF(e.getValue());
                }
                out.writeByte(0);
            }
            out.writeByte(0);
        }

        byte[] done() throws IOException {
            out.flush();
            return bytes.toByteArray();
        }
    }

    /** Tile chunk (1, 2), its chunk 5 (x 1, z 1 in the tile) filled: stone stepping up with x, the first column new. */
    private static byte[] steps() throws IOException {
        Writer w = new Writer();
        w.tile(1, 2);
        for (int c = 0; c < 16; c++) {
            if (c != 5) {
                w.emptyChunk();
                continue;
            }
            for (int px = 0; px < 256; px++) {
                int x = px / 16;
                if (px == 0) w.column("minecraft:stone", Map.of(), -1, 64, 0, "minecraft:plains", -1, -1, false, null);
                else w.column(null, null, 0, 64 + x, x % 16, null, 0, -1, false, null);
            }
            w.chunkTail();
        }
        return w.done();
    }

    @Test
    void columnsLandWhereTheTileChunkAndChunkSay() throws IOException {
        XaeroMapRegion r = XaeroMapRegion.read(steps());
        // Tile chunk 1, 2 starts at block 64, 128; its chunk 5 is 1, 1 in it: blocks 80, 144 to 95, 159.
        assertTrue(r.chunk[(2 * 4 + 1) * 32 + (1 * 4 + 1)]);
        assertEquals(1, countChunks(r));
        int i = XaeroMapRegion.index(80 + 3, 144 + 7);
        assertEquals(0, r.state[i]);
        assertEquals(64 + 3, r.height[i] & 0xFF);
        assertEquals(3, r.light[i]);
        assertEquals("minecraft:stone", r.states.get(r.state[i]).name());
        assertEquals("minecraft:plains", r.biomes.get(r.biome[i]));
        assertEquals(XaeroMapRegion.NONE, r.state[XaeroMapRegion.index(79, 144)]);
        assertEquals(-1, r.biome[XaeroMapRegion.index(79, 144)]);
    }

    private static int countChunks(XaeroMapRegion r) {
        int n = 0;
        for (boolean b : r.chunk) n += b ? 1 : 0;
        return n;
    }

    @Test
    void waterTopIceAndPropertiesAreRead() throws IOException {
        Writer w = new Writer();
        w.tile(0, 0);
        for (int px = 0; px < 256; px++) {
            if (px == 0) w.column("minecraft:sand", Map.of(), -1, 60, 0, "minecraft:ocean", -1, 63, true, null);
            else if (px == 1) w.column("terrain_slabs:tuff_slab", Map.of("type", "bottom", "waterlogged", "true"), -1, 70, 0, null, 0, -1, true, null);
            else if (px == 2) w.column(null, null, 0, 61, 0, null, 0, 63, true, "minecraft:ice");
            else w.column(null, null, -1, 0, 0, null, 0, -1, false, null);
        }
        w.chunkTail();
        for (int c = 1; c < 16; c++) w.emptyChunk();
        XaeroMapRegion r = XaeroMapRegion.read(w.done());
        int sand = XaeroMapRegion.index(0, 0), slab = XaeroMapRegion.index(0, 1), ice = XaeroMapRegion.index(0, 2), bare = XaeroMapRegion.index(0, 3);
        assertTrue(r.water[sand]);
        assertEquals(63, r.top[sand]);
        assertEquals(60, r.height[sand] & 0xFF);
        assertEquals(Map.of("type", "bottom", "waterlogged", "true"), r.states.get(r.state[slab]).properties());
        assertEquals(-1, r.top[slab]);
        assertEquals("minecraft:ice", r.states.get(r.overlay[ice]).name());
        assertEquals(XaeroMapRegion.NONE, r.state[bare]);
        assertFalse(r.water[bare]);
        assertArrayEquals(new String[]{"minecraft:ocean"}, r.biomes.toArray());
    }

    @Test
    void heightsOutsideTheByteAreTakenFromTheirNeighbours() throws IOException {
        // A chunk of a slope from y 230 to 290 along x (Xaero keeps 290 as 34), and one column of a lone 300.
        Writer w = new Writer();
        w.tile(0, 0);
        for (int px = 0; px < 256; px++) {
            int x = px / 16, z = px % 16;
            int y = 230 + x * 4;
            if (x == 8 && z == 8) y = 300;
            if (px == 0) w.column("minecraft:stone", Map.of(), -1, y, 0, "minecraft:jagged_peaks", -1, -1, false, null);
            else w.column(null, null, 0, y, 0, null, 0, -1, false, null);
        }
        w.chunkTail();
        for (int c = 1; c < 16; c++) w.emptyChunk();
        int[] y = XaeroMapRegion.read(w.done()).heights(-64, 319);
        for (int x = 0; x < 16; x++) assertEquals(230 + x * 4, y[XaeroMapRegion.index(x, 3)], "x " + x);
        assertEquals(300, y[XaeroMapRegion.index(8, 8)]);
        assertEquals(Integer.MIN_VALUE, y[XaeroMapRegion.index(20, 3)]);
    }

    @Test
    void aByteAloneTakesTheHeightNearest128() {
        assertEquals(40, XaeroMapRegion.nearest(40, 128, -64, 319));
        assertEquals(250, XaeroMapRegion.nearest(250, 128, -64, 319));
        assertEquals(-6, XaeroMapRegion.nearest(250, -20, -64, 319));
        assertEquals(296, XaeroMapRegion.nearest(40, 280, -64, 319));
        assertEquals(40, XaeroMapRegion.nearest(40, 280, 0, 255)); // the Nether and old worlds: one height only
    }

    @Test
    void theZipXaeroWritesIsRead() throws IOException {
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(zip)) {
            out.putNextEntry(new ZipEntry("region.xaero"));
            out.write(steps());
        }
        assertEquals(1, countChunks(XaeroMapRegion.readZip(new ByteArrayInputStream(zip.toByteArray()))));
    }

    @Test
    void anotherVersionOrABrokenFileIsRefused() throws IOException {
        byte[] good = steps();
        byte[] other = good.clone();
        other[4] = 9; // version 6.9
        assertThrows(IOException.class, () -> XaeroMapRegion.read(other));
        byte[] cut = Arrays.copyOf(good, good.length - 7);
        assertThrows(IOException.class, () -> XaeroMapRegion.read(cut));
        byte[] old = good.clone();
        old[0] = 0;
        assertThrows(IOException.class, () -> XaeroMapRegion.read(old));
    }
}
