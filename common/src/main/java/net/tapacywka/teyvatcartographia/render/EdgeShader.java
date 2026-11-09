package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.compat.ModCompat;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.data.MapOutline;
import net.tapacywka.teyvatcartographia.version.Gpu;

/**
 * The mod's own shader for the map's pictures with their outline ({@code shaders/core/map_edge}, user decision
 * 08.11.2026): the line, its stroke and glow worked out per screen pixel from what does not move
 * ({@link MapOutline#field}), so on the world map the edge breathes ({@link MapOutline#breath}) and is smooth at every
 * zoom. With Map edges not animated, and under VulkanMod (which builds its pipelines in its own way), the outline is
 * baked into the pictures as in 1.2.0, a pixel a block ({@link #inUse}).
 * <p>
 * Since 26.1 a pipeline of its own ({@link #PIPELINE}): the game's GUI elements take no values of their own, so the
 * values of each picture go with its element ({@link #values}, {@link Draw#edgeQuad}) and the mod's layer
 * ({@link CachedLayer}) hands them to the draw as the uniform block {@code EdgeParams}. Outside a layer the picture is
 * drawn as it is.
 */
public final class EdgeShader {
    private EdgeShader() {}

    /** What {@code EdgeMode} draws: see map_edge.fsh. */
    public static final int ALL = 0, LAND_ONLY = 1, SHOWN_ONLY = 2;

    /** The uniform block of the values: five vec4 (the picture, the view, the surf and the mode, the stroke, the glow). */
    static final String UNIFORMS = "EdgeParams";
    private static final int FLOATS = 20;

    /** The game's textured GUI pipeline with the mod's shader, the field as Sampler1 and the values. */
    public static final RenderPipeline PIPELINE = Gpu.edgeBindings(
                    Gpu.vertices(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET), DefaultVertexFormat.POSITION_TEX_COLOR, true),
                    "Sampler1", UNIFORMS)
            .withLocation(id("pipeline/map_edge"))
            .withVertexShader(id("core/map_edge"))
            .withFragmentShader(id("core/map_edge"))
            .withCull(false)
            .build();

    private static final float[] VALUES = new float[FLOATS], PHASES = new float[2], SURF = new float[2];
    private static Gpu.Uniforms uniforms;

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, path);
    }

    /** Whether the loaders are to register it: not under VulkanMod. */
    public static boolean wanted() {
        return !ModCompat.vulkan();
    }

    /**
     * Whether the outline is drawn by this shader now: it is wanted and Map edges moves the edge (animated, or with
     * waves). Not animated, the outline is baked into the pictures as in 1.2.0, a pixel a block (user request
     * 08.11.2026); the stores make every outline again when this changes.
     */
    public static boolean inUse() {
        return wanted() && MapConfig.get(MapConfig.MAP_OUTLINE_MOTION) != MapConfig.EdgeMotion.STILL;
    }

    /**
     * Sets what the pictures drawn next have in common: how many blocks a screen pixel holds and whether the edge
     * breathes (the world map, as the setting says), with waves, or stands still (the minimap, the settings' preview).
     */
    public static void view(double blocksPerPixel, boolean breathing, boolean waves) {
        double seconds = Util.getMillis() / 1000.0;
        MapOutline.phases(seconds, PHASES);
        MapOutline.surf(seconds, SURF);
        VALUES[4] = PHASES[0];
        VALUES[5] = PHASES[1];
        VALUES[6] = breathing ? MapOutline.BREATH : 0f;
        VALUES[7] = (float) blocksPerPixel;
        VALUES[8] = SURF[0];
        VALUES[9] = waves ? SURF[1] : 1f;
        VALUES[10] = waves ? 1f : 0f;
        rgb(Theme.MAP_OUTLINE, 12);
        rgb(Theme.MAP_OUTLINE_GLOW, 16);
    }

    private static void rgb(int color, int at) {
        VALUES[at] = (color >> 16 & 0xFF) / 255f;
        VALUES[at + 1] = (color >> 8 & 0xFF) / 255f;
        VALUES[at + 2] = (color & 0xFF) / 255f;
        VALUES[at + 3] = 1f;
    }

    /**
     * Sets the picture drawn next: its first block, how many blocks it spans, {@link #ALL}, {@link #LAND_ONLY} or
     * {@link #SHOWN_ONLY}, and how far in from its edges its texture is sampled (the texture coordinates' inset, taken
     * off again for its field and the world position).
     */
    public static void picture(int blockX0, int blockZ0, int blocks, int drawn, float edgeInset) {
        VALUES[0] = (float) Math.floorMod(blockX0, MapOutline.PERIOD);
        VALUES[1] = (float) Math.floorMod(blockZ0, MapOutline.PERIOD);
        VALUES[2] = (float) blocks;
        VALUES[3] = edgeInset;
        VALUES[11] = (float) drawn;
    }

    /** The values set now, for the element of the picture drawn next (it is drawn later). */
    static float[] values() {
        return VALUES.clone();
    }

    /** Writes an element's values for the draw that follows (before its render pass opens). */
    static void write(CommandEncoder encoder, float[] values) {
        if (uniforms == null) uniforms = new Gpu.Uniforms("Teyvat Cartographia map edge", FLOATS);
        uniforms.write(encoder, values);
    }

    /** Hands the values last written to the pass. */
    static void bind(RenderPass pass) {
        uniforms.bind(pass, UNIFORMS);
    }
}
