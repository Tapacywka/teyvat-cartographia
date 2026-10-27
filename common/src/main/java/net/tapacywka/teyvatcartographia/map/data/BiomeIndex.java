package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.map.MapView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The biome of every explored chunk of one dimension, for the map search: read from the saved regions on a
 * background thread and kept in {@code biomes.idx} next to them, so later refreshes only read the regions saved
 * since (by file time). Each chunk also keeps its average map colour (from the region thumbnail) for the colour
 * swatch of a search result. The main thread only reads the published {@link Snapshot}.
 */
public final class BiomeIndex {
    private static final int MAGIC = 0x54594231; // "TYB1"
    private static final int VERSION = 1;
    private static final int CHUNKS = MapTile.CHUNKS * MapTile.CHUNKS;
    /** Patches of a biome smaller than this (in chunks) are not offered as places, unless the biome has no larger one. */
    private static final int MIN_PLACE_CHUNKS = 4;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TeyvatCartographia-Search");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Map<Path, BiomeIndex> INDEXES = new ConcurrentHashMap<>();

    /** One region: palette index per chunk ({@code -1} = unknown) and average ARGB colour per chunk (0 = none). */
    record Region(int tx, int tz, long modified, short[] biome, int[] color) {}

    /** A published state of the index; never changed after publishing. */
    public record Snapshot(List<ResourceKey<Biome>> palette, Long2ObjectMap<Region> regions) {}

    /** A connected patch of one biome: a block inside it near its middle, its size in chunks and its colour. */
    public record Place(int x, int z, int chunks) {}

    /** The places of a biome, nearest to the reference point first, and the biome's average map colour (ARGB). */
    public record Places(List<Place> places, int color) {}

    private final Path dir;
    private volatile Snapshot snapshot;
    private volatile boolean busy;
    // Worker-thread state.
    private final List<ResourceKey<Biome>> palette = new ArrayList<>();
    private final Map<ResourceKey<Biome>, Short> paletteIndex = new HashMap<>();
    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    private boolean cacheRead;

    private BiomeIndex(Path dir) {
        this.dir = dir;
    }

    /** The index of a dimension's map folder. */
    public static BiomeIndex of(Path dir) {
        return INDEXES.computeIfAbsent(dir, BiomeIndex::new);
    }

    /** Runs a job on the search thread, after any refresh queued before it. */
    public static void run(Runnable job) {
        WORKER.execute(job);
    }

    /** The latest published state, or {@code null} before the first refresh finished. */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** True while a refresh is queued or running. */
    public boolean busy() {
        return busy;
    }

    /** Marks a refresh as coming (it is queued behind the map writes first). */
    void queued() {
        busy = true;
    }

    /** Queues a refresh: reads the regions saved since the last one (all of them the first time) and publishes. */
    public void refresh() {
        busy = true;
        WORKER.execute(() -> {
            boolean changed = false;
            try {
                if (!cacheRead) {
                    cacheRead = true;
                    changed = readCache();
                }
                if (update()) {
                    changed = true;
                    writeCache();
                }
            } catch (Throwable e) {
                TeyvatCartographia.LOGGER.warn("Could not index the biomes of the map in {}", dir, e);
            } finally {
                // A new snapshot only when something changed: a search compares them by identity.
                if (changed || snapshot == null) {
                    snapshot = new Snapshot(Collections.unmodifiableList(new ArrayList<>(palette)), new Long2ObjectOpenHashMap<>(regions));
                }
                busy = false;
            }
        });
    }

