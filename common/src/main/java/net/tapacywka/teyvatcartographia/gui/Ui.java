package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.render.Backdrop;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Reference-styled widgets on a common layout grid. Every size below is a multiple of the 4-unit grid step,
 * identical widgets always use the same constant, and content (icons, text) is centred on the widget box.
 */
public final class Ui {
    private Ui() {}

    /** Layout grid (GUI units). */
    public static final class Grid {
        private Grid() {}

        public static final int STEP = 4;
        /** Distance of HUD elements from the screen edges. */
        public static final int MARGIN = 8;
        /** Inner padding of panels. */
        public static final int PAD = 8;
        /** Gap between neighbouring widgets. */
        public static final int GAP = 4;
        /** Small icon buttons (pin list, locate player); the corner buttons are larger. */
        public static final int BUTTON = 20;
        /** Icon inside buttons, pills, chips, list rows. */
        public static final int ICON = 12;
        /** Larger icon (pin type grid, panel header). */
        public static final int ICON_L = 16;
        public static final int PILL_H = 20;
        public static final int HEADER_H = 28;
        public static final int ROW = 20;
        public static final int CHIP_H = 20;
        public static final int ACTION_H = 24;
        public static final int TOOL = 20;
        public static final int SLOT = 28;
        public static final int TOGGLE_W = 28, TOGGLE_H = 16;
        public static final int TILE_H = 28;
    }

    // ------------------------------------------------------------------ panel chrome

    /** Side panel chrome: body (dark or parchment), header with icon, gold title and close cross. */
    public static void panel(GuiGraphics g, float x, float y, float w, float h, boolean dark, Component title, Icon titleIcon, int mx, int my) {
        // Translucent body over the blurred backdrop (vanilla "Menu Background Blur"), if the screen captured one.
        Backdrop.draw(g, x, y, x + w, y + h);
        // The same in both styles: a panel is a plain rectangle (no frame or bevel).
        Draw.rect(g, x, y, x + w, y + h, dark ? Theme.PANEL_DARK : Theme.PANEL_LIGHT);
        if (!dark) Draw.gradientRectV(g, x, y + h * 0.55f, x + w, y + h, Theme.PANEL_LIGHT_GRADIENT_TOP, Theme.PANEL_LIGHT_GRADIENT_BOTTOM);
        Draw.rect(g, x, y, x + w, y + Grid.HEADER_H, Theme.PANEL_DARK_HEADER);
        Draw.rect(g, x, y + Grid.HEADER_H - 1, x + w, y + Grid.HEADER_H, Theme.PANEL_HEADER_LINE);
        float cy = y + Grid.HEADER_H / 2f;
        float tx = x + Grid.PAD;
        if (titleIcon != null) {
            Draw.icon(g, titleIcon, x + Grid.PAD + Grid.ICON_L / 2f, cy, Grid.ICON_L, Theme.GOLD);
            tx = x + Grid.PAD + Grid.ICON_L + Grid.GAP + 2;
        }
        float closeCx = x + w - Grid.PAD - Grid.ICON / 2f;
        int maxW = (int) (closeCx - Grid.ICON / 2f - Grid.GAP - tx);
        DrawText.text(g, DrawText.fit(title.getString(), maxW), tx, DrawText.textY(y, Grid.HEADER_H), Theme.GOLD, false);
        boolean hover = closeHit(x, y, w, mx, my);
        if (hover) Cursors.want(Cursors.Shape.HAND);
        Draw.icon(g, Icon.CLOSE, closeCx, cy, Grid.ICON, hover ? Theme.WHITE : Theme.CREAM);
    }

    public static boolean closeHit(float x, float y, float w, double mx, double my) {
        return Draw.inRect(mx, my, x + w - Grid.HEADER_H, y, Grid.HEADER_H, Grid.HEADER_H);
    }

    // ------------------------------------------------------------------ buttons

