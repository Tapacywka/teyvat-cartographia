package net.tapacywka.teyvatcartographia.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Waystones (BlayTheNinth) on the maps, like the teleport waypoints and domains of Genshin Impact (user decisions
 * 31.10.2026): every waystone, sharestone and warp plate the player has activated, as Waystones sends them to the client
 * ({@link WaystonesBridge}: the list at login, then each change), and the ones seen in loaded chunks but not activated
 * yet, grey. A waystone a structure placed as a dungeon's (origin "dungeon") is a dungeon. They are automatic points: only
 * tracked from the map; teleporting stays with Waystones and its own rules.
 *
 * <p>Nothing here names a class of Waystones: the waystones in loaded chunks are found by their block entity's id and their
 * block state, and the bridge reads the events by reflection, so this mod needs no Waystones to build or run and the same
 * code works with the Waystones of every game version. The waystones seen are kept per world map in
 * {@code waystones.json}; the activated ones come from the server at every login.
 */
public final class WaystonesCompat {
    private WaystonesCompat() {}

    public static final String MOD_ID = "waystones";

    /** What a stone of Waystones is on the map. */
    public enum Kind { WAYSTONE, SHARESTONE, WARP_PLATE }

    /**
     * One stone: its place, kind, whether a structure placed it as a dungeon's, and for an activated one its id in
     * Waystones and name (null for a stone only seen).
     */
    public record Stone(ResourceKey<Level> dimension, BlockPos pos, Kind kind, boolean dungeon, UUID id, String name) {
        long key() {
            return pos.asLong();
        }
    }

    /** The stones seen in loaded chunks, per dimension, by their place (kept in the world map's waystones.json). */
    private static final Map<ResourceKey<Level>, Map<Long, Stone>> SEEN = new HashMap<>();
    /** The stones Waystones says the player has activated (or may use: sharestones, warp plates), by their id. */
    private static final Map<UUID, Stone> ACTIVE = new LinkedHashMap<>();
    /** The Waystones type of each activated stone (a list from the server replaces those of its type). */
    private static final Map<UUID, String> ACTIVE_TYPE = new HashMap<>();

    private static String worldId;
    private static Path file;
    private static boolean dirty;
    private static long savedAt;
    private static int version;

    /** Changes whenever the stones on the maps may have changed. */
    public static int version() {
        return version;
    }

    public static boolean loaded() {
        return ModCompat.waystones();
    }

    // ------------------------------------------------------------------ the rules (pure, unit tested)

    /** The kind of a block entity by its id ("waystones:waystone" and the like), or null for anything else. */
    static Kind kindOfBlockEntity(String id) {
        return switch (id) {
            case "waystones:waystone" -> Kind.WAYSTONE;
            case "waystones:sharestone" -> Kind.SHARESTONE;
            case "waystones:warp_plate" -> Kind.WARP_PLATE;
            default -> null;
        };
    }

    /** The kind of a Waystones waystone type ("waystones:waystone", "waystones:blue_sharestone" ...), or null. */
    static Kind kindOfType(String type) {
        if (type == null) return null;
        if (type.equals("waystones:waystone")) return Kind.WAYSTONE;
        if (type.equals("waystones:warp_plate")) return Kind.WARP_PLATE;
        if (type.startsWith("waystones:") && (type.endsWith("_sharestone") || type.equals("waystones:sharestone"))) return Kind.SHARESTONE;
        return null;
    }

