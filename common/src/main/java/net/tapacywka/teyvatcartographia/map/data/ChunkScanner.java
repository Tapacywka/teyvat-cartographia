package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.resources.ResourceKey;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.tapacywka.teyvatcartographia.config.MapConfig;

import java.util.Map;

/**
 * Stage 1-2 of the pipeline: reads one loaded client chunk on a worker thread and produces a compact,
 * immutable {@link Snapshot} (surface height, painted base colour, water depth, biome). Only chunks already
 * present in the client chunk cache are read; nothing is loaded or generated. Like the vanilla section compile,
 * it reads blocks, biome tints and block light off the main thread; a read that fails because the chunk changed
 * meanwhile throws, and the chunk is mapped again later.
 */
public final class ChunkScanner {
    private ChunkScanner() {}

    /**
     * Immutable sampled data of one chunk, safe to hand to worker threads. {@code keepColour}: columns whose colour is
     * final (no depth gradient), such as a {@link Shimmer}; {@code bottom}: the colour of the ground under shallow water
     * (0 elsewhere), seen through it.
     */
    public record Snapshot(int cx, int cz, int[] color, short[] height, byte[] waterDepth, boolean[] isVoid, byte[] blockLight,
                           boolean[] keepColour, int[] bottom, ResourceKey<Biome> biome) {}

    /** Water up to this deep shows its bottom (when the setting is on). */
    public static final int SHALLOW = 10;

    private static final int MAX_WATER_DEPTH = 24;
    /** {@link Snapshot#waterDepth} of a lava column (no water there, but not walkable either). */
    public static final byte LAVA = -1;

