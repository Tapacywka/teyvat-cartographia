package net.tapacywka.teyvatcartographia.nav;

/**
 * The ground the near path ({@link PathFinder}) is searched over, one column at a time: in the game the loaded level
 * ({@link LevelTerrain}), in the unit tests a drawn grid.
 */
interface Terrain {
    /** A column that cannot be walked (a tree trunk, outside the loaded chunks, nothing found). */
    int BLOCKED = Integer.MIN_VALUE + 1;
    int DRY = 0, SHALLOW = 1, DEEP = 2;

    /**
     * Walkable height of the column at block (x, z): the block above the ground (under leaves the ground below the
     * canopy) or the surface of its water; {@link #BLOCKED} if it cannot be walked. {@link #water} then tells the
     * column's water.
     */
    int ground(int x, int z);

    /** The water of the column last read by {@link #ground}: {@link #DRY}, {@link #SHALLOW} (one block) or {@link #DEEP}. */
    int water();
}
