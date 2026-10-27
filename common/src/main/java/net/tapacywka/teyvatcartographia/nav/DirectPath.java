package net.tapacywka.teyvatcartographia.nav;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.TileStore;

/**
 * The path as a straight line from the player to the target, whatever lies between (setting
 * {@link MapConfig#PATH_ROUTE} AIR, user request 27.10.2026: for flying with elytra or aircraft). Laid once per
 * {@link Navigation#pathRequest} from where the player stands then, like the searched paths, with a point every few
 * blocks so the walked part is cut off and the stars follow it as on any line; the points in unexplored land are marked
 * as fog. No search runs meanwhile ({@link PathFinder}, {@link FarPath}).
 */
public final class DirectPath {
    private DirectPath() {}

    /** Blocks between the points of the line; a longer line gets fewer, at most {@link #MAX_POINTS}. */
    static final float SPACING = 8;
    static final int MAX_POINTS = 1024;

    private static final float[] points = new float[2 * MAX_POINTS];
    private static final boolean[] fog = new boolean[MAX_POINTS];
    private static int count, version, handled = -1;

    /** Whether the path goes straight to the target. */
    public static boolean active() {
        return MapConfig.PATH_ROUTE.get() == MapConfig.Route.AIR;
    }

    /** Client tick: lays the line once per request while it is shown, clears it when the setting changes or nothing is tracked. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Navigation.Target t = Navigation.target();
        if (!active() || p == null || !Navigation.inPlayerDimension(t, p)) {
            clear();
            return;
        }
        if (Navigation.pathRequest() == handled || !Navigation.pathWanted()) return;
        handled = Navigation.pathRequest();
        count = line(p.getX(), p.getZ(), t.x(), t.z(), points);
        TileStore store = MapDataManager.get().active();
        for (int i = 0; i < count; i++) {
            fog[i] = store != null && !store.hasChunk((int) Math.floor(points[2 * i]) >> 4, (int) Math.floor(points[2 * i + 1]) >> 4);
        }
        version++;
    }

    private static void clear() {
        if (count > 0) version++;
        count = 0;
        handled = -1;
    }

    /**
     * Fills {@code out} with the points (x, z) of a straight line from (px, pz) to (tx, tz): both ends and evenly spaced
     * points between, about {@link #SPACING} blocks apart, at most {@link #MAX_POINTS} in all. Returns their number.
     */
    static int line(double px, double pz, double tx, double tz, float[] out) {
        double d = Math.hypot(tx - px, tz - pz);
        int n = Math.min(MAX_POINTS, Math.max(2, (int) Math.ceil(d / SPACING) + 1));
        for (int i = 0; i < n; i++) {
            double f = (double) i / (n - 1);
            out[2 * i] = (float) (px + (tx - px) * f);
            out[2 * i + 1] = (float) (pz + (tz - pz) * f);
        }
        return n;
    }

    public static int pointCount() {
        return count;
    }

    public static float x(int i) {
        return points[2 * i];
    }

    public static float z(int i) {
        return points[2 * i + 1];
    }

    public static boolean fog(int i) {
        return fog[i];
    }

    public static int version() {
        return version;
    }
}
