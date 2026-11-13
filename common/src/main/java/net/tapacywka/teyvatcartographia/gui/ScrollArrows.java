package net.tapacywka.teyvatcartographia.gui;

/**
 * The two arrows at the ends of a scroll bar (user decision 13.11.2026, variant C of the mockup, after Genshin's
 * lists, as in Actually Amazing Config Screen): small triangles above and below the track, which loses their room. A click scrolls one row; held, the list
 * goes on scrolling smoothly after a short pause. Where the list cannot go on, the arrow is dim and takes no click.
 * Pure: the drawing is {@link Ui#scrollArrows}; the time comes from the caller.
 */
public final class ScrollArrows {
    /** The room each arrow takes from either end of the track, GUI units. */
    public static final float ROOM = 6;
    /** Half the triangle's width and its height. */
    public static final float HALF_W = 3, TRI_H = 3;
    /** Held this long, the arrow starts scrolling on its own. */
    static final long DELAY_NS = 300_000_000L;
    /** Rows a second while held. */
    static final float HOLD_ROWS = 12;

    /** The arrow held down: -1 up, 1 down, 0 none. */
    private int held;
    private long since, last;

    /**
     * The arrow under the pointer for a bar whose middle is {@code cx} and whose arrows sit in {@code top} to
     * {@code bottom} (the track between them): -1 the upper, 1 the lower, 0 neither.
     */
    public static int at(double mx, double my, float cx, float top, float bottom) {
        if (Math.abs(mx - cx) > HALF_W + 1 || bottom - top < 2 * ROOM) return 0;
        if (my >= top && my < top + ROOM) return -1;
        if (my >= bottom - ROOM && my < bottom) return 1;
        return 0;
    }

    /** The list can still go that way (where it is going, so an arrow dims as soon as a click reaches the end). */
    public static boolean can(int dir, SmoothScroll s) {
        if (s.max() <= 0 || dir == 0) return false;
        return dir < 0 ? s.target() > 0.01f : s.target() < s.max() - 0.01f;
    }

    /** The middle of the upper ({@code dir} -1) or lower triangle, given the arrows' {@code top} and {@code bottom}. */
    public static float middle(int dir, float top, float bottom) {
        return dir < 0 ? top + ROOM / 2f - 0.5f : bottom - ROOM / 2f + 0.5f;
    }

    /** A press on arrow {@code dir}: one row of {@code unit} that way, and holding starts. False when it did nothing. */
    public boolean press(int dir, SmoothScroll s, float unit, long now) {
        if (!can(dir, s)) return false;
        s.wheel(-dir, unit);
        held = dir;
        since = last = now;
        return true;
    }

    /** Once a frame: a held arrow keeps the list going after {@link #DELAY_NS}. */
    public void update(SmoothScroll s, float unit, long now) {
        if (held == 0) return;
        if (now - since > DELAY_NS) s.wheel(-held * HOLD_ROWS * Math.min(0.1f, (now - last) / 1e9f), unit);
        last = now;
    }

    public void release() {
        held = 0;
    }

    /** The arrow held down: -1 up, 1 down, 0 none. */
    public int held() {
        return held;
    }
}
