package net.tapacywka.teyvatcartographia.gui.panel;

import net.tapacywka.teyvatcartographia.gui.Slide;
import net.tapacywka.teyvatcartographia.gui.Ui;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.gui.WorldMapScreen;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Right-edge panel of the world map (references: settings, regions, waypoint and custom pin panels).
 * Content area: x = left + PAD, y = HEADER_H + PAD, width = w - 2 * PAD (all on the layout grid).
 */
public abstract class SidePanel {
    protected final WorldMapScreen screen;
    protected float x, y, w, h;
    /** Slides in when opened and out when closed ({@link #slideOut}): the screen keeps drawing it until it is gone. */
    private final Slide slide = new Slide();
    /** Seconds since the last frame, for what a panel moves inside itself. */
    protected float frameDt;

    protected SidePanel(WorldMapScreen screen) {
        this.screen = screen;
        slide.show();
    }

    public void layout(float screenW, float screenH) {
        // ~30% of the width, kept on the 4-unit grid and between 176 and 216 units (narrower, Russian labels and
        // the half-width buttons do not fit: measured by the text room audit).
        w = Math.max(176, Math.min(216, Math.round(screenW * 0.3f / Grid.STEP) * Grid.STEP));
        h = screenH;
        y = 0;
        // Start off-screen to the right; render() slides it in. Avoids covering the map before the first frame.
        x = screenW - w * slide.amount();
    }

    public float width() {
        return w;
    }

    public float left() {
        return x;
    }

    public void slideIn() {
        slide.show();
    }

    public void slideOut() {
        slide.hide();
    }

    public boolean slidingOut() {
        return !slide.in();
    }

    /** Out of sight: the screen can forget it. */
    public boolean gone() {
        return slide.gone();
    }

    /** In place, covering whatever is under it. */
    public boolean inPlace() {
        return slide.in() && slide.settled();
    }

    /** How far in, 0 to 1 (the map behind dims with it). */
    public float shown() {
        return slide.amount();
    }

    /** Whether {@code other} shows the same thing: opened again while this one slides out, this one turns back instead. */
    public boolean sameContent(SidePanel other) {
        return getClass() == other.getClass();
    }

    protected float contentX() {
        return x + Grid.PAD;
    }

    protected float contentY() {
        return y + Grid.HEADER_H + Grid.PAD;
    }

    protected float contentW() {
        return w - 2 * Grid.PAD;
    }

    /** Top of the bottom-most action button. */
    protected float bottomActionY() {
        return y + h - Grid.PAD - Grid.ACTION_H;
    }

    protected abstract boolean dark();

    protected abstract Component title();

    protected Icon titleIcon() {
        return null;
    }

    /**
     * True if a click on the map outside this panel should only close the panel (settings, pin list,
     * dimensions); false if the click should also act on the map (pin info / pin editor).
     */
    public boolean consumesOutsideClick() {
        return dark();
    }

    public final void render(GuiGraphics g, int mx, int my, float dt, float screenW) {
        frameDt = dt;
        slide.update(dt);
        x = screenW - w * slide.amount();
        Ui.panel(g, x, y, w, h, dark(), title(), titleIcon(), mx, my);
        renderBody(g, mx, my, contentX(), contentY(), contentW());
    }

    protected abstract void renderBody(GuiGraphics g, int mx, int my, float bx, float by, float bw);

    public boolean contains(double mx, double my) {
        return mx >= x;
    }

    /** Returns true if consumed. Default handles the close cross. */
    public boolean mouseClicked(double mx, double my, int button) {
        return closeClicked(mx, my) || contains(mx, my);
    }

    /** Handles the header close cross. */
    protected boolean closeClicked(double mx, double my) {
        if (Ui.closeHit(x, y, w, mx, my)) {
            screen.closePanel();
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        return contains(mx, my);
    }

    public boolean keyPressed(int key, int scan, int mods) {
        return false;
    }

    public boolean charTyped(char c, int mods) {
        return false;
    }

    /** True while a text field has focus (map hotkeys are suspended). */
    public boolean capturesKeyboard() {
        return false;
    }

    public void onClose() {}
}