    /**
     * Full-width capsule action button ("Teleport", "Confirm"): dark icon circle in the left cap (none for a null icon), label centred in
     * the space between that circle and the right edge (so it looks centred next to the icon).
     * Height is always {@link Grid#ACTION_H}.
     */
    public static void actionButton(GuiGraphics g, float x, float y, float w, Component label, Icon icon,
                                    boolean enabled, int mx, int my) {
        float h = Grid.ACTION_H;
        boolean hover = enabled && Draw.inRect(mx, my, x, y, w, h);
        if (hover) Cursors.want(Cursors.Shape.HAND);
        else if (Draw.inRect(mx, my, x, y, w, h)) Cursors.want(Cursors.Shape.UNAVAILABLE);
        int fill = !enabled ? Theme.BUTTON_DISABLED : hover ? Theme.BUTTON_HOVER : Theme.BUTTON;
        float cx = x + h / 2f, cy = y + h / 2f, r = h / 2f - 2;
        if (Theme.Style.squareUi) {
            Draw.rect(g, x, y, x + w, y + h, fill);
            if (hover) Draw.frame(g, x, y, w, h, 1, Theme.BUTTON_HOVER_OUTLINE);
            if (icon != null) Draw.rect(g, cx - r, cy - r, cx + r, cy + r, Theme.BUTTON_ICON_BADGE);
        } else {
            Draw.capsule(g, x, y, w, h, fill);
            if (hover) Draw.capsuleOutline(g, x, y, w, h, 1f, Theme.BUTTON_HOVER_OUTLINE);
            if (icon != null) Draw.circle(g, cx, cy, r, Theme.BUTTON_ICON_BADGE);
        }
        if (icon != null) Draw.icon(g, icon, cx, cy, Grid.ICON, enabled ? Theme.YELLOW : Theme.BUTTON_ICON_DISABLED);
        // Equal gaps: from the icon badge's right edge to the text, and from the text to the button's right edge.
        // Without an icon there is no badge: centred on the face.
        float labelLeft = icon != null ? cx + r : x, labelRight = x + w;
        DrawText.room(label, labelRight - labelLeft - 2 * Grid.GAP);
        DrawText.textCentered(g, label, (labelLeft + labelRight) / 2f, DrawText.textY(y, h), enabled ? Theme.CREAM : Theme.BUTTON_TEXT_DISABLED, false);
    }

    public static boolean actionHit(float x, float y, float w, double mx, double my) {
        return Draw.inRect(mx, my, x, y, w, Grid.ACTION_H);
    }

    /**
     * Small icon button ({@link Grid#TOOL} square, e.g. "delete pin" at the end of a field), styled like the icon
     * badge of the action buttons (dark fill, yellow icon): a circle in the rounded style, a square in the rectangular one.
     */
    public static void toolButton(GuiGraphics g, float x, float y, Icon icon, int mx, int my) {
        float s = Grid.TOOL, cx = x + s / 2f, cy = y + s / 2f;
        boolean hover = Draw.inRect(mx, my, x, y, s, s);
        if (hover) Cursors.want(Cursors.Shape.HAND);
        if (Theme.Style.squareUi) {
            Draw.rect(g, x, y, x + s, y + s, Theme.BUTTON_ICON_BADGE);
            if (hover) Draw.frame(g, x, y, s, s, 1, Theme.BUTTON_HOVER_OUTLINE);
        } else {
            float r = s / 2f;
            Draw.circle(g, cx, cy, r, Theme.BUTTON_ICON_BADGE);
            if (hover) Draw.circleOutline(g, cx, cy, r - 0.5f, 1f, Theme.BUTTON_HOVER_OUTLINE);
        }
        Draw.icon(g, icon, cx, cy, Grid.ICON, Theme.YELLOW);
    }

    /** How much darker an inactive control is (a choice with one option). */
    public static final float INACTIVE = 0.4f;

    /** {@code c} darkened for an inactive control. */
    public static int inactive(int c) {
        return Theme.lerp(c, c & 0xFF000000, INACTIVE);
    }

