package net.tapacywka.teyvatcartographia.gui;

import com.mojang.blaze3d.platform.Window;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.TileStore;
import net.tapacywka.teyvatcartographia.render.CachedLayer;
import net.tapacywka.teyvatcartographia.render.DayLight;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.FogTexture;
import net.tapacywka.teyvatcartographia.render.MapLayerRenderer;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The picture of the world map screen: the fog-of-war backdrop with a gentle parallax (it follows panning and stays
 * still while zooming) and the map regions lit by the time of day, or LOD tiles far out.
 * <p>
 * It lives in an opaque screen-sized layer, so the screen is filled once per frame. The layer is redrawn only when the
 * view (and with it the fog's parallax), the map data, the lighting, the theme or the screen changes, while the
 * picture is still incomplete (regions loading or uploading), and once a second anyway: that keeps the visible regions
 * recently used, so the region cache does not evict them while they are only shown from the layer.
 */
final class MapPicture {
    private static final double FOG_PARALLAX = 0.35;
    // Accumulated screen offset of the fog, and the view it was last updated at.
    private double fogOffsetX, fogOffsetZ;
    private double fogLastX = Double.NaN, fogLastZ, fogLastScale;

    private final CachedLayer layer = new CachedLayer();
    private boolean complete;
    private TileStore kStore;
    private int kVersion, kTint, kWidth, kHeight, kTheme;
    private float kNight;
    private double kCenterX, kCenterZ, kScale, kGuiScale, kFogX, kFogZ;
    private long kDrawnNs;

    /** Draws the picture of {@code view} (centre and scale given separately) over the whole screen. */
    void draw(GuiGraphics g, TileStore store, MapView view, double centerX, double centerZ, double scale, int width, int height, float pt) {
        updateFogParallax(centerX, centerZ, scale);
        // Cave layers are not lit by the sky.
        boolean cave = store != null && store.cave;
        int tint = cave ? 0xFFFFFFFF : DayLight.tint(pt);
        // Quantised like the tint, so the slow change of daylight does not redraw every frame.
        float night = cave ? 0f : Math.round(DayLight.night(pt) * 255) / 255f;
        int version = store == null ? -1 : store.version();
        Window w = Minecraft.getInstance().getWindow();
        double guiScale = w.getGuiScale();
        long now = Util.getNanos();
        boolean dirty = !layer.exists() || !complete || now - kDrawnNs > 1_000_000_000L
                || store != kStore || version != kVersion || centerX != kCenterX || centerZ != kCenterZ || scale != kScale
                || tint != kTint || night != kNight || width != kWidth || height != kHeight || guiScale != kGuiScale
                || fogOffsetX != kFogX || fogOffsetZ != kFogZ || Theme.generation() != kTheme;
        if (dirty) {
            layer.bind(g, w.getWidth(), w.getHeight(), false);
            Draw.beginLayer();
            try {
                // The dimension's own fog (the Aether's sea of clouds), not under a cave layer.
                FogTexture.draw(g, store == null || store.cave ? null : store.dimension, 0, 0, width, height, (float) fogOffsetX, (float) fogOffsetZ, Theme.WHITE);
                complete = MapLayerRenderer.drawRect(g, store, view, 0, 0, width, height, tint, night);
                g.flush();
            } finally {
                Draw.endLayer();
                CachedLayer.unbind();
            }
            kStore = store;
            kVersion = version;
            kCenterX = centerX;
            kCenterZ = centerZ;
            kScale = scale;
            kTint = tint;
            kNight = night;
            kWidth = width;
            kHeight = height;
            kGuiScale = guiScale;
            kDrawnNs = now;
            kFogX = fogOffsetX;
            kFogZ = fogOffsetZ;
            kTheme = Theme.generation();
        }
        Draw.texturedQuadPremultiplied(g, layer.textureId(), 0, 0, width, height, 0f, 1f, 1f, 0f);
    }

    /** The fog follows panning only: zooming (a scale change) leaves it where it is. */
    private void updateFogParallax(double centerX, double centerZ, double scale) {
        if (!Double.isNaN(fogLastX) && scale == fogLastScale) {
            fogOffsetX += (centerX - fogLastX) * scale * FOG_PARALLAX;
            fogOffsetZ += (centerZ - fogLastZ) * scale * FOG_PARALLAX;
        }
        fogLastX = centerX;
        fogLastZ = centerZ;
        fogLastScale = scale;
    }

    void destroy() {
        layer.destroy();
    }
}
