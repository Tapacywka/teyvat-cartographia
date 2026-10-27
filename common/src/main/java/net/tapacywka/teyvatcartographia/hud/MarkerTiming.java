package net.tapacywka.teyvatcartographia.hud;

import net.tapacywka.teyvatcartographia.render.MarkerRenderer;

/**
 * How long the tracked pin's marker shows in the world ({@link WorldMarkerLayer}): {@code SHOW_MS} after tracking
 * starts or the pulse key is pressed, fading in over {@code FADE_IN_MS} and out over {@code FADE_OUT_MS}; and while a
 * pulse (the diamond) runs, a new press of the key is ignored.
 */
final class MarkerTiming {
    private MarkerTiming() {}

    static final long SHOW_MS = 60_000, FADE_IN_MS = 400, FADE_OUT_MS = 1_500;

    /** Visibility 0..1 of the marker {@code elapsedMs} after it was shown. */
    static float visibility(long elapsedMs) {
        if (elapsedMs < FADE_IN_MS) return smooth(elapsedMs / (double) FADE_IN_MS);
        if (elapsedMs < SHOW_MS) return 1f;
        return smooth(1 - (elapsedMs - SHOW_MS) / (double) FADE_OUT_MS);
    }

    /** Whether the pulse started at {@code pulseStart} ({@code Long.MIN_VALUE}: none yet) still runs at {@code now}. */
    static boolean pulseRunning(long pulseStart, long now) {
        return pulseStart != Long.MIN_VALUE && now - pulseStart < MarkerRenderer.WORLD_PULSE_LIFE * 1000;
    }

    /**
     * Seconds the beam takes to fade in when the marker comes onto the screen from the limiter ellipse, and to fade out
     * when it goes onto the ellipse (user request 25.10.2026), after dimming on the way there ({@link #approach}).
     */
    static final float EDGE_FADE_SECONDS = 1f;

    /**
     * The beam's fade (0..1, before smoothing) {@code dt} seconds on: towards 1 while the marker is on the screen
     * ({@code inView}), towards 0 while it rides the ellipse, at {@link #EDGE_FADE_SECONDS} for the whole way.
     */
    static float edgeStep(float fade, boolean inView, float dt) {
        float f = fade + (inView ? 1 : -1) * dt / EDGE_FADE_SECONDS;
        return Math.max(0f, Math.min(1f, f));
    }

    /**
     * Where on the way to the limiter ellipse the beam starts to dim (as a share of the ellipse's radius), and how bright
     * it is on the ellipse, before the timed fade there ({@link #edgeStep}) takes it the rest of the way (user choice
     * 25.10.2026: both together).
     */
    static final float APPROACH_FROM = 0.8f, APPROACH_LOW = 0.5f;

    /**
     * The beam's brightness 1..{@link #APPROACH_LOW} for the marker at {@code ellipse} (the sum of the squared offsets
     * over the half-axes: 1 on the ellipse, more beyond it; {@code NaN}: behind the camera, as on the ellipse).
     */
    static float approach(float ellipse) {
        if (!(ellipse < 1f)) return APPROACH_LOW;
        float r = (float) Math.sqrt(ellipse);
        return 1f - (1f - APPROACH_LOW) * smooth((r - APPROACH_FROM) / (1f - APPROACH_FROM));
    }

    static float smooth(double t) {
        float x = (float) Math.max(0, Math.min(1, t));
        return x * x * (3 - 2 * x);
    }
}
