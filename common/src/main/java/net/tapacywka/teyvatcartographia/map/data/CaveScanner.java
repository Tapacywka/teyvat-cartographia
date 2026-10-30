package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.version.Game;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;

import java.util.Map;

/**
 * Maps one chunk of a cave layer (worker thread): the height band of {@code BAND} blocks the player is in. For every
 * column within {@code radius} blocks of the player it finds the floor inside the band, the highest solid block with
 * open space above it, and paints it in its map colour (water and lava in theirs, ore brighter), with a little relief and a soft
 * shadow along the walls. Columns that are solid through the whole band are rock (dark); where the space goes down
 * below the band, the floor further down is painted darker. Columns farther away stay unknown, so the layer opens up
 * only where the player has been. Nothing is loaded; a read that fails because the chunk changed throws.
 */
public final class CaveScanner {
    private CaveScanner() {}

    /** Height of one cave layer (blocks). */
    public static final int BAND = 16;
    /** How far below the band a floor is still looked for under an open shaft. */
    private static final int DROP = 16;
    /** Solid rock between the caves. */
    private static final int ROCK = 0xFF3A3D44;
    /** Ore on the floor: gems (diamond, emerald, lapis) and the rest. */
    private static final int ORE_GEM = 0xFF7CC7C2, ORE = 0xFFD9C27A;
    private static final int UNKNOWN = Integer.MIN_VALUE, SOLID = Integer.MIN_VALUE + 1;

    /** The band a height belongs to. */
    public static int band(int y) {
        return Math.floorDiv(y, BAND);
    }

