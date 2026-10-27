package net.tapacywka.teyvatcartographia.nav;

import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/**
 * Navigation target shared by the world map, the minimap and the marker in the world: distance and level. The walking
 * path to it is found by {@link PathFinder}.
 */
public final class Navigation {
    private Navigation() {}

    /**
     * What is tracked: a custom pin ({@code markerId}) or an automatic point ({@code auto}, matched by position since
     * automatic points are not stored). Exactly one of the two is set.
     */
    public record Target(ResourceKey<Level> dimension, double x, double z, int y, UUID markerId, MarkerType auto) {
        /**
         * The saved target. Its pin or its automatic point is optional for the codec (a broken one is dropped);
         * {@code MarkerStore} drops a target with neither.
         */
        public static final Codec<Target> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Target::dimension),
                Codec.DOUBLE.fieldOf("x").forGetter(Target::x),
                Codec.DOUBLE.fieldOf("z").forGetter(Target::z),
                Codec.INT.lenientOptionalFieldOf("y", Integer.MIN_VALUE).forGetter(Target::y),
                UUIDUtil.STRING_CODEC.lenientOptionalFieldOf("marker").forGetter(t -> Optional.ofNullable(t.markerId())),
                MarkerType.AUTO_CODEC.lenientOptionalFieldOf("auto").forGetter(t -> Optional.ofNullable(t.auto()))
        ).apply(i, (dim, x, z, y, marker, auto) -> new Target(dim, x, z, y, marker.orElse(null), marker.isPresent() ? null : auto.orElse(null))));
    }

    private static Target target;
    private static final double ARRIVE_DISTANCE = 4.0;

    /** {@link MarkerStore#version()} at which the marker target was last checked (avoids a lookup per call). */
    private static int checkedVersion = Integer.MIN_VALUE;

    public static Target target() {
        // Keep marker targets in sync with renamed/deleted markers.
        if (target != null && target.markerId() != null && checkedVersion != MarkerStore.version()) {
            checkedVersion = MarkerStore.version();
            Marker m = MarkerStore.find(target.markerId());
            if (m == null) {
                target = null;
                MarkerStore.save();
            }
        }
        return target;
    }

    public static boolean isTracking(Marker m) {
        Target t = target();
        if (t == null || m == null) return false;
        if (m.automatic) {
            // Automatic points (spawn, last death) are not stored: match by type and position.
            return t.auto() == m.type && t.dimension() == m.dimension
                    && Math.abs(t.x() - m.centerX()) < 0.01 && Math.abs(t.z() - m.centerZ()) < 0.01;
        }
        return m.id.equals(t.markerId());
    }

    public static void track(Marker m) {
        target = new Target(m.dimension, m.centerX(), m.centerZ(), m.y, m.automatic ? null : m.id, m.automatic ? m.type : null);
        requestPath();
        MarkerStore.save();
    }

    /**
     * The path to the target is searched once per request, not again while the player walks: a request comes with
     * every new tracking and with the pulse key (V). {@link PathFinder} and {@link FarPath} compare this number with
     * the one they last searched for.
     */
    private static int pathRequest;

    public static int pathRequest() {
        return pathRequest;
    }

    public static void requestPath() {
        pathRequest++;
    }

    /** Who shows the path right now: the world map, the stars in the world, the minimap (set every frame). */
    public static final int PATH_MAP = 0, PATH_WORLD = 1, PATH_MINIMAP = 2;
    private static final boolean[] pathWanted = new boolean[3];

    public static void setPathWanted(int who, boolean wanted) {
        pathWanted[who] = wanted;
    }

    /** Whether anything shows the path (the searches run only then). */
    public static boolean pathWanted() {
        return pathWanted[PATH_MAP] || pathWanted[PATH_WORLD] || pathWanted[PATH_MINIMAP];
    }

    /** Whether the player sits in a boat (the path then goes by water). */
    public static boolean inBoat(LocalPlayer player) {
        return player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat;
    }

    /**
     * Whether the path goes by boat across deep water of any width: the setting says so (user decision 26.10.2026), or
     * the player sits in a boat.
     */
    public static boolean byBoat(LocalPlayer player) {
        return net.tapacywka.teyvatcartographia.config.MapConfig.PATH_ROUTE.get() == net.tapacywka.teyvatcartographia.config.MapConfig.Route.WATER
                || inBoat(player);
    }

    /** Whether the player sat in a boat on the last tick (getting in or out asks for the path again). */
    private static boolean wasInBoat;

    public static void clear() {
        target = null;
        MarkerStore.save();
    }

    /** Restored by {@link MarkerStore} on load. */
    public static void restore(Target t) {
        target = t;
    }

    /** Distance from the player to the target in the current dimension ({@link #distance(LocalPlayer, double, int, double)}), or -1. */
    public static double distance(LocalPlayer player) {
        Target t = target();
        if (t == null || player == null || t.dimension() != player.level().dimension()) return -1;
        return distance(player, t.x(), t.y(), t.z());
    }

    /**
     * Distance shown everywhere in the mod: in 3D from the player's feet to the floor of the point, or horizontal
     * when the point's height is unknown ({@link Integer#MIN_VALUE}).
     */
    public static double distance(LocalPlayer player, double x, int y, double z) {
        return distance(player.getX(), player.getY(), player.getZ(), x, y, z);
    }

    /** {@link #distance(LocalPlayer, double, int, double)} from the point (px, py, pz). */
    public static double distance(double px, double py, double pz, double x, int y, double z) {
        double dx = x - px, dz = z - pz;
        double dy = y == Integer.MIN_VALUE ? 0 : y - py;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The target counts as above or below the player when it is more than {@code LEVEL_HEIGHT} blocks higher or
     * lower, or when the direction to it is steeper than {@code LEVEL_ANGLE} degrees. A height difference of up to
     * {@code LEVEL_MIN} blocks (a step, a slab) never counts, however close the target is.
     */
    private static final double LEVEL_HEIGHT = 16, LEVEL_MIN = 3, LEVEL_SLOPE = Math.tan(Math.toRadians(30));

    /**
     * Whether the target is above (1) or below (-1) the player (see {@link #LEVEL_HEIGHT}); 0 for the same level,
     * no target, a target in another dimension or a target without a known Y.
     */
    public static int level(LocalPlayer player) {
        Target t = target();
        if (t == null || player == null || t.dimension() != player.level().dimension()) return 0;
        return level(player.getX(), player.getY(), player.getZ(), t.x(), t.y(), t.z());
    }

    /** {@link #level(LocalPlayer)} for a player at (px, py, pz) and a target at (x, y, z), y {@link Integer#MIN_VALUE} if unknown. */
    static int level(double px, double py, double pz, double x, int y, double z) {
        if (y == Integer.MIN_VALUE) return 0;
        double dy = y - py, ady = Math.abs(dy);
        if (ady <= LEVEL_MIN) return 0;
        double dx = x - px, dz = z - pz;
        boolean steep = ady > LEVEL_HEIGHT || ady > Math.sqrt(dx * dx + dz * dz) * LEVEL_SLOPE;
        return !steep ? 0 : dy > 0 ? 1 : -1;
    }

    /**
     * Whether a player {@code distance} blocks from the target (-1: another dimension) has arrived: only automatic
     * points (the spawn, the death place) end their tracking on arrival, custom pins stay tracked.
     */
    static boolean arrives(Target t, double distance) {
        return t != null && t.auto() != null && distance >= 0 && distance < ARRIVE_DISTANCE;
    }

    /** Whether the target is in the player's current dimension. */
    public static boolean inPlayerDimension(Target t, LocalPlayer player) {
        return t != null && player != null && t.dimension() == player.level().dimension();
    }

    /**
     * Ends tracking of an automatic point once reached, or when the tracked death point moved (the player died
     * somewhere else); asks for the path again when the player gets into or out of a boat. Called every client tick.
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean boat = mc.player != null && inBoat(mc.player);
        if (boat != wasInBoat) {
            wasInBoat = boat;
            if (target != null) requestPath();
        }
        if (target == null || target.auto() == null || mc.player == null) return;
        if (target.auto() == MarkerType.DEATH) {
            var death = mc.player.getLastDeathLocation();
            if (death.isEmpty() || death.get().pos().getX() + 0.5 != target.x() || death.get().pos().getZ() + 0.5 != target.z()
                    || death.get().dimension() != target.dimension()) {
                clear();
                return;
            }
        }
        if (arrives(target, distance(mc.player))) {
            mc.player.displayClientMessage(Component.translatable("teyvatcartographia.nav.arrived"), true);
            clear();
        }
    }
}
