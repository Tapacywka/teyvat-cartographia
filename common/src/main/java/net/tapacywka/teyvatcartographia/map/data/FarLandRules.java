package net.tapacywka.teyvatcartographia.map.data;

import java.util.Arrays;

/**
 * The rules of the far land (user decisions 07.11.2026), apart from the game so they can be tested: the land beyond what
 * was explored, worked out from the world's generator, as a faint veil over the Fog of War with a brighter line along the
 * coast (the look chosen of four mockups on 04.11.2026), the water left as the fog.
 * <p>
 * What it costs follows the screen, not the world (the map to the world border is 14 trillion chunks): each zoom has its
 * level, how many blocks one pixel of the level's map stands for ({@link #STEPS}), so a whole screen is about the same
 * work at every zoom. A coarse level asks the generator only every {@link #SAMPLE} pixels and blends between the answers
 * before it draws the line between land and sea: a point for each pixel of the same few points came out in jagged steps,
 * full of specks (seen in game 07.11.2026). A "cell" here is 16 x 16 pixels of a level: a chunk at step 1. Its land and
 * water are a mask of 256 bits, bit {@code z * 16 + x}, set for water.
 */
public final class FarLandRules {
    private FarLandRules() {}

    /** Blocks one pixel stands for, level by level: every column at 0, then ever coarser. */
    public static final int[] STEPS = {1, 2, 4, 8};

    /** On a coarse level the generator is asked every so many pixels (8, 16 and 32 blocks: 98.5, 97.5 and 95.9% as the real map). */
    static final int SAMPLE = 4;

    /** Screen pixels a block takes from which a level is shown (GUI 3: zoom 1/4 and nearer, 1/8, 1/16, 1/32). */
    private static final double[] FROM = {0.6, 0.3, 0.15, 0};

    /** How far around the player each level is worked out while the map is closed, in radii of the setting. */
    private static final int[] HOME_RADII = {1, 2, 3, 4};

    /** An answer of water with no more than this many neighbours of water is no water at a coarse level ({@link #settled}). */
    private static final int LONE_WATER = 1;

    /** How far a value must stand from the line between land and sea for a chunk to be taken as sure. */
    static final float MARGIN = 0.02f;

    /** Land: a pale veil at 17%; the coast (land beside water): a brighter line at 45%. */
    static final int LAND_ABGR = abgr(43, 200, 212, 232), COAST_ABGR = abgr(115, 225, 232, 245);

    /** What the corners of a chunk say. */
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

    /** How far around the player a level is worked out, in blocks, for the setting's {@code radius}. */
    static int homeRadius(int level, int radius) {
        return HOME_RADII[level] * radius;
    }

    /** Whether the middle of cell (x, z) of a level with this {@code step} lies within {@code radius} blocks of a place. */
    static boolean inCircle(int x, int z, int step, double px, double pz, double radius) {
        double half = 8.0 * step, dx = x * 16.0 * step + half - px, dz = z * 16.0 * step + half - pz;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** The nine corners of a chunk: all over the line land, all under it water, else it must be read column by column. */
    static int kind(float[] corners) {
        boolean land = true, water = true;
        for (float v : corners) {
            if (v <= MARGIN) land = false;
            if (v >= -MARGIN) water = false;
        }
        return land ? LAND : water ? WATER : UNSURE;
    }

    /**
     * One answer of a coarse level, with its eight neighbours in the same spacing: water with at most one of them water
     * is a pond or a cave met by chance. Blown up to the spacing it would be a black speck, and thousands of them covered
     * the land at the farthest zoom (seen in game 07.11.2026), so it counts as the land around it. Islands stay.
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
     * The mask of a coarse cell from the answers at its corners: {@code corners} holds (16 / {@link #SAMPLE} + 1) squared
     * values, row by row, over 0 on land; each pixel is water where the blend of the four around its middle is not.
     */
    static long[] blend(float[] corners) {
        int side = 16 / SAMPLE + 1;
        long[] mask = new long[4];
        for (int z = 0; z < 16; z++) {
            float v = (z + 0.5f) / SAMPLE;
            int row = (int) v;
            float fz = v - row;
            for (int x = 0; x < 16; x++) {
                float u = (x + 0.5f) / SAMPLE;
                int col = (int) u;
                float fx = u - col;
                int i = row * side + col;
                float top = corners[i] * (1 - fx) + corners[i + 1] * fx, bottom = corners[i + side] * (1 - fx) + corners[i + side + 1] * fx;
                if (top * (1 - fz) + bottom * fz <= 0) setWater(mask, x, z);
            }
        }
        return mask;
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