    /**
     * The chunk at (cx, cz) and the eight around it as the level holds them now, null where not loaded, at
     * {@code [(dz + 1) * 3 + dx + 1]}. Taken on the main thread when a chunk is queued and handed to the worker: read
     * through the level on the worker, a neighbour unloaded in between answered with the default biome (dark water
     * lines along chunk borders behind a moving player, 24.10.2026); a chunk object keeps its data once taken.
     */
    public static LevelChunk[] around(ClientLevel level, int cx, int cz) {
        LevelChunk[] out = new LevelChunk[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                out[(dz + 1) * 3 + dx + 1] = level.getChunkSource().getChunk(cx + dx, cz + dz, false);
            }
        }
        return out;
    }

    /** {@link #scan(ClientLevel, LevelChunk, boolean, LevelChunk[])} with the chunks around as they are now (main thread). */
    public static Snapshot scan(ClientLevel level, LevelChunk chunk, boolean ceiling) {
        return scan(level, chunk, ceiling, around(level, chunk.getPos().x, chunk.getPos().z));
    }

    public static Snapshot scan(ClientLevel level, LevelChunk chunk, boolean ceiling, LevelChunk[] around) {
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        around[4] = chunk; // itself, whatever the level held when the ring was taken
        int[] color = new int[256];
        short[] height = new short[256];
        byte[] depth = new byte[256];
        boolean[] isVoid = new boolean[256], keep = new boolean[256];
        int[] bottom = new int[256];
        int minY = level.getMinBuildHeight();
        int ceilingStart = Math.min(level.getMaxBuildHeight() - 1, minY + level.dimensionType().logicalHeight() - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean shallow = MapConfig.get(MapConfig.SHALLOW_WATER) > 0;
        TintGrid water = TintGrid.of(around, cx, cz, BiomeColors.WATER_COLOR_RESOLVER),
                grass = TintGrid.of(around, cx, cz, BiomeColors.GRASS_COLOR_RESOLVER),
                foliageTint = TintGrid.of(around, cx, cz, BiomeColors.FOLIAGE_COLOR_RESOLVER);
        // The colours of the block textures (like Xaero's Minimap), in every dimension; the theme's own colours for
        // this dimension's blocks come first.
        Map<Block, Integer> own = MapPalette.dimension(level.dimension());
        java.util.Set<Block> plantSet = Plants.shownSet();
        boolean plants = MapConfig.get(MapConfig.MAP_PLANTS), ichorShimmer = MapConfig.get(MapConfig.SIFT_ICHOR_SHIMMER),
                snowShimmer = MapConfig.get(MapConfig.SIFT_SNOW_SHIMMER);

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int wx = (cx << 4) + x, wz = (cz << 4) + z;
                int y = ceiling ? findCeilingSurface(chunk, pos, wx, wz, ceilingStart, minY)
                        : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z); // already the Y of the top block
                BlockState state = Blocks.AIR.defaultBlockState();
                MapColor mc = MapColor.NONE;
                // Skip invisible blocks (glass, barriers, ...) down to something with a map colour.
                for (int guard = 0; y >= minY && guard < 32; guard++, y--) {
                    pos.set(wx, y, wz);
                    state = chunk.getBlockState(pos);
                    mc = state.getMapColor(level, pos);
                    // A solid plant the list leaves out (a cactus, chorus) is looked through to the ground under it.
                    if (!ceiling && Plants.hidden(state.getBlock(), plantSet)) continue;
                    if (mc != MapColor.NONE || !state.getFluidState().isEmpty()) break;
                }
                if (y < minY) {
                    isVoid[i] = true;
                    height[i] = (short) minY;
                    continue;
                }
                height[i] = (short) y;
                FluidState fluid = state.getFluidState();
                // Plants over the ground or the water (flowers, petals, mushrooms, crops, dead bushes, cane, chorus, lily
                // pads), like Xaero's Minimap: the highest one up to the world surface colours the column; the height stays
                // the ground's (relief, contours and paths unchanged). The motion-blocking height skips them all.
                BlockState shown = state;
                int shownY = y;
                if (!ceiling && plants) {
                    for (int py = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z); py > y; py--) {
                        BlockState s = chunk.getBlockState(pos.set(wx, py, wz));
                        // A colour of its texture is enough: many mod plants (Regions Unexplored's flowers, shrubs, branches,
                        // leaf litter; The Sift's sculkflower) leave the vanilla map colour unset, and were left off the map
                        // (24.10.2026, runPlantsMapCompatTest).
                        if (plantSet.contains(s.getBlock()) && (s.getMapColor(level, pos) != MapColor.NONE || TextureColors.get(s) != null)) {
                            shown = s;
                            shownY = py;
                            break;
                        }
                    }
                }
                if (fluid.is(FluidTags.WATER)) {
                    int d = 1;
                    while (d < MAX_WATER_DEPTH && y - d >= minY) {
                        pos.set(wx, y - d, wz);
                        if (!chunk.getBlockState(pos).getFluidState().is(FluidTags.WATER)) break;
                        d++;
                    }
                    depth[i] = (byte) d;
                    if (shallow && d <= SHALLOW && y - d >= minY) {
                        // The ground under the water, seen through it where it is shallow.
                        BlockState floor = chunk.getBlockState(pos.set(wx, y - d, wz));
                        TextureColors.Entry fe = TextureColors.get(floor);
                        bottom[i] = fe != null ? tinted(level, floor, pos, fe, grass, foliageTint, x, y - d, z)
                                : MapPalette.standard(floor.getMapColor(level, pos));
                    }
                    pos.set(wx, y, wz);
                    if (Sift.ichor(fluid)) {
                        // The Sift's rainbow water is in the water tag: walked around like water, drawn as itself (its
                        // shimmer, or the average of its texture when that is off).
                        color[i] = ichorShimmer || TextureColors.ichor() == 0 ? 0xFF000000 | Shimmer.ichor(wx, wz) : TextureColors.ichor();
                        keep[i] = true;
                        continue;
                    }
                    TextureColors.Entry floating = shown == state ? null : TextureColors.get(shown);
                    if (floating != null) {
                        // A plant on the water (lily pads): its colour, no depth gradient; still water for the paths.
                        color[i] = tinted(level, shown, pos.set(wx, shownY, wz), floating, grass, foliageTint, x, y, z);
                        keep[i] = true;
                        continue;
                    }
                    int tint = 0xFF000000 | water.at(x, y, z);
                    color[i] = TextureColors.water() != 0 ? TextureColors.multiply(TextureColors.water(), tint) : tint;
                    continue;
                }
                if (fluid.is(FluidTags.LAVA)) {
                    depth[i] = LAVA;
                    color[i] = TextureColors.lava() != 0 ? TextureColors.lava() : MapPalette.LAVA;
                    continue;
                }
                pos.set(wx, y + 1, wz);
                BlockState above = chunk.getBlockState(pos);
                pos.set(wx, y, wz);
                Integer mine = own == null ? null : own.get(shown.getBlock());
                TextureColors.Entry tex = TextureColors.get(shown);
                if (Sift.snow(state.getBlock()) || Sift.snow(above.getBlock())) {
                    // Before vanilla snow (it is a snow layer too): its shimmer, or its texture colour when that is off.
                    TextureColors.Entry s = TextureColors.get(Sift.snow(above.getBlock()) ? above.getBlock() : state.getBlock());
                    color[i] = snowShimmer || s == null ? 0xFF000000 | Shimmer.ichorSnow(wx, wz) : s.argb();
                } else if (above.getBlock() instanceof SnowLayerBlock) {
                    color[i] = TextureColors.snow() != 0 ? TextureColors.snow() : MapPalette.standard(MapColor.SNOW);
                } else if (mine != null) {
                    color[i] = mine; // before the biome tints: the theme gives the block's whole colour
                } else if (tex != null) {
                    color[i] = tinted(level, shown, pos.set(wx, shownY, wz), tex, grass, foliageTint, x, y, z);
                } else {
                    color[i] = MapPalette.standard(mc); // no texture read (yet): the game's own map colour
                }
            }
        }
        // Real block light (torches, lava, glowstone, lanterns...) just above the visible surface, 0..15,
        // as computed by the vanilla light engine. Sky light is ignored: the map shows artificial light only.
        byte[] blockLight = new byte[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                if (isVoid[i]) continue;
                pos.set((cx << 4) + x, height[i] + 1, (cz << 4) + z);
                int surface = level.getBrightness(LightLayer.BLOCK, pos);
                pos.set((cx << 4) + x, height[i], (cz << 4) + z);
                int self = chunk.getBlockState(pos).getLightEmission(); // emissive surface blocks (lava, glowstone)
                blockLight[i] = (byte) Math.max(surface, self);
            }
        }
        pos.set((cx << 4) + 8, height[8 * 16 + 8], (cz << 4) + 8);
        Holder<Biome> biome = level.getBiome(pos);
        return new Snapshot(cx, cz, color, height, depth, isVoid, blockLight, keep, bottom, biome.unwrapKey().orElse(null));
    }

    /**
     * Mielon's The Sift: its ichor and ichor snow, drawn with a {@link Shimmer} (their texture averages are a grey
     * lilac, nothing like the rainbow in the world). Looked up once, the first time a chunk has water or snow; all null
     * without the mod.
     */
    private static final class Sift {
        private static final Fluid ICHOR = fluid("ichor"), FLOWING_ICHOR = fluid("flowing_ichor");
        private static final Block SNOW = block("ichor_snow"), SNOW_BLOCK = block("ichor_snow_block");

        static boolean ichor(FluidState f) {
            return ICHOR != null && (f.getType() == ICHOR || f.getType() == FLOWING_ICHOR);
        }

        static boolean snow(Block b) {
            return SNOW != null && (b == SNOW || b == SNOW_BLOCK);
        }

        private static Fluid fluid(String path) {
            return BuiltInRegistries.FLUID.getOptional(ResourceLocation.fromNamespaceAndPath("the_sift", path)).orElse(null);
        }

        private static Block block(String path) {
            return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("the_sift", path)).orElse(null);
        }
    }

    /** Where a {@link TintGrid} reads the game's tint: at a block, and whether a chunk is loaded. */
    interface TintSource {
        int tint(int wx, int y, int wz);

        boolean loaded(int cx, int cz);
    }

    /**
     * One biome tint (grass, foliage or water) of a chunk, sampled lazily at the centres of its 4 x 4 quarts and
     * interpolated per column. The vanilla tint is already a blend over neighbouring biomes, so it changes smoothly;
     * asking it for every column took most of the time of mapping a chunk. The grid has one more ring of quarts in
     * the neighbouring chunks, so the interpolation goes on across the chunk's border: clamped at the border, a biome
     * edge lying on it (biomes change on the 4 x 4 grid, so one in four does) became a sharp straight line.
     */
    static final class TintGrid {
        private final TintSource source;
        private final int cx, cz;
        /** 6 x 6 cells: index g is quart g - 1 (-1 and 4 lie in the neighbouring chunks). */
        private final int[] cells = new int[36];
        /** Bit per cell already sampled. */
        private long sampled;

        TintGrid(TintSource source, int cx, int cz) {
            this.source = source;
            this.cx = cx;
            this.cz = cz;
        }

        /**
         * The biome colours blended over the game's Biome Blend radius, as {@code level.getBlockTint} does, but only over
         * the chunks of {@code around} ({@link ChunkScanner#around}, taken on the main thread): read through the level,
         * the game's blend and then a neighbour unloaded while the worker read it gave the default biome (other water),
         * a darker band along that chunk border (fixed 24.10.2026). Biomes per quart, as stored (no vanilla jitter).
         */
        static TintGrid of(LevelChunk[] around, int cx, int cz, ColorResolver resolver) {
            int radius = net.minecraft.client.Minecraft.getInstance().options.biomeBlendRadius().get();
            Loaded loaded = (ncx, ncz) -> chunk(around, cx, cz, ncx, ncz) != null;
            return new TintGrid(new TintSource() {
                @Override
                public int tint(int wx, int y, int wz) {
                    return blend(wx, wz, radius, loaded, (x, z) -> resolver.getColor(
                            chunk(around, cx, cz, x >> 4, z >> 4).getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)).value(), x, z));
                }

                @Override
                public boolean loaded(int ncx, int ncz) {
                    return loaded.test(ncx, ncz);
                }
            }, cx, cz);
        }

        /** The chunk (ncx, ncz) of {@code around} the chunk (cx, cz), null when not loaded or not among the nine. */
        private static LevelChunk chunk(LevelChunk[] around, int cx, int cz, int ncx, int ncz) {
            int dx = ncx - cx, dz = ncz - cz;
            return Math.abs(dx) > 1 || Math.abs(dz) > 1 ? null : around[(dz + 1) * 3 + dx + 1];
        }

        /** A chunk is loaded ({@code cx}, {@code cz}). */
        interface Loaded {
            boolean test(int cx, int cz);
        }

        /**
         * The average of {@code colour} over the square of {@code radius} blocks around (wx, wz), only at blocks of
         * loaded chunks; at (wx, wz) alone when none is.
         */
        static int blend(int wx, int wz, int radius, Loaded loaded, java.util.function.IntBinaryOperator colour) {
            int r = 0, g = 0, b = 0, n = 0;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int x = wx + dx, z = wz + dz;
                    if (!loaded.test(x >> 4, z >> 4)) continue;
                    int c = colour.applyAsInt(x, z);
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
            }
            if (n == 0) return colour.applyAsInt(wx, wz);
            return (r / n) << 16 | (g / n) << 8 | b / n;
        }

        int at(int x, int y, int z) {
            // Quart q (blocks 4q to 4q + 3) is centred at 4q + 1.5: columns 0 and 1 lie between quarts -1 and 0.
            float fx = (x - 1.5f) / 4f + 1, fz = (z - 1.5f) / 4f + 1;
            int x0 = (int) fx, z0 = (int) fz;
            float tx = fx - x0, tz = fz - z0;
            int c00 = cell(x0, z0, y), c10 = cell(x0 + 1, z0, y);
            int c01 = cell(x0, z0 + 1, y), c11 = cell(x0 + 1, z0 + 1, y);
            int r = mix(c00 >> 16, c10 >> 16, c01 >> 16, c11 >> 16, tx, tz);
            int g = mix(c00 >> 8, c10 >> 8, c01 >> 8, c11 >> 8, tx, tz);
            int b = mix(c00, c10, c01, c11, tx, tz);
            return (r << 16) | (g << 8) | b;
        }

        private int cell(int gx, int gz, int y) {
            // A quart of a neighbouring chunk that is not loaded: the game would answer with its default biome, so
            // the chunk's own nearest quart stands in (the chunk is mapped again when that neighbour is).
            int ndx = gx == 0 ? -1 : gx == 5 ? 1 : 0, ndz = gz == 0 ? -1 : gz == 5 ? 1 : 0;
            if ((ndx != 0 || ndz != 0) && !source.loaded(cx + ndx, cz + ndz)) {
                gx = Math.max(1, Math.min(4, gx));
                gz = Math.max(1, Math.min(4, gz));
            }
            int k = gz * 6 + gx;
            if ((sampled & (1L << k)) == 0) {
                int wx = (cx << 4) + (gx - 1) * 4 + 2, wz = (cz << 4) + (gz - 1) * 4 + 2;
                cells[k] = source.tint(wx, y, wz);
                sampled |= 1L << k;
            }
            return cells[k];
        }

        private static int mix(int c00, int c10, int c01, int c11, float tx, float tz) {
            float top = (c00 & 0xFF) + ((c10 & 0xFF) - (c00 & 0xFF)) * tx;
            float bottom = (c01 & 0xFF) + ((c11 & 0xFF) - (c01 & 0xFF)) * tx;
            return Math.round(top + (bottom - top) * tz);
        }
    }

    /**
     * A block's texture colour with the tint it needs (biome grass or foliage at the column, or its handler at pos;
     * always, since the setting to turn that off was removed on 24.10.2026: off, modded grass and leaves clashed with the biome).
     */
    private static int tinted(ClientLevel level, BlockState state, BlockPos pos, TextureColors.Entry tex, TintGrid grass, TintGrid foliage,
                              int x, int y, int z) {
        return switch (tex.tint()) {
            case GRASS -> TextureColors.multiply(tex.argb(), grass.at(x, y, z));
            case FOLIAGE -> TextureColors.multiply(tex.argb(), foliage.at(x, y, z));
            case WORLD -> worldTint(level, state, pos, tex);
            case NONE -> tex.argb();
        };
    }

    /**
     * A block tinted by its own colour handler ({@link TextureColors.Tint#WORLD}): its texture times what the handler gives
     * at its place, as the game colours it (leaf litter takes the dry foliage of its biome). The colour without a world
     * when the handler cannot answer here.
     */
    private static int worldTint(ClientLevel level, BlockState state, BlockPos pos, TextureColors.Entry tex) {
        try {
            int c = net.minecraft.client.Minecraft.getInstance().getBlockColors().getColor(state, level, pos, tex.tintIndex());
            return c == -1 ? tex.texture() : TextureColors.multiply(tex.texture(), c);
        } catch (RuntimeException e) {
            return tex.argb();
        }
    }

    /** For dimensions with a ceiling (Nether): first floor below the roof. */
    private static int findCeilingSurface(LevelChunk chunk, BlockPos.MutableBlockPos pos, int wx, int wz, int start, int minY) {
        int y = start;
        while (y > minY && isSolid(chunk, pos.set(wx, y, wz))) y--;
        while (y > minY && !isSolid(chunk, pos.set(wx, y, wz))) y--;
        return y;
    }

    private static boolean isSolid(LevelChunk chunk, BlockPos pos) {
        BlockState s = chunk.getBlockState(pos);
        return !s.isAir() && (s.blocksMotion() || !s.getFluidState().isEmpty());
    }
}
