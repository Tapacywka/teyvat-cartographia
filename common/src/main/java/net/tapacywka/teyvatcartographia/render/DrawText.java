package net.tapacywka.teyvatcartographia.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;

/**
 * Text in GUI coordinates: always the vanilla Minecraft font at its native 1x size, snapped to the physical pixel grid
 * (fractional scales distort pixel fonts).
 */
public final class DrawText {
    private DrawText() {}

    /** Height of a line of text in GUI units. Glyphs occupy the top 8 units; centre text on a box with {@link #textY}. */
    public static final int LINE = 9;

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static int width(String s) {
        return font().width(s);
    }

    public static int width(Component c) {
        return font().width(c);
    }

    /** Y for text vertically centred in a box [top, top + height). */
    public static float textY(float top, float height) {
        return top + (height - 8f) / 2f;
    }

    public static void text(GuiGraphicsExtractor g, Component text, float x, float y, int color, boolean shadow) {
        draw(g, text.getVisualOrderText(), Draw.snap(x), Draw.snap(y), color, shadow, 0, false);
    }

    public static void text(GuiGraphicsExtractor g, String text, float x, float y, int color, boolean shadow) {
        text(g, Component.literal(text), x, y, color, shadow);
    }

    /**
     * The text's glyphs as the mod's own elements (into the frame or a {@link CachedLayer} being redrawn), at (x, y)
     * after the GUI pose; with {@code outlined} first its 8 copies in {@code outline} around it.
     */
    private static void draw(GuiGraphicsExtractor g, FormattedCharSequence seq, float x, float y, int color, boolean shadow, int outline,
                             boolean outlined) {
        Matrix3x2f pose = new Matrix3x2f(g.pose()).translate(x, y);
        if (outlined) TextShape.submit(g, font().prepareText(seq, 0, 0, opaque(outline), false, false, 0), pose, TextShape.OUTLINE);
        TextShape.submit(g, font().prepareText(seq, 0, 0, opaque(color), shadow, false, 0), pose, TextShape.ONCE);
    }

    /** A colour given without alpha is opaque, as the game's text used to take it (26.1 draws alpha 0 as nothing). */
    private static int opaque(int color) {
        return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
    }

    /**
     * Width of the visible glyphs. {@link Font#width} includes the 1-unit advance after the last character,
     * which would push centred text half a unit left and right-aligned text one unit short of the edge.
     */
    private static int visualWidth(Component text) {
        return Math.max(0, width(text) - 1);
    }

    public static void textCentered(GuiGraphicsExtractor g, Component text, float cx, float y, int color, boolean shadow) {
        text(g, text, cx - visualWidth(text) / 2f, y, color, shadow);
    }

    public static void textRight(GuiGraphicsExtractor g, Component text, float right, float y, int color, boolean shadow) {
        text(g, text, right - visualWidth(text), y, color, shadow);
    }

    /** Map-label text: light fill with a dark 8-direction outline, centred at (cx, cy). */
    public static void textOutlined(GuiGraphicsExtractor g, Component text, float cx, float cy, int color, int outline) {
        FormattedCharSequence seq = text.getVisualOrderText();
        float w = Math.max(0, font().width(seq) - 1); // visible glyph width (no trailing advance)
        draw(g, seq, Draw.snap(cx - w / 2f), Draw.snap(cy - 4f), color, false, outline, true);
    }

    /**
     * Development check of the room texts get (devtest {@code FitAuditTest}): null in play, so nothing is measured. The
     * probe learns the place from the call stack.
     */
    public static TextRoom probe;

    /** Receives a text, its width and the room it has (all in GUI units). */
    public interface TextRoom {
        void report(String text, float width, float room);
    }

    /** Reports a text drawn without shortening and the room it has, while the probe is on. */
    public static void room(Component text, float room) {
        if (probe != null) probe.report(text.getString(), width(text), room);
    }

    public static void room(String text, float room) {
        if (probe != null) probe.report(text, width(text), room);
    }

    /** The first variant that fits {@code maxWidth} as it is, else the last one shortened (longest variant first). */
    public static String fitFirst(int maxWidth, String... variants) {
        for (int i = 0; i < variants.length - 1; i++) {
            if (width(variants[i]) <= maxWidth) return fit(variants[i], maxWidth);
        }
        return fit(variants[variants.length - 1], maxWidth);
    }

    /** Shortens a string with an ellipsis to fit {@code maxWidth}. */
    public static String fit(String s, int maxWidth) {
        if (probe != null) probe.report(s, width(s), maxWidth);
        if (width(s) <= maxWidth) return s;
        String e = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (width(s.substring(0, mid) + e) <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo) + e;
    }
}
