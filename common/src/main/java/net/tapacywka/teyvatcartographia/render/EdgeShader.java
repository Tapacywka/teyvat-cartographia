package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.shaders.AbstractUniform;
import net.minecraft.Util;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.compat.ModCompat;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.data.MapOutline;

/**
 * The mod's own shader for the map's pictures with their outline ({@code shaders/core/map_edge}, user decision
 * 08.11.2026): the line, its stroke and glow worked out per screen pixel from what does not move
 * ({@link MapOutline#field}), so on the world map the edge breathes ({@link MapOutline#breath}) and is smooth at every
 * zoom. Each loader registers it while the game loads its shaders. Without it (not loaded, or under VulkanMod, which
 * builds its pipelines from the game's own shaders), and with Map edges not animated, the outline is baked into the
 * pictures as in 1.2.0, a pixel a block ({@link #inUse}).
 */
public final class EdgeShader {
    private EdgeShader() {}

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "map_edge");

    /** What {@code EdgeMode} draws: see map_edge.fsh. */
    public static final int ALL = 0, LAND_ONLY = 1, SHOWN_ONLY = 2;

    private static ShaderInstance shader;
    private static AbstractUniform origin, size, inset, phase, breath, surf, pixel, mode, stroke, glow;
    private static final float[] PHASES = new float[2], SURF = new float[2];

    /** Whether the loaders are to load it: not under VulkanMod. */
    public static boolean wanted() {
        return !ModCompat.vulkan();
    }

    /** The game loaded it (again, after every reload of the resources). */
    public static void loaded(ShaderInstance s) {
        shader = s;
        origin = s.safeGetUniform("EdgeOrigin");
        size = s.safeGetUniform("EdgeSize");
        inset = s.safeGetUniform("EdgeInset");
        phase = s.safeGetUniform("EdgePhase");
        breath = s.safeGetUniform("EdgeBreath");
        surf = s.safeGetUniform("EdgeSurf");
        pixel = s.safeGetUniform("EdgePixel");
        mode = s.safeGetUniform("EdgeMode");
        stroke = s.safeGetUniform("EdgeStroke");
        glow = s.safeGetUniform("EdgeGlow");
    }

    /** It could not be loaded: the outline stays baked into the pictures. */
    public static void failed(Exception e) {
        shader = null;
        TeyvatCartographia.LOGGER.warn("Could not load the map's edge shader; the edge of the map stays still", e);
    }

    /**
     * Whether the outline is drawn by this shader now: it is loaded and Map edges moves the edge (animated, or with
     * waves). Not animated, the outline is baked into the pictures as in 1.2.0, a pixel a block (user request
     * 08.11.2026); the stores make every outline again when this changes.
     */
    public static boolean inUse() {
        return shader != null && MapConfig.get(MapConfig.MAP_OUTLINE_MOTION) != MapConfig.EdgeMotion.STILL;
    }

    /** For {@code RenderSystem.setShader}. */
    static ShaderInstance shader() {
        return shader;
    }

    /**
     * Sets what the pictures drawn next have in common: how many blocks a screen pixel holds and whether the edge
     * breathes (the world map, as the setting says), with waves, or stands still (the minimap, the settings' preview).
     */
    public static void view(double blocksPerPixel, boolean breathing, boolean waves) {
        if (shader == null) return;
        pixel.set((float) blocksPerPixel);
        breath.set(breathing ? MapOutline.BREATH : 0f);
        double seconds = Util.getMillis() / 1000.0;
        MapOutline.phases(seconds, PHASES);
        phase.set(PHASES[0], PHASES[1]);
        MapOutline.surf(seconds, SURF);
        surf.set(SURF[0], waves ? SURF[1] : 1f, waves ? 1f : 0f);
        stroke.set((Theme.MAP_OUTLINE >> 16 & 0xFF) / 255f, (Theme.MAP_OUTLINE >> 8 & 0xFF) / 255f, (Theme.MAP_OUTLINE & 0xFF) / 255f);
        glow.set((Theme.MAP_OUTLINE_GLOW >> 16 & 0xFF) / 255f, (Theme.MAP_OUTLINE_GLOW >> 8 & 0xFF) / 255f, (Theme.MAP_OUTLINE_GLOW & 0xFF) / 255f);
    }

    /**
     * Sets the picture drawn next: its first block, how many blocks it spans, {@link #ALL}, {@link #LAND_ONLY} or
     * {@link #SHOWN_ONLY}, and how far in from its edges its texture is sampled (the texture coordinates' inset, taken
     * off again for its field and the world position).
     */
    public static void picture(int blockX0, int blockZ0, int blocks, int drawn, float edgeInset) {
        if (shader == null) return;
        origin.set((float) Math.floorMod(blockX0, MapOutline.PERIOD), (float) Math.floorMod(blockZ0, MapOutline.PERIOD));
        size.set((float) blocks);
        inset.set(edgeInset);
        mode.set((float) drawn);
    }
}
