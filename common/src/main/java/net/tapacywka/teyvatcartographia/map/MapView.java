package net.tapacywka.teyvatcartographia.map;

/**
 * The shared coordinate system of the world map and the minimap.
 * <ul>
 *   <li>World space: Minecraft block X/Z (double, may be negative). +X = east, +Z = south.</li>
 *   <li>Map space: world space relative to {@link #centerX}/{@link #centerZ}, multiplied by {@link #scale}.</li>
 *   <li>Screen space: GUI pixels; map space rotated by {@link #rotation} around the screen anchor (cx, cy).</li>
 * </ul>
 * A rotation of 0 means north is up.
 */
public final class MapView {
    /**
     * Zoom levels of the world map and the minimap (GUI pixels per block): 1 is the middle, doubling up to 32 and
     * halving down to 1/32 (user request 25.10.2026: no 1/3 and 3). At 1/16 and 1/32 only the tracked target keeps its
     * pin, and no biome names or region lines are drawn ({@link #DETAIL_FROM}).
     */
    public static final double[] ZOOM_STEPS = {1 / 32.0, 1 / 16.0, 1 / 8.0, 1 / 4.0, 1 / 2.0, 1, 2, 4, 8, 16, 32};
    /** Index of 1 in {@link #ZOOM_STEPS}. */
    public static final int ZOOM_DEFAULT = 5;
    private static final String[] ZOOM_LABELS = {"1/32", "1/16", "1/8", "1/4", "1/2", "1", "2", "4", "8", "16", "32"};
    /** The smallest zoom with pins, biome names and region lines on the maps: 1/8 (hidden at 1/16 and 1/32). */
    public static final double DETAIL_FROM = 1 / 8.0;

    /** Index of the zoom step closest to {@code scale} (compared in log space). */
    public static int nearestZoomStep(double scale) {
        double l = Math.log(Math.max(1e-6, scale));
        int best = 0;
        for (int i = 1; i < ZOOM_STEPS.length; i++) {
            if (Math.abs(Math.log(ZOOM_STEPS[i]) - l) < Math.abs(Math.log(ZOOM_STEPS[best]) - l)) best = i;
        }
        return best;
    }

    /** The zoom step closest to {@code scale}. */
    public static double snapZoom(double scale) {
        return ZOOM_STEPS[nearestZoomStep(scale)];
    }

    /** The step one up ({@code dir} 1) or down (-1) from {@code scale}, clamped to the ends. */
    public static double stepZoom(double scale, int dir) {
        return ZOOM_STEPS[Math.max(0, Math.min(ZOOM_STEPS.length - 1, nearestZoomStep(scale) + dir))];
    }

    /** "1/4", "1", "8": the step closest to {@code scale} as shown in the settings. */
    public static String zoomLabel(double scale) {
        return ZOOM_LABELS[nearestZoomStep(scale)];
    }

    public double centerX, centerZ;
    /** GUI pixels per block. */
    public double scale = 1.0;
    /** Rotation of the world around the anchor, radians, clockwise on screen. */
    public double rotation;
    /** Screen anchor (where {@link #centerX}/{@link #centerZ} is drawn). */
    public float cx, cy;

    private double cos = 1, sin = 0;

    public MapView set(double centerX, double centerZ, double scale, double rotation, float cx, float cy) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.scale = scale;
        this.cx = cx;
        this.cy = cy;
        setRotation(rotation);
        return this;
    }

    private void setRotation(double rotation) {
        this.rotation = rotation;
        this.cos = Math.cos(rotation);
        this.sin = Math.sin(rotation);
    }

    public float screenX(double wx, double wz) {
        double dx = (wx - centerX) * scale, dz = (wz - centerZ) * scale;
        return (float) (cx + dx * cos - dz * sin);
    }

    public float screenY(double wx, double wz) {
        double dx = (wx - centerX) * scale, dz = (wz - centerZ) * scale;
        return (float) (cy + dx * sin + dz * cos);
    }

    public double worldX(double sx, double sy) {
        double dx = sx - cx, dy = sy - cy;
        return centerX + (dx * cos + dy * sin) / scale;
    }

    public double worldZ(double sx, double sy) {
        double dx = sx - cx, dy = sy - cy;
        return centerZ + (-dx * sin + dy * cos) / scale;
    }

    /** Screen angle (radians, clockwise from up) of something facing along Minecraft yaw {@code yawDeg}. */
    public double headingOnScreen(float yawDeg) {
        return Math.toRadians(yawDeg) + Math.PI + rotation;
    }

    /** Rotation that puts the given Minecraft yaw at the top of the screen. */
    public static double rotationForYaw(float yawDeg) {
        return Math.PI - Math.toRadians(yawDeg);
    }

    // ---------------------------------------------------------------- grid helpers

    public static final int TILE_BITS = 8;
    public static final int TILE_SIZE = 1 << TILE_BITS; // 256 blocks per map region
    public static final int CHUNKS_PER_TILE = TILE_SIZE >> 4;

    public static int blockToTile(int block) {
        return block >> TILE_BITS; // arithmetic shift == floorDiv for negatives
    }

    public static int chunkToTile(int chunk) {
        return chunk >> (TILE_BITS - 4);
    }

    public static long tileKey(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
    }

    public static int tileKeyX(long key) {
        return (int) (key >> 32);
    }

    public static int tileKeyZ(long key) {
        return (int) key;
    }
}
