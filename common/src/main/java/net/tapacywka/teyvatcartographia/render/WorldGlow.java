package net.tapacywka.teyvatcartographia.render;

import net.tapacywka.teyvatcartographia.version.Game;
import net.tapacywka.teyvatcartographia.version.Gpu;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.resources.Identifier;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

/**
 * Light drawn into the world (the marker's beam, the path stars): camera-relative position + colour vertices, added to
 * the scene (additive blending; the beam's tint over it with normal blending), hidden by terrain (depth test) without
 * hiding anything (no depth writes), both faces. Since 26.1 drawn by a render pass of its own into the game's frame and
 * depth, with the level's projection still in force ({@link #begin} after the level, {@link #end} once drawn).
 */
public final class WorldGlow {
    private WorldGlow() {}

    private static RenderPipeline pipeline(String name, boolean quads, BlendFunction blend) {
        return Gpu.vertices(Gpu.world(), DefaultVertexFormat.POSITION_COLOR, quads)
                .withLocation(Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "pipeline/" + name))
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(DepthStencilState.DEFAULT.depthTest(), false))
                .withColorTargetState(new ColorTargetState(blend))
                .build();
    }

    public static final RenderPipeline QUADS_ADD = pipeline("world_glow_quads", true, Gpu.ADD_LIGHT);
    public static final RenderPipeline QUADS_TINT = pipeline("world_tint_quads", true, BlendFunction.TRANSLUCENT);
    public static final RenderPipeline TRIANGLES_ADD = pipeline("world_glow_triangles", false, Gpu.ADD_LIGHT);
    public static final RenderPipeline TRIANGLES_TINT = pipeline("world_tint_triangles", false, BlendFunction.TRANSLUCENT);
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

    /** Starts a batch of quads or ({@code quads} false) triangles. */
    public static BufferBuilder begin(boolean quads) {
        return Gpu.begin(quads, DefaultVertexFormat.POSITION_COLOR);
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
            RenderPipeline pipeline = Gpu.quads(mesh) ? (additive ? QUADS_ADD : QUADS_TINT) : (additive ? TRIANGLES_ADD : TRIANGLES_TINT);
            RenderTarget main = Game.mainTarget();
            RenderTarget depthTarget = depth != null ? depth : main;
            Gpu.upload(UPLOAD, mesh, DefaultVertexFormat.POSITION_COLOR);
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(VIEW, COLOR, OFFSET, TEXTURE_MATRIX);
            try (RenderPass pass = Gpu.pass(RenderSystem.getDevice().createCommandEncoder(), "Teyvat Cartographia world glow",
                    main.getColorTextureView(), depthTarget.getDepthTextureView())) {
                Gpu.use(pass, pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                Gpu.draw(pass, UPLOAD);
            }
        }
    }

    private static final Gpu.Upload UPLOAD = new Gpu.Upload();
    private static final Vector4f COLOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
}
