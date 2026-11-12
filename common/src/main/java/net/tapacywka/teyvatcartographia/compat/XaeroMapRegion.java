package net.tapacywka.teyvatcartographia.compat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * One region of Xaero's World Map (512 x 512 blocks, the file {@code <x>_<z>.zip} with {@code region.xaero} in it), read
 * for the import of an explored map (user request 03.11.2026, docs/XAERO_MAP_IMPORT.md). Format 6.8 of Xaero's World Map
 * 1.46, worked out from a player's files: big-endian; a 0xFF byte and the version int, then tile chunks (64 x 64 blocks)
 * to the end: their position byte ({@code x << 4 | z}), 16 chunks ({@code -1} for an empty one, else 256 columns and a
 * tail of a byte, an int and a byte). A column is a parameter int and, by its bits: 0 a block (21 a new one as NBT, else
 * its palette index), 24 a byte (the height of the top over water), 1 overlays (a count, each a parameter int, bit 0
 * its own block: 10 new as NBT, else index; without a block it is water), always the biome (22 a new name, else index);
 * bits 8 to 11 the block light, 12 to 19 the height modulo 256. Nothing here touches the game: tested on bytes alone.
 */
public final class XaeroMapRegion {
    public static final int SIZE = 512;
    /** The only version read (Xaero's World Map 1.46.0, and probably the versions around it). */
    public static final int VERSION = 0x00060008;
    /** {@link #state} of a column with no block (nothing mapped there, or air). */
    public static final int NONE = -1;

    /** A block state as Xaero keeps it: its id and its properties as text. */
    public record State(String name, Map<String, String> properties) {}

    /** The block states and biomes of the region, in the order they first appear. */
    public final List<State> states = new ArrayList<>();
    public final List<String> biomes = new ArrayList<>();
    /** Per column ({@code z * SIZE + x}): palette index of the block, {@link #NONE} without one. */
    public final int[] state = new int[SIZE * SIZE];
    /** Per column: the height modulo 256 (0..255). */
    public final byte[] height = new byte[SIZE * SIZE];
    /** Per column: the byte of bit 24 (the top over water, modulo 256), -1 without one. */
    public final short[] top = new short[SIZE * SIZE];
    /** Per column: the block light, 0..15. */
    public final byte[] light = new byte[SIZE * SIZE];
    /** Per column: water over the block (an overlay without a block of its own). */
    public final boolean[] water = new boolean[SIZE * SIZE];
    /** Per column: the block of the first overlay with one (ice over water, glass), {@link #NONE} without. */
    public final int[] overlay = new int[SIZE * SIZE];
    /** Per column: palette index of the biome, -1 where the column is not mapped. */
    public final short[] biome = new short[SIZE * SIZE];
    /** Per chunk ({@code cz * 32 + cx}): mapped by Xaero. */
    public final boolean[] chunk = new boolean[32 * 32];

    private XaeroMapRegion() {
        Arrays.fill(state, NONE);
        Arrays.fill(top, (short) -1);
        Arrays.fill(overlay, NONE);
        Arrays.fill(biome, (short) -1);
    }

    /** Index of the column at x, z of the region (0..511). */
    public static int index(int x, int z) {
        return z * SIZE + x;
    }