    /** Brings the regions up to date with the files (one listing, with their times); returns true if anything changed. */
    private boolean update() throws IOException {
        Long2LongOpenHashMap onDisk = new Long2LongOpenHashMap();
        if (Files.isDirectory(dir)) {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Matcher m = MapStorage.DAT_FILE.matcher(file.getFileName().toString());
                    if (m.matches()) {
                        onDisk.put(MapView.tileKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), attrs.lastModifiedTime().toMillis());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        boolean changed = false;
        for (Long2LongMap.Entry e : onDisk.long2LongEntrySet()) {
            long key = e.getLongKey(), modified = e.getLongValue();
            Region old = regions.get(key);
            if (old != null && old.modified == modified) continue;
            Region fresh = read(MapView.tileKeyX(key), MapView.tileKeyZ(key), modified);
            if (fresh == null) regions.remove(key);
            else regions.put(key, fresh);
            changed = true;
        }
        for (ObjectIterator<Long2ObjectMap.Entry<Region>> it = regions.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
            if (!onDisk.containsKey(it.next().getLongKey())) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private Region read(int tx, int tz, long modified) {
        MapStorage.RegionBiomes rb = MapStorage.readBiomes(dir.resolve("r." + tx + "." + tz + ".dat"));
        if (rb == null) return null;
        short[] biome = new short[CHUNKS];
        for (int i = 0; i < CHUNKS; i++) {
            short local = rb.chunkBiome()[i];
            ResourceKey<Biome> b = local < 0 || local >= rb.palette().size() ? null : rb.palette().get(local);
            biome[i] = b == null ? -1 : indexOf(b);
        }
        int[] color = new int[CHUNKS];
        try (NativeImage thumb = MapStorage.loadThumbnail(dir, tx, tz)) {
            if (thumb != null) chunkColors(thumb, color);
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.debug("No thumbnail for region {},{} in {}", tx, tz, dir, e);
        }
        return new Region(tx, tz, modified, biome, color);
    }

    /** Average colour of each chunk's thumbnail pixels (4 x 4 per chunk), weighted by how much of it is explored. */
    private static void chunkColors(NativeImage thumb, int[] out) {
        int px = MapStorage.THUMB / MapTile.CHUNKS;
        for (int cz = 0; cz < MapTile.CHUNKS; cz++) {
            for (int cx = 0; cx < MapTile.CHUNKS; cx++) {
                long r = 0, g = 0, b = 0, w = 0;
                for (int y = 0; y < px; y++) {
                    for (int x = 0; x < px; x++) {
                        int c = thumb.getPixelRGBA(cx * px + x, cz * px + y); // ABGR
                        int a = c >>> 24;
                        r += (long) (c & 0xFF) * a;
                        g += (long) ((c >> 8) & 0xFF) * a;
                        b += (long) ((c >> 16) & 0xFF) * a;
                        w += a;
                    }
                }
                out[cz * MapTile.CHUNKS + cx] = w == 0 ? 0 : 0xFF000000 | (int) (r / w) << 16 | (int) (g / w) << 8 | (int) (b / w);
            }
        }
    }

    private short indexOf(ResourceKey<Biome> b) {
        Short i = paletteIndex.get(b);
        if (i != null) return i;
        short n = (short) palette.size();
        palette.add(b);
        paletteIndex.put(b, n);
        return n;
    }

    // ------------------------------------------------------------------ places (search thread)

    /**
     * The connected patches of a biome (chunks touching at a side), nearest to ({@code fromX}, {@code fromZ}) first.
     * Call on the search thread ({@link #run}).
     */
    public static Places places(Snapshot s, ResourceKey<Biome> biome, double fromX, double fromZ) {
        int index = s.palette().indexOf(biome);
        LongOpenHashSet chunks = new LongOpenHashSet();
        long r = 0, g = 0, b = 0, n = 0;
        if (index >= 0) {
            for (Region reg : s.regions().values()) {
                for (int i = 0; i < CHUNKS; i++) {
                    if (reg.biome[i] != index) continue;
                    int cx = reg.tx * MapTile.CHUNKS + i % MapTile.CHUNKS, cz = reg.tz * MapTile.CHUNKS + i / MapTile.CHUNKS;
                    chunks.add(ChunkPos.asLong(cx, cz));
                    int c = reg.color[i];
                    if (c != 0) {
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
            }
        }
        int color = n == 0 ? 0 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        List<Place> all = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        LongArrayList patch = new LongArrayList();
        for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
            long start = it.nextLong();
            if (!seen.add(start)) continue;
            queue.clear();
            patch.clear();
            queue.add(start);
            double sx = 0, sz = 0;
            while (!queue.isEmpty()) {
                long k = queue.removeLong(queue.size() - 1);
                patch.add(k);
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                sx += cx;
                sz += cz;
                visit(ChunkPos.asLong(cx + 1, cz), chunks, seen, queue);
                visit(ChunkPos.asLong(cx - 1, cz), chunks, seen, queue);
                visit(ChunkPos.asLong(cx, cz + 1), chunks, seen, queue);
                visit(ChunkPos.asLong(cx, cz - 1), chunks, seen, queue);
            }
            // The chunk of the patch nearest its middle, so the point is inside the biome even for a bent patch.
            double mx = sx / patch.size(), mz = sz / patch.size();
            long best = patch.getLong(0);
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < patch.size(); i++) {
                long k = patch.getLong(i);
                double dx = ChunkPos.getX(k) - mx, dz = ChunkPos.getZ(k) - mz, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            all.add(new Place(ChunkPos.getX(best) * 16 + 8, ChunkPos.getZ(best) * 16 + 8, patch.size()));
        }
        boolean anyLarge = all.stream().anyMatch(p -> p.chunks() >= MIN_PLACE_CHUNKS);
        List<Place> out = new ArrayList<>();
        for (Place p : all) if (!anyLarge || p.chunks() >= MIN_PLACE_CHUNKS) out.add(p);
        out.sort((p, q) -> Double.compare(dist2(p, fromX, fromZ), dist2(q, fromX, fromZ)));
        return new Places(out, color);
    }

    private static double dist2(Place p, double x, double z) {
        double dx = p.x() - x, dz = p.z() - z;
        return dx * dx + dz * dz;
    }

    /** A neighbour of the biome not reached yet joins the patch. */
    private static void visit(long key, LongOpenHashSet chunks, LongOpenHashSet seen, LongArrayList queue) {
        if (chunks.contains(key) && seen.add(key)) queue.add(key);
    }

    // ------------------------------------------------------------------ cache file

    private Path cacheFile() {
        return dir.resolve("biomes.idx");
    }

    /** Reads {@code biomes.idx}; returns true if it held any regions. */
    private boolean readCache() {
        Path file = cacheFile();
        if (!Files.exists(file)) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return false;
            List<ResourceKey<Biome>> filePalette = MapStorage.readPalette(in);
            int count = in.readInt();
            Long2ObjectOpenHashMap<Region> read = new Long2ObjectOpenHashMap<>();
            for (int k = 0; k < count; k++) {
                int tx = in.readInt(), tz = in.readInt();
                long modified = in.readLong();
                short[] biome = new short[CHUNKS];
                int[] color = new int[CHUNKS];
                for (int i = 0; i < CHUNKS; i++) {
                    short f = in.readShort();
                    ResourceKey<Biome> b = f < 0 || f >= filePalette.size() ? null : filePalette.get(f);
                    biome[i] = b == null ? -1 : indexOf(b);
                }
                for (int i = 0; i < CHUNKS; i++) color[i] = in.readInt();
                read.put(MapView.tileKey(tx, tz), new Region(tx, tz, modified, biome, color));
            }
            regions.putAll(read);
            return !read.isEmpty();
        } catch (IOException e) {
            // A damaged cache is rebuilt from the regions.
            TeyvatCartographia.LOGGER.debug("Biome index {} unreadable, rebuilding", file, e);
            regions.clear();
            return false;
        }
    }

    private void writeCache() {
        Path file = cacheFile(), tmp = dir.resolve("biomes.idx.tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                MapStorage.writePalette(out, palette);
                out.writeInt(regions.size());
                for (Region reg : regions.values()) {
                    out.writeInt(reg.tx);
                    out.writeInt(reg.tz);
                    out.writeLong(reg.modified);
                    for (short s : reg.biome) out.writeShort(s);
                    for (int c : reg.color) out.writeInt(c);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.warn("Could not write the biome index {}", file, e);
        }
    }
}
