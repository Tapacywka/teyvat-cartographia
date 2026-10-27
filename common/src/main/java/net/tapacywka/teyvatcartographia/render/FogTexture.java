package net.tapacywka.teyvatcartographia.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.compat.ModCompat;

import java.io.InputStream;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * "Fog of war" backdrop for unexplored space: the theme's seamlessly tiling {@code fog.png} (soft dark clouds with
 * sparse faint stars in the built-in theme), loaded once per theme and drawn as one repeating quad, every
 * {@link #REPEAT} GUI units whatever its resolution (the built-in fogs are 256 x 256: soft clouds lose nothing by being
 * scaled up). A dimension may have a
 * fog of its own, {@code fog/<namespace>/<path>.png} (the Aether: a sea of clouds). A {@code .json} beside a fog
 * ({@code fog.json}, {@code fog/<namespace>/<path>.json}) may say {@code "repeat": 384} (GUI units per repeat) and,
 * for a dimension, {@code "island_shadow": true} when the land floats over it and casts a shadow on it
 * ({@code MapLayerRenderer}).
 */
public final class FogTexture {
    private FogTexture() {}

    /** On-screen size of one repeat of a fog, in GUI units, unless its {@code .json} says otherwise. */
    public static final int REPEAT = 512;

    /** One fog: its texture, its repeat (GUI units) and whether the map's land casts a shadow on it. */
    private record Fog(DynamicTexture texture, int repeat, boolean shadow) {}

    private static Fog base;
    /** The dimensions' own fogs, null for a dimension without one (looked up once per theme). */
    private static final Map<ResourceKey<Level>, Fog> dimensions = new HashMap<>();

    private static Fog base() {
        if (base == null) {
            NativeImage img = read("fog.png");
            JsonObject settings = settings("fog.json");
            base = new Fog(texture(img != null ? img : plain()), repeat(settings), false);
        }
        return base;
    }

    /** The fog of a dimension: its own (unless its switch is off, {@link ModCompat#ownFog}), else the theme's. */
    private static Fog of(ResourceKey<Level> dimension) {
        if (dimension == null || !ModCompat.ownFog(dimension)) return base();
        if (!dimensions.containsKey(dimension)) {
            ResourceLocation id = dimension.location();
            String path = "fog/" + id.getNamespace() + "/" + id.getPath();
            NativeImage img = read(path + ".png");
            JsonObject settings = img == null ? null : settings(path + ".json");
            dimensions.put(dimension, img == null ? null : new Fog(texture(img), repeat(settings),
                    settings != null && settings.has("island_shadow") && settings.get("island_shadow").getAsBoolean()));
        }
        Fog f = dimensions.get(dimension);
        return f != null ? f : base();
    }

    private static DynamicTexture texture(NativeImage img) {
        DynamicTexture texture = new DynamicTexture(img);
        texture.setFilter(true, false);
        // Repeat, so the whole screen is one quad with texture coordinates beyond 0..1.
        texture.bind();
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        return texture;
    }

    /** A theme resource, else the default theme's (like icons). */
    private static Optional<Resource> resource(String path) {
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(Theme.asset(path));
        if (res.isEmpty() && !Theme.id().equals(Theme.DEFAULT)) {
            res = Minecraft.getInstance().getResourceManager().getResource(Theme.asset(Theme.DEFAULT, path));
        }
        return res;
    }

    /** A fog image of the theme (any size, tiles seamlessly), or null. */
    private static NativeImage read(String path) {
        Optional<Resource> res = resource(path);
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open()) {
            return NativeImage.read(NativeImage.Format.RGBA, in);
        } catch (Exception e) {
            TeyvatCartographia.LOGGER.warn("Could not read fog texture {}", path, e);
            return null;
        }
    }

    /** The settings beside a fog, or null. */
    private static JsonObject settings(String path) {
        Optional<Resource> res = resource(path);
        if (res.isEmpty()) return null;
        try (Reader r = res.get().openAsReader()) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            TeyvatCartographia.LOGGER.warn("Could not read fog settings {}", path, e);
            return null;
        }
    }

    /** GUI units per repeat: the settings' {@code repeat} (16 to 4096), else {@link #REPEAT}. */
    private static int repeat(JsonObject settings) {
        if (settings == null || !settings.has("repeat")) return REPEAT;
        try {
            return Math.max(16, Math.min(4096, settings.get("repeat").getAsInt()));
        } catch (RuntimeException e) {
            return REPEAT;
        }
    }

    /** Whether a dimension has a fog of its own (the minimap then shows it instead of its plain backdrop). */
    public static boolean hasOwn(ResourceKey<Level> dimension) {
        return dimension != null && of(dimension) != base();
    }

    /** Whether the land of this dimension casts a shadow on its fog (floating islands). */
    public static boolean shadow(ResourceKey<Level> dimension) {
        return of(dimension).shadow();
    }

    /** Frees the textures; they are loaded again (for the current theme) on the next draw. */
    public static void invalidate() {
        if (base != null) base.texture().close();
        base = null;
        for (Fog f : dimensions.values()) if (f != null) f.texture().close();
        dimensions.clear();
    }

    /** Fallback when no fog.png can be read: a plain fill in the theme's {@link Theme#FOG_BASE} colour. */
    private static NativeImage plain() {
        NativeImage img = new NativeImage(64, 64, false);
        int c = Theme.FOG_BASE;
        img.fillRect(0, 0, 64, 64, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16)); // ARGB -> ABGR
        return img;
    }

    /**
     * Fills the rectangle with the theme's fog; {@code offsetX/Y} scroll it (parallax with the map). One repeat is
     * {@link #REPEAT} GUI units unless the fog's settings say otherwise.
     */
    public static void draw(GuiGraphics g, float x0, float y0, float x1, float y1, float offsetX, float offsetY, int tint) {
        draw(g, null, x0, y0, x1, y1, offsetX, offsetY, tint);
    }

    /** As above with the fog of a dimension (its own, else the theme's). */
    public static void draw(GuiGraphics g, ResourceKey<Level> dimension, float x0, float y0, float x1, float y1, float offsetX, float offsetY,
                            int tint) {
        Fog fog = of(dimension);
        int id = fog.texture().getId();
        float tile = fog.repeat();
        float u0 = mod(offsetX, tile) / tile, v0 = mod(offsetY, tile) / tile;
        Draw.texturedQuad(g, id, x0, y0, x1, y1, u0, v0, u0 + (x1 - x0) / tile, v0 + (y1 - y0) / tile, tint);
    }

    private static float mod(float a, float m) {
        float r = a % m;
        return r < 0 ? r + m : r;
    }
}