    /** Toggle switch ({@link Grid#TOGGLE_W} x {@link Grid#TOGGLE_H}) in the style of the "Map Settings" switches. */
    public static void toggle(GuiGraphics g, float x, float y, boolean on, int mx, int my) {
        float w = Grid.TOGGLE_W, h = Grid.TOGGLE_H, r = h / 2f;
        boolean hover = Draw.inRect(mx, my, x, y, w, h);
        if (hover) Cursors.want(Cursors.Shape.HAND);
        float kx = on ? x + w - r : x + r, ky = y + r;
        int track = on ? Theme.TOGGLE_TRACK_ON : Theme.TOGGLE_TRACK_OFF, outline = hover ? Theme.TOGGLE_OUTLINE_HOVER : Theme.TOGGLE_OUTLINE;
        int knob = Theme.TOGGLE_KNOB, dot = Theme.TOGGLE_TRACK_ON, cross = Theme.TOGGLE_OFF_CROSS;
        if (Theme.Style.squareUi) {
            // As the rounded switch with square shapes: a framed track and a square knob.
            Draw.rect(g, x, y, x + w, y + h, track);
            Draw.frame(g, x, y, w, h, 1, outline);
            Draw.rect(g, kx - r + 3, ky - r + 3, kx + r - 3, ky + r - 3, knob);
        } else {
            Draw.capsule(g, x, y, w, h, track);
            Draw.capsuleOutline(g, x, y, w, h, 1f, outline);
            Draw.circle(g, kx, ky, r - 3, knob);
        }
        if (on) {
            if (Theme.Style.squareUi) Draw.rect(g, kx - 1, ky - 1, kx + 1, ky + 1, dot);
            else Draw.circle(g, kx, ky, 2f, dot);
        } else {
            Draw.icon(g, Icon.CLOSE, kx, ky, 6, cross);
        }
    }

    public static boolean toggleHit(float x, float y, double mx, double my) {
        return Draw.inRect(mx, my, x - 2, y - 2, Grid.TOGGLE_W + 4, Grid.TOGGLE_H + 4);
    }

    /** Grid tile used by the region (dimension) list: dark tile, thin border, orange bar when active. */
    public static void gridTile(GuiGraphics g, float x, float y, float w, Component label, boolean active, int mx, int my) {
        float h = Grid.TILE_H;
        boolean hover = Draw.inRect(mx, my, x, y, w, h);
        if (hover) Cursors.want(Cursors.Shape.HAND);
        // The same in both styles (the tile is square anyway).
        Draw.rect(g, x, y, x + w, y + h, hover ? Theme.PANEL_DARK_TILE_HOVER : Theme.PANEL_DARK_TILE);
        Draw.roundRectOutline(g, x, y, w, h, 0, 1, Theme.TILE_OUTLINE);
        if (active) Draw.rect(g, x, y, x + 3, y + h, Theme.ORANGE_ACCENT);
        // Accent bar (3) + small inset on both sides. The label is centred in the visible face: with the accent bar it moves
        // one unit right (measured on screenshots: centred on the whole tile it sat one unit left of the face's centre).
        String text = DrawText.fit(label.getString(), (int) w - 2 * (3 + Grid.GAP));
        DrawText.textCentered(g, Component.literal(text), x + w / 2f + (active ? 1 : 0), DrawText.textY(y, h), Theme.CREAM, false);
    }

    /** Width {@link #pill} will use for this text. */
    public static float pillWidth(Component text, boolean hasIcon) {
        float inset = Grid.PILL_H / 2f;
        float lead = hasIcon ? inset + Grid.ICON / 2f + Grid.GAP : inset;
        return lead + DrawText.width(text) + inset;
    }

    /** Dark translucent pill ({@link Grid#PILL_H} high) with an icon and text, right-aligned at {@code rightX}. Returns width. */
    public static float pill(GuiGraphics g, float rightX, float y, Component text, Icon icon, int iconColor) {
        float h = Grid.PILL_H;
        // Rounded ends: content inset = h/2 on both sides; icon sits in the left cap.
        float inset = h / 2f;
        float lead = icon != null ? inset + Grid.ICON / 2f + Grid.GAP : inset;
        float w = lead + DrawText.width(text) + inset;
        float x = rightX - w;
        pillShape(g, x, y, w, h, Theme.INFO_BOX, Theme.INFO_BOX_OUTLINE);
        if (icon != null) Draw.icon(g, icon, x + inset, y + h / 2f, Grid.ICON, iconColor);
        DrawText.text(g, text, x + lead, DrawText.textY(y, h), Theme.WHITE, false);
        return w;
    }

