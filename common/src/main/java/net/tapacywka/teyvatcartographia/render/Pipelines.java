package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.version.Gpu;

import java.util.List;

/**
 * The mod's GUI pipelines: the game's GUI shaders, one per way of blending, in quads as the game's GUI renderer needs
 * (it draws every element with the index pattern of quads; {@link Shape} hands each triangle over as a quad whose last
 * corner repeats its third), both faces (the mod's shapes wind either way; culled, the minimap's faded edge and the
 * view cone vanished).
 * Each loader registers {@link #ALL} so they are compiled with the game's.
 */
public final class Pipelines {
    private Pipelines() {}

    /** Coloured triangles blended over what is underneath (its alpha kept as coverage, so a layer ends premultiplied). */
    public static final RenderPipeline COLOR = quads(RenderPipelines.GUI_SNIPPET, DefaultVertexFormat.POSITION_COLOR)
            .withLocation(id("gui_triangles"))
            .withCull(false)
            .build();
    public static final RenderPipeline COLOR_ADD = quads(RenderPipelines.GUI_SNIPPET, DefaultVertexFormat.POSITION_COLOR)
            .withLocation(id("gui_triangles_additive"))
            .withCull(false)
            .withColorTargetState(new ColorTargetState(Gpu.ADD_LIGHT))
            .build();
    /** Textured triangles (straight alpha). */
    public static final RenderPipeline TEX = quads(RenderPipelines.GUI_TEXTURED_SNIPPET, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withLocation(id("gui_textured_triangles"))
            .withCull(false)
            .build();
    /** Textured triangles holding premultiplied colour (the icon atlas, the mod's own layers). */
    public static final RenderPipeline TEX_PREMULTIPLIED = quads(RenderPipelines.GUI_TEXTURED_SNIPPET, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withLocation(id("gui_textured_triangles_premultiplied"))
            .withCull(false)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .build();

    public static final List<RenderPipeline> ALL = List.of(COLOR, COLOR_ADD, TEX, TEX_PREMULTIPLIED);

    private static RenderPipeline.Builder quads(RenderPipeline.Snippet snippet, VertexFormat format) {
        return Gpu.vertices(RenderPipeline.builder(snippet), format, true);
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "pipeline/" + name);
    }
}
