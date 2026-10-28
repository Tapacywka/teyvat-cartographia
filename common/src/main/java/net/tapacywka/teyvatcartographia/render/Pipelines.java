package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;

import java.util.List;
import java.util.Optional;

/**
 * The mod's GUI pipelines: the game's GUI shaders, one per way of blending, in quads as the game's GUI renderer needs
 * (it draws every element with the index pattern of quads; {@link Shape} hands each triangle over as a quad whose last
 * corner repeats its third), both faces (the mod's shapes wind either way; culled, the minimap's faded edge and the
 * view cone vanished).
 * Each loader registers {@link #ALL} so they are compiled with the game's.
 */
public final class Pipelines {
    private Pipelines() {}

    /** Light added to the picture (glows, sparkles). */
    private static final BlendFunction ADD_LIGHT = new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE);

    /** Coloured triangles blended over what is underneath (its alpha kept as coverage, so a layer ends premultiplied). */
    public static final RenderPipeline COLOR = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(id("gui_triangles"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withCull(false)
            .build();
    public static final RenderPipeline COLOR_ADD = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(id("gui_triangles_additive"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(ADD_LIGHT))
            .build();
    /** Textured triangles (straight alpha). */
    public static final RenderPipeline TEX = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(id("gui_textured_triangles"))
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withCull(false)
            .build();
    /** Textured triangles holding premultiplied colour (the icon atlas, the mod's own layers). */
    public static final RenderPipeline TEX_PREMULTIPLIED = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(id("gui_textured_triangles_premultiplied"))
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .build();
    /** Textured triangles replacing what is underneath (no blending). */
    public static final RenderPipeline TEX_OPAQUE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(id("gui_textured_triangles_opaque"))
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_ALL))
            .build();

    public static final List<RenderPipeline> ALL = List.of(COLOR, COLOR_ADD, TEX, TEX_PREMULTIPLIED, TEX_OPAQUE);

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "pipeline/" + name);
    }
}
