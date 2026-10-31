package net.tapacywka.teyvatcartographia.marker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.tapacywka.teyvatcartographia.render.Icon;

/**
 * Marker types: the pins the player makes and the automatic points that come from real data:
 * <ul>
 *   <li>{@link Category#EXPLORATION} (shown as "Custom"): the seven custom pin types of Genshin Impact;</li>
 *   <li>{@link Category#INTERACTION} (shown as "Interactive"): the stones of Waystones, like the teleport waypoints and
 *       domains of Genshin Impact (automatic points since 0.8.0; until then the player's own sign, party and structure
 *       pins, removed with every such pin, user decision 31.10.2026);</li>
 *   <li>{@link Category#AUTO}: the spawn point and the last death.</li>
 * </ul>
 */
public enum MarkerType {
    DEMON(Icon.DEMON, Category.EXPLORATION),
    QUEST(Icon.QUEST, Category.EXPLORATION),
    STAR(Icon.STAR, Category.EXPLORATION),
    MEAT(Icon.MEAT, Category.EXPLORATION),
    FLOWER(Icon.FLOWER, Category.EXPLORATION),
    PICKAXE(Icon.PICKAXE, Category.EXPLORATION),
    HOOK(Icon.HOOK, Category.EXPLORATION),
    // Declaration order = order in the pin editor's grid.
    // Automatic points (not selectable for pins)
    SPAWN(Icon.SPAWN, Category.AUTO),
    DEATH(Icon.DEATH, Category.AUTO),
    // Waystones (compat/WaystonesCompat), the interactive points: a waystone, one of a dungeon, a sharestone, a warp plate
    WAYSTONE(Icon.WAYSTONE, Category.INTERACTION),
    DUNGEON(Icon.DUNGEON, Category.INTERACTION),
    SHARESTONE(Icon.SHARESTONE, Category.INTERACTION),
    WARP_PLATE(Icon.WARP_PLATE, Category.INTERACTION);

    public enum Category {
        EXPLORATION, INTERACTION, AUTO;

        /** Translation key of the section header in the pin editor. */
        public String langKey() {
            return "teyvatcartographia.pin.category." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public final Icon icon;
    public final Category category;
    /** Selectable for the player's own pins (the custom types). */
    public final boolean custom;

    MarkerType(Icon icon, Category category) {
        this.icon = icon;
        this.category = category;
        this.custom = category == Category.EXPLORATION;
    }

    /** The seven custom types, in the order of the pin editor's grid. */
    public static java.util.List<MarkerType> customTypes() {
        return java.util.Arrays.stream(values()).filter(t -> t.category == Category.EXPLORATION).toList();
    }

    /** Translation key of the type's name (named by what its icon shows), the tooltip of its tile in the pin editor. */
    public String langKey() {
        return "teyvatcartographia.pin.type." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Operator check for "Teleport here" on the world map: permission level 2+ (what /tp itself requires). The server sends the player's op level
     * to the client and updates it on /op and /deop; in singleplayer it is granted only with cheats enabled.
     */
    public static boolean isOperator() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.hasPermissions(2);
    }

    /**
     * The type of a saved pin, by name: old and unknown names map to a current type ({@link #parse}); a removed one (the
     * interactive pin types, 0.8.0) is an error, so the list it is read in leaves that pin out like any broken one.
     */
    public static final Codec<MarkerType> PIN_CODEC = Codec.STRING.comapFlatMap(s -> {
        MarkerType t = parse(s);
        return t != null ? DataResult.success(t)
                : DataResult.error(() -> "The interactive pin types are gone since 0.8.0: the " + s.toLowerCase(java.util.Locale.ROOT) + " pin is left out");
    }, MarkerType::name);

    /** The type of a tracked automatic point, by name: an error for anything but an automatic type. */
    public static final Codec<MarkerType> AUTO_CODEC = Codec.STRING.comapFlatMap(s -> {
        for (MarkerType t : values()) if (!t.custom && t.name().equals(s)) return DataResult.success(t);
        return DataResult.error(() -> "Not an automatic point: " + s);
    }, MarkerType::name);

    /**
     * Parses a saved type; types from earlier versions are mapped to the closest current one. The interactive pin types
     * (sign, party, structure; waypoint, statue, domain up to 0.1.2) give null: such pins are removed when read.
     */
    private static MarkerType parse(String s) {
        if (s == null) return STAR;
        return switch (s) {
            case "HERB" -> FLOWER;
            // The custom types up to 0.1.2.
            case "BOSS" -> DEMON;
            case "FOOD" -> MEAT;
            case "CLOVER" -> FLOWER;
            case "MINE" -> PICKAXE;
            case "FISHING" -> HOOK;
            // The interactive pin types, removed in 0.8.0 (and their names up to 0.1.2).
            case "SIGN", "PARTY", "STRUCTURE", "WAYPOINT", "STATUE", "DOMAIN" -> null;
            case "POI", "TELEPORT", "HOME", "CHEST", "NOTE", "FLAG" -> STAR;
            default -> {
                try {
                    MarkerType t = valueOf(s);
                    yield t.custom ? t : STAR;
                } catch (IllegalArgumentException e) {
                    yield STAR;
                }
            }
        };
    }
}
