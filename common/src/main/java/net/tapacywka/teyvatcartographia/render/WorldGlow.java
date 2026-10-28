package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Light drawn into the world (the marker's beam, the path stars): camera-relative position + colour vertices, added to
 * the scene (additive blending; the beam's tint over it with normal blending), hidden by terrain (depth test) without
 * hiding anything (no depth writes), both faces. Since 26.1 drawn by a render pass of its own into the game's frame and
 * depth, with the level's projection still in force ({@link #begin} after the level, {@link #end} once drawn).
 */
public final class WorldGlow {
    private WorldGlow() {}

    private static final BlendFunction ADD_LIGHT = new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE);

    private static RenderPipeline pipeline(String name, VertexFormat.Mode mode, BlendFunction blend) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "pipeline/" + name))
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, mode)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false))
                .withColorTargetState(new ColorTargetState(blend))
                .build();
    }

    public static final RenderPipeline QUADS_ADD = pipeline("world_glow_quads", VertexFormat.Mode.QUADS, ADD_LIGHT);
    public static final RenderPipeline QUADS_TINT = pipeline("world_tint_quads", VertexFormat.Mode.QUADS, BlendFunction.TRANSLUCENT);
    public static final RenderPipeline TRIANGLES_ADD = pipeline("world_glow_triangles", VertexFormat.Mode.TRIANGLES, ADD_LIGHT);
    public static final RenderPipeline TRIANGLES_TINT = pipeline("world_tint_triangles", VertexFormat.Mode.TRIANGLES, BlendFunction.TRANSLUCENT);
    public static final List<RenderPipeline> ALL = List.of(QUADS_ADD, QUADS_TINT, TRIANGLES_ADD, TRIANGLES_TINT);

    /** The level's view for the batches until {@link #end}, and the depth they are tested against. */
    private static final Matrix4f VIEW = new Matrix4f();
    private static RenderTarget depth;

    /** Starts drawing after the level with its view (the model view without the camera's position). */
    public static void begin(Matrix4fc levelView, RenderTarget depthFrom) {
        VIEW.set(levelView);
        depth = depthFrom;
    }

    public static void end() {
        depth = null;
    }

    /** Starts a batch of {@code mode} (quads or triangles). */
    public static BufferBuilder begin(VertexFormat.Mode mode) {
        return Tesselator.getInstance().begin(mode, DefaultVertexFormat.POSITION_COLOR);
    }

    /** Draws the batch added to the scene (nothing when it is empty). */
    public static void draw(BufferBuilder b) {
        draw(b, true);
    }

    /**
     * Draws the batch, added to the scene or ({@code additive} false) laid over it with normal blending: a colour that
     * tints a bright sky instead of only brightening it (the beam's gold turned white on a blue sky, 26.10.2026).
     */
    public static void draw(BufferBuilder b, boolean additive) {
        MeshData mesh = b.build();
        if (mesh == null) return;
        try (mesh) {
            VertexFormat.Mode mode = mesh.drawState().mode();
            RenderPipeline pipeline = mode == VertexFormat.Mode.QUADS ? (additive ? QUADS_ADD : QUADS_TINT) : (additive ? TRIANGLES_ADD : TRIANGLES_TINT);
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            RenderTarget depthTarget = depth != null ? depth : main;
            GpuBuffer vertices = pipeline.getVertexFormat().uploadImmediateVertexBuffer(mesh.vertexBuffer());
            int count = mesh.drawState().indexCount();
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(mode);
            GpuBuffer indexBuffer = indices.getBuffer(count);
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(VIEW, COLOR, OFFSET, TEXTURE_MATRIX);
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Teyvat Cartographia world glow",
                    main.getColorTextureView(), OptionalInt.empty(), depthTarget.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, count, 1);
            }
        }
    }

    private static final Vector4f COLOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
}
