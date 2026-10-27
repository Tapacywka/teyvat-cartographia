package net.tapacywka.teyvatcartographia.marker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.map.WorldId;
import net.tapacywka.teyvatcartographia.nav.Navigation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The player's pins and the navigation target of one world, persisted as
 * {@code <gameDir>/teyvatcartographia/<world>/markers.json}. Every pin is the player's own, interactive ones too (the
 * pins a server shared with every player were removed on 24.10.2026). Main thread only.
 */
public final class MarkerStore {
    private MarkerStore() {}

    public static final int MAX_MARKERS = 300;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The contents of {@code markers.json}: the format version, the pins and the tracked target. */
    private record Saved(List<Marker> markers, Optional<Navigation.Target> target) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("version", 1).forGetter(s -> 1),
                Marker.CODEC.listOf().optionalFieldOf("markers", List.of()).forGetter(Saved::markers),
                Navigation.Target.CODEC.lenientOptionalFieldOf("target").forGetter(Saved::target)
        ).apply(i, (version, markers, target) -> new Saved(markers, target)));
    }

    private static final List<Marker> markers = new ArrayList<>();
    private static String worldId;
    /** Incremented on every change of the markers or the navigation target (all of them go through {@link #save()}). */
    private static int version;

    public static int version() {
        return version;
    }

    /** The pins of a dimension. */
    public static List<Marker> inDimension(ResourceKey<Level> dim) {
        List<Marker> out = new ArrayList<>();
        for (Marker m : markers) if (m.dimension == dim) out.add(m);
        return out;
    }

    /** Index-based access for per-frame loops (no list or iterator allocation); 0 ≤ i < {@link #count()}. */
    public static Marker get(int i) {
        return markers.get(i);
    }

    public static Marker find(UUID id) {
        for (Marker m : markers) if (m.id.equals(id)) return m;
        return null;
    }

    /** A new pin. False when there is no room left. */
    public static boolean add(Marker m) {
        if (markers.size() >= MAX_MARKERS) return false;
        markers.add(m);
        save();
        return true;
    }

    /** What {@link #addAll} did with the pins it was given. */
    public record Added(int added, int duplicates, int overLimit) {}

    /**
     * Adds imported pins with one save. A pin with the same dimension, position and name as one already here is a
     * repeat and is left out; nothing beyond {@link #MAX_MARKERS}. A pin keeps its id unless that id is taken.
     */
    public static Added addAll(List<Marker> in) {
        int added = 0, duplicates = 0, overLimit = 0;
        for (Marker m : in) {
            if (contains(m)) duplicates++;
            else if (markers.size() >= MAX_MARKERS) overLimit++;
            else {
                Marker copy = new Marker(find(m.id) == null ? m.id : UUID.randomUUID(), m.name, m.type, m.dimension, m.x, m.y, m.z, m.created, false);
                copy.band = m.band;
                markers.add(copy);
                added++;
            }
        }
        if (added > 0) save();
        return new Added(added, duplicates, overLimit);
    }

    /** The same dimension, position and name as one of the pins. */
    private static boolean contains(Marker m) {
        for (Marker o : markers) if (same(o, m)) return true;
        return false;
    }

    private static boolean same(Marker a, Marker b) {
        return a.dimension == b.dimension && a.x == b.x && a.y == b.y && a.z == b.z && a.name.equals(b.name);
    }

    /** The pins of this world, for export. */
    public static List<Marker> all() {
        return List.copyOf(markers);
    }

    public static void remove(Marker m) {
        if (Navigation.isTracking(m)) Navigation.clear();
        markers.remove(m);
        save();
    }

    /** Renames a pin or changes its type. */
    public static void update(Marker m, String name, MarkerType type) {
        m.name = name;
        m.type = type;
        save();
    }

    /** The number of pins, for loops with {@link #get} (their limit is {@link #MAX_MARKERS}). */
    public static int count() {
        return markers.size();
    }

    /** Loads markers for the given world (no-op if already loaded). {@code null} unloads. */
    public static void switchWorld(String id) {
        if (id == null ? worldId == null : id.equals(worldId)) return;
        version++;
        markers.clear();
        Navigation.restore(null);
        worldId = id;
        if (id == null) return;
        Path file = file();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                // A broken pin is left out and logged; the others load.
                Saved saved = Saved.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(r))
                        .resultOrPartial(e -> TeyvatCartographia.LOGGER.error("Problem in {}: {}", file, e)).orElse(null);
                if (saved != null) {
                    markers.addAll(saved.markers());
                    // A pin or an automatic point. Free points of older versions (neither) are dropped.
                    saved.target().filter(t -> t.markerId() != null || t.auto() != null).ifPresent(Navigation::restore);
                    TeyvatCartographia.LOGGER.info("Teyvat Cartographia: loaded {} markers for {}", markers.size(), id);
                }
            } catch (IOException | RuntimeException e) {
                TeyvatCartographia.LOGGER.error("Could not read markers from {}", file, e);
            }
        }
        takeOldSharedPins();
    }

    /**
     * Single player, once per world: the interactive pins 0.1.1 and 0.1.2 kept in the world save as shared pins
     * ({@code data/teyvatcartographia_pins.dat}) become the player's own; the file is renamed so it is read only once.
     * On a server they stay in its save, out of reach of the client.
     */
    private static void takeOldSharedPins() {
        var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        Path old = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve(TeyvatCartographia.MOD_ID + "_pins.dat");
        if (!Files.isRegularFile(old)) return;
        try {
            var root = net.minecraft.nbt.NbtIo.readCompressed(old, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            List<Marker> pins = readOldShared(root);
            Added a = addAll(pins);
            Files.move(old, old.resolveSibling(old.getFileName() + ".moved"), StandardCopyOption.REPLACE_EXISTING);
            TeyvatCartographia.LOGGER.info("Teyvat Cartographia: {} of the world's {} shared pins are now the player's own ({} repeats, {} over the limit)",
                    a.added(), pins.size(), a.duplicates(), a.overLimit());
        } catch (IOException | RuntimeException e) {
            TeyvatCartographia.LOGGER.error("Could not read the old shared pins from {}", old, e);
        }
    }

    /** The pins of an old shared pins file: {@code {data: {pins: [...]}}} as the server saved them. */
    static List<Marker> readOldShared(net.minecraft.nbt.CompoundTag root) {
        net.minecraft.nbt.Tag list = root.getCompound("data").get("pins");
        if (list == null) return List.of();
        return Marker.CODEC.listOf().parse(net.minecraft.nbt.NbtOps.INSTANCE, list)
                .resultOrPartial(e -> TeyvatCartographia.LOGGER.error("Problem in the old shared pins: {}", e)).orElse(List.of());
    }

    private static Path file() {
        return WorldId.root().resolve(worldId).resolve("markers.json");
    }

    public static void save() {
        version++;
        if (worldId == null) return;
        var root = Saved.CODEC.encodeStart(JsonOps.INSTANCE, new Saved(markers, Optional.ofNullable(Navigation.target())))
                .resultOrPartial(e -> TeyvatCartographia.LOGGER.error("Could not encode markers: {}", e)).orElse(null);
        if (root == null) return;
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("markers.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.error("Could not save markers to {}", file, e);
        }
    }
}
