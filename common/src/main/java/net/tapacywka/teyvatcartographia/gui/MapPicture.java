package net.tapacywka.teyvatcartographia.gui;

import com.mojang.blaze3d.platform.Window;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.FarLand;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.TileStore;
import net.tapacywka.teyvatcartographia.render.BorderLine;
import net.tapacywka.teyvatcartographia.render.CachedLayer;
import net.tapacywka.teyvatcartographia.render.DayLight;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.FogTexture;
import net.tapacywka.teyvatcartographia.render.MapLayerRenderer;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The picture of the world map screen: the fog-of-war backdrop with a gentle parallax (it follows panning and stays
 * still while zooming), the far land over it, and the map regions lit by the time of day, or LOD tiles far out.
 * <p>
 * It lives in an opaque screen-sized layer, so the screen is filled once per frame. The layer is redrawn only when the
 * view (and with it the fog's parallax), the map data, the lighting, the theme or the screen changes, while the
 * picture is still incomplete (regions loading or uploading), and once a second anyway: that keeps the visible regions
 * recently used, so the region cache does not evict them while they are only shown from the layer.
 */
final class MapPicture {
    private static final double FOG_PARALLAX = 0.35;
    /** While the far land comes in, the picture is drawn again at most this often. */
    private static final long FAR_REFRESH_NS = 200_000_000L;
    /** While an edge on it breathes, the picture is drawn again this often (60 times a second at most). */
    private static final long BREATH_NS = 16_000_000L;
    // Accumulated screen offset of the fog, and the view it was last updated at.
    private double fogOffsetX, fogOffsetZ;
    private double fogLastX = Double.NaN, fogLastZ, fogLastScale;

    private final CachedLayer layer = new CachedLayer();
    private boolean complete;
    /** The inputs of this frame and of the drawn picture: two objects made once, so a frame allocates nothing. */
    private final Key now = new Key(), drawn = new Key();
    // Not in the key: the far land and the time are compared with a delay, not for equality.
    private int drawnFarVersion;
    private long drawnNs;
    /** Whether the last picture had an edge that breathes. */
    private boolean breathing;

    /** Draws the picture of {@code view} (centre and scale given separately) over the whole screen. */
    void draw(GuiGraphicsExtractor g, TileStore store, MapView view, double centerX, double centerZ, double scale, int width, int height, float pt) {
        updateFogParallax(centerX, centerZ, scale);
        // Cave layers are not lit by the sky.
        boolean cave = store != null && store.cave;
        int tint = cave ? 0xFFFFFFFF : DayLight.tint(pt);
        // Quantised like the tint, so the slow change of daylight does not redraw every frame.
        float night = cave ? 0f : Math.round(DayLight.night(pt) * 255) / 255f;
        // Not under a cave layer.
        FarLand far = store == null || cave ? null : MapDataManager.get().farLand(store.dimension);
        int farVersion = MapLayerRenderer.farVersion(far, view);
        Window w = Minecraft.getInstance().getWindow();
        now.store = store;
        now.far = far;
        now.version = store == null ? -1 : store.version();
        now.border = store == null ? 0 : BorderLine.stamp(store.dimension);
        now.centerX = centerX;
        now.centerZ = centerZ;
        now.scale = scale;
        now.tint = tint;
        now.night = night;
        now.width = width;
        now.height = height;
        now.guiScale = w.getGuiScale();
        now.fogX = fogOffsetX;
        now.fogZ = fogOffsetZ;
        now.theme = Theme.generation();
        long ns = Util.getNanos();
        boolean dirty = !layer.exists() || !complete || ns - drawnNs > 1_000_000_000L || drawn.differs(now)
                || farVersion != drawnFarVersion && ns - drawnNs > FAR_REFRESH_NS
                || breathing && ns - drawnNs >= BREATH_NS;
        if (dirty) {
            layer.bind(g, w.getWidth(), w.getHeight(), false);
            try {
                // The dimension's own fog (the End's starry void), not under a cave layer.
                FogTexture.draw(g, store == null || store.cave ? null : store.dimension, 0, 0, width, height, (float) fogOffsetX, (float) fogOffsetZ, Theme.WHITE);
                boolean farDone = MapLayerRenderer.drawFar(g, far, view, 0, 0, width, height, true);
                MapLayerRenderer.breathing(true);
                try {
                    complete = MapLayerRenderer.drawRect(g, store, view, 0, 0, width, height, tint, night) && farDone;
                } finally {
                    breathing = MapLayerRenderer.breathing(false);
                }
                if (store != null) BorderLine.draw(g, store.dimension, view, 0, 0, width, height);
            } finally {
                layer.unbind();
            }
            drawn.remember(now);
            drawnFarVersion = farVersion;
            drawnNs = ns;
        }
        Draw.texturedQuadPremultiplied(g, layer.tex(), 0, 0, width, height, 0f, CachedLayer.v(1f), 1f, CachedLayer.v(0f));
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

    /**
     * Everything the picture is drawn from that must stay equal for the layer to be reused. Every field takes part in
     * {@link #differs} and {@link #remember} ({@code MapPictureKeyTest} changes each one by reflection). The store and
     * the far land are compared by identity only, so they are kept as plain objects.
     */
    static final class Key {
        Object store, far;
        int version, border, tint, width, height, theme;
        float night;
        double centerX, centerZ, scale, guiScale, fogX, fogZ;

        boolean differs(Key o) {
            return store != o.store || far != o.far || version != o.version || border != o.border || tint != o.tint
                    || width != o.width || height != o.height || theme != o.theme || night != o.night
                    || centerX != o.centerX || centerZ != o.centerZ || scale != o.scale || guiScale != o.guiScale
                    || fogX != o.fogX || fogZ != o.fogZ;
        }

        void remember(Key o) {
            store = o.store;
            far = o.far;
            version = o.version;
            border = o.border;
            tint = o.tint;
            width = o.width;
            height = o.height;
            theme = o.theme;
            night = o.night;
            centerX = o.centerX;
            centerZ = o.centerZ;
            scale = o.scale;
            guiScale = o.guiScale;
            fogX = o.fogX;
            fogZ = o.fogZ;
        }
    }

    void destroy() {
        layer.destroy();
    }
}
