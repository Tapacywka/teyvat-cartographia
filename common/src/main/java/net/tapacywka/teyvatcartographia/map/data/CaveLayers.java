package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * The cave layers of one dimension ({@link MapDataManager} decides where they exist): a store per band of
 * {@link CaveScanner#BAND} blocks under {@code <dimension>/caves/b<band>/}, the bands with data, and the band the player
 * is in ({@link Underground}). While the player is underground the chunks reaching within {@link #RADIUS} blocks of them
 * are mapped into their band on the workers, every {@link #TICKS} ticks. Main thread, but the workers' scans.
 */
final class CaveLayers {
    static final double RADIUS = 32;
    static final int TICKS = 10;
    /** Blocks above the player's eyes looked at for a roof. */
    private static final int ROOF_SCAN = 128;
    private static final BlockPos.MutableBlockPos ROOF_POS = new BlockPos.MutableBlockPos();

    /** Where a scanned chunk of a band goes (the manager's results, applied on the game's thread). */
    interface Sink {
        void scanned(int band, ChunkShader.Result result);
    }

    private final IntFunction<TileStore> make;
    private final Map<Integer, TileStore> stores = new HashMap<>();
    private final TreeSet<Integer> bands = new TreeSet<>();
    private final NavigableSet<Integer> bandsView = Collections.unmodifiableNavigableSet(bands.descendingSet());
    private final Underground underground = new Underground();
    private final Underground.Roof roof = new Underground.Roof();

    /** {@code make} opens the store of a band of the dimension now mapped. */
    CaveLayers(IntFunction<TileStore> make) {
        this.make = make;
    }

    /** The band of a folder under {@code caves/} ({@code b<band>}), or null for another folder. */
    static Integer bandOf(String folder) {
        return folder.matches("b-?\\d+") ? Integer.parseInt(folder.substring(1)) : null;
    }

    /** The folder of a band under {@code caves/}. */
    static String folder(int band) {
        return "b" + band;
    }

    /** Lists the bands saved for the dimension now mapped, from the folder names under its {@code caves/}. */
    void listSaved(Iterable<String> folders) {
        for (String f : folders) {
            Integer b = bandOf(f);
            if (b != null) bands.add(b);
        }
    }

    /** The store of a band, opened (and the band listed) on first use. */
    TileStore store(int band) {
        TileStore s = stores.get(band);
        if (s == null) {
            s = make.apply(band);
            stores.put(band, s);
            bands.add(band);
        }
        return s;
    }

    /** The bands with map data, highest first. */
    NavigableSet<Integer> bands() {
        return bandsView;
    }

    Iterable<TileStore> stores() {
        return stores.values();
    }

    /** The band the player is in, or null above ground. */
    Integer current() {
        return underground.current();
    }

    /**
     * Whether the player is underground ({@link Underground}): the longest run of natural blocks above their head,
     * looked for up to {@link #ROOF_SCAN} blocks up; nothing when {@code caves} is false. The sky light and the
     * heightmaps are not used: on the client they are not reliable in closed caves.
     */
    void updateUnderground(ClientLevel level, LocalPlayer player, boolean caves) {
        Integer now = null;
        if (caves) {
            BlockPos eye = BlockPos.containing(player.getEyePosition());
            int top = Math.min(level.getMaxY() + 1, eye.getY() + ROOF_SCAN);
            roof.reset();
            for (int y = eye.getY() + 1; y < top && roof.best() < Underground.ROOF; y++) {
                roof.add(Underground.natural(level.getBlockState(ROOF_POS.set(eye.getX(), y, eye.getZ()))));
            }
            now = Underground.band(roof.best(), player.getBlockY());
        }
        underground.update(now);
    }

    /** The first and last chunk (x0, x1, z0, z1) reaching within {@link #RADIUS} blocks of a point. */
    static int[] chunksAround(double px, double pz) {
        return new int[]{(int) Math.floor(px - RADIUS) >> 4, (int) Math.floor(px + RADIUS) >> 4,
                (int) Math.floor(pz - RADIUS) >> 4, (int) Math.floor(pz + RADIUS) >> 4};
    }

    /** Maps the chunks reaching within {@link #RADIUS} of the player into their band on the workers; {@code inFlight} counts them. */
    void scan(ClientLevel level, LocalPlayer player, int band, Executor workers, AtomicInteger inFlight, Sink sink) {
        double px = player.getX(), pz = player.getZ();
        int[] c = chunksAround(px, pz);
        store(band); // the band is listed from its first scan on
        for (int cz = c[2]; cz <= c[3]; cz++) {
            for (int cx = c[0]; cx <= c[1]; cx++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null || chunk.isEmpty()) continue;
                inFlight.incrementAndGet();
                workers.execute(() -> {
                    try {
                        sink.scanned(band, CaveScanner.scan(level, chunk, band, px, pz, RADIUS));
                    } catch (RuntimeException e) {
                        TeyvatCartographia.LOGGER.debug("Cave chunk {},{} could not be sampled", chunk.getPos().x(), chunk.getPos().z(), e);
                    } catch (LinkageError e) {
                        // A mod's block whose classes are missing.
                        TeyvatCartographia.LOGGER.error("Cave mapping failed for chunk {},{}", chunk.getPos().x(), chunk.getPos().z(), e);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }
        }
    }

    /** Saves and forgets every band (another dimension or world). */
    void close() {
        stores.values().forEach(TileStore::close);
        stores.clear();
        bands.clear();
        underground.reset();
    }
}
