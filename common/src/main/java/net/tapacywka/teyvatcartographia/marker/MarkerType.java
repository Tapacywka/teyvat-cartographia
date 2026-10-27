package net.tapacywka.teyvatcartographia.marker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.tapacywka.teyvatcartographia.render.Icon;

/**
 * Marker types in two pin categories plus automatic points that come from real world data (spawn, last death):
 * <ul>
 *   <li>{@link Category#EXPLORATION} (shown as "Custom"): the seven custom pin types of Genshin Impact, for every player;</li>
 *   <li>{@link Category#INTERACTION} (shown as "Interactive"): sign, party and structure (waypoint, statue and domain up
 *       to 0.1.2), for every player too since 24.10.2026 (operators only before).</li>
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
    SIGN(Icon.SIGN, Category.INTERACTION),
    PARTY(Icon.PARTY, Category.INTERACTION),
    STRUCTURE(Icon.STRUCTURE, Category.INTERACTION),
    // Automatic points (not selectable for custom pins)
    SPAWN(Icon.SPAWN, Category.AUTO),
    DEATH(Icon.DEATH, Category.AUTO);

    public enum Category {
        EXPLORATION, INTERACTION, AUTO;

        /** Translation key of the section header in the pin editor. */
        public String langKey() {
            return "teyvatcartographia.pin.category." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public final Icon icon;
    public final Category category;
    /** Selectable for pins (custom and system). */
    public final boolean custom;

    MarkerType(Icon icon, Category category) {
        this.icon = icon;
        this.category = category;
        this.custom = category != Category.AUTO;
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

    /** The type of a saved pin, by name: never fails ({@link #parse} maps old and unknown names). */
    public static final Codec<MarkerType> PIN_CODEC = Codec.STRING.xmap(MarkerType::parse, MarkerType::name);

    /** The type of a tracked automatic point, by name: an error for anything but an automatic type. */
    public static final Codec<MarkerType> AUTO_CODEC = Codec.STRING.comapFlatMap(s -> {
        for (MarkerType t : values()) if (!t.custom && t.name().equals(s)) return DataResult.success(t);
        return DataResult.error(() -> "Not an automatic point: " + s);
    }, MarkerType::name);

    /** Parses a saved type; types from earlier versions are mapped to the closest current one. */
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
            // The interactive types up to 0.1.2.
            case "WAYPOINT" -> SIGN;
            case "STATUE" -> PARTY;
            case "DOMAIN" -> STRUCTURE;
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
