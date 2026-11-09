package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.resources.ResourceKey;
import com.mojang.blaze3d.platform.NativeImage;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.map.MapView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disk format of the explored map (separate from user settings):
 * {@code <gameDir>/teyvatcartographia/<world>/<dimension>/r.<tx>.<tz>.png} holds the rendered pixels, and
 * {@code r.<tx>.<tz>.dat} the gzip'd heights, flags and chunk metadata. Pure IO, safe to call from worker threads.
 */
public final class MapStorage {
    private static final int MAGIC = 0x54594D31; // "TYM1"
    /** v2: surface sampled at the correct height (v1 read the block below the surface); v1 regions are rebuilt. */
    /** v3: no water rim highlight along coasts. */
    /** v4: no zoom-dependent alpha fade on the explored edge (drawn in screen space instead). */
    /** v5: stronger biome tint for grass, foliage and water. */
    /** v6: per-column block light (light sources on the map). */
    /** v7 (0.2.0): colours of the block textures, plants, shallow water; older regions are deleted ({@link #dropOutdated}). */
    static final int VERSION = 7;
    private static final Pattern TILE_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.png");

    private MapStorage() {}

    public static Set<Long> listTiles(Path dir) {
        Set<Long> keys = new HashSet<>();
        if (!Files.isDirectory(dir)) return keys;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "r.*.png")) {
            for (Path p : stream) {
                Matcher m = TILE_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    keys.add(MapView.tileKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                }
            }
        } catch (IOException | NumberFormatException e) {
            TeyvatCartographia.LOGGER.warn("Could not list map tiles in {}", dir, e);
        }
        return keys;
    }

    static void save(Path dir, MapTile.TileSnapshot s) throws IOException {
        try {
            Files.createDirectories(dir);
            String base = "r." + s.tx() + "." + s.tz();
            Path png = dir.resolve(base + ".png"), pngTmp = dir.resolve(base + ".png.tmp");
            Path dat = dir.resolve(base + ".dat"), datTmp = dir.resolve(base + ".dat.tmp");
            s.image().writeToFile(pngTmp);
            try (NativeImage thumb = thumbnail(s.image())) {
                writeThumbnail(dir, s.tx(), s.tz(), thumb);
            } catch (IOException e) {
                TeyvatCartographia.LOGGER.warn("Could not write the map thumbnail of region {},{}", s.tx(), s.tz(), e);
            }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(datTmp))))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                for (short h : s.heights()) out.writeShort(h);
                out.write(s.flags());
                out.write(s.chunkState());
                for (short b : s.chunkBiome()) out.writeShort(b);
                writePalette(out, s.biomePalette());
                out.write(s.light());
            }
            replace(datTmp, dat);
            replace(pngTmp, png);
        } finally {
            s.image().close();
        }
    }

    /** Loads a tile; returns {@code null} if the files are missing. Caller owns the returned tile. */
    static MapTile load(Path dir, int tx, int tz) throws IOException {
        String base = "r." + tx + "." + tz;
        Path png = dir.resolve(base + ".png"), dat = dir.resolve(base + ".dat");
        if (!Files.exists(png) || !isCurrentVersion(dat)) return null; // missing or outdated: rebuilt from the world
        NativeImage image;
        try (InputStream in = Files.newInputStream(png)) {
            image = NativeImage.read(NativeImage.Format.RGBA, in);
        }
        if (image.getWidth() != MapTile.SIZE || image.getHeight() != MapTile.SIZE) {
            image.close();
            throw new IOException("Unexpected tile size in " + png);
        }
        short[] heights = new short[MapTile.SIZE * MapTile.SIZE];
        byte[] flags = new byte[MapTile.SIZE * MapTile.SIZE];
        byte[] chunkState = new byte[MapTile.CHUNKS * MapTile.CHUNKS];
        byte[] light = new byte[MapTile.SIZE * MapTile.SIZE];
        short[] chunkBiome = new short[MapTile.CHUNKS * MapTile.CHUNKS];
        List<ResourceKey<Biome>> palette = new ArrayList<>();
        java.util.Arrays.fill(heights, MapTile.UNKNOWN_HEIGHT);
        java.util.Arrays.fill(chunkBiome, (short) -1);
        if (Files.exists(dat)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(dat))))) {
                if (in.readInt() == MAGIC && in.readInt() == VERSION) {
                    for (int i = 0; i < heights.length; i++) heights[i] = in.readShort();
                    in.readFully(flags);
                    in.readFully(chunkState);
                    for (int i = 0; i < chunkBiome.length; i++) chunkBiome[i] = in.readShort();
                    palette.addAll(readPalette(in));
                    in.readFully(light);
                }
            } catch (IOException e) {
                // Pixels are still usable; metadata will be rebuilt as chunks are revisited.
                TeyvatCartographia.LOGGER.warn("Damaged map metadata {}, ignoring", dat, e);
            }
        }
        MapTile tile = new MapTile(tx, tz, image, heights, flags, light, chunkState, chunkBiome);
        tile.biomePalette.addAll(palette);
        tile.gpuDirty = true;
        return tile;
    }

    /** The biome of every chunk of a region: palette indices ({@code -1} = unknown) and the palette. */
    record RegionBiomes(short[] chunkBiome, List<ResourceKey<Biome>> palette) {}

    /**
     * Reads only the chunk biomes of a region's metadata (for the biome search, {@link BiomeIndex}); {@code null}
     * if the file is missing, outdated or damaged. Safe on worker threads.
     */
    static RegionBiomes readBiomes(Path dat) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(dat))))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            int pixels = MapTile.SIZE * MapTile.SIZE;
            in.skipNBytes(pixels * 2L + pixels + MapTile.CHUNKS * MapTile.CHUNKS); // heights, flags, chunk states (as in load)
            short[] chunkBiome = new short[MapTile.CHUNKS * MapTile.CHUNKS];
            for (int i = 0; i < chunkBiome.length; i++) chunkBiome[i] = in.readShort();
            return new RegionBiomes(chunkBiome, readPalette(in));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads only which chunks of a region are on the map ({@link TileStore#chunkBits}), for the outline of the regions
     * beside it; none if the file is missing, outdated or damaged. Safe on worker threads.
     */
    static long[] readChunks(Path dir, int tx, int tz) {
        byte[] state = new byte[MapTile.CHUNKS * MapTile.CHUNKS];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(dir.resolve("r." + tx + "." + tz + ".dat")))))) {
            if (in.readInt() == MAGIC && in.readInt() == VERSION) {
                int pixels = MapTile.SIZE * MapTile.SIZE;
                in.skipNBytes(pixels * 2L + pixels); // heights, flags (as in load)
                in.readFully(state);
            }
        } catch (IOException e) {
            java.util.Arrays.fill(state, (byte) 0);
        }
        return TileStore.chunkBits(state);
    }

    /**
     * A biome palette: a count, then one id per entry (also in {@link BiomeIndex}'s cache). An unreadable id keeps its
     * place (as unknown), so the other indices stay right.
     */
    static List<ResourceKey<Biome>> readPalette(DataInputStream in) throws IOException {
        List<ResourceKey<Biome>> palette = new ArrayList<>();
        int n = in.readInt();
        for (int i = 0; i < n && i < 4096; i++) {
            ResourceLocation id = ResourceLocation.tryParse(in.readUTF());
            palette.add(id == null ? null : ResourceKey.create(Registries.BIOME, id));
        }
        return palette;
    }

    static void writePalette(DataOutputStream out, List<ResourceKey<Biome>> palette) throws IOException {
        out.writeInt(palette.size());
        for (ResourceKey<Biome> b : palette) out.writeUTF(b == null ? "" : b.location().toString());
    }

    /** Matches a region's metadata file, {@code r.<tx>.<tz>.dat}. */
    static final Pattern DAT_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.dat");

    // ------------------------------------------------------------------ LOD thumbnails

    /** Side of a region's thumbnail ({@code r.<tx>.<tz>.lod.png}): one pixel per 4 x 4 blocks. */
    public static final int THUMB = MapTile.SIZE / 4;

    /**
     * Quarter-size copy of a region image (ABGR): each pixel averages the explored ones of its 4 x 4 block, and its
     * alpha is the average of the 16 (on the map the explored share, so its edge stays soft; the far land's faint veil
     * stays faint). Caller owns the result.
     */
    static NativeImage thumbnail(NativeImage src) {
        NativeImage out = new NativeImage(THUMB, THUMB, true);
        int[] chunk = new int[CHUNK_PIXELS], thumb = new int[CHUNK_THUMB * CHUNK_THUMB];
        for (int lcz = 0; lcz < MapTile.CHUNKS; lcz++) {
            for (int lcx = 0; lcx < MapTile.CHUNKS; lcx++) {
                readChunk(src, lcx, lcz, chunk);
                thumbChunk(chunk, thumb);
                for (int ty = 0; ty < CHUNK_THUMB; ty++) {
                    for (int tx = 0; tx < CHUNK_THUMB; tx++) out.setPixelRGBA(lcx * CHUNK_THUMB + tx, lcz * CHUNK_THUMB + ty, thumb[ty * CHUNK_THUMB + tx]);
                }
            }
        }
        return out;
    }

    /** Pixels of a chunk in a region's picture (16 x 16) and in its thumbnail (4 x 4 a side). */
    static final int CHUNK_PIXELS = 16 * 16, CHUNK_THUMB = THUMB / MapTile.CHUNKS;

    /** The 16 x 16 picture pixels of chunk (lcx, lcz) of a region, row by row. */
    static void readChunk(NativeImage picture, int lcx, int lcz, int[] into) {
        int x0 = lcx * 16, y0 = lcz * 16;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) into[y * 16 + x] = picture.getPixelRGBA(x0 + x, y0 + y);
        }
    }

    /**
     * A chunk's 4 x 4 thumbnail pixels from its 16 x 16 picture pixels (row by row): each pixel averages the explored
     * ones of its 4 x 4 block, and its alpha is the average of the 16 (on the map the explored share, so its edge stays
     * soft; the far land's faint veil stays faint).
     */
    static void thumbChunk(int[] chunk, int[] thumb) {
        for (int ty = 0; ty < CHUNK_THUMB; ty++) {
            for (int tx = 0; tx < CHUNK_THUMB; tx++) {
                int r = 0, g = 0, b = 0, a = 0, n = 0;
                for (int dy = 0; dy < 4; dy++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int c = chunk[(ty * 4 + dy) * 16 + tx * 4 + dx];
                        if ((c >>> 24) == 0) continue;
                        r += c & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += (c >> 16) & 0xFF;
                        a += c >>> 24;
                        n++;
                    }
                }
                thumb[ty * CHUNK_THUMB + tx] = n == 0 ? 0 : ((a / 16) << 24) | ((b / n) << 16) | ((g / n) << 8) | (r / n);
            }
        }
    }

    /** Where the chunks of {@link #thumbnailChunks} are read from and their thumbnail pixels go. */
    interface ChunkPixels {
        /** The chunk's 16 x 16 picture pixels, row by row ({@link #readChunk}). */
        void read(int lcx, int lcz, int[] into);

        /** The chunk's 4 x 4 thumbnail pixels, row by row. */
        void write(int lcx, int lcz, int[] thumb);
    }

    /**
     * The thumbnail pixels of the chunks set in {@code chunks} (a bit per chunk of the region, {@code lcz * CHUNKS +
     * lcx}): a changed chunk costs 256 reads of the picture instead of the region's 65 536 (the whole thumbnail of every
     * changed region took 2% of the game's thread, profile 09.11.2026). The pixels go through two buffers, a call each
     * per chunk, not per pixel (that made the whole cell three times slower). Returns the chunks done.
     */
    static int thumbnailChunks(long[] chunks, ChunkPixels pixels, int[] chunk, int[] thumb) {
        int done = 0;
        for (int w = 0; w < chunks.length; w++) {
            long bits = chunks[w];
            while (bits != 0) {
                int i = w * 64 + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                int lcx = i % MapTile.CHUNKS, lcz = i / MapTile.CHUNKS;
                pixels.read(lcx, lcz, chunk);
                thumbChunk(chunk, thumb);
                pixels.write(lcx, lcz, thumb);
                done++;
            }
        }
        return done;
    }

    private static void writeThumbnail(Path dir, int tx, int tz, NativeImage thumb) throws IOException {
        Path file = dir.resolve("r." + tx + "." + tz + ".lod.png"), tmp = dir.resolve("r." + tx + "." + tz + ".lod.png.tmp");
        thumb.writeToFile(tmp);
        replace(tmp, file);
    }

    /** How often {@link #replace} tries, and how long it waits between tries. */
    static final int REPLACE_TRIES = 6;
    private static final long REPLACE_WAIT_MS = 25;

    /** One move of a file over another; a test puts its own in. */
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    /**
     * Puts a finished temporary file in a file's place. Tried again a few times: regions are also read on threads of
     * their own (thumbnails, chunk lists, the biome search), and where a file being read cannot be replaced (Windows
     * before 10 1809, volumes that are not NTFS) the move fails until the reader is done, a matter of milliseconds.
     * Called on the writes' thread only, so the wait holds no frame.
     */
    static void replace(Path tmp, Path file) throws IOException {
        replace(tmp, file, (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING));
    }

    static void replace(Path tmp, Path file, Mover mover) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                mover.move(tmp, file);
                return;
            } catch (IOException e) {
                if (attempt >= REPLACE_TRIES) throw e;
            }
            try {
                Thread.sleep(REPLACE_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while replacing " + file, e);
            }
        }
    }

    /**
     * A region's thumbnail, or {@code null} if the region is not on disk. Regions saved before thumbnails existed
     * get one made from their image now (and written, so this happens once). Caller owns the result.
     */
    static NativeImage loadThumbnail(Path dir, int tx, int tz) throws IOException {
        Path file = dir.resolve("r." + tx + "." + tz + ".lod.png");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in);
                if (img.getWidth() == THUMB && img.getHeight() == THUMB) return img;
                img.close();
            }
        }
        Path png = dir.resolve("r." + tx + "." + tz + ".png");
        if (!Files.exists(png)) return null;
        NativeImage image;
        try (InputStream in = Files.newInputStream(png)) {
            image = NativeImage.read(NativeImage.Format.RGBA, in);
        }
        try {
            if (image.getWidth() != MapTile.SIZE || image.getHeight() != MapTile.SIZE) return null;
            NativeImage thumb = thumbnail(image);
            writeThumbnail(dir, tx, tz, thumb);
            return thumb;
        } finally {
            image.close();
        }
    }


    /**
     * Deletes the regions of an earlier format from a map folder, with their thumbnails, the folder's index and its
     * biome cache, once (then {@code format.txt} says the folder is current). User decision 24.10.2026: a map drawn in
     * the colours of an older version left chunk-shaped patches beside the newly mapped land, so an old map is mapped
     * again from scratch instead. Returns how many regions were deleted.
     */
    static int dropOutdated(Path dir) {
        Path stamp = dir.resolve("format.txt");
        int dropped = 0;
        try {
            if (Files.exists(stamp) && Files.readString(stamp, StandardCharsets.UTF_8).trim().equals(Integer.toString(VERSION))) return 0;
            if (Files.isDirectory(dir)) {
                List<Path> old = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "r.*.png")) {
                    for (Path p : stream) {
                        Matcher m = TILE_FILE.matcher(p.getFileName().toString());
                        if (m.matches() && !isCurrentVersion(dir.resolve("r." + m.group(1) + "." + m.group(2) + ".dat"))) old.add(p);
                    }
                }
                for (Path png : old) {
                    String base = png.getFileName().toString();
                    base = base.substring(0, base.length() - ".png".length());
                    Files.deleteIfExists(png);
                    Files.deleteIfExists(dir.resolve(base + ".dat"));
                    Files.deleteIfExists(dir.resolve(base + ".lod.png"));
                }
                dropped = old.size();
                if (dropped > 0) {
                    Files.deleteIfExists(dir.resolve("index.txt"));
                    Files.deleteIfExists(dir.resolve("chunks.idx"));
                    Files.deleteIfExists(dir.resolve("biomes.idx"));
                    TeyvatCartographia.LOGGER.info("Deleted {} map regions of an older format in {}", dropped, dir);
                }
            }
            Files.createDirectories(dir);
            Files.writeString(stamp, Integer.toString(VERSION), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.warn("Could not check the map format in {}", dir, e);
        }
        return dropped;
    }
    private static boolean isCurrentVersion(Path dat) {
        if (!Files.exists(dat)) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(dat))))) {
            return in.readInt() == MAGIC && in.readInt() == VERSION;
        } catch (IOException e) {
            return false;
        }
    }

    /** Names the dimension of a map folder ({@code dimension.txt}): only folders with this file are listed as maps. */
    static void writeDimension(Path dir, ResourceKey<Level> dimension) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("dimension.txt"), dimension.location().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.warn("Could not create map directory {}", dir, e);
        }
    }

    /** Dimension folders that contain map data for a world. */
    public static List<String> listDimensions(Path worldDir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(worldDir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(worldDir, Files::isDirectory)) {
            for (Path p : stream) out.add(p.getFileName().toString());
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.warn("Could not list dimensions in {}", worldDir, e);
        }
        return out;
    }
}