    /** Round button ({@link Grid#BUTTON} diameter) centred at (cx, cy). */
    public static void iconButton(GuiGraphics g, float cx, float cy, Icon icon, boolean light, int mx, int my) {
        iconButton(g, cx, cy, Grid.BUTTON, Grid.ICON, icon, light, mx, my);
    }

    /** Round button of a custom diameter (e.g. the larger compass button). */
    public static void iconButton(GuiGraphics g, float cx, float cy, float diameter, float iconSize, Icon icon, boolean light, int mx, int my) {
        float r = diameter / 2f;
        boolean hover = buttonHit(cx, cy, diameter, mx, my);
        if (hover) Cursors.want(Cursors.Shape.HAND);
        int fill = light ? (hover ? Theme.ICON_BUTTON_LIGHT_HOVER : Theme.ICON_BUTTON_LIGHT) : (hover ? Theme.ICON_BUTTON_HOVER : Theme.ICON_BUTTON);
        if (Theme.Style.squareUi) {
            Draw.rect(g, cx - r + 1, cy - r + 1, cx + r + 1, cy + r + 1, Theme.ICON_BUTTON_SHADOW);
            Draw.rect(g, cx - r, cy - r, cx + r, cy + r, fill);
            Draw.frame(g, cx - r + 2, cy - r + 2, diameter - 4, diameter - 4, 1, light ? Theme.ICON_BUTTON_LIGHT_RING : Theme.ICON_BUTTON_RING);
        } else {
            Draw.circle(g, cx, cy, r + 1, Theme.ICON_BUTTON_SHADOW);
            Draw.circle(g, cx, cy, r, fill);
            Draw.circleOutline(g, cx, cy, r - 2, 1f, light ? Theme.ICON_BUTTON_LIGHT_RING : Theme.ICON_BUTTON_RING);
        }
        Draw.icon(g, icon, cx, cy, iconSize, light ? Theme.ICON_BUTTON_LIGHT_ICON : Theme.ICON_BUTTON_ICON);
    }

    public static boolean iconButtonHit(float cx, float cy, double mx, double my) {
        return buttonHit(cx, cy, Grid.BUTTON, mx, my);
    }

    /** Hit test of a round (or, in the rectangular style, square) button of the given diameter centred at (cx, cy). */
    public static boolean buttonHit(float cx, float cy, float diameter, double mx, double my) {
        float r = diameter / 2f + 1;
        return Theme.Style.squareUi ? Draw.inRect(mx, my, cx - r, cy - r, 2 * r, 2 * r) : Draw.inCircle(mx, my, cx, cy, r);
    }

    /**
     * Input/value field on the light panel: a capsule in the rounded style (matching the round buttons and icons it
     * carries), a plain rectangle in the rectangular one.
     */
    public static void chip(GuiGraphics g, float x, float y, float w, float h, int fill) {
        if (Theme.Style.squareUi) Draw.rect(g, x, y, x + w, y + h, fill);
        else Draw.capsule(g, x, y, w, h, fill);
    }

    /** Pill shape: a capsule in the rounded style, a rectangle (with its outline, if any) in the rectangular one. */
    public static void pillShape(GuiGraphics g, float x, float y, float w, float h, int fill, int outline) {
        if (Theme.Style.squareUi) {
            Draw.rect(g, x, y, x + w, y + h, fill);
            if ((outline >>> 24) != 0) Draw.frame(g, x, y, w, h, 1, outline);
        } else {
            Draw.capsule(g, x, y, w, h, fill);
            if ((outline >>> 24) != 0) Draw.capsuleOutline(g, x, y, w, h, 1f, outline);
        }
    }