    /**
     * The height of every mapped column in blocks. Xaero keeps it modulo 256, and a world of -64 to 319 has up to two
     * heights for one byte. Unwrapped as a phase: the columns whose byte has one height there (64 to 255; under 0 the
     * surface hardly ever is) are certain, and from them the height spreads to the mapped neighbours by the difference
     * of their bytes taken between -128 and 127 (no two neighbouring columns differ by more), so a slope or a peak over
     * 255 keeps rising and a sea floor under 64 stays low. A patch no certain column reaches takes the height nearest
     * 128. Unmapped columns get {@code Integer.MIN_VALUE}.
     */
    public int[] heights(int minY, int maxY) {
        int n = SIZE * SIZE;
        int[] y = new int[n];
        Arrays.fill(y, Integer.MIN_VALUE);
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (biome[i] < 0) continue;
            int h = height[i] & 0xFF;
            if (h >= 64 || nearest(h, minY - 256, minY, maxY) == nearest(h, maxY + 256, minY, maxY)) {
                y[i] = h >= 64 ? nearest(h, 255, minY, maxY) : nearest(h, 128, minY, maxY);
                queue[tail++] = i;
            }
        }
        int next = 0;
        while (true) {
            while (head < tail) {
                int i = queue[head++], x = i % SIZE, z = i / SIZE;
                for (int d = 0; d < 4; d++) {
                    int nx = x + (d == 0 ? -1 : d == 1 ? 1 : 0), nz = z + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) continue;
                    int j = index(nx, nz);
                    if (biome[j] < 0 || y[j] != Integer.MIN_VALUE) continue;
                    int step = (height[j] - height[i]) & 0xFF;
                    if (step >= 128) step -= 256;
                    y[j] = nearest(height[j] & 0xFF, y[i] + step, minY, maxY);
                    queue[tail++] = j;
                }
            }
            // A patch no certain column reached: one of its columns nearest 128, spread from there.
            while (next < n && (biome[next] < 0 || y[next] != Integer.MIN_VALUE)) next++;
            if (next == n) return y;
            y[next] = nearest(height[next] & 0xFF, 128, minY, maxY);
            queue[tail++] = next;
        }
    }

    /** Of the heights {@code h - 256}, {@code h} and {@code h + 256} within minY..maxY, the one nearest {@code to}. */
    static int nearest(int h, int to, int minY, int maxY) {
        int best = h;
        for (int c = h - 256; c <= h + 256; c += 256) {
            if (c < minY || c > maxY) continue;
            if (best < minY || best > maxY || Math.abs(c - to) < Math.abs(best - to)) best = c;
        }
        return best;
    }

    /** Reads a region file as Xaero writes it (a zip with {@code region.xaero}). */
    public static XaeroMapRegion readZip(InputStream zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(zip)) {
            if (in.getNextEntry() == null) throw new IOException("empty region archive");
            return read(in.readAllBytes());
        }
    }

    /** Reads the bytes of {@code region.xaero}. */
    public static XaeroMapRegion read(byte[] data) throws IOException {
        XaeroMapRegion r = new XaeroMapRegion();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readUnsignedByte() != 0xFF) throw new IOException("Xaero region of the old format");
        int version = in.readInt();
        if (version != VERSION) throw new IOException("Xaero region format " + Integer.toHexString(version) + ", read only " + Integer.toHexString(VERSION));
        while (in.available() > 0) {
            int tile = in.readUnsignedByte();
            int tx = tile >> 4, tz = tile & 15;
            if (tx > 7 || tz > 7) throw new IOException("tile chunk " + tx + ", " + tz + " outside the region");
            for (int c = 0; c < 16; c++) {
                int p = in.readInt();
                if (p == -1) continue;
                int cx = tx * 4 + c / 4, cz = tz * 4 + c % 4;
                r.chunk[cz * 32 + cx] = true;
                for (int px = 0; px < 256; px++) {
                    if (px > 0) p = in.readInt();
                    int i = index(cx * 16 + px / 16, cz * 16 + px % 16);
                    r.column(in, p, i);
                }
                in.readUnsignedByte();
                in.readInt();
                in.readUnsignedByte();
            }
        }
        return r;
    }

    private void column(DataInputStream in, int p, int i) throws IOException {
        if ((p & 1) != 0) state[i] = (p & 1 << 21) != 0 ? add(readState(in)) : checked(in.readInt(), states.size());
        if ((p & 1 << 24) != 0) top[i] = (short) in.readUnsignedByte();
        if ((p & 2) != 0) {
            int n = in.readUnsignedByte();
            for (int o = 0; o < n; o++) {
                int ov = in.readInt();
                if ((ov & 1) == 0) {
                    water[i] = true;
                    continue;
                }
                int s = (ov & 1 << 10) != 0 ? add(readState(in)) : checked(in.readInt(), states.size());
                if (overlay[i] == NONE) overlay[i] = s;
            }
        }
        if ((p & 1 << 22) != 0) {
            biomes.add(in.readUTF());
            biome[i] = (short) (biomes.size() - 1);
        } else {
            biome[i] = (short) checked(in.readInt(), biomes.size());
        }
        light[i] = (byte) (p >> 8 & 15);
        height[i] = (byte) (p >> 12 & 0xFF);
    }

    private int add(State s) {
        states.add(s);
        return states.size() - 1;
    }

    private static int checked(int index, int size) throws IOException {
        if (index < 0 || index >= size) throw new IOException("palette index " + index + " of " + size);
        return index;
    }

    /** A block state in NBT: a compound (named, the name empty) with the string {@code Name} and the compound {@code Properties}. */
    static State readState(DataInputStream in) throws IOException {
        if (in.readUnsignedByte() != 10) throw new IOException("block state is not a compound");
        in.readUTF();
        String name = null;
        Map<String, String> props = new LinkedHashMap<>();
        for (int type; (type = in.readUnsignedByte()) != 0; ) {
            String key = in.readUTF();
            if (type == 8 && key.equals("Name")) name = in.readUTF();
            else if (type == 10 && key.equals("Properties")) {
                for (int t; (t = in.readUnsignedByte()) != 0; ) {
                    String k = in.readUTF();
                    if (t == 8) props.put(k, in.readUTF());
                    else skip(in, t);
                }
            } else skip(in, type);
        }
        if (name == null) throw new IOException("block state without a name");
        return new State(name, props);
    }

    private static void skip(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3, 5 -> in.readInt();
            case 4, 6 -> in.readLong();
            case 7 -> in.skipNBytes(in.readInt());
            case 8 -> in.readUTF();
            case 9 -> {
                int t = in.readUnsignedByte(), n = in.readInt();
                for (int i = 0; i < n; i++) skip(in, t);
            }
            case 10 -> {
                for (int t; (t = in.readUnsignedByte()) != 0; ) {
                    in.readUTF();
                    skip(in, t);
                }
            }
            case 11 -> in.skipNBytes(4L * in.readInt());
            case 12 -> in.skipNBytes(8L * in.readInt());
            default -> throw new IOException("NBT tag " + type);
        }
    }
}
