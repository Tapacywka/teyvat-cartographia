package net.tapacywka.teyvatcartographia.nav;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The near path over drawn ground: flat land at height 64 with walls, water, ridges and an island on it. The player
 * stands at (0, 0), the goal is 60 blocks east unless said otherwise.
 */
class PathSearchTest {
    /** Drawn ground: rectangles over flat land, the last one drawn wins. */
    private static final class Ground implements Terrain {
        private record Rect(int x0, int z0, int x1, int z1, int height, int water) {}

        private final List<Rect> rects = new ArrayList<>();
        private int water;

        /** Blocks x0..x1, z0..z1 (inclusive): ground at {@code height} (or BLOCKED) with that water. */
        Ground rect(int x0, int z0, int x1, int z1, int height, int water) {
            rects.add(new Rect(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), height, water));
            return this;
        }

        Ground wall(int x0, int z0, int x1, int z1) {
            return rect(x0, z0, x1, z1, BLOCKED, DRY);
        }

        Ground deepWater(int x0, int z0, int x1, int z1) {
            return rect(x0, z0, x1, z1, 63, DEEP);
        }

        Ground shallowWater(int x0, int z0, int x1, int z1) {
            return rect(x0, z0, x1, z1, 64, SHALLOW);
        }

        @Override
        public int ground(int x, int z) {
            for (int i = rects.size() - 1; i >= 0; i--) {
                Rect r = rects.get(i);
                if (x >= r.x0 && x <= r.x1 && z >= r.z0 && z <= r.z1) {
                    water = r.water;
                    return r.height;
                }
            }
            water = DRY;
            return 64;
        }

        @Override
        public int water() {
            return water;
        }

        /** The water under a point of the path. */
        int waterAt(float x, float z) {
            ground((int) Math.floor(x), (int) Math.floor(z));
            return water;
        }