    /** Tooltip box: rounded in the rounded style; in the rectangular one a vanilla tooltip (fill with an inset outline). */
    private static void tooltipBox(GuiGraphics g, float x, float y, float w, float h) {
        if (Theme.Style.squareUi) {
            Draw.rect(g, x, y, x + w, y + h, Theme.TOOLTIP);
            Draw.frame(g, x + 1, y + 1, w - 2, h - 2, 1, Theme.TOOLTIP_OUTLINE);
        } else {
            Draw.roundRect(g, x, y, w, h, 3, Theme.TOOLTIP);
            Draw.roundRectOutline(g, x, y, w, h, 3, 1f, Theme.TOOLTIP_OUTLINE);
        }
    }

    /** Tooltip with a gold title and a word-wrapped description (max ~200 units wide), kept on screen. */
    public static void wrappedTooltip(GuiGraphics g, Component title, Component body, int mx, int my, int screenW, int screenH) {
        int maxW = Math.min(200, screenW - 2 * Grid.MARGIN - 2 * Grid.PAD);
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = DrawText.font().split(body, maxW);
        int w = DrawText.width(title);
        for (net.minecraft.util.FormattedCharSequence l : lines) w = Math.max(w, DrawText.font().width(l));
        w += 2 * Grid.PAD;
        int lineStep = DrawText.LINE + 2;
        int h = 2 * Grid.PAD + DrawText.LINE + Grid.GAP + lines.size() * lineStep - 2;
        float x = mx + 12, y = my + 12;
        if (x + w > screenW - Grid.MARGIN) x = mx - 12 - w;
        if (y + h > screenH - Grid.MARGIN) y = screenH - Grid.MARGIN - h;
        x = Math.max(Grid.MARGIN, x);
        y = Math.max(Grid.MARGIN, y);
        tooltipBox(g, x, y, w, h);
        DrawText.text(g, title, x + Grid.PAD, y + Grid.PAD, Theme.GOLD, false);
        float ty = y + Grid.PAD + DrawText.LINE + Grid.GAP;
        for (net.minecraft.util.FormattedCharSequence l : lines) {
            g.pose().pushPose();
            g.pose().translate(Draw.snap(x + Grid.PAD), Draw.snap(ty), 0);
            g.drawString(DrawText.font(), l, 0, 0, Theme.CREAM, false);
            g.pose().popPose();
            ty += lineStep;
        }
    }

    /** Tooltip box with a gold title and an optional cream subtitle, placed near the cursor and kept on screen. */
    public static void tooltip(GuiGraphics g, String title, String sub, int mx, int my, int screenW, int screenH) {
        int w = Math.max(DrawText.width(title), sub == null ? 0 : DrawText.width(sub)) + 2 * Grid.PAD;
        int h = Grid.PAD * 2 + DrawText.LINE + (sub == null ? 0 : DrawText.LINE + 2);
        DrawText.room(title, screenW - 2 * Grid.MARGIN - 2 * Grid.PAD);
        if (sub != null) DrawText.room(sub, screenW - 2 * Grid.MARGIN - 2 * Grid.PAD);
        float x = Math.min(mx + 12, screenW - w - Grid.MARGIN), y = Math.max(Grid.MARGIN, my - h - 4);
        if (x < Grid.MARGIN) x = Grid.MARGIN;
        tooltipBox(g, x, y, w, h); // near-opaque so map labels underneath don't show through
        DrawText.text(g, title, x + Grid.PAD, y + Grid.PAD, Theme.GOLD, false);
        if (sub != null) DrawText.text(g, sub, x + Grid.PAD, y + Grid.PAD + DrawText.LINE + 2, Theme.CREAM, false);
    }

    /** Length and half height of the pointer of {@link #popupLeftOf} (the size of the pick list's pointer). */
    private static final float POINTER_L = 5, POINTER_H = 4;

    /** Corner radius of {@link #popupLeftOf} in the rounded style (as the tooltips). */
    private static final float POPUP_R = 3;

