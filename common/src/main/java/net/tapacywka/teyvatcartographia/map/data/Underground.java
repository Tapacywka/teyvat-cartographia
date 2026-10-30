package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.version.Game;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Whether the player is underground, and in which cave band ({@link MapDataManager}): at least {@code ROOF} natural
 * blocks in a row somewhere above the head (stone, dirt, sand, gravel, ores, terracotta and the like, also those of
 * mods by their tags), so a forest, a house of several floors with shelves and barrels, or a stone roof does not
 * count (user report 25.10.2026: any 4 solid blocks up to 128 above did, floors and roof of a house included). A new
 * state (or band) holds only once it stayed the same for {@code TICKS} ticks, so the minimap does not flicker at a
 * cave mouth.
 */
final class Underground {
    static final int ROOF = 4, TICKS = 10;

    private Integer current, candidate;
    private int ticks;

    /** The cave band for a player at block height {@code y} with a run of {@code naturalRun} natural blocks over the head, or null. */
    static Integer band(int naturalRun, int y) {
        return naturalRun >= ROOF ? CaveScanner.band(y) : null;
    }

    /** The block is ground the world made: solid and of a natural kind (not leaves, wood, glass, bricks or furniture). */
    static boolean natural(BlockState s) {
        if (!Game.blocksMotion(s)) return false;
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.TERRACOTTA)
                || s.is(CommonTags.STONES) || s.is(CommonTags.GRAVELS) || s.is(CommonTags.ORES) || s.is(CommonTags.SANDS)
                || s.is(BlockTags.BASE_STONE_NETHER) || s.is(CommonTags.NETHERRACKS) || s.is(CommonTags.END_STONES)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.CALCITE) || s.is(Blocks.CLAY)
                || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.AMETHYST_BLOCK)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE) || s.is(Blocks.OBSIDIAN)
                || s.is(Blocks.MAGMA_BLOCK);
    }

    /** The longest run of natural blocks in a column, fed from the head upwards (no allocation per tick). */
    static final class Roof {
        private int run, best;

        void reset() {
            run = best = 0;
        }

        /** The next block up: natural or not (air, a floor, a roof, a shelf end the run). */
        void add(boolean natural) {
            run = natural ? run + 1 : 0;
            best = Math.max(best, run);
        }

        int best() {
            return best;
        }
    }

    /** The state this tick ({@code now}: a band or null); returns the state that holds. */
    Integer update(Integer now) {
        if (Objects.equals(now, current)) {
            ticks = 0;
        } else if (Objects.equals(now, candidate)) {
            if (++ticks >= TICKS) {
                current = now;
                ticks = 0;
            }
        } else {
            candidate = now;
            ticks = 0;
        }
        return current;
    }

    /** The state that holds: a cave band, or null above ground. */
    Integer current() {
        return current;
    }

    void reset() {
        current = candidate = null;
        ticks = 0;
    }
}
