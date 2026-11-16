package net.tapacywka.teyvatcartographia.radar;

/**
 * The rules of the radar, apart from the world and the screen so they
 * can be tested: which kinds of entities a player may see where, how far above or below one counts as another height,
 * and the signals of servers that forbid a radar.
 */
public final class RadarRules {
    private RadarRules() {}

    /** The kinds of entities on the radar, each with a setting of its own. */
    public enum Kind { PLAYERS, HOSTILE, PASSIVE, VILLAGERS, PETS, OTHER }

    /** How players show: arrows in their team's colour, or their skins' heads in a frame of it. */
    public enum PlayerLook { ARROWS, HEADS }

    /** How mobs show: dots in the colour of their kind, or their faces in a frame of it. */
    public enum MobLook { DOTS, HEADS }

    /**
     * Where a kind shows: nowhere, on the minimap, on both maps, on the world map alone (last,
     * so the names of older files keep their meaning).
     */
    public enum Show {
        OFF, MINIMAP, BOTH, MAP;

        public boolean on(boolean minimap) {
            return this == BOTH || (minimap ? this == MINIMAP : this == MAP);
        }

        /** The value with the world map's and the minimap's boxes as given. */
        public static Show of(boolean map, boolean minimap) {
            return map ? (minimap ? BOTH : MAP) : (minimap ? MINIMAP : OFF);
        }
    }

    /** Blocks above or below the player from which an entity counts as on another height (fainter, an arrow). */
    public static final double HEIGHT = 6;

    /**
     * Xaero's Minimap's codes a server sends in its chat (read by that mod, kept by many servers): fair play (no radar,
     * no caves), no minimap at all, and back to normal.
     */
    public static final String FAIR_PLAY = "§f§a§i§r§x§a§e§r§o";
    public static final String NO_MINIMAP = "§n§o§m§i§n§i§m§a§p";
    public static final String RESET = "§r§e§s§e§t§x§a§e§r§o";

    /**
     * Whether a kind may show at all (fair play): in single player everything; on a server
     * the other players, and the mobs and the rest only when the player allowed it; nothing while the server forbids a
     * radar.
     */
    public static boolean allowed(Kind kind, boolean singlePlayer, boolean serverMobs, boolean forbidden) {
        if (forbidden) return false;
        return singlePlayer || kind == Kind.PLAYERS || serverMobs;
    }

    /** -1 well below the player, 1 well above, 0 about the same height. */
    public static int height(double dy) {
        return dy > HEIGHT ? 1 : dy < -HEIGHT ? -1 : 0;
    }

    /** Cave layers above or below the one a map shows whose entities it shows too. */
    public static final int LAYERS_AROUND = 2;

    /**
     * Whether an entity shows on a map that shows cave layer {@code viewed} (null: the surface), by the layers of the
     * cave map: without cave layers on that map only those on the surface; on the surface
     * also those underground up to {@link #LAYERS_AROUND} layers under the ground at their place, in a layer mapped
     * already; on a cave layer those up to that many layers above or below it (underground ones in a mapped layer).
     * {@code band} is the entity's layer, {@code surfaceBand} that of the ground above it.
     */
    public static boolean onLayer(boolean caves, Integer viewed, boolean underground, int band, int surfaceBand,
                                  java.util.function.IntPredicate mapped) {
        if (!caves) return !underground;
        if (viewed == null) return !underground || surfaceBand - band <= LAYERS_AROUND && mapped.test(band);
        return Math.abs(band - viewed) <= LAYERS_AROUND && (!underground || mapped.test(band));
    }

    /** Whether the radar is forbidden after a chat message, given whether it was before. */
    public static boolean forbiddenAfter(String message, boolean before) {
        if (message.contains(RESET)) return false;
        return before || message.contains(FAIR_PLAY) || message.contains(NO_MINIMAP);
    }

    /** Whether a point {@code dx, dz} from the centre lies within {@code range} blocks (a circle, as the game counts). */
    public static boolean inRange(double dx, double dz, int range) {
        return dx * dx + dz * dz <= (double) range * range;
    }
}