    /**
     * The layers of a layer field from top to bottom: the cave bands (given highest first) with the surface (null) among
     * them, under the bands that start above the sea level.
     */
    public static java.util.List<Integer> layerOrder(Iterable<Integer> bandsHighestFirst, int seaLevel) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        boolean surfacePlaced = false;
        for (int b : bandsHighestFirst) {
            if (!surfacePlaced && b * BAND <= seaLevel) {
                out.add(null);
                surfacePlaced = true;
            }
            out.add(b);
        }
        if (!surfacePlaced) out.add(null);
        return out;
    }

    /**
     * The heights of a band as its tooltip names them, away from the surface (user request 25.10.2026): a band over the
     * surface in the layer field from its bottom up ("64 to 79"), one under it from its top down ("63 to 48").
     */
    public static int[] heights(int band, int seaLevel) {
        int bottom = band * BAND, top = bottom + BAND - 1;
        return bottom > seaLevel ? new int[]{bottom, top} : new int[]{top, bottom};
    }

    public static ChunkShader.Result scan(ClientLevel level, LevelChunk chunk, int band, double px, double pz, double radius) {
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        int yMin = band * BAND, yMax = yMin + BAND - 1, bottom = level.getMinY();
        int[] floor = new int[256], color = new int[256];
        byte[] flags = new byte[256];
        boolean[] drop = new boolean[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double r2 = radius * radius;
        // The standard colours as in ChunkScanner (the theme's colours for this dimension first); no biome tint underground.
        Map<Block, Integer> own = MapPalette.dimension(level.dimension());
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x, wx = (cx << 4) + x, wz = (cz << 4) + z;
                double dx = wx + 0.5 - px, dz = wz + 0.5 - pz;
                floor[i] = UNKNOWN;
                if (dx * dx + dz * dz > r2) continue;
                // The band's top counts as closed: a floor needs open space inside the band (the floor of a cave above
                // belongs to that band).
                boolean aboveSolid = true, open = false;
                int y = yMax, found = UNKNOWN;
                for (; y >= Math.max(bottom, yMin - DROP); y--) {
                    BlockState s = chunk.getBlockState(pos.set(wx, y, wz));
                    boolean solid = solid(s);
                    if (!solid) open = true;
                    if (solid && !aboveSolid) {
                        found = y;
                        break;
                    }
                    // Past the band only an open shaft is followed further down.
                    if (y == yMin && !open) break;
                    aboveSolid = solid;
                }
                if (found == UNKNOWN && !open) {
                    floor[i] = SOLID;
                    flags[i] = (byte) (MapTile.FLAG_KNOWN | MapTile.FLAG_IMPASSABLE);
                    color[i] = ROCK;
                    continue;
                }
                if (found == UNKNOWN) found = yMin - DROP; // a deep shaft: painted as a dark drop
                floor[i] = found;
                drop[i] = found < yMin;
                BlockState s = chunk.getBlockState(pos.set(wx, found, wz));
                byte f = MapTile.FLAG_KNOWN;
                int c;
                if (s.getFluidState().is(FluidTags.WATER)) {
                    int depth = 1;
                    while (depth < 8 && isWater(chunk.getBlockState(pos.set(wx, found - depth, wz)))) depth++;
                    c = MapPalette.lerp(MapPalette.WATER_SHALLOW, MapPalette.deepWater(MapPalette.WATER_SHALLOW), Math.min(1f, depth / 8f));
                    f |= MapTile.FLAG_WATER;
                    if (depth >= 2) f |= MapTile.FLAG_IMPASSABLE;
                } else if (s.getFluidState().is(FluidTags.LAVA)) {
                    c = TextureColors.lava() != 0 ? TextureColors.lava() : MapPalette.LAVA;
                    f |= MapTile.FLAG_IMPASSABLE;
                } else if (s.is(CommonTags.ORES)) {
                    // Ore stands out from the stone: gems in teal, everything else in pale gold.
                    boolean gem = s.is(CommonTags.DIAMOND_ORES) || s.is(CommonTags.EMERALD_ORES) || s.is(CommonTags.LAPIS_ORES);
                    c = gem ? ORE_GEM : ORE;
                } else {
                    MapColor mc = s.getMapColor(level, pos);
                    Integer mine = own == null ? null : own.get(s.getBlock());
                    TextureColors.Entry tex = mine == null ? TextureColors.get(s) : null;
                    if (mine != null) c = mine;
                    else if (tex != null) c = tex.argb();
                    else c = MapPalette.standard(mc == MapColor.NONE ? MapColor.STONE : mc);
                }
                color[i] = c;
                flags[i] = f;
            }
        }
        int[] abgr = new int[256];
        short[] heights = new short[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x, h = floor[i];
                if (h == UNKNOWN) continue;
                if (h == SOLID) {
                    heights[i] = (short) (yMax + 1);
                    abgr[i] = MapPalette.toAbgr(ROCK);
                    continue;
                }
                heights[i] = (short) h;
                int c = color[i];
                if ((flags[i] & MapTile.FLAG_WATER) == 0) {
                    // Relief from the neighbours inside the chunk, and a soft shadow along the walls.
                    int hN = z > 0 && floor[i - 16] > SOLID ? floor[i - 16] : h, hW = x > 0 && floor[i - 1] > SOLID ? floor[i - 1] : h;
                    float slope = Math.max(-4, Math.min(4, (h - hN) + (h - hW)));
                    c = MapPalette.scale(c, 1f + slope * 0.05f);
                }
                if (nextToRock(floor, x, z)) c = MapPalette.scale(c, 0.8f);
                if (drop[i]) c = MapPalette.scale(c, 0.55f);
                abgr[i] = MapPalette.toAbgr(c);
            }
        }
        return new ChunkShader.Result(cx, cz, abgr, heights, flags, new byte[256], 0x0F, null, true);
    }

    private static boolean nextToRock(int[] floor, int x, int z) {
        return x > 0 && floor[z * 16 + x - 1] == SOLID || x < 15 && floor[z * 16 + x + 1] == SOLID
                || z > 0 && floor[(z - 1) * 16 + x] == SOLID || z < 15 && floor[(z + 1) * 16 + x] == SOLID;
    }

    /** Something to stand on or a liquid surface: the floor of a cave. */
    private static boolean solid(BlockState s) {
        return !s.isAir() && (Game.blocksMotion(s) || !s.getFluidState().isEmpty());
    }

    private static boolean isWater(BlockState s) {
        return s.getFluidState().is(FluidTags.WATER);
    }
}
