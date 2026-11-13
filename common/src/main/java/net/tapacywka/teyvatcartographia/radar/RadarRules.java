package net.tapacywka.teyvatcartographia.radar;

/**
 * The rules of the radar (user decisions 12.11.2026, docs/RADAR_SPEC.md), apart from the world and the screen so they
 * can be tested: which kinds of entities a player may see where, how far above or below one counts as another height,
 * and the signals of servers that forbid a radar.
 */
public final class RadarRules {
    private RadarRules() {}

    /** The kinds of entities on the radar, each with a setting of its own. */
    public enum Kind { PLAYERS, HOSTILE, PASSIVE, VILLAGERS, PETS, OTHER }

    /** How players show (user decision 13.11.2026): arrows in their team's colour, or their skins' heads in a frame of it. */
    public enum PlayerLook { ARROWS, HEADS }

    /** How mobs show (user decision 13.11.2026): dots in the colour of their kind, or their faces in a frame of it. */
    public enum MobLook { DOTS, HEADS }

    /** Where a kind shows: nowhere, on the minimap, on both maps. */
    public enum Show {
        OFF, MINIMAP, BOTH;

        public boolean on(boolean minimap) {
            return this == BOTH || this == MINIMAP && minimap;
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
     * Whether a kind may show at all (fair play, user decision 12.11.2026): in single player everything; on a server
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

    /** Cave layers above or below the one a map shows whose entities it shows too (user decision 13.11.2026). */
    public static final int LAYERS_AROUND = 2;

    /**
     * Whether an entity shows on a map that shows cave layer {@code viewed} (null: the surface), by the layers of the
     * cave map (user decisions 13.11.2026): without cave layers on that map only those on the surface; on the surface
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

    /** Blocks seen from above under which a sub-level of Sable without a name is left out (falling trees, debris). */
    public static final int SHIP_MIN_COLUMNS = 24;
    /** The silhouette's longest side on screen (GUI units) under which a sub-level shows as its icon instead. */
    public static final float SHIP_SILHOUETTE_FROM = 8;

    /** Whether a sub-level of Sable shows at all: one with a name always, one without only when it is large enough. */
    public static boolean shipShown(boolean named, int columns) {
        return named || columns >= SHIP_MIN_COLUMNS;
    }

    /**
     * Whether a sub-level is drawn as its silhouette (variant C) or as its icon (A, user decision 12.11.2026): the icon
     * while no silhouette is read yet ({@code columns} 0) or while it would be too small to tell on the map.
     */
    public static boolean shipSilhouette(int columns, float longestSideOnScreen) {
        return columns > 0 && longestSideOnScreen >= SHIP_SILHOUETTE_FROM;
    }

    /** How much darker a column of a silhouette gets per block below the sub-level's top, and at most. */
    public static final float SHADE_STEP = 0.05f, SHADE_MAX = 0.4f;

    /** A column's colour {@code below} blocks under the top of its sub-level: a little darker, as the map shades relief. */
    public static int shade(int argb, int below) {
        float f = 1 - Math.min(SHADE_MAX, Math.max(0, below) * SHADE_STEP);
        int r = Math.round(((argb >> 16) & 0xFF) * f), g = Math.round(((argb >> 8) & 0xFF) * f), b = Math.round((argb & 0xFF) * f);
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Whether a point {@code dx, dz} from the centre lies within {@code range} blocks (a circle, as the game counts). */
    public static boolean inRange(double dx, double dz, int range) {
        return dx * dx + dz * dz <= (double) range * range;
    }
}
