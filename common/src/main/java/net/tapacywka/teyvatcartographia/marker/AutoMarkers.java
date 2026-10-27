package net.tapacywka.teyvatcartographia.marker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Points of interest derived from real data the client knows: the world spawn point (overworld) and the
 * player's last death location. Nothing is invented.
 */
public final class AutoMarkers {
    private AutoMarkers() {}

    private static final UUID SPAWN_ID = UUID.nameUUIDFromBytes("teyvatcartographia:spawn".getBytes());
    private static final UUID DEATH_ID = UUID.nameUUIDFromBytes("teyvatcartographia:death".getBytes());

    // Callers ask every frame (minimap, world map): the inputs are checked at most once per game tick, and the list
    // is built again only when they changed (spawn point, death place, dimension, level or language).
    private static List<Marker> cached = List.of();
    private static ResourceKey<Level> cachedDim;
    private static ClientLevel cachedLevel;
    private static long cachedTick = Long.MIN_VALUE;
    private static BlockPos cachedSpawn;
    private static GlobalPos cachedDeath;
    private static String cachedLanguage;

    /** Read-only list of automatic markers in the given dimension. */
    public static List<Marker> forDimension(ResourceKey<Level> dimension) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return List.of();
        long tick = level.getGameTime();
        if (level == cachedLevel && tick == cachedTick && dimension == cachedDim) return cached;
        cachedTick = tick;
        BlockPos spawn = dimension == Level.OVERWORLD && level.dimension() == Level.OVERWORLD ? level.getSharedSpawnPos() : null;
        GlobalPos death = mc.player.getLastDeathLocation().filter(d -> d.dimension() == dimension).orElse(null);
        String language = mc.getLanguageManager().getSelected();
        if (level == cachedLevel && dimension == cachedDim && Objects.equals(spawn, cachedSpawn) && Objects.equals(death, cachedDeath)
                && language.equals(cachedLanguage)) return cached;
        List<Marker> fresh = List.copyOf(build(dimension, spawn, death));
        if (!samePlaces(fresh, cached) || dimension != cachedDim) version++;
        cached = fresh;
        cachedLevel = level;
        cachedDim = dimension;
        cachedSpawn = spawn;
        cachedDeath = death;
        cachedLanguage = language;
        return cached;
    }

    /**
     * The automatic markers of every dimension (for the map search), built fresh without touching the cache of
     * {@link #forDimension}: the spawn point is known only while the player is in the overworld.
     */
    public static List<Marker> everywhere() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return List.of();
        List<Marker> out = new ArrayList<>(2);
        if (level.dimension() == Level.OVERWORLD) out.addAll(build(Level.OVERWORLD, level.getSharedSpawnPos(), null));
        mc.player.getLastDeathLocation().ifPresent(d -> out.addAll(build(d.dimension(), null, d)));
        return out;
    }

    private static int version;

    /** Changes whenever the automatic markers returned by {@link #forDimension} change (place, type or dimension). */
    public static int version() {
        return version;
    }

    private static boolean samePlaces(List<Marker> a, List<Marker> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Marker x = a.get(i), y = b.get(i);
            if (x.type != y.type || x.x != y.x || x.y != y.y || x.z != y.z) return false;
        }
        return true;
    }

    /** The markers: the spawn point (null outside the overworld) and the death place (null when not in this dimension). */
    private static List<Marker> build(ResourceKey<Level> dimension, BlockPos spawn, GlobalPos death) {
        List<Marker> out = new ArrayList<>(2);
        if (spawn != null) {
            out.add(new Marker(SPAWN_ID, Component.translatable("teyvatcartographia.marker.spawn").getString(), MarkerType.SPAWN,
                    dimension, spawn.getX(), spawn.getY(), spawn.getZ(), 0L, true));
        }
        if (death != null) {
            BlockPos p = death.pos();
            out.add(new Marker(DEATH_ID, Component.translatable("teyvatcartographia.marker.death").getString(), MarkerType.DEATH,
                    dimension, p.getX(), p.getY(), p.getZ(), 0L, true));
        }
        return out;
    }
}
