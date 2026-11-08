package net.tapacywka.teyvatcartographia.map.data;

import java.util.Arrays;

/**
 * The rules of the far land (user decisions 07.11.2026), apart from the game so they can be tested: the land beyond what
 * was explored, worked out from the world's generator, as a faint veil over the Fog of War with a brighter line along the
 * coast (the look chosen of four mockups on 04.11.2026), the water left as the fog.
 * <p>
 * One silhouette at every zoom (user decision 08.11.2026: the land was drawn anew at each zoom, sharper the nearer, and
 * looked strange): the generator is asked on one grid, every {@link #FINE} blocks, whatever the zoom, and each level only
 * draws those answers in pixels of its size ({@link #STEPS}), blended between them before the line between land and sea
 * is drawn (a point for each pixel came out in jagged steps, full of specks, 07.11.2026). Zoomed nearer than the finest
 * level, its picture is drawn larger. What it costs follows the screen, not the world (the map to the world border is 14
 * trillion chunks), and only the coasts cost much: the answers every {@link #SQUARE} blocks come first, and a square whose
 * four corners stand clear of the line ({@link #SURE_MARGIN}) is all land or all water at once ({@link #sure}).
 * <p>
 * A "cell" here is 16 x 16 pixels of a level. Its land and water are a mask of 256 bits, bit {@code z * 16 + x}, set for
 * water.
 */
public final class FarLandRules {
    private FarLandRules() {}

    /** Blocks one pixel stands for, level by level (GUI 3: zoom 1/8 and nearer, 1/16, 1/32). */
    public static final int[] STEPS = {2, 4, 8};

    /** The generator is asked every so many blocks, at every level (98.5% as the real map). */
    static final int FINE = 8;

    /** The first look: answers this far apart, which settle a square of land or water at once. */
    static final int SQUARE = 32;

    /** Answers of {@link #FINE} along a side of a {@link #SQUARE}, both ends with it. */
    static final int SIDE = SQUARE / FINE + 1;

    /** Screen pixels a block takes from which a level is shown (GUI 3: zoom 1/8 and nearer, 1/16, 1/32). */
    private static final double[] FROM = {0.3, 0.15, 0};

    /** How far around the player each level is worked out while the map is closed, in radii of {@link FarLand#RADIUS}. */
    private static final int[] HOME_RADII = {1, 2, 3};

    /** An answer of water with no more than this many neighbours of water is no water ({@link #settled}). */
    private static final int LONE_WATER = 1;

    /** A value this far over the line is land for sure: the dearer densities need not be asked ({@code FarLandSource}). */
    static final float MARGIN = 0.02f;

    /**
     * How far all four corners of a {@link #SQUARE} must stand from the line for it to be taken as sure. Measured by the
     * trial (08.11.2026, four windows of 2048 blocks): 60% of the questions, 0.06% of the pixels unlike every place asked
     * (ponds in the land, mostly); 0.15 kept 70% for 0.03%, 0.07 52% for 0.13%.
     */
    static final float SURE_MARGIN = 0.1f;

    /** Land: a pale veil at 17%; the coast (land beside water): a brighter line at 45%. */
    static final int LAND_ABGR = abgr(43, 200, 212, 232), COAST_ABGR = abgr(115, 225, 232, 245);

    /** What the corners of a square say. */
    static final int UNSURE = 0, LAND = 1, WATER = 2;

    /** A cell of land, and of water; shared, never written to. */
    static final long[] ALL_LAND = {0, 0, 0, 0}, ALL_WATER = {-1, -1, -1, -1};

    private static final short[] NO_HEIGHTS = new short[256];
    private static final byte[] NO_LIGHT = new byte[256], WATER_FLAGS = new byte[256];
    private static final int[] NO_PIXELS = new int[256];

    static {
        Arrays.fill(WATER_FLAGS, (byte) (MapTile.FLAG_KNOWN | MapTile.FLAG_WATER));
    }

    /** The level to show when a block takes {@code pixelsPerBlock} screen pixels. */
    public static int level(double pixelsPerBlock) {
        for (int i = 0; i < FROM.length; i++) if (pixelsPerBlock >= FROM[i]) return i;
        return FROM.length - 1;
    }

    /** How far around the player a level is worked out, in blocks, for a {@code radius}. */
    static int homeRadius(int level, int radius) {
        return HOME_RADII[level] * radius;
    }

