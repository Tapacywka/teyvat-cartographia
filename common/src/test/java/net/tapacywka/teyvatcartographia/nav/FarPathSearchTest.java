package net.tapacywka.teyvatcartographia.nav;

import net.tapacywka.teyvatcartographia.map.data.MapTile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The far path over a drawn map: explored flat land at height 64 with lakes, lava and unexplored land on it. The player
 * stands at (0, 0), the target is 300 blocks east.
 */
class FarPathSearchTest {
    private static final byte LAND = MapTile.FLAG_KNOWN, DEEP = MapTile.FLAG_KNOWN | MapTile.FLAG_WATER | MapTile.FLAG_IMPASSABLE,
            LAVA = MapTile.FLAG_KNOWN | MapTile.FLAG_IMPASSABLE, FOG = 0;

    /** A drawn map: rectangles over explored flat land, the last one drawn wins. */
    private static final class Map implements MapGround {
        private record Rect(int x0, int z0, int x1, int z1, byte flags, int height) {}

        private final List<Rect> rects = new ArrayList<>();

        Map rect(int x0, int z0, int x1, int z1, byte flags, int height) {
            rects.add(new Rect(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), flags, height));
            return this;
        }

        private Rect at(int x, int z) {
            for (int i = rects.size() - 1; i >= 0; i--) {
                Rect r = rects.get(i);
                if (x >= r.x0 && x <= r.x1 && z >= r.z0 && z <= r.z1) return r;
            }
            return null;
        }

        @Override
        public boolean has(int x, int z) {
            return true;
        }

        @Override
        public byte flags(int x, int z) {
            Rect r = at(x, z);
            return r == null ? LAND : r.flags;
        }

        @Override
        public int height(int x, int z) {
            Rect r = at(x, z);
            return r == null ? 64 : r.height;
        }
    }

    private static FarPath walk(Map m) {
        return FarPath.search(m, 0.5, 65, 0.5, false, false, 300, 0);
    }

    private static float maxAbsZ(FarPath f) {
        float most = 0;
        for (int i = 0; i < f.pointCount; i++) most = Math.max(most, Math.abs(f.pz(i)));
        return most;
    }

    private static int waterPoints(FarPath f) {
        int n = 0;
        for (int i = 0; i < f.pointCount; i++) if (f.water[i]) n++;
        return n;
    }

    private static boolean ends(FarPath f) {
        int last = f.pointCount - 1;
        return last > 0 && Math.hypot(f.px(last) - 300, f.pz(last)) < 12;
    }

    @Test
    void flatLandStraightThere() {
        FarPath f = walk(new Map());
        assertTrue(f.reachedGoal());
        assertTrue(ends(f), "ends at the target");
        assertTrue(maxAbsZ(f) < 8, "straight: " + maxAbsZ(f));
        for (int i = 0; i < f.pointCount; i++) assertFalse(f.fogAt(i), "no fog on explored land");
        assertEquals(0, waterPoints(f));
    }

    @Test
    void aWideLakeIsWalkedAround() {
        FarPath f = walk(new Map().rect(100, -60, 200, 60, DEEP, 62));
        assertTrue(f.reachedGoal());
        // The cells go round; the soft arcs laid through them keep clear of blocked cells only, so they may cut a corner
        // of the lake by a point or two.
        assertTrue(waterPoints(f) <= 2, "on foot never across 100 blocks of water: " + waterPoints(f) + " points on it");
        for (int i = 0; i < f.pointCount; i++) {
            if (f.px(i) > 110 && f.px(i) < 190) assertTrue(Math.abs(f.pz(i)) > 56, "beside the lake, not over it: " + f.px(i) + ", " + f.pz(i));
        }
        assertTrue(maxAbsZ(f) > 60, "round the lake: " + maxAbsZ(f));
    }

    @Test
    void narrowWaterIsSwumAcross() {
        // A river 12 blocks wide across the whole map: no way round, narrow enough to swim.
        FarPath f = walk(new Map().rect(150, -2000, 161, 2000, DEEP, 62));
        assertTrue(f.reachedGoal());
        assertTrue(waterPoints(f) > 0, "across the river");
        assertTrue(maxAbsZ(f) < 16, "straight across: " + maxAbsZ(f));
    }

    @Test
    void lavaOnlyThroughItsGap() {
        // A wall of lava across the map with a gap at z 200.
        FarPath f = walk(new Map().rect(150, -2000, 165, 2000, LAVA, 64).rect(150, 192, 165, 215, LAND, 64));
        assertTrue(f.reachedGoal());
        boolean throughGap = false;
        for (int i = 0; i < f.pointCount; i++) {
            if (f.px(i) >= 150 && f.px(i) <= 165) {
                assertTrue(f.pz(i) > 185 && f.pz(i) < 222, "in the lava band only through the gap: " + f.px(i) + ", " + f.pz(i));
                throughGap = true;
            }
        }
        assertTrue(throughGap);
    }

    @Test
    void byBoatStraightAcrossTheLake() {
        FarPath f = FarPath.search(new Map().rect(100, -60, 200, 60, DEEP, 62), 0.5, 65, 0.5, false, true, 300, 0);
        assertTrue(f.reachedGoal());
        assertTrue(waterPoints(f) > 0, "on the lake");
        assertTrue(maxAbsZ(f) < 16, "straight across: " + maxAbsZ(f));
    }

    @Test
    void unexploredLandIsCrossedAndMarked() {
        FarPath f = walk(new Map().rect(120, -2000, 180, 2000, FOG, 0));
        assertTrue(f.reachedGoal());
        int fog = 0;
        for (int i = 0; i < f.pointCount; i++) {
            if (f.fogAt(i)) {
                fog++;
                assertTrue(f.px(i) > 110 && f.px(i) < 190, "fog points only in the band: " + f.px(i));
            }
        }
        assertTrue(fog > 0, "the part through the fog is marked");
    }

    @Test
    void wideWaterWithNoWayRoundIsSwumAsLittleAsItMust() {
        // A sea 100 blocks wide across the whole map: the second search swims it.
        FarPath f = walk(new Map().rect(150, -2000, 249, 2000, DEEP, 62));
        assertTrue(f.reachedGoal(), "the forced search still reaches the target");
        assertTrue(waterPoints(f) > 0);
        assertTrue(maxAbsZ(f) < 16, "straight across, no detour along the shore: " + maxAbsZ(f));
    }
}