    /**
     * What a block state says, from its text ("Block{waystones:waystone}[half=lower,origin=dungeon,waterlogged=false]"):
     * null for the upper half of a stone (the lower one stands for it), else whether a structure placed it as a dungeon's.
     */
    static Boolean dungeonOfState(String state) {
        String props = state.indexOf('[') < 0 ? "" : state.substring(state.indexOf('[') + 1, state.lastIndexOf(']'));
        boolean dungeon = false;
        for (String p : props.split(",")) {
            String[] kv = p.split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("half") && kv[1].equals("upper")) return null;
            if (kv[0].equals("origin")) dungeon = kv[1].equalsIgnoreCase("dungeon");
        }
        return dungeon;
    }

    /** The seen stones of a dimension with those of one chunk replaced by what the chunk holds now. */
    static Map<Long, Stone> replaceChunk(Map<Long, Stone> seen, int chunkX, int chunkZ, List<Stone> found) {
        Map<Long, Stone> out = new HashMap<>(seen);
        out.values().removeIf(s -> (s.pos().getX() >> 4) == chunkX && (s.pos().getZ() >> 4) == chunkZ);
        for (Stone s : found) out.put(s.key(), s);
        return out;
    }

    /** The settings that decide which stones show. */
    record Shown(boolean waystones, boolean sharestones, boolean warpPlates, boolean inactive) {
        boolean kind(Kind k) {
            return switch (k) {
                case WAYSTONE -> waystones;
                case SHARESTONE -> sharestones;
                case WARP_PLATE -> warpPlates;
            };
        }
    }

    /**
     * The points of one dimension: every activated stone there, then every stone seen but not activated (none at the
     * place of an activated one), each kind only when shown.
     */
    static List<Marker> markers(ResourceKey<Level> dimension, Map<Long, Stone> seen, Iterable<Stone> active, Shown shown,
                                java.util.function.Function<Stone, String> nameOf) {
        List<Marker> out = new ArrayList<>();
        java.util.Set<Long> activePlaces = new java.util.HashSet<>();
        for (Stone s : active) {
            if (s.dimension() != dimension) continue;
            activePlaces.add(s.key());
            if (!shown.kind(s.kind())) continue;
            out.add(marker(s, nameOf.apply(s), false));
        }
        if (shown.inactive() && seen != null) {
            for (Stone s : seen.values()) {
                if (activePlaces.contains(s.key()) || !shown.kind(s.kind())) continue;
                out.add(marker(s, nameOf.apply(s), true));
            }
        }
        // A stable order: the minimap and the map compare lists to know when to redraw.
        out.sort(java.util.Comparator.comparingLong((Marker m) -> BlockPos.asLong(m.x, m.y, m.z)));
        return out;
    }

    static MarkerType typeOf(Stone s) {
        return switch (s.kind()) {
            case WAYSTONE -> s.dungeon() ? MarkerType.DUNGEON : MarkerType.WAYSTONE;
            case SHARESTONE -> MarkerType.SHARESTONE;
            case WARP_PLATE -> MarkerType.WARP_PLATE;
        };
    }

    private static Marker marker(Stone s, String name, boolean inactive) {
        UUID id = s.id() != null ? s.id()
                : UUID.nameUUIDFromBytes(("waystones:" + s.dimension().location() + ":" + s.key()).getBytes(StandardCharsets.UTF_8));
        Marker m = new Marker(id, name, typeOf(s), s.dimension(), s.pos().getX(), s.pos().getY(), s.pos().getZ(), 0L, true);
        m.inactive = inactive;
        return m;
    }

    // ------------------------------------------------------------------ the game

    /** The points of the stones in a dimension, as the settings show them (empty without Waystones). */
    public static List<Marker> forDimension(ResourceKey<Level> dimension) {
        if (!loaded() || (SEEN.isEmpty() && ACTIVE.isEmpty())) return List.of();
        return markers(dimension, SEEN.get(dimension), ACTIVE.values(), shown(), WaystonesCompat::nameOf);
    }

    /** The points of the stones of every known dimension (for the map search). */
    public static List<Marker> everywhere() {
        if (!loaded()) return List.of();
        java.util.Set<ResourceKey<Level>> dims = new java.util.LinkedHashSet<>(SEEN.keySet());
        for (Stone s : ACTIVE.values()) dims.add(s.dimension());
        List<Marker> out = new ArrayList<>();
        for (ResourceKey<Level> d : dims) out.addAll(forDimension(d));
        return out;
    }

    private static Shown shown() {
        return new Shown(MapConfig.get(MapConfig.WAYSTONES_SHOW), MapConfig.get(MapConfig.WAYSTONES_SHARESTONES),
                MapConfig.get(MapConfig.WAYSTONES_WARP_PLATES), MapConfig.get(MapConfig.WAYSTONES_INACTIVE));
    }

    /** An activated stone's own name; else the name of its kind ("Waystone", "Dungeon" ...). */
    private static String nameOf(Stone s) {
        if (s.name() != null && !s.name().isBlank()) return s.name();
        return Component.translatable("teyvatcartographia.waystones." + typeOf(s).name().toLowerCase(Locale.ROOT)).getString();
    }

    /** A chunk came in: its stones replace those seen there before (a stone broken since is gone). */
    public static void chunkLoaded(ResourceKey<Level> dimension, LevelChunk chunk) {
        if (!loaded()) return;
        List<Stone> found = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            Kind kind = kindOfBlockEntity(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
            if (kind == null) continue;
            Boolean dungeon = dungeonOfState(be.getBlockState().toString());
            if (dungeon == null) continue;
            found.add(new Stone(dimension, be.getBlockPos().immutable(), kind, dungeon, null, null));
        }
        Map<Long, Stone> before = SEEN.getOrDefault(dimension, Map.of());
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        boolean hadAny = before.values().stream().anyMatch(s -> (s.pos().getX() >> 4) == cx && (s.pos().getZ() >> 4) == cz);
        if (found.isEmpty() && !hadAny) return;
        Map<Long, Stone> after = replaceChunk(before, cx, cz, found);
        if (after.equals(before)) return;
        SEEN.put(dimension, after);
        dirty = true;
        version++;
    }

    /** A list of stones of one Waystones type from the server: it replaces every activated stone of that type. */
    static void listReceived(String type, List<Stone> stones) {
        List<UUID> old = new ArrayList<>();
        for (Map.Entry<UUID, String> e : ACTIVE_TYPE.entrySet()) if (Objects.equals(e.getValue(), type)) old.add(e.getKey());
        for (UUID id : old) {
            ACTIVE.remove(id);
            ACTIVE_TYPE.remove(id);
        }
        for (Stone s : stones) {
            ACTIVE.put(s.id(), s);
            ACTIVE_TYPE.put(s.id(), type);
        }
        version++;
    }

    /** One stone changed (renamed, moved, activated): null drops it (a type this map does not show). */
    static void updateReceived(UUID id, String type, Stone stone) {
        if (stone == null) {
            removeReceived(id);
            return;
        }
        ACTIVE.put(id, stone);
        ACTIVE_TYPE.put(id, type);
        version++;
    }

    /**
     * A stone is gone (broken, or picked up with Silk Touch): also as a stone seen at its place, which would otherwise
     * stay there grey until its chunk is loaded again.
     */
    static void removeReceived(UUID id) {
        Stone gone = ACTIVE.remove(id);
        ACTIVE_TYPE.remove(id);
        if (gone == null) return;
        Map<Long, Stone> seen = SEEN.get(gone.dimension());
        if (seen != null && seen.remove(gone.key()) != null) dirty = true;
        version++;
    }

    /** For the unit tests: forget everything, see a stone, and the points of a dimension with every kind shown. */
    static void clearForTest() {
        SEEN.clear();
        ACTIVE.clear();
        ACTIVE_TYPE.clear();
    }

    static void seenForTest(Stone s) {
        SEEN.computeIfAbsent(s.dimension(), d -> new HashMap<>()).put(s.key(), s);
    }

    static List<Marker> markersForTest(ResourceKey<Level> dimension) {
        return markers(dimension, SEEN.get(dimension), ACTIVE.values(), new Shown(true, true, true, true), s -> "stone");
    }

    /** Every client tick: the world's file read on a new world, written now and then while stones were seen. */
    public static void tick(String world, Path worldDir) {
        if (!loaded()) return;
        // The events are listened to from the first tick on: by then Waystones and its library Balm are set up.
        WaystonesBridge.register();
        if (!Objects.equals(world, worldId)) {
            save();
            SEEN.clear();
            worldId = world;
            file = world == null || worldDir == null ? null : worldDir.resolve("waystones.json");
            load();
            version++;
        }
        long now = System.currentTimeMillis();
        if (dirty && now - savedAt > 10_000) save();
    }

    /** Leaving the world: the seen stones written, the activated ones forgotten (the server sends them again). */
    public static void leave() {
        if (!loaded()) return;
        save();
        ACTIVE.clear();
        ACTIVE_TYPE.clear();
        version++;
    }

    private static void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("seen");
            for (var e : arr) {
                JsonObject o = e.getAsJsonObject();
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(o.get("dimension").getAsString()));
                Kind kind = Kind.valueOf(o.get("kind").getAsString());
                BlockPos pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
                Stone s = new Stone(dim, pos, kind, o.has("dungeon") && o.get("dungeon").getAsBoolean(), null, null);
                SEEN.computeIfAbsent(dim, d -> new HashMap<>()).put(s.key(), s);
            }
        } catch (IOException | RuntimeException ex) {
            TeyvatCartographia.LOGGER.warn("Could not read the waystones seen from {}", file, ex);
        }
    }

    private static void save() {
        if (!dirty || file == null) return;
        dirty = false;
        savedAt = System.currentTimeMillis();
        JsonArray arr = new JsonArray();
        for (Map<Long, Stone> m : SEEN.values()) {
            for (Stone s : m.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("dimension", s.dimension().location().toString());
                o.addProperty("kind", s.kind().name());
                o.addProperty("x", s.pos().getX());
                o.addProperty("y", s.pos().getY());
                o.addProperty("z", s.pos().getZ());
                if (s.dungeon()) o.addProperty("dungeon", true);
                arr.add(o);
            }
        }
        JsonObject root = new JsonObject();
        root.add("seen", arr);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            TeyvatCartographia.LOGGER.warn("Could not write the waystones seen to {}", file, ex);
        }
    }
}