    /** Whether the middle of cell (x, z) of a level with this {@code step} lies within {@code radius} blocks of a place. */
    static boolean inCircle(int x, int z, int step, double px, double pz, double radius) {
        double half = 8.0 * step, dx = x * 16.0 * step + half - px, dz = z * 16.0 * step + half - pz;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** What one answer says: far enough over the line land, far enough under it water, else unsure. */
    static int sure(float value) {
        return value > SURE_MARGIN ? LAND : value < -SURE_MARGIN ? WATER : UNSURE;
    }

    /** What the ends of a side of a square say together: land or water when both are sure of it, else unsure. */
    static int sure(float a, float b) {
        int k = sure(a);
        return k != UNSURE && sure(b) == k ? k : UNSURE;
    }

    /** What the corners of a square say together: land or water when all are sure of it, else unsure. */
    static int sure(float a, float b, float c, float d) {
        int k = sure(a);
        return k != UNSURE && sure(b) == k && sure(c) == k && sure(d) == k ? k : UNSURE;
    }

    /**
     * One answer with its eight neighbours {@link #FINE} blocks around: water with at most one of them water is a pond or
     * a cave met by chance. Blown up to the spacing it would be a black speck, and thousands of them covered the land at
     * the farthest zoom (seen in game 07.11.2026), so it counts as the land around it. Islands stay.
     */
    static float settled(float value, float[] around) {
        if (value > 0) return value;
        int water = 0;
        float land = 0;
        for (float v : around) {
            if (v <= 0) water++;
            else land += v;
        }
        return water <= LONE_WATER ? land / (around.length - water) : value;
    }

    /**
     * The pixels of one {@link #SQUARE} of a level with this {@code step}, from the answers in it: {@code answers} holds
     * {@link #SIDE} squared values, row by row, over 0 on land; each pixel is water where the blend of the four around its
     * middle is not. They go into {@code mask} from pixel (x0, z0), {@code SQUARE / step} on a side.
     */
    static void blend(float[] answers, int step, long[] mask, int x0, int z0) {
        int n = SQUARE / step;
        for (int z = 0; z < n; z++) {
            float v = (z + 0.5f) * step / FINE;
            int row = (int) v;
            float fz = v - row;
            for (int x = 0; x < n; x++) {
                float u = (x + 0.5f) * step / FINE;
                int col = (int) u;
                float fx = u - col;
                int i = row * SIDE + col;
                float top = answers[i] * (1 - fx) + answers[i + 1] * fx, bottom = answers[i + SIDE] * (1 - fx) + answers[i + SIDE + 1] * fx;
                if (top * (1 - fz) + bottom * fz <= 0) setWater(mask, x0 + x, z0 + z);
            }
        }
    }

    /** All the pixels of one {@link #SQUARE} of a level with this {@code step} water, from pixel (x0, z0). */
    static void fillWater(long[] mask, int step, int x0, int z0) {
        int n = SQUARE / step;
        for (int z = z0; z < z0 + n; z++) for (int x = x0; x < x0 + n; x++) setWater(mask, x, z);
    }

    /** The generator's answers, place (gx, gz) every {@link #FINE} blocks: over 0 on land. */
    interface Grid {
        float asked(int gx, int gz);
    }

    /** Answers along a side of a square, less one. */
    private static final int PER = SQUARE / FINE;

    /**
     * The mask of cell (x, z) of a level with pixels of {@code step} blocks, square by square: one whose corners are sure
     * of one thing ({@link #sure}) is that at once, the others are blended from every answer in them. {@code square} and
     * {@code around} are the caller's to write in ({@link #SIDE} squared, 8). The masks of all land and all water are
     * {@link #ALL_LAND} and {@link #ALL_WATER}.
     */
    static long[] cell(Grid grid, int x, int z, int step, float[] square, float[] around) {
        int squares = 16 * step / SQUARE, n = SQUARE / step;
        int gx0 = x * 16 * step / FINE, gz0 = z * 16 * step / FINE;
        long[] mask = null;
        int land = 0, water = 0;
        for (int sz = 0; sz < squares; sz++) {
            for (int sx = 0; sx < squares; sx++) {
                int gx = gx0 + sx * PER, gz = gz0 + sz * PER;
                int kind = sure(grid.asked(gx, gz), grid.asked(gx + PER, gz), grid.asked(gx, gz + PER), grid.asked(gx + PER, gz + PER));
                if (kind == LAND) {
                    land++;
                    continue;
                }
                if (mask == null) mask = new long[4];
                if (kind == WATER) {
                    water++;
                    fillWater(mask, step, sx * n, sz * n);
                    continue;
                }
                for (int j = 0; j < SIDE; j++) {
                    for (int i = 0; i < SIDE; i++) square[j * SIDE + i] = settled(grid, gx + i, gz + j, around);
                }
                blend(square, step, mask, sx * n, sz * n);
            }
        }
        int all = squares * squares;
        return land == all ? ALL_LAND : water == all ? ALL_WATER : mask;
    }

    /** The answer at (gx, gz), a lone pond counted as the land around it ({@link #settled(float, float[])}). */
    static float settled(Grid grid, int gx, int gz, float[] around) {
        float v = at(grid, gx, gz);
        if (v > 0) return v;
        int n = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) if (dx != 0 || dz != 0) around[n++] = at(grid, gx + dx, gz + dz);
        }
        return settled(v, around);
    }

    /**
     * The answer at (gx, gz): drawn between the corners of its square when they are sure of one thing, else asked. On a
     * side, between the side's two ends alone, so the squares on both sides say the same there whatever their other corners.
     */
    static float at(Grid grid, int gx, int gz) {
        int rx = Math.floorMod(gx, PER), rz = Math.floorMod(gz, PER), cx = gx - rx, cz = gz - rz;
        if (rx == 0 && rz == 0) return grid.asked(gx, gz);
        if (rz == 0) {
            float a = grid.asked(cx, cz), b = grid.asked(cx + PER, cz);
            if (sure(a, b) != UNSURE) return a + (b - a) * rx / PER;
        } else if (rx == 0) {
            float a = grid.asked(cx, cz), b = grid.asked(cx, cz + PER);
            if (sure(a, b) != UNSURE) return a + (b - a) * rz / PER;
        } else {
            float a = grid.asked(cx, cz), b = grid.asked(cx + PER, cz), c = grid.asked(cx, cz + PER), d = grid.asked(cx + PER, cz + PER);
            if (sure(a, b, c, d) != UNSURE) {
                float fx = rx / (float) PER, fz = rz / (float) PER;
                return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
            }
        }
        return grid.asked(gx, gz);
    }

    static boolean water(long[] cell, int x, int z) {
        int i = z * 16 + x;
        return (cell[i >> 6] >>> (i & 63) & 1L) != 0;
    }

    static void setWater(long[] cell, int x, int z) {
        int i = z * 16 + x;
        cell[i >> 6] |= 1L << (i & 63);
    }

    /** The world border, in blocks. */
    public record Bounds(double minX, double minZ, double maxX, double maxZ) {
        /** Whether all of cell (x, z) of a level with this {@code step} lies beyond the border. */
        boolean cellOutside(int x, int z, int step) {
            double size = 16.0 * step, x0 = x * size, z0 = z * size;
            return x0 + size <= minX || z0 + size <= minZ || x0 >= maxX || z0 >= maxZ;
        }

        boolean contains(double x, double z) {
            return x >= minX && x < maxX && z >= minZ && z < maxZ;
        }
    }

    /**
     * The picture of cell (x, z) of a level with this {@code step}: {@code own} and the cells beside it, land a veil, land
     * beside water the coast, water and what lies beyond the border nothing.
     */
    static ChunkShader.Result shade(int x, int z, int step, long[] own, long[] north, long[] south, long[] west, long[] east, Bounds bounds) {
        boolean inside = !bounds.cellOutside(x, z, step);
        if (!inside || Arrays.equals(own, ALL_WATER)) return new ChunkShader.Result(x, z, NO_PIXELS, NO_HEIGHTS, WATER_FLAGS, NO_LIGHT, 0, null, false);
        int[] pixels = new int[256];
        byte[] flags = new byte[256];
        for (int cz = 0; cz < 16; cz++) {
            for (int cx = 0; cx < 16; cx++) {
                int i = cz * 16 + cx;
                double wx = ((double) x * 16 + cx + 0.5) * step, wz = ((double) z * 16 + cz + 0.5) * step;
                if (water(own, cx, cz) || !bounds.contains(wx, wz)) {
                    flags[i] = MapTile.FLAG_KNOWN | MapTile.FLAG_WATER;
                    continue;
                }
                flags[i] = MapTile.FLAG_KNOWN;
                boolean coast = (cx > 0 ? water(own, cx - 1, cz) : water(west, 15, cz)) || (cx < 15 ? water(own, cx + 1, cz) : water(east, 0, cz))
                        || (cz > 0 ? water(own, cx, cz - 1) : water(north, cx, 15)) || (cz < 15 ? water(own, cx, cz + 1) : water(south, cx, 0));
                pixels[i] = coast ? COAST_ABGR : LAND_ABGR;
            }
        }
        return new ChunkShader.Result(x, z, pixels, NO_HEIGHTS, flags, NO_LIGHT, 0, null, false);
    }

    /** The cells around a centre, ring by ring outwards: the middle of what is looked at comes first. */
    static final class Spiral {
        private final int centerX, centerZ, lastRing;
        private int ring, index;
        private int x, z;
        private boolean begun;

        /** Up to and with ring {@code lastRing}. */
        Spiral(int centerX, int centerZ, int lastRing) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.lastRing = lastRing;
        }

        /** Moves on to the next cell; false after the last one. */
        boolean next() {
            if (!begun) {
                begun = true;
                x = centerX;
                z = centerZ;
                return true;
            }
            if (ring == 0 || ++index >= 8 * ring) {
                ring++;
                index = 0;
            }
            if (ring > lastRing) return false;
            int side = index / (2 * ring), along = index % (2 * ring);
            switch (side) {
                case 0 -> {
                    x = centerX - ring + along;
                    z = centerZ - ring;
                }
                case 1 -> {
                    x = centerX + ring;
                    z = centerZ - ring + along;
                }
                case 2 -> {
                    x = centerX + ring - along;
                    z = centerZ + ring;
                }
                default -> {
                    x = centerX - ring;
                    z = centerZ + ring - along;
                }
            }
            return true;
        }

        int x() {
            return x;
        }

        int z() {
            return z;
        }
    }

    private static int abgr(int a, int r, int g, int b) {
        return a << 24 | b << 16 | g << 8 | r;
    }
}
