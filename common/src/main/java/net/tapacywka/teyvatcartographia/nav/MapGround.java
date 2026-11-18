package net.tapacywka.teyvatcartographia.nav;

import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.MapTile;
import net.tapacywka.teyvatcartographia.map.data.TileStore;

/**
 * The explored map the far path ({@link FarPath}) is searched over, a column at a time: in the game the stored map
 * regions ({@link Store}), in the unit tests a drawn map. A column is read in two steps, so a region is looked up once
 * for the columns of a cell: {@link #has} finds the region of a block, {@link #flags} and {@link #height} read a column
 * of that region.
 */
interface MapGround {
    /** Whether the region of block (x, z) is in memory; the next reads are of it. Without it the cell is fog. */
    boolean has(int x, int z);

    /** The map flags of the column at block (x, z) ({@link MapTile#FLAG_KNOWN}, {@code FLAG_WATER}, {@code FLAG_IMPASSABLE}). */
    byte flags(int x, int z);

    /** The height of the column's ground (or water surface) at block (x, z). */
    int height(int x, int z);

    /** The stored map regions of a dimension, as they are in memory (none is read from disk here). */
    final class Store implements MapGround {
        private TileStore store;
        private MapTile tile;

        void use(TileStore store) {
            this.store = store;
            tile = null;
        }

        @Override
        public boolean has(int x, int z) {
            tile = store.peek(MapView.blockToTile(x), MapView.blockToTile(z));
            return tile != null;
        }

        @Override
        public byte flags(int x, int z) {
            return tile.flags(x & (MapTile.SIZE - 1), z & (MapTile.SIZE - 1));
        }

        @Override
        public int height(int x, int z) {
            return tile.height(x & (MapTile.SIZE - 1), z & (MapTile.SIZE - 1));
        }
    }
}
