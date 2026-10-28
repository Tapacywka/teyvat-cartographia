package net.tapacywka.teyvatcartographia.hud;

import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import net.tapacywka.teyvatcartographia.marker.MarkerVisibility;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.nav.Route;
import net.tapacywka.teyvatcartographia.render.DistanceText;
import net.tapacywka.teyvatcartographia.render.CachedLayer;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.MarkerRenderer;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.tapacywka.teyvatcartographia.render.WorldGlow;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.util.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * The tracked target as a marker in the 3D world (references: ingame_waypoint_*), drawn on the HUD at the target's
 * projected position, so it keeps a constant size and shows through terrain:
 * <ul>
 *   <li>in view: the glyph with the distance in blocks under it; at any distance a golden beam of light rises from
 *       the target in the world ({@link #renderBeam});</li>
 *   <li>outside the limiter ellipse around the screen centre (also behind the camera): held on that ellipse, like
 *       in Genshin Impact, going around the minimap, with a pointer towards the target and no distance;</li>
 *   <li>closer than 10 blocks it fades out, gone at the target; at any distance an arrow shows a target above or
 *       below the player ({@link Navigation#level});</li>
 *   <li>the pulse key makes a diamond, white turning gold, close in on it ({@link #pulse}); the marker never pulses
 *       on its own.</li>
 *   <li>it is shown for a minute after tracking starts or the pulse key is pressed, then fades out; the pulse key
 *       fades it in again for another minute.</li>
 * </ul>
 * The camera matrices come from the end of the level rendering of the same frame ({@link #afterLevel}). The HUD part
 * allocates nothing per frame; the beam is one small vertex batch of 16 quads.
 */
public final class WorldMarkerLayer {
    /** The above / below arrow, {@code LEVEL_GAP} GUI units past the glyph's edge, at any distance. */
    private static final float LEVEL_GAP = 2;
    /**
     * Glyph box on screen (GUI units), the same at every distance: at GUI scale 3 the glyph is about 30 pixels, like
     * the waypoint diamond on ingame_waypoint_* (about 28 pixels in 1920 x 1080).
     */
    private static final float SIZE = 12;
    /** Glyph inside the box, the soft shadow behind it, and the above / below arrow. */
    private static final float ICON = 10, HALO = SIZE * 0.6f, LEVEL_ARROW = 5;
    /** The marker floats this far above the floor of the target block (about a player's chest). */
    private static final double ANCHOR_Y = 1.0;
    /** Closer than {@code FADE_FAR} blocks the marker fades out; it is gone at the target itself. */
    private static final double FADE_FAR = 10;
    /**
     * A beam of light rises from the target at any distance (until 25.10.2026 within 225 blocks), like the golden shaft
     * of light over a Genshin Impact waypoint (ingame_waypoint_beam_01): a soft wide glow with a narrow bright core,
     * faint at the marker, full a little above it and fading out smoothly to its top ({@link BeamShape}). It always faces
     * the camera and keeps about the same width on screen at any distance; terrain hides it. Where any part of it, its
     * top included, would be beyond {@code BEAM_NEAR} of the camera's far plane it is drawn nearer along the same line of
     * sight, all its sizes scaled alike, so it looks the same and is not cut off. It dims to half as the marker nears the limiter ellipse ({@link MarkerTiming#approach}),
     * fades out the rest over a second once the marker rides the ellipse and in over a second when it comes back onto
     * the screen ({@link MarkerTiming#edgeStep}), and out with the marker close to the target.
     */
    private static final float BEAM_NEAR = 0.8f;
    /** Strength of the glow added over the amber tint (the light core is added at full strength). */
    private static final float BEAM_ADDED = 0.5f;
    /**
     * Half-widths per block of distance: on ingame_waypoint_beam_01 at 63 m in 1080 px the glow is about 26 px wide and
     * the core about 5 (FOV 70: one block at distance d covers 1080 / (1.4 d) px); the glow is drawn a bit wider than that,
     * since its edges fade to nothing.
     */
    private static final float BEAM_GLOW_K = 0.025f, BEAM_CORE_K = 0.0035f;
    /**
     * The limiter is an ellipse around the screen centre (half-axes as fractions of the screen, measured on
     * ingame_waypoint_01/02). Its centre line keeps this many GUI units from the bottom: the vanilla hotbar, health,
     * food and armour rows reach 49 units up, plus the marker's own pointer below the glyph and a small gap.
     */
    private static final float LIMIT_RX = 0.31f, LIMIT_RY = 0.36f, HOTBAR_CLEARANCE = 70;

    // Camera of the current frame, captured when the level has been rendered.
    private static final Matrix4f VIEW = new Matrix4f(), VIEW_PROJ = new Matrix4f();
    private static double camX, camY, camZ;
    private static boolean captured;

    // Scratch (render thread only).
    private static final Vector4f CLIP = new Vector4f(), EYE = new Vector4f();
    private static final float[] EDGE = new float[2];
    /** The beam's rows: heights relative to the camera and brightness ({@link BeamShape#rows}). */
    private static final float[] BEAM_Y = new float[BeamShape.ROWS], BEAM_A = new float[BeamShape.ROWS];

    private static long pulseStart = Long.MIN_VALUE;
    /**
     * Fade of the beam at the screen's edge on the last HUD frame, 0..1: dimmed near the limiter ellipse, then gone a
     * second after the glyph went onto it (the beam stays at the target's place meanwhile; until 25.10.2026 it was not
     * drawn at all once the glyph left the view, so no fade was ever seen). The beam is drawn in the world before the
     * HUD, so it follows the previous frame.
     */
    private static float edgeFade;
    /** The beam's timed fade at the screen's edge before smoothing ({@link MarkerTiming#edgeStep}), and when it was last moved. */
    private static float edgeT;
    private static long edgeMs;

    /**
     * Fabric's WorldRenderEvents.LAST: once the level is rendered draws the stars and the beam and keeps this
     * frame's view and projection for the HUD.
     */
    public static void afterLevel(Matrix4fc levelView, Camera camera, DeltaTracker delta) {
        // The level's projection of this frame (since 26.1 kept in the camera's render state).
        Matrix4f projection = Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        renderInWorld(levelView, camera, delta);
        VIEW.set(levelView);
        projection.mul(VIEW, VIEW_PROJ);
        Vec3 c = camera.position();
        camX = c.x;
        camY = c.y;
        camZ = c.z;
        captured = true;
    }

    /**
     * The marker is shown for a minute after tracking starts or the pulse key is pressed, then fades out; it
     * fades in and out ({@link MarkerTiming}).
     */
    /** Target the timer belongs to (a new target starts it again) and when its showing began. */
    private static Navigation.Target shownTarget;
    private static long shownSince;
    /**
     * Visibility of the marker on the last HUD frame, 0..1; the beam fades with it.
     */
    private static float shown;
    /** Fade of the marker close to the target on the last HUD frame, 0..1 (the beam fades with it). */
    private static float nearFade;
    /** Visibility of the stars on the last HUD frame (they follow the marker's timer, even with the marker off). */
    private static float starsShown;
    /** Whether the beam is on for the tracked pin's kind (last HUD frame). */
    private static boolean beamOn;

    /**
     * Pulse key: shows the marker again for a minute (fading in if hidden) and a golden diamond closes in on it.
     * Presses while the diamond is still running are ignored (restarting cut the running one off). The path is searched
     * again from here only when the player is off it; near the line ({@link Route#nearLine}) the stars just show again.
     */
    public static void pulse() {
        if (Navigation.target() == null) return;
        long now = Util.getMillis();
        if (MarkerTiming.pulseRunning(pulseStart, now)) return;
        // Start the fade-in from the current visibility, so a visible marker does not blink.
        shownSince = now - (long) (visibility(now) * MarkerTiming.FADE_IN_MS);
        pulseStart = now;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !Route.nearLine(p.getX(), p.getZ())) Navigation.requestPath();
    }


    /** Keeps the timer on the tracked target: tracking another target (or the same one again) shows it for a minute. */
    private static void trackTimer(Navigation.Target t) {
        if (!java.util.Objects.equals(t, shownTarget)) {
            shownTarget = t;
            shownSince = Util.getMillis();
        }
    }

    private static float visibility(long now) {
        if (shownTarget == null) return 0f;
        return MarkerTiming.visibility(now - shownSince);
    }

    /** One frame of the HUD layer: each loader calls it from its own (NeoForge GuiLayer, Fabric HudElement). */
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        edgeFade = 0f;
        shown = 0f;
        starsShown = 0f;
        beamOn = false;
        Navigation.setPathWanted(Navigation.PATH_WORLD, false); // the stars need the path only while they show
        if (player == null || mc.level == null) return;
        Navigation.Target t = Navigation.target();
        trackTimer(t);
        // The marker of the tracked pin, with its beam and the stars as the settings of its kind say.
        MapConfig.PinOptions o = MarkerVisibility.targetOptions();
        if (!captured || mc.options.hideGui || o == null) return;
        if (mc.screen != null && !(mc.screen instanceof ChatScreen)) return;
        if (!Navigation.inPlayerDimension(t, player)) return;
        float vis = visibility(Util.getMillis());
        if (vis <= 0.01f) return;
        if (o.pathInWorld()) {
            starsShown = vis;
            Navigation.setPathWanted(Navigation.PATH_WORLD, true);
        }
        // The marker (and its beam) only on the "In the world" step and above; the stars follow the path's setting.
        if (!o.marker()) return;
        shown = vis;
        beamOn = o.beam();

        float pt = delta.getGameTimeDeltaPartialTick(true);
        double px = Mth.lerp(pt, player.xo, player.getX()), py = Mth.lerp(pt, player.yo, player.getY()), pz = Mth.lerp(pt, player.zo, player.getZ());
        double ty = targetY(mc, t, py);
        double dx = t.x() - px, dy = ty - py, dz = t.z() - pz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        nearFade = smooth(dist / FADE_FAR);
        float alpha = nearFade * shown;
        if (alpha <= 0.01f) return;

        float gw = g.guiWidth(), gh = g.guiHeight();
        float rx = (float) (t.x() - camX), ry = (float) (ty + ANCHOR_Y - camY), rz = (float) (t.z() - camZ);
        Vector4f v = CLIP.set(rx, ry, rz, 1f).mul(VIEW_PROJ);
        boolean front = v.w > 1e-3f;
        float sx = 0, sy = 0;
        if (front) {
            sx = (v.x / v.w * 0.5f + 0.5f) * gw;
            sy = (0.5f - v.y / v.w * 0.5f) * gh;
        }
        float cx = gw / 2f, cy = gh / 2f;
        float erx = gw * LIMIT_RX, ery = Math.max(SIZE, Math.min(gh * LIMIT_RY, gh - HOTBAR_CLEARANCE - cy));
        float ellipse = sq((sx - cx) / erx) + sq((sy - cy) / ery);
        boolean inside = front && ellipse <= 1f;
        boolean inView = inside && !inHud(sx, sy);
        // The beam dims to half on the way to the ellipse, fades the rest over a second on it, and in over a second once
        // the marker is back on the screen (not shown for a while, e.g. a new target: it starts faded out).
        long nowMs = Util.getMillis();
        float dtS = nowMs - edgeMs > 250 ? 0f : (nowMs - edgeMs) / 1000f;
        if (nowMs - edgeMs > 250) edgeT = 0f;
        edgeMs = nowMs;
        edgeT = MarkerTiming.edgeStep(edgeT, inView, dtS);
        edgeFade = MarkerTiming.smooth(edgeT) * MarkerTiming.approach(front ? ellipse : Float.NaN);

        float dirX = 0, dirY = 0;
        if (!inView) {
            if (front) {
                // Still ahead: slide out along the projected direction, so the marker moves without a jump.
                dirX = sx - cx;
                dirY = sy - cy;
            } else {
                // Behind the camera: direction from the camera-space offset, bent towards the bottom.
                Vector4f e = EYE.set(rx, ry, rz, 1f).mul(VIEW);
                dirX = e.x;
                dirY = -e.y + Math.max(0f, e.z);
            }
            if (Math.abs(dirX) < 1e-4f && Math.abs(dirY) < 1e-4f) dirY = 1;
            clampToLimiter(cx, cy, erx, ery, dirX, dirY);
            sx = EDGE[0];
            sy = EDGE[1];
        }
        sx = Draw.snap(sx);
        sy = Draw.snap(sy);

        // 1. Pulse after the key was pressed: a diamond closes in on the marker (the beam is drawn in the world,
        // see renderBeam)
        float pulseSeconds = (Util.getMillis() - pulseStart) / 1000f;
        boolean pulsing = pulseStart != Long.MIN_VALUE && pulseSeconds >= 0 && pulseSeconds < MarkerRenderer.WORLD_PULSE_LIFE;
        // Golden, like the beam (one colour for every kind since 26.10.2026).
        int pulseColor = Theme.worldMarkerPulse();
        if (pulsing) MarkerRenderer.worldPulse(g, sx, sy, SIZE, pulseSeconds, SIZE * MarkerRenderer.WORLD_PULSE_START, pulseColor, alpha);

        // 2. Halo, glyph, the above / below arrow and the distance: in view only (on the limiter the edge pointer is the
        // only arrow); the level arrow at any distance (it stopped at 225 blocks until 25.10.2026). The same number as on the minimap and
        // the world map (Navigation.distance), not the interpolated one.
        double shownDist = Navigation.distance(player);
        int level = inView ? Navigation.level(player) : 0;
        Component text = inView && alpha > 0.03f ? distanceText(Math.round(shownDist)) : null;
        Icon icon = targetIcon(t);
        if (pulsing) {
            // The glow changes every frame for less than a second: drawn directly.
            drawMarker(g, icon, MarkerRenderer.worldPulseGlow(pulseSeconds), pulseColor, level, text, sx, sy, alpha);
        } else {
            drawSprite(g, icon, level, text, sx, sy, alpha);
        }

        // 3. Pointer towards the target while held at the edge
        if (!inView) {
            float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            float ux = dirX / len, uy = dirY / len;
            float ax = sx + ux * (SIZE / 2f + 2), ay = sy + uy * (SIZE / 2f + 2);
            Draw.triangle(g, ax + ux * 3, ay + uy * 3, ax - uy * 2.5f, ay + ux * 2.5f, ax + uy * 2.5f, ay - ux * 2.5f,
                    Theme.withAlpha(pulseColor, alpha));
        }
    }

    /**
     * Halo and glyph (glowing gold when the pulse diamond arrives), the white above / below arrow close to the glyph,
     * and the distance under it ({@code text}, may be null).
     */
    private static void drawMarker(GuiGraphicsExtractor g, Icon icon, float glow, int glowColor, int level, Component text, float sx, float sy, float alpha) {
        int halo = Theme.WORLD_MARKER_HALO;
        Draw.ring(g, sx, sy, 0f, HALO, Theme.withAlpha(halo, alpha), Theme.withAlpha(halo, 0f));
        int tint = Theme.lerp(Theme.MARKER_ICON, glowColor, glow);
        Draw.icon(g, icon, sx, sy, ICON, Theme.withAlpha(tint, alpha));
        float glyphHalf = ICON / 2f, below = sy + glyphHalf + 1;
        if (level > 0) MarkerRenderer.levelArrow(g, sx, sy - glyphHalf - LEVEL_GAP - LEVEL_ARROW / 2f, LEVEL_ARROW, true, Theme.WORLD_MARKER_LEVEL, alpha);
        if (level < 0) {
            MarkerRenderer.levelArrow(g, sx, sy + glyphHalf + LEVEL_GAP + LEVEL_ARROW / 2f, LEVEL_ARROW, false, Theme.WORLD_MARKER_LEVEL, alpha);
            below = sy + glyphHalf + LEVEL_GAP + LEVEL_ARROW + 1;
        }
        if (text != null) distance(g, text, sx, below + 1, Theme.withAlpha(Theme.WORLD_MARKER_TEXT, alpha));
    }

    /**
     * Scale of the distance text: smaller than the vanilla font, like the small "63m" on ingame_waypoint_beam_01, but
     * a whole number of screen pixels per font pixel so it stays sharp (2/3 at GUI scale 3, 16 pixels high).
     */
    private static float textScale() {
        int s = (int) Math.round(Minecraft.getInstance().getWindow().getGuiScale());
        return Math.max(1, Math.round(s * 0.6f)) / (float) Math.max(1, s);
    }

    /**
     * The distance centred under the glyph, with its shadow drawn first as plain text: the cached layer has no depth
     * buffer, so the vanilla shadow (which relies on depth to stay behind) would cover the letters.
     */
    private static void distance(GuiGraphicsExtractor g, Component text, float cx, float top, int color) {
        float k = textScale();
        int shadow = (color & 0xFF000000) | ((color & 0xFCFCFC) >> 2);
        g.pose().pushMatrix();
        g.pose().translate(Draw.snap(cx - Math.max(0, DrawText.width(text) - 1) * k / 2f), Draw.snap(top));
        g.pose().scale(k, k);
        DrawText.text(g, text, 1, 1, shadow, false);
        DrawText.text(g, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    // The marker without the pulse, cached in a small layer redrawn only when the glyph, the level arrow, the distance
    // text, the theme or the GUI scale change; every frame it is one textured quad at the marker's place, faded there.
    private static final CachedLayer sprite = new CachedLayer();
    private static Icon kIcon;
    private static int kLevel, kTheme;
    private static Component kText;
    private static double kScale;
    /** The layer's extent around the glyph centre, whole GUI units so it stays on the pixel grid. */
    private static int spriteHalfW, spriteTop, spriteBottom;

    private static void drawSprite(GuiGraphicsExtractor g, Icon icon, int level, Component text, float sx, float sy, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        Window w = mc.getWindow();
        double scale = w.getGuiScale();
        if (!sprite.exists() || icon != kIcon || level != kLevel || text != kText || Theme.generation() != kTheme || scale != kScale) {
            float glyphHalf = ICON / 2f, k = textScale();
            spriteHalfW = (int) Math.ceil(Math.max(HALO, (text == null ? 0 : DrawText.width(text)) * k / 2f + 1)) + 1;
            spriteTop = (int) Math.ceil(Math.max(HALO, glyphHalf + LEVEL_GAP + LEVEL_ARROW + 1)) + 1;
            // Below: the down arrow, then the text (9 font units with its shadow, scaled by k).
            spriteBottom = (int) Math.ceil(Math.max(HALO, glyphHalf + LEVEL_GAP + LEVEL_ARROW + 2 + 10 * k)) + 1;
            int bw = 2 * spriteHalfW, bh = spriteTop + spriteBottom;
            int pw = Math.max(1, (int) Math.ceil(bw * scale)), ph = Math.max(1, (int) Math.ceil(bh * scale));
            sprite.bind(g, pw, ph, false);
            float guiW = (float) (w.getWidth() / scale), guiH = (float) (w.getHeight() / scale);
            g.pose().pushMatrix();
            g.pose().scale(guiW / bw, guiH / bh);
            try {
                drawMarker(g, icon, 0f, 0, level, text, spriteHalfW, spriteTop, 1f);
            } finally {
                g.pose().popMatrix();
                sprite.unbind();
            }
            kIcon = icon;
            kLevel = level;
            kText = text;
            kTheme = Theme.generation();
            kScale = scale;
        }
        Draw.texturedQuadPremultiplied(g, sprite.tex(), sx - spriteHalfW, sy - spriteTop, sx + spriteHalfW, sy + spriteBottom,
                0f, 1f, 1f, 0f, alpha);
    }

    /** The path to the target as stars in the world, shown and faded on the marker's timer. */
    /**
     * The path stars, then the beam, after the whole level. In Fabulous graphics the water, the particles, the clouds
     * and the weather are laid together by depth at the end: stars drawn after the particles and the beam drawn after
     * the weather (neither writing depth) went under the water there, taking its colour, and the beam stopped above the
     * ground and was cut by the clouds (tester reports 26.10.2026). Since 26.1 the light is drawn by
     * a render pass of its own with the level's view ({@link WorldGlow#begin}), whatever the model-view stack holds.
     */
    private static void renderInWorld(Matrix4fc levelView, Camera camera, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        boolean stars = starsShown > 0f && Navigation.inPlayerDimension(Navigation.target(), mc.player);
        if (!stars && !beamOn) return;
        // The depth Fabulous leaves in the main target hid every star; the particles' target holds the scene's depth
        // (its own copy, plus the particles), so the light is tested against that one there.
        RenderTarget particles = mc.levelRenderer.getParticlesTarget();
        WorldGlow.begin(levelView, Minecraft.useShaderTransparency() && particles != null ? particles : null);
        try {
            if (stars) PathStars.render(camera, starsShown);
            renderBeam(camera, delta);
        } finally {
            WorldGlow.end();
        }
    }

    /**
     * The beam of light over the target, after the whole level (so neither translucent water nor a cloud behind it
     * hides it; a cloud in front of it does by depth, except in Fabulous, where the clouds keep their depth apart):
     * additive, depth tested but not depth writing, two
     * camera-facing layers (glow and core), each a strip from the centre to both edges whose alpha falls to zero at the
     * edges, at the bottom and at the top.
     */
    private static void renderBeam(Camera camera, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui || !beamOn) return;
        Navigation.Target t = Navigation.target();
        if (!Navigation.inPlayerDimension(t, player)) return;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        double px = Mth.lerp(pt, player.xo, player.getX()), py = Mth.lerp(pt, player.yo, player.getY()), pz = Mth.lerp(pt, player.zo, player.getZ());
        double ty = targetY(mc, t, py);
        double dx = t.x() - px, dy = ty - py, dz = t.z() - pz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float a = shown * nearFade * edgeFade;
        if (a <= 0.01f) return;

        Vec3 cam = camera.position();
        float bx = (float) (t.x() - cam.x), bz = (float) (t.z() - cam.z);
        float y0 = (float) (ty + ANCHOR_Y - cam.y);
        BeamShape.rows(dist, BEAM_Y, BEAM_A);
        for (int i = 0; i < BeamShape.ROWS; i++) BEAM_Y[i] += y0;
        // Across the view: perpendicular to the horizontal direction from the camera to the beam.
        float len = (float) Math.sqrt(bx * bx + bz * bz);
        float rx = len < 1e-3f ? 1f : -bz / len, rz = len < 1e-3f ? 0f : bx / len;
        float glow = Math.max(0.3f, dist * BEAM_GLOW_K), core = Math.max(0.06f, dist * BEAM_CORE_K);
        // Any part farther than the far plane allows (its top too, which looking up at it brings deeper into the view):
        // the same beam nearer along the line of sight, everything scaled alike.
        float near = mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.depthFar * BEAM_NEAR;
        float top = BEAM_Y[BeamShape.ROWS - 1];
        float reach = (float) Math.sqrt(len * len + Math.max(y0 * y0, top * top));
        if (reach > near) {
            float k = near / reach;
            bx *= k; bz *= k; glow *= k; core *= k;
            for (int i = 0; i < BeamShape.ROWS; i++) BEAM_Y[i] *= k;
        }

        // An amber tint laid over the scene first, so the beam stays golden on a bright sky (added light alone turned
        // white on blue), then the glow and the light core added to it, the glow at half strength.
        BufferBuilder tint = WorldGlow.begin(VertexFormat.Mode.QUADS);
        beamLayer(tint, bx, bz, rx, rz, glow, Theme.withAlpha(Theme.WORLD_MARKER_BEAM_TINT, a));
        WorldGlow.draw(tint, false);
        BufferBuilder b = WorldGlow.begin(VertexFormat.Mode.QUADS);
        beamLayer(b, bx, bz, rx, rz, glow, Theme.withAlpha(Theme.WORLD_MARKER_BEAM, a * BEAM_ADDED));
        beamLayer(b, bx, bz, rx, rz, core, Theme.withAlpha(Theme.WORLD_MARKER_BEAM_CORE, a));
        WorldGlow.draw(b);
    }

    /**
     * One beam layer of half-width {@code w}: both halves, each a strip up the rows {@link #BEAM_Y} at their brightness
     * {@link #BEAM_A} along the middle, clear at the edges.
     */
    private static void beamLayer(BufferBuilder b, float x, float z, float rx, float rz, float w, int color) {
        int clear = color & 0x00FFFFFF;
        for (int side = -1; side <= 1; side += 2) {
            float ex = x + rx * w * side, ez = z + rz * w * side;
            for (int i = 0; i + 1 < BeamShape.ROWS; i++) {
                float ya = BEAM_Y[i], yb = BEAM_Y[i + 1];
                b.addVertex(x, ya, z).setColor(Theme.withAlpha(color, BEAM_A[i])); b.addVertex(ex, ya, ez).setColor(clear);
                b.addVertex(ex, yb, ez).setColor(clear); b.addVertex(x, yb, z).setColor(Theme.withAlpha(color, BEAM_A[i + 1]));
            }
        }
    }
    private static float smooth(double t) {
        float x = (float) Math.max(0, Math.min(1, t));
        return x * x * (3 - 2 * x);
    }

    private static float sq(float v) {
        return v * v;
    }

    /**
     * Where the ray from the screen centre (cx, cy) in direction (dx, dy) meets the limiter ellipse with half-axes
     * (rx, ry), going around the minimap. Result in {@link #EDGE}.
     */
    private static void clampToLimiter(float cx, float cy, float rx, float ry, float dx, float dy) {
        float t = (float) (1.0 / Math.sqrt(sq(dx / rx) + sq(dy / ry)));
        float x = cx + dx * t, y = cy + dy * t;
        if (MinimapLayer.shown) {
            // Inside the minimap (plus the glyph's half size and a gap): stop where the ray enters that area, so the
            // marker slides along the minimap's border instead of covering it.
            float k = SIZE / 2f + Grid.GAP;
            float ex0 = MinimapLayer.shownX0 - k, ey0 = MinimapLayer.shownY0 - k, ex1 = MinimapLayer.shownX1 + k, ey1 = MinimapLayer.shownY1 + k;
            boolean centreInside = cx > ex0 && cx < ex1 && cy > ey0 && cy < ey1;
            if (!centreInside && x > ex0 && x < ex1 && y > ey0 && y < ey1) {
                float enterX = dx > 0 ? (ex0 - cx) / dx : dx < 0 ? (ex1 - cx) / dx : -Float.MAX_VALUE;
                float enterY = dy > 0 ? (ey0 - cy) / dy : dy < 0 ? (ey1 - cy) / dy : -Float.MAX_VALUE;
                float te = Math.max(0f, Math.max(enterX, enterY));
                x = cx + dx * te;
                y = cy + dy * te;
            }
        }
        EDGE[0] = x;
        EDGE[1] = y;
    }

    /** Whether (x, y) is under the minimap, where the marker would be hidden. */
    private static boolean inHud(float x, float y) {
        if (!MinimapLayer.shown) return false;
        float k = SIZE / 2f;
        return x > MinimapLayer.shownX0 - k && x < MinimapLayer.shownX1 + k && y > MinimapLayer.shownY0 - k && y < MinimapLayer.shownY1 + k;
    }

    /** Height of the target's floor: its stored Y, else the loaded terrain, else the player's own height. */
    private static double targetY(Minecraft mc, Navigation.Target t, double playerY) {
        if (t.y() != Integer.MIN_VALUE) return t.y();
        int bx = Mth.floor(t.x()), bz = Mth.floor(t.z());
        if (mc.level.hasChunk(bx >> 4, bz >> 4)) return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        return playerY;
    }

    // The target's glyph, looked up again only when the target or the pins change.
    private static Navigation.Target iconTarget;
    private static int iconVersion;
    private static Icon icon;

    private static Icon targetIcon(Navigation.Target t) {
        if (t != iconTarget || MarkerStore.version() != iconVersion || icon == null) {
            iconTarget = t;
            iconVersion = MarkerStore.version();
            MarkerType type = t.auto();
            if (t.markerId() != null) {
                Marker m = MarkerStore.find(t.markerId());
                type = m != null ? m.type : MarkerType.STAR;
            }
            icon = type.icon;
        }
        return icon;
    }

    // The distance text, rebuilt only when the number or the language changes.
    private static long distanceValue = Long.MIN_VALUE;
    private static String distanceLang;
    private static Component distance;

    private static Component distanceText(long d) {
        String lang = DistanceText.language();
        if (distance == null || d != distanceValue || !lang.equals(distanceLang)) {
            distanceValue = d;
            distanceLang = lang;
            distance = DistanceText.blocks(d);
        }
        return distance;
    }
}
