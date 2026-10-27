package net.tapacywka.teyvatcartographia.render;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Distances as shown everywhere in the mod (world marker, minimap, world map, pin list): in blocks, with the
 * plural form of the selected language ("1 block", "21 блок", "3 блока", "12 блоков").
 */
public final class DistanceText {
    private DistanceText() {}

    private static final String KEY = "teyvatcartographia.distance.blocks.";

    /** "N blocks" in the selected language. Callers cache the result while the number stays the same. */
    public static Component blocks(long n) {
        return Component.translatable(KEY + pluralForm(n, language()), n);
    }

    public static String language() {
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

    /** Plural form of a count: "one", "few" or "many" (East Slavic rules for ru / uk / be, else one / many). */
    public static String pluralForm(long n, String lang) {
        n = Math.abs(n);
        if (lang.startsWith("ru") || lang.startsWith("uk") || lang.startsWith("be")) {
            long d = n % 10, h = n % 100;
            if (d == 1 && h != 11) return "one";
            if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return "few";
            return "many";
        }
        return n == 1 ? "one" : "many";
    }
}
