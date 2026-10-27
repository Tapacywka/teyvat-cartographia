package net.tapacywka.teyvatcartographia.nav;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** The loaded level as the near path's {@link Terrain}: one instance, set up for each search (main thread only). */
final class LevelTerrain implements Terrain {
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ClientLevel level;
    private int startY;
    private boolean underground;
    private int water;

    /** For a search from {@code startY}; underground the ground is the cave floor around that height, not the surface. */
    LevelTerrain set(ClientLevel level, int startY, boolean underground) {
        this.level = level;
        this.startY = startY;
        this.underground = underground;
        return this;
    }

    @Override
    public int water() {
        return water;
    }

    @Override
    public int ground(int x, int z) {
        water = DRY;
        if (!level.hasChunk(x >> 4, z >> 4)) return BLOCKED;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        // Underground: from just above the player down, through the rock of the ceiling to the open space, then on
        // to its floor.
        boolean needSpace = underground;
        if (underground) y = startY + 3; // not the heightmap: on the client it can be wrong under thick rock
        int bottom = level.getMinBuildHeight();
        for (int i = 0; i < 24 && y > bottom; i++) {
            BlockState s = level.getBlockState(pos.set(x, y - 1, z));
            if (needSpace) {
                if (s.isAir() || !s.blocksMotion() && s.getFluidState().isEmpty()) needSpace = false;
                y--;
                continue;
            }
            if (s.getFluidState().is(FluidTags.WATER)) {
                // Shallow: one block of water over the ground; deeper water is swum, at the surface.
                water = level.getBlockState(pos.set(x, y - 2, z)).getFluidState().is(FluidTags.WATER) ? DEEP : SHALLOW;
                return y;
            }
            if (s.is(BlockTags.LOGS)) return BLOCKED;
            if (s.is(BlockTags.LEAVES) || s.isAir() || !s.blocksMotion()) {
                y--;
                continue;
            }
            return y;
        }
        return BLOCKED;
    }
}
