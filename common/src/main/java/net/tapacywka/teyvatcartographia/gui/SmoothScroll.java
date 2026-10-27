package net.tapacywka.teyvatcartographia.gui;

/**
 * How far a list is scrolled, in GUI units, without steps (user request 25.10.2026: the lists stopped at row tops and
 * the wheel moved one row at a time): the wheel and the scroll bar move a target, the shown position eases to it like
 * the map's zoom. Used by the settings list, the plant list, the pin list and the pick list on the map.
 */
public final class SmoothScroll {
    /** How quickly the shown position closes in on the target (per second, exponential). */
    private static final float EASE = 14;

    private float shown, target, max;
    private long ns;

    /** The position to draw at; call once per frame with the furthest scroll. */
    public float update(float max) {
        this.max = Math.max(0, max);
        target = clamp(target);
        long now = System.nanoTime();
        float dt = ns == 0 ? 1 : Math.min(0.1f, (now - ns) / 1e9f);
        ns = now;
        shown += (target - shown) * (float) (1 - Math.exp(-dt * EASE));
        if (Math.abs(target - shown) < 0.25f) shown = target;
        shown = Math.max(0, Math.min(this.max, shown));
        return shown;
    }

    /** The position drawn last frame. */
    public float pos() {
        return shown;
    }

    /** Where the list is going. */
    public float target() {
        return target;
    }

    /** The furthest scroll as of the last frame. */
    public float max() {
        return max;
    }

    /** The wheel: {@code amount} notches (positive up, as the game gives them), {@code unit} GUI units each. */
    public void wheel(double amount, float unit) {
        target = clamp(target - (float) amount * unit);
    }

    /** Goes to {@code to}, eased (kept within the list by the next {@link #update}). */
    public void to(float to) {
        target = Math.max(0, to);
    }

    /** Goes to {@code to} at once (a new list, another tab). */
    public void jump(float to) {
        shown = target = Math.max(0, to);
    }

    /** The scroll bar's thumb at {@code t} (0 to 1) of its track. */
    public void toFraction(float t) {
        target = Math.max(0, Math.min(1, t)) * max;
    }

    /** Where the shown position is, 0 to 1 of the way (for the thumb). */
    public float fraction() {
        return max <= 0 ? 0 : shown / max;
    }

    private float clamp(float v) {
        return Math.max(0, Math.min(max, v));
    }
}
