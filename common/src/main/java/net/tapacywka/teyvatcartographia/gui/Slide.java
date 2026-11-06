package net.tapacywka.teyvatcartographia.gui;

/**
 * How far one part of the world map has slid in, from 0 (out of sight) to 1 (in place): side panels, button groups,
 * menus, the map itself (user request 06.11.2026: everything on the map slides out as it slid in, in 0.15 s).
 * <p>
 * The position is a function of one number that only moves towards the end asked for, the same way in both directions:
 * turning back half way changes nothing but the direction, so nothing jumps. Going out runs the way in backwards.
 * Nothing here draws or reads the input; the owner calls {@link #update} once per frame.
 */
public final class Slide {
    /** The time of a whole slide, in or out. */
    public static final float SECONDS = 0.15f;
    /** Rows that follow each other: at most this part of a slide between two rows, and this part for all together. */
    private static final float ROW_GAP = 0.12f, ROWS_SPREAD = 0.4f;
    /** Devtest only: below 1 every slide runs slower, so a shot can catch it half way. */
    public static float pace = 1;

    private float t;
    private boolean in;

    /** Heads in. */
    public void show() {
        in = true;
    }

    /** Heads out. */
    public void hide() {
        in = false;
    }

    /** Heads in or out. */
    public void set(boolean in) {
        this.in = in;
    }

    /** One frame of {@code dt} seconds. */
    public void update(float dt) {
        float step = dt * pace / SECONDS;
        t = in ? Math.min(1, t + step) : Math.max(0, t - step);
    }

    /** Goes on from where {@code other} is (a list opened again over the one still sliding out). */
    public void takeFrom(Slide other) {
        t = other.t;
    }

    /** Whether it is heading in (or is in). */
    public boolean in() {
        return in;
    }

    /** Whether any of it is on the screen. */
    public boolean visible() {
        return t > 0;
    }

    /** Out and finished: its owner may go. */
    public boolean gone() {
        return !in && t <= 0;
    }

    /** At the end it is heading for. */
    public boolean settled() {
        return t == (in ? 1 : 0);
    }

    /** How far in, 0 to 1, eased: quick at the far end, slowing into place. */
    public float amount() {
        return ease(t);
    }

    /** How far from its place, for a part that travels {@code distance} units. */
    public float offset(float distance) {
        return distance * (1 - amount());
    }

    /**
     * {@link #amount} of row {@code row} of {@code rows} that follow each other: the first leads on the way in, the
     * last on the way out. All rows are in place at 1 and out at 0.
     */
    public float staggered(int row, int rows) {
        if (rows <= 1) return amount();
        float gap = Math.min(ROW_GAP, ROWS_SPREAD / (rows - 1)), span = 1 - gap * (rows - 1);
        return ease(Math.max(0, Math.min(1, (t - row * gap) / span)));
    }

    private static float ease(float t) {
        float rest = 1 - t;
        return 1 - rest * rest * rest;
    }
}