        boolean blockedAt(float x, float z) {
            return ground((int) Math.floor(x), (int) Math.floor(z)) == BLOCKED;
        }
    }

    private static int search(Ground g, double px, double pz, boolean inWater, double gx, double gz) {
        return PathFinder.search(g, px, 64, pz, inWater, gx, gz);
    }

    private static float maxAbsZ() {
        float m = 0;
        for (int i = 0; i < PathFinder.pointCount(); i++) m = Math.max(m, Math.abs(PathFinder.z(i)));
        return m;
    }

    private static boolean endsNear(double gx, double gz, double within) {
        int n = PathFinder.pointCount();
        return n > 0 && Math.hypot(PathFinder.x(n - 1) - gx, PathFinder.z(n - 1) - gz) <= within;
    }

    /**
     * Points of the path well inside deep water (deep for 2 blocks all round: the grid has 2-block cells and the
     * smoothed line may touch an edge), ignoring the first {@code skipNear} blocks along it (a swimmer's way out).
     */
    private static int deepPoints(Ground g, float skipNear) {
        int n = 0;
        for (int i = 0; i < PathFinder.pointCount(); i++) {
            if (i * PathFinder.SPACING < skipNear) continue;
            float x = PathFinder.x(i), z = PathFinder.z(i);
            boolean inside = true;
            for (int dx = -2; dx <= 2 && inside; dx += 2) for (int dz = -2; dz <= 2 && inside; dz += 2) inside = g.waterAt(x + dx, z + dz) == Terrain.DEEP;
            if (inside) {
                n++;
            }
        }
        return n;
    }

    /** Points of the path in a wall, more than a block from its sides (the same tolerance as for water). */
    private static int wallPoints(Ground g) {
        int n = 0;
        for (int i = 0; i < PathFinder.pointCount(); i++) {
            float x = PathFinder.x(i), z = PathFinder.z(i);
            if (g.blockedAt(x, z)) {
                if (g.blockedAt(x - 1, z) && g.blockedAt(x + 1, z) && g.blockedAt(x, z - 1) && g.blockedAt(x, z + 1)) n++;
            }
        }
        return n;
    }

    @Test
    void flatLandGoesStraight() {
        Ground g = new Ground();
        assertTrue(search(g, 0.5, 0.5, false, 60.5, 0.5) > 30);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(endsNear(60.5, 0.5, 3));
        assertTrue(maxAbsZ() < 3, "straight on flat land, max |z| " + maxAbsZ());
    }

    @Test
    void aWallIsWalkedRound() {
        Ground g = new Ground().wall(30, -40, 31, 40);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(maxAbsZ() > 40, "round the end of the wall, max |z| " + maxAbsZ());
        assertTrue(wallPoints(g) == 0, "a point inside the wall");
    }

    @Test
    void deepWaterIsWalkedRound() {
        Ground g = new Ground().deepWater(20, -30, 40, 30);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(deepPoints(g, 0) == 0, "no swimming from the shore");
        assertTrue(maxAbsZ() > 28, "round the lake, max |z| " + maxAbsZ());
    }

    @Test
    void shallowWaterIsWadedThrough() {
        Ground g = new Ground().shallowWater(20, -60, 40, 60);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(maxAbsZ() < 3, "straight through shallow water, max |z| " + maxAbsZ());
    }

    @Test
    void lowStepsAreNotWalkedRound() {
        // B12: a ridge two blocks high across the way, with a level gap 40 blocks aside: straight over it.
        Ground g = new Ground().rect(30, -100, 33, 100, 66, Terrain.DRY).rect(30, 38, 33, 42, 64, Terrain.DRY);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(maxAbsZ() < 5, "over the low ridge, max |z| " + maxAbsZ());
    }

    @Test
    void tooSteepIsWalkedRound() {
        // Four blocks up and down between neighbouring cells cannot be walked: through the gap 40 blocks aside.
        Ground g = new Ground().rect(30, -100, 33, 100, 72, Terrain.DRY).rect(30, 38, 33, 42, 64, Terrain.DRY);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(maxAbsZ() > 34, "through the gap, max |z| " + maxAbsZ());
    }

    @Test
    void aSwimmerSwimsOutToTheShore() {
        // B9: the player in the middle of a lake.
        Ground g = new Ground().deepWater(-30, -30, 30, 30);
        assertTrue(search(g, 0.5, 0.5, true, 60.5, 0.5) > 10);
        assertTrue(PathFinder.reachedGoal());
    }

    @Test
    void fromTheShoreThePathKeepsOutOfTheBay() {
        // B18: on the shore of a bay whose far side is the goal; walking round, not swimming across.
        Ground g = new Ground().deepWater(4, -30, 56, 30);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(deepPoints(g, 0) == 0, "no point in the bay");
    }

    @Test
    void overDeepWaterButNotInItThereIsStillAPath() {
        // B21: flying or in a boat over a lake, the path swims out to the shore like a swimmer's.
        Ground g = new Ground().deepWater(-30, -30, 30, 30);
        assertTrue(search(g, 0.5, 0.5, false, 60.5, 0.5) > 10);
        assertTrue(PathFinder.reachedGoal());
    }

    @Test
    void anIslandGoalIsSwumToFromTheNearestShore() {
        // The goal on an island in a lake too wide to swim by choice: with no other way, the path walks to the shore
        // nearest the island and swims from there (25 blocks), no hang (user request 25.10.2026).
        Ground g = new Ground().deepWater(30, -40, 100, 40).rect(55, -5, 65, 5, 64, Terrain.DRY);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> search(g, 0.5, 0.5, false, 60.5, 0.5));
        assertTrue(PathFinder.reachedGoal(), "on to the island");
        assertTrue(waterPoints() * PathFinder.SPACING <= 32, "only the shortest swim: " + waterPoints() * PathFinder.SPACING + " blocks");
    }

    @Test
    void theSwimStartsFromTheShoreNearestTheIsland() {
        // The island lies close to the north shore: the path walks round to it (not into the water at once).
        Ground g = new Ground().deepWater(10, -60, 110, 40).rect(55, 30, 65, 34, 64, Terrain.DRY);
        search(g, 0.5, 0.5, false, 60.5, 32.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(waterPoints() * PathFinder.SPACING <= 12, "a swim of about 6 blocks: " + waterPoints() * PathFinder.SPACING);
    }

    @Test
    void aNarrowRiverIsSwumAcross() {
        // Ten blocks of deep water across the whole grid: narrower than PathFinder.NARROW, swum on foot.
        Ground g = new Ground().deepWater(28, -300, 37, 300);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal(), "across the river");
        assertTrue(maxAbsZ() < 5, "straight over, max |z| " + maxAbsZ());
        assertTrue(waterPoints() > 0, "the points over the river are marked as water");
    }

    @Test
    void shallowWaterCostsAsMuchAsLand() {
        // A strip of shallow water 20 blocks wide with dry land 6 blocks aside: wading straight through is no dearer than
        // walking (user decision 26.10.2026), so the path does not bend to the dry land.
        Ground g = new Ground().shallowWater(20, -300, 40, 300).rect(20, 5, 40, 7, 64, Terrain.DRY);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(maxAbsZ() < 3, "straight through, max |z| " + maxAbsZ());
    }

    @Test
    void narrowDeepWaterNextToABridgeIsWalkedOverTheBridge() {
        // A river 10 blocks wide with a bridge 6 blocks aside: swimming costs half again as much as walking, so the short
        // way round over the bridge wins.
        Ground g = new Ground().deepWater(28, -300, 37, 300).rect(28, 5, 37, 7, 64, Terrain.DRY);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(deepPoints(g, 0) == 0, "over the bridge");
    }

    @Test
    void aWideRiverIsSwumOnlyWithNoOtherWay() {
        // Twenty-six blocks of deep water across the whole grid: too wide to swim by choice, but there is no way round.
        Ground g = new Ground().deepWater(20, -300, 45, 300);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal(), "across the river after all");
        assertTrue(maxAbsZ() < 5, "straight over, max |z| " + maxAbsZ());
        // With a bridge of land 40 blocks aside, that is taken instead.
        g.rect(20, 38, 45, 42, 64, Terrain.DRY);
        search(g, 0.5, 0.5, false, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(deepPoints(g, 0) == 0, "over the bridge, not through the water");
    }

    @Test
    void byBoatAWideRiverIsCrossed() {
        Ground g = new Ground().deepWater(20, -300, 45, 300);
        PathFinder.search(g, 0.5, 64, 0.5, false, true, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal(), "across the river by boat");
        assertTrue(maxAbsZ() < 5, "straight over, max |z| " + maxAbsZ());
        assertTrue(waterPoints() > 10, "the crossing is marked as water: " + waterPoints());
    }

    @Test
    void byBoatALakeIsCrossedNotWalkedRound() {
        // The lake of deepWaterIsWalkedRound: by boat straight across it.
        Ground g = new Ground().deepWater(20, -30, 40, 30);
        PathFinder.search(g, 0.5, 64, 0.5, false, true, 60.5, 0.5);
        assertTrue(PathFinder.reachedGoal());
        assertTrue(maxAbsZ() < 10, "across the lake, max |z| " + maxAbsZ());
    }

    private static int waterPoints() {
        int n = 0;
        for (int i = 0; i < PathFinder.pointCount(); i++) if (PathFinder.water(i)) n++;
        return n;
    }

    @Test
    void swimmingCountsTheWaterInARow() {
        assertTrue(PathFinder.swimAcross(0, false, false, 2) == 2, "into the water from land");
        assertTrue(PathFinder.swimAcross(10, true, false, 2) == 12, "on through the water");
        assertTrue(PathFinder.swimAcross(10, true, false, 2.8f) > PathFinder.NARROW - 4, "diagonal steps count their length");
        assertTrue(PathFinder.swimAcross(0, false, true, 2) == Float.NEGATIVE_INFINITY, "out of the player's own water: no limit");
        assertTrue(PathFinder.swimAcross(Float.NEGATIVE_INFINITY, true, false, 2) == Float.NEGATIVE_INFINITY, "and on from it");
    }

    @Test
    void theGoalOutsideTheGridIsHeadedFor() {
        // A goal beyond the search radius: the path ends at the edge, towards it.
        Ground g = new Ground();
        search(g, 0.5, 0.5, false, 500.5, 0.5);
        int n = PathFinder.pointCount();
        assertTrue(n > 0 && PathFinder.x(n - 1) > 100, "towards the far goal");
    }
}
