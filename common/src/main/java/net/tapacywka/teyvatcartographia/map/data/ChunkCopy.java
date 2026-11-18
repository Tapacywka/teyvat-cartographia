package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.material.FluidState;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What the map reads of a chunk, copied on the game's thread when the chunk is handed to a worker: its blocks (the
 * sections with any, {@link PalettedContainer#copy}), the two heightmaps the scan starts from, and the block light of
 * its sections. The worker reads the copy, so a block the game changes or a light update meanwhile does not reach it
 * half done; the chunk object itself stays for its biomes, which do not change. Read only, any thread.
 */
public final class ChunkCopy implements BlockGetter {
    /** Nanoseconds spent copying on the game's thread, and chunks copied, since the game started (the suite reads them). */
    private static final AtomicLong NANOS = new AtomicLong(), COUNT = new AtomicLong();

    /** The chunk the copy was taken of: its position and its biomes. */
    final LevelChunk chunk;
    private final int minY, height, minSection;
    /** Block states by section from the bottom, null for a section of air. */
    private final PalettedContainer<BlockState>[] states;
    /** The Y of the top block of each column by the two heightmaps, x + 16 z. */
    private final short[] motionBlocking = new short[256], worldSurface = new short[256];
    /** Block light by section from one below the bottom to one above the top, as the light engine keeps it; null for none. */
    private final DataLayer[] light;
    private final int minLightSection;

    @SuppressWarnings("unchecked")
    private ChunkCopy(ClientLevel level, LevelChunk chunk) {
        this.chunk = chunk;
        minY = chunk.getMinBuildHeight();
        height = chunk.getHeight();
        minSection = chunk.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();
        states = new PalettedContainer[sections.length];
        for (int i = 0; i < sections.length; i++) if (!sections[i].hasOnlyAir()) states[i] = sections[i].getStates().copy();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                motionBlocking[z * 16 + x] = (short) chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                worldSurface[z * 16 + x] = (short) chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        }
        minLightSection = minSection - 1;
        light = new DataLayer[sections.length + 2];
        LayerLightEventListener engine = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        for (int i = 0; i < light.length; i++) {
            DataLayer d = engine.getDataLayerData(SectionPos.of(cx, minLightSection + i, cz));
            if (d != null) light[i] = d.copy();
        }
    }

    /** The copy of a chunk the level holds; game's thread. */
    public static ChunkCopy take(ClientLevel level, LevelChunk chunk) {
        long t0 = System.nanoTime();
        ChunkCopy c = new ChunkCopy(level, chunk);
        NANOS.addAndGet(System.nanoTime() - t0);
        COUNT.incrementAndGet();
        return c;
    }

    /** Nanoseconds spent copying so far (the suite measures its cost per frame). */
    public static long nanos() {
        return NANOS.get();
    }

    public static long count() {
        return COUNT.get();
    }

    /** The Y of the top block of a column by a heightmap (as {@link LevelChunk#getHeight(Heightmap.Types, int, int)}). */
    int height(Heightmap.Types type, int x, int z) {
        return (type == Heightmap.Types.WORLD_SURFACE ? worldSurface : motionBlocking)[(z & 15) * 16 + (x & 15)];
    }

    /** Block light at a block, 0 to 15, as the light engine had it (0 where it had none). */
    int blockLight(BlockPos pos) {
        int i = SectionPos.blockToSectionCoord(pos.getY()) - minLightSection;
        if (i < 0 || i >= light.length || light[i] == null) return 0;
        return light[i].get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int i = SectionPos.blockToSectionCoord(pos.getY()) - minSection;
        if (i < 0 || i >= states.length || states[i] == null) return Blocks.AIR.defaultBlockState();
        return states[i].get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minY;
    }
}
