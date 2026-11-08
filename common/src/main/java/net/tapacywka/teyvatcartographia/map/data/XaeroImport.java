package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.BlockPos;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.compat.XaeroMapRegion;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the surface of the Overworld mapped by Xaero's World Map into this mod's map of it (user request 03.11.2026,
 * docs/XAERO_MAP_IMPORT.md): every region of Xaero's folders is read ({@link XaeroMapRegion}), each of its chunks becomes
 * a {@link ChunkScanner.Snapshot} in this mod's colours (block textures, the biome tints of the world's registry, water,
 * lava, block light) and is shaded by {@link ChunkShader} among its neighbours, as a chunk the scanner read. Chunks
 * already on this mod's map are left as they are (the user's choice: its own map comes first), and of several Xaero
 * folders the first one with a chunk gives it. Runs on its own thread while {@link MapDataManager} has the map closed
 * ({@link MapDataManager#pauseForImport}); reads the registries only, so it works from any dimension. Only the Overworld's
 * surface (user decision 04.11.2026): its caves, the Nether (Xaero keeps it in cave layers only, with a third of the
 * columns as rock it never looked under) and the other dimensions this mod maps as the player goes.
 */
public final class XaeroImport {
    private static final Pattern REGION = Pattern.compile("(-?\\d+)_(-?\\d+)\\.zip");
    /** Xaero's regions per side of this mod's region: 512 against 256 blocks. */
    private static final int TILES = XaeroMapRegion.SIZE / MapTile.SIZE;

    /** Progress for the screen: regions done of all, chunks written, regions Xaero left broken. */
    public final AtomicInteger done = new AtomicInteger(), total = new AtomicInteger(), chunks = new AtomicInteger(), broken = new AtomicInteger();
    /** Bytes of Xaero's files of the regions done, for the time left ({@link #secondsLeft}). */
    private final java.util.concurrent.atomic.AtomicLong bytesDone = new java.util.concurrent.atomic.AtomicLong();
    /** Bytes of all the files, and when the work began ({@link System#nanoTime}); set once the regions are listed. */
    private volatile long totalBytes, began;
    public final AtomicBoolean cancel = new AtomicBoolean();
    private final int threads = threads();

    /** The import's threads: every core but one (the game keeps one). */
    public static int threads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /** About how many seconds are left (see {@link ImportTime}), or -1 while the regions are still being listed. */
    public double secondsLeft() {
        long from = began;
        if (from == 0) return -1;
        return ImportTime.left((System.nanoTime() - from) / 1e9, done.get(), bytesDone.get(), total.get(), totalBytes, threads);
    }

    private final ClientLevel level;
    private final Path target;
    private final List<Path> sources;
    private final int minY, maxY;
    private final MapConfig.Detail detail = MapConfig.get(MapConfig.DETAIL);
    private final boolean shallow = MapConfig.get(MapConfig.SHALLOW_WATER) > 0;
    private final Map<Block, Integer> own;

    /**
     * @param level   the level the player is in, for the registries (any dimension: the Overworld's heights come from its
     *                dimension type)
     * @param sources Xaero's folders of the Overworld (each holding {@code <x>_<z>.zip}), the first one first
     * @param target  this mod's folder of the Overworld
     */
    public XaeroImport(ClientLevel level, List<Path> sources, Path target) {
        this.level = level;
        this.sources = sources;
        this.target = target;
        net.minecraft.world.level.dimension.DimensionType overworld = level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD);
        this.minY = overworld.minY();
        this.maxY = overworld.minY() + overworld.height() - 1;
        this.own = MapPalette.dimension(Level.OVERWORLD);
    }

    /** Xaero's regions of a folder by key {@link MapView#tileKey} of their region coordinates. */
    static Map<Long, Path> regions(Path dir) {
        Map<Long, Path> out = new HashMap<>();
        try (var files = Files.list(dir)) {
            files.forEach(f -> {
                Matcher m = REGION.matcher(f.getFileName().toString());
                if (m.matches()) out.put(MapView.tileKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), f);
            });
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.warn("Could not list Xaero's map folder {}", dir, e);
        }
        return out;
    }

    /** Imports every region; returns the chunks written. */
    public int run() {
        // Per Xaero region, its file in every folder that has it, the first folder first.
        Map<Long, List<Path>> work = new java.util.LinkedHashMap<>();
        for (Path s : sources) regions(s).forEach((key, file) -> work.computeIfAbsent(key, k -> new ArrayList<>()).add(file));
        long bytes = 0;
        for (List<Path> files : work.values()) bytes += size(files);
        totalBytes = bytes;
        total.set(work.size());
        Map<Long, Integer> index = new java.util.concurrent.ConcurrentHashMap<>(readIndex(target));
        // Regions on every core but one (the game keeps one): a Xaero region writes its own four regions of this mod,
        // never one another region writes (512 blocks against 256, aligned).
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Teyvat Cartographia Xaero import");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        long start = System.nanoTime();
        began = start;
        // A faster PNG level and one row filter during the import were tried (03.11.2026): 6% less time on the whole
        // map, 9% larger files, the game's PNG writer changed meanwhile; left out.
        try {
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            work.forEach((key, files) -> jobs.add(pool.submit(() -> region(key, files, index))));
            for (var job : jobs) {
                try {
                    job.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    TeyvatCartographia.LOGGER.error("Import of a Xaero map region failed", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel.set(true);
        } finally {
            pool.shutdownNow();
        }
        writeIndex(target, index);
        // Imported from another dimension into an Overworld not visited yet: without this file its map is not listed.
        if (chunks.get() > 0) MapStorage.writeDimension(target, Level.OVERWORLD);
        TeyvatCartographia.LOGGER.info("Xaero import: {} regions, {} chunks in {} ms on {} threads; per phase (ms over all threads): read {}, heights {}, colours {}, shade {}, save {}",
                done.get(), chunks.get(), (System.nanoTime() - start) / 1_000_000, threads,
                nanos[0].get() / 1_000_000, nanos[1].get() / 1_000_000, nanos[2].get() / 1_000_000, nanos[3].get() / 1_000_000, nanos[4].get() / 1_000_000);
        return chunks.get();
    }

    /** Time spent per phase over all threads: reading, heights, colours and tints, shading, saving. */
    private final java.util.concurrent.atomic.AtomicLong[] nanos = {new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong(),
            new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong()};

    private long since(int phase, long from) {
        long now = System.nanoTime();
        nanos[phase].addAndGet(now - from);
        return now;
    }

    /** A region file, or null (counted) when Xaero left it broken. */
    private XaeroMapRegion read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return XaeroMapRegion.readZip(in);
        } catch (IOException e) {
            broken.incrementAndGet();
            TeyvatCartographia.LOGGER.warn("Xaero's map region {} could not be read: {}", file, e.toString());
            return null;
        }
    }

    /** One Xaero region from every folder that has it, the first one first, into this mod's four regions there. */
    private void region(long key, List<Path> files, Map<Long, Integer> index) {
        if (cancel.get()) return;
        int rx = MapView.tileKeyX(key), rz = MapView.tileKeyZ(key);
        MapTile[] tiles = new MapTile[TILES * TILES];
        try {
            long t0 = System.nanoTime();
            for (int t = 0; t < tiles.length; t++) {
                int tx = rx * TILES + t % TILES, tz = rz * TILES + t / TILES;
                MapTile tile = MapStorage.load(target, tx, tz);
                tiles[t] = tile != null ? tile : new MapTile(tx, tz);
            }
            // Of several folders, each fills what the ones before it left.
            for (Path file : files) {
                XaeroMapRegion region = read(file);
                if (region == null) continue;
                t0 = since(0, t0);
                importRegion(region, rx, rz, tiles);
                t0 = System.nanoTime();
            }
            for (MapTile t : tiles) {
                if (!t.diskDirty) continue;
                t.diskDirty = false;
                MapStorage.save(target, t.snapshot());
                index.put(t.key, t.chunkCount);
            }
            since(4, t0);
        } catch (IOException | RuntimeException e) {
            TeyvatCartographia.LOGGER.error("Import of Xaero's map region {},{} failed", rx, rz, e);
        } finally {
            for (MapTile t : tiles) if (t != null) t.release();
        }
        bytesDone.addAndGet(size(files)); // before the count: the time left never sees a region without its bytes
        done.incrementAndGet();
    }

    /** Bytes of the files in all (a file that cannot be read counts 0). */
    private static long size(List<Path> files) {
        long n = 0;
        for (Path f : files) {
            try {
                n += Files.size(f);
            } catch (IOException e) {
                // Counted as nothing: read() reports it.
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ one region

    /** Per column of the region, this mod's sampled data (as {@link ChunkScanner#scan} gives it). */
    private void importRegion(XaeroMapRegion r, int rx, int rz, MapTile[] tiles) {
        long t0 = System.nanoTime();
        int n = XaeroMapRegion.SIZE * XaeroMapRegion.SIZE;
        int[] y = r.heights(minY, maxY);
        t0 = since(1, t0);
        int[] color = new int[n], bottom = new int[n];
        short[] height = new short[n];
        byte[] depth = new byte[n], flags = new byte[n];
        boolean[] keep = new boolean[n];
        // What depends on the block alone, once per block of the palette, not per column.
        Look[] looks = new Look[r.states.size()];
        for (int s = 0; s < looks.length; s++) looks[s] = look(state(r.states.get(s)));
        List<Holder<Biome>> biomes = new ArrayList<>();
        for (String b : r.biomes) biomes.add(biome(b));
        int[][] tints = tints(r, biomes, rx, rz);
        int water = TextureColors.water(), stone = MapPalette.standard(net.minecraft.world.level.material.MapColor.STONE);
        for (int i = 0; i < n; i++) {
            if (y[i] == Integer.MIN_VALUE) continue;
            Look l = r.state[i] < 0 ? null : looks[r.state[i]];
            if (r.water[i] || l != null && l.water) {
                int d = r.top[i] >= 0 ? (r.top[i] - (r.height[i] & 0xFF)) & 0xFF : 1;
                d = Math.max(1, Math.min(24, d));
                height[i] = (short) Math.min(maxY, y[i] + (r.top[i] >= 0 ? d : 0));
                depth[i] = (byte) d;
                if (r.overlay[i] >= 0) {
                    color[i] = looks[r.overlay[i]].colour(tints, i);
                    keep[i] = true;
                } else {
                    int tint = 0xFF000000 | tints[2][i];
                    color[i] = water != 0 ? TextureColors.multiply(water, tint) : tint;
                    if (shallow && d <= ChunkScanner.SHALLOW && l != null) bottom[i] = l.colour(tints, i);
                }
            } else if (l != null && l.lava) {
                height[i] = (short) y[i];
                depth[i] = ChunkScanner.LAVA;
                color[i] = TextureColors.lava() != 0 ? TextureColors.lava() : MapPalette.LAVA;
            } else {
                height[i] = (short) y[i];
                color[i] = l == null ? stone : l.colour(tints, i);
            }
            int dd = depth[i];
            flags[i] = (byte) (MapTile.FLAG_KNOWN | (dd > 0 ? MapTile.FLAG_WATER : 0) | (dd >= 2 || dd == ChunkScanner.LAVA ? MapTile.FLAG_IMPASSABLE : 0));
        }
        t0 = since(2, t0);
        for (int cz = 0; cz < 32; cz++) {
            for (int cx = 0; cx < 32; cx++) {
                if (!r.chunk[cz * 32 + cx]) continue;
                int tile = (cz >> 4) * TILES + (cx >> 4);
                MapTile t = tiles[tile];
                int lcx = cx & 15, lcz = cz & 15;
                if (t.hasChunk(lcx, lcz)) continue; // on this mod's map already (or from a folder before)
                chunk(r, rx, rz, cx, cz, color, bottom, height, depth, flags, keep, biomes, tiles, t, lcx, lcz);
            }
        }
        since(3, t0);
    }

    private void chunk(XaeroMapRegion r, int rx, int rz, int cx, int cz, int[] color, int[] bottom, short[] height, byte[] depth,
                       byte[] flags, boolean[] keep, List<Holder<Biome>> biomes, MapTile[] tiles, MapTile t, int lcx, int lcz) {
        int[] c = new int[256], b = new int[256];
        short[] h = new short[256];
        byte[] d = new byte[256], light = new byte[256];
        boolean[] isVoid = new boolean[256], k = new boolean[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = XaeroMapRegion.index(cx * 16 + x, cz * 16 + z), o = z * 16 + x;
                if ((flags[i] & MapTile.FLAG_KNOWN) == 0) {
                    isVoid[o] = true;
                    h[o] = (short) minY;
                    continue;
                }
                c[o] = color[i];
                b[o] = bottom[i];
                h[o] = height[i];
                d[o] = depth[i];
                k[o] = keep[i];
                light[o] = r.light[i];
            }
        }
        int mid = XaeroMapRegion.index(cx * 16 + 8, cz * 16 + 8);
        ResourceKey<Biome> biome = r.biome[mid] < 0 ? null : biomes.get(r.biome[mid]).unwrapKey().orElse(null);
        int wcx = rx * 32 + cx, wcz = rz * 32 + cz;
        ChunkScanner.Snapshot snap = new ChunkScanner.Snapshot(wcx, wcz, c, h, d, isVoid, light, k, b, biome);
        ChunkShader.Neighbourhood n = neighbourhood(cx, cz, height, flags, tiles, rx, rz);
        ChunkShader.Result res = ChunkShader.shade(snap, n, detail);
        t.applyChunk(lcx, lcz, res.abgr(), res.heights(), res.flags(), res.light(), res.neighbourMask(), res.biome(), false);
        chunks.incrementAndGet();
    }

    /**
     * The padding around chunk cx, cz of the region: Xaero's columns there, else this mod's (its regions of the area are
     * loaded), else the chunk's own edge continued (a neighbour outside the region: no fade into the fog there).
     */
    private ChunkShader.Neighbourhood neighbourhood(int cx, int cz, short[] height, byte[] flags, MapTile[] tiles, int rx, int rz) {
        int w = ChunkShader.W, pad = ChunkShader.PAD;
        short[] h = new short[w * w];
        byte[] f = new byte[w * w];
        int size = XaeroMapRegion.SIZE;
        for (int z = -pad; z < 16 + pad; z++) {
            for (int x = -pad; x < 16 + pad; x++) {
                int p = ChunkShader.pidx(x, z);
                int ax = cx * 16 + x, az = cz * 16 + z;
                if (ax >= 0 && az >= 0 && ax < size && az < size) {
                    int i = XaeroMapRegion.index(ax, az);
                    if ((flags[i] & MapTile.FLAG_KNOWN) != 0) {
                        h[p] = height[i];
                        f[p] = flags[i];
                        continue;
                    }
                    MapTile t = tiles[(az >> 8) * TILES + (ax >> 8)];
                    int ti = MapTile.idx(ax & 255, az & 255);
                    if ((t.flags[ti] & MapTile.FLAG_KNOWN) != 0) {
                        h[p] = t.heights[ti];
                        f[p] = t.flags[ti];
                        continue;
                    }
                }
                int i = XaeroMapRegion.index(cx * 16 + Math.max(0, Math.min(15, x)), cz * 16 + Math.max(0, Math.min(15, z)));
                h[p] = height[i];
                f[p] = flags[i];
            }
        }
        // Every side counts as known: the import does not come back to redo a chunk once its neighbour arrives.
        return new ChunkShader.Neighbourhood(h, f, 15);
    }

    // ------------------------------------------------------------------ colours

    /**
     * What a block of the palette looks like on the map: water or lava, and its colour as {@link ChunkScanner} gives it
     * (the theme's, the texture's, or the game's map colour), with the biome tint it takes ({@code tint} 0 none, 1 grass,
     * 2 foliage).
     */
    private record Look(boolean water, boolean lava, int argb, int tint) {
        int colour(int[][] tints, int i) {
            return tint == 0 ? argb : TextureColors.multiply(argb, 0xFF000000 | tints[tint - 1][i]);
        }
    }

    private Look look(BlockState st) {
        boolean water = st.getFluidState().is(net.minecraft.tags.FluidTags.WATER), lava = st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        if (st.getBlock() instanceof SnowLayerBlock) return new Look(water, lava, TextureColors.snow() != 0 ? TextureColors.snow() : MapPalette.standard(net.minecraft.world.level.material.MapColor.SNOW), 0);
        Integer mine = own == null ? null : own.get(st.getBlock());
        if (mine != null) return new Look(water, lava, mine, 0);
        TextureColors.Entry tex = TextureColors.get(st);
        if (tex == null) return new Look(water, lava, MapPalette.standard(st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)), 0);
        return switch (tex.tint()) {
            case GRASS -> new Look(water, lava, tex.argb(), 1);
            case FOLIAGE -> new Look(water, lava, tex.argb(), 2);
            case WORLD, NONE -> new Look(water, lava, tex.argb(), 0);
        };
    }

    /**
     * Grass, foliage and water tints per column from the biomes Xaero kept, each averaged over the 5 x 5 mapped columns
     * around (the game blends biome colours too): one biome lookup per column, then a box sum along x and along z.
     */
    private static int[][] tints(XaeroMapRegion r, List<Holder<Biome>> biomes, int rx, int rz) {
        int size = XaeroMapRegion.SIZE, n = size * size;
        int bx0 = rx * size, bz0 = rz * size;
        int[] grass = new int[biomes.size()], foliage = new int[biomes.size()], water = new int[biomes.size()];
        boolean[] grassByPlace = new boolean[biomes.size()];
        for (int b = 0; b < biomes.size(); b++) {
            Biome biome = biomes.get(b).value();
            grass[b] = biome.getGrassColor(0, 0);
            grassByPlace[b] = biome.getGrassColor(0, 0) != biome.getGrassColor(1000, 1000) || biome.getGrassColor(0, 0) != biome.getGrassColor(-333, 777);
            foliage[b] = biome.getFoliageColor();
            water[b] = biome.getWaterColor();
        }
        int[][] out = new int[3][];
        int[] raw = new int[n];
        for (int k = 0; k < 3; k++) {
            for (int i = 0; i < n; i++) {
                int b = r.biome[i];
                if (b < 0) continue;
                raw[i] = k == 0 ? grassByPlace[b] ? biomes.get(b).value().getGrassColor(bx0 + i % size, bz0 + i / size) : grass[b] : k == 1 ? foliage[b] : water[b];
            }
            out[k] = blur(raw, r.biome);
        }
        return out;
    }

    /** The 5 x 5 average of {@code c} over the mapped columns ({@code biome >= 0}), by box sums along x then z. */
    private static int[] blur(int[] c, short[] biome) {
        int size = XaeroMapRegion.SIZE, n = size * size;
        int[] r1 = new int[n], g1 = new int[n], b1 = new int[n], n1 = new int[n];
        for (int z = 0; z < size; z++) {
            int r = 0, g = 0, b = 0, m = 0, row = z * size;
            for (int x = -2; x < size; x++) {
                int in = x + 2, outX = x - 3;
                if (in < size && biome[row + in] >= 0) { int v = c[row + in]; r += v >> 16 & 255; g += v >> 8 & 255; b += v & 255; m++; }
                if (outX >= 0 && biome[row + outX] >= 0) { int v = c[row + outX]; r -= v >> 16 & 255; g -= v >> 8 & 255; b -= v & 255; m--; }
                if (x >= 0) { int i = row + x; r1[i] = r; g1[i] = g; b1[i] = b; n1[i] = m; }
            }
        }
        int[] out = new int[n];
        for (int x = 0; x < size; x++) {
            int r = 0, g = 0, b = 0, m = 0;
            for (int z = -2; z < size; z++) {
                int in = z + 2, outZ = z - 3;
                if (in < size) { int i = in * size + x; r += r1[i]; g += g1[i]; b += b1[i]; m += n1[i]; }
                if (outZ >= 0) { int i = outZ * size + x; r -= r1[i]; g -= g1[i]; b -= b1[i]; m -= n1[i]; }
                if (z >= 0 && m > 0) out[z * size + x] = r / m << 16 | g / m << 8 | b / m;
            }
        }
        return out;
    }

    /**
     * The block state Xaero names, with the properties this game knows; stone for a block of a mod not in this game
     * (an unknown block drawn as air came out black, 04.11.2026), so its place still shows as ground.
     */
    static BlockState state(XaeroMapRegion.State s) {
        ResourceLocation id = ResourceLocation.tryParse(s.name());
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null || block == Blocks.AIR && !s.name().equals("minecraft:air")) return Blocks.STONE.defaultBlockState();
        BlockState st = block.defaultBlockState();
        for (var e : s.properties().entrySet()) {
            Property<?> p = block.getStateDefinition().getProperty(e.getKey());
            if (p != null) st = with(st, p, e.getValue());
        }
        return st;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState st, Property<T> p, String value) {
        return p.getValue(value).map(v -> st.setValue(p, v)).orElse(st);
    }

    /** The biome Xaero names from the level's registry (mods' biomes come with the server); plains when unknown. */
    private Holder<Biome> biome(String name) {
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id != null) {
            var h = registry.getHolder(ResourceKey.create(Registries.BIOME, id));
            if (h.isPresent()) return h.get();
        }
        return registry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
    }

    // ------------------------------------------------------------------ index

    private static Map<Long, Integer> readIndex(Path dir) {
        Map<Long, Integer> out = new HashMap<>();
        Path index = dir.resolve("index.txt");
        if (!Files.exists(index)) return out;
        try (BufferedReader r = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.trim().split(" ");
                if (p.length == 3) out.put(MapView.tileKey(Integer.parseInt(p[0]), Integer.parseInt(p[1])), Integer.parseInt(p[2]));
            }
        } catch (IOException | NumberFormatException e) {
            TeyvatCartographia.LOGGER.warn("Could not read map index {}", index, e);
        }
        return out;
    }

    private void writeIndex(Path dir, Map<Long, Integer> index) {
        StringBuilder sb = new StringBuilder();
        index.forEach((k, v) -> sb.append(MapView.tileKeyX(k)).append(' ').append(MapView.tileKeyZ(k)).append(' ').append(v).append('\n'));
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("index.txt.tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
            Files.move(tmp, dir.resolve("index.txt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.warn("Could not write map index in {}", dir, e);
        }
    }
}
