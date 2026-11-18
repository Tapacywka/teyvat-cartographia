package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The settings' preview of the terrain detail: the {@code (2 * rx + 1) x (2 * rz + 1)} loaded chunks around a chunk
 * shaded in each detail mode, as the map would shade them, one ABGR picture per mode (indexed by
 * {@link MapConfig.Detail#ordinal()}), {@code 16 * (2 * rx + 1)} pixels wide. The chunks are taken on the game's thread,
 * read and shaded on a worker. The surface of the dimension only (not a cave layer).
 */
final class DetailPreview {
    private DetailPreview() {}

    /** The padding around a chunk from the map beside it (game's thread), as {@link MapDataManager} gives the scanner. */
    interface Around {
        ChunkShader.Neighbourhood at(int cx, int cz);
    }

    static void sample(ClientLevel level, int pcx, int pcz, int rx, int rz, Around around, Executor workers, Consumer<int[][]> done) {
        boolean ceiling = level.dimensionType().hasCeiling();
        int side = 2 * rx + 1, rows = 2 * rz + 1;
        List<LevelChunk> chunks = new ArrayList<>();
        List<ChunkShader.Neighbourhood> pads = new ArrayList<>();
        List<LevelChunk[]> rings = new ArrayList<>();
        for (int dz = -rz; dz <= rz; dz++) {
            for (int dx = -rx; dx <= rx; dx++) {
                LevelChunk chunk = level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (chunk == null || chunk.isEmpty()) continue;
                chunks.add(chunk);
                pads.add(around.at(pcx + dx, pcz + dz));
                rings.add(ChunkScanner.around(level, pcx + dx, pcz + dz));
            }
        }
        workers.execute(() -> {
            MapConfig.Detail[] modes = MapConfig.Detail.values();
            int[][] out = new int[modes.length][256 * side * rows];
            try {
                for (int i = 0; i < chunks.size(); i++) {
                    LevelChunk chunk = chunks.get(i);
                    ChunkScanner.Snapshot snap = ChunkScanner.scan(level, chunk, ceiling, rings.get(i));
                    ChunkShader.Neighbourhood n = pads.get(i);
                    MapDataManager.fillOwnColumns(snap, n);
                    int ox = (chunk.getPos().x - pcx + rx) * 16, oz = (chunk.getPos().z - pcz + rz) * 16;
                    for (MapConfig.Detail d : modes) place(ChunkShader.shade(snap, n, d).abgr(), out[d.ordinal()], side, ox, oz);
                }
                done.accept(out);
            } catch (RuntimeException e) {
                TeyvatCartographia.LOGGER.debug("The detail preview could not be sampled", e);
            }
        });
    }

    /** Lays a chunk's 16 x 16 pixels into a picture {@code side} chunks wide, its top left pixel at ({@code ox}, {@code oz}). */
    static void place(int[] chunk, int[] picture, int side, int ox, int oz) {
        for (int z = 0; z < 16; z++) System.arraycopy(chunk, z * 16, picture, (oz + z) * 16 * side + ox, 16);
    }
}