    /**
     * A popup with a gold title and cream lines, left of a button: the tip of its pointer is at {@code tipX}, level
     * with {@code cy} (the button's middle); the box is centred on {@code cy} where it fits above {@code bottom}. Like
     * the side panels it is frosted glass: the vanilla menu blur ({@link Backdrop}) under the translucent panel colour,
     * box and pointer as one shape with one outline.
     */
    public static void popupLeftOf(GuiGraphics g, Component title, java.util.List<Component> lines, float tipX, float cy, float bottom) {
        popupLeftOf(g, title, lines, 0, 0, tipX, cy, bottom);
    }

    /** The box of a popup: where to put what goes under its lines. */
    public record Box(float x, float y, float w, float h) {}

    /**
     * {@link #popupLeftOf} at least {@code minW} wide inside its padding, with {@code extraH} more room under the lines
     * (for buttons); returns its box.
     */
    public static Box popupLeftOf(GuiGraphics g, Component title, java.util.List<Component> lines, int minW, int extraH, float tipX, float cy, float bottom) {
        int w = Math.max(minW, DrawText.width(title));
        for (Component l : lines) w = Math.max(w, DrawText.width(l));
        w += 2 * Grid.PAD;
        int lineStep = DrawText.LINE + 2;
        int h = 2 * Grid.PAD + DrawText.LINE + Grid.GAP + lines.size() * lineStep - 2 + extraH;
        float right = Draw.snap(tipX - POINTER_L);
        float x = right - w, y = Draw.snap(Math.min(cy - h / 2f, bottom - h));
        DrawText.room(title, right - Grid.MARGIN - 2 * Grid.PAD);
        for (Component l : lines) DrawText.room(l, right - Grid.MARGIN - 2 * Grid.PAD);
        float py = Math.max(y + POINTER_H + 3, Math.min(y + h - POINTER_H - 3, Draw.snap(cy)));
        float tip = right + POINTER_L, r = Theme.Style.squareUi ? 0 : POPUP_R;
        // Blur: the box (in the rounded style without its corners, which the rounded fill covers) and the pointer.
        if (r == 0) Backdrop.draw(g, x, y, right, y + h);
        else {
            Backdrop.draw(g, x + r, y, right - r, y + h);
            Backdrop.draw(g, x, y + r, x + r, y + h - r);
            Backdrop.draw(g, right - r, y + r, right, y + h - r);
        }
        Backdrop.drawTriangle(g, right, py - POINTER_H, right, py + POINTER_H, tip, py);
        // Fill: box and pointer side by side (they must not overlap, the colour is translucent), then one outline
        // around both; in the rectangular style one unit inside the edge, as the vanilla tooltip.
        if (r == 0) Draw.rect(g, x, y, right, y + h, Theme.PANEL_DARK);
        else Draw.roundRect(g, x, y, w, h, r, Theme.PANEL_DARK);
        Draw.triangle(g, right, py - POINTER_H, right, py + POINTER_H, tip, py, Theme.PANEL_DARK);
        Draw.calloutOutline(g, x, y, w, h, r, tip, py, POINTER_H, r == 0 ? 1 : 0, 1, Theme.TOOLTIP_OUTLINE);
        DrawText.text(g, title, x + Grid.PAD, y + Grid.PAD, Theme.GOLD, false);
        float ty = y + Grid.PAD + DrawText.LINE + Grid.GAP;
        for (Component l : lines) {
            DrawText.text(g, l, x + Grid.PAD, ty, Theme.CREAM, false);
            ty += lineStep;
        }
        return new Box(x, y, w, h);
    }

    /** A one-line tooltip whose right edge is at {@code right}, centred on {@code cy} (beside a button at the screen edge). */
    public static void tooltipLeftOf(GuiGraphics g, String title, float right, float cy) {
        int w = DrawText.width(title) + 2 * Grid.PAD, h = Grid.PAD * 2 + DrawText.LINE;
        DrawText.room(title, right - Grid.MARGIN - 2 * Grid.PAD);
        float x = Draw.snap(right - w), y = Draw.snap(cy - h / 2f);
        tooltipBox(g, x, y, w, h);
        DrawText.text(g, title, x + Grid.PAD, y + Grid.PAD, Theme.GOLD, false);
    }
}
