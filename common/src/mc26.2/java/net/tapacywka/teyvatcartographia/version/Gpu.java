package net.tapacywka.teyvatcartographia.version;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

/** The GPU calls that changed between the 26.x games: Minecraft 26.2's side (mc26.1.2 and mc26.3 have the others). */
public final class Gpu {
    private Gpu() {}

    /** Light added to the picture (glows, sparkles). */
    public static final BlendFunction ADD_LIGHT = new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE);

    /** The pipeline's vertices: {@code format} in quads or triangles. */
    public static RenderPipeline.Builder vertices(RenderPipeline.Builder builder, VertexFormat format, boolean quads) {
        return builder.withVertexBinding(0, format).withPrimitiveTopology(quads ? PrimitiveTopology.QUADS : PrimitiveTopology.TRIANGLES);
    }

    /** A pipeline drawn into the world: the level's matrices and projection. */
    public static RenderPipeline.Builder world() {
        return RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.GLOBALS).withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION);
    }

    /** The vertex format of the pipeline's vertices. */
    public static VertexFormat format(RenderPipeline pipeline) {
        return pipeline.getVertexFormatBinding(0);
    }

    /** An RGBA texture of 8 bits per channel. */
    public static GpuTexture texture(String label, int usage, int width, int height, int mipLevels) {
        return RenderSystem.getDevice().createTexture(label, usage, GpuFormat.RGBA8_UNORM, width, height, 1, mipLevels);
    }

    /** The whole image into the texture's mip level {@code level}. */
    public static void write(CommandEncoder encoder, GpuTexture texture, NativeImage image, int level) {
        encoder.writeToTexture(texture, image, level, 0, 0, 0);
    }

    // 26.2 has no Tesselator: the batches share one buffer of their own (one batch at a time, as the Tesselator's).
    private static ByteBufferBuilder shared;

    private static ByteBufferBuilder shared() {
        if (shared == null) shared = new ByteBufferBuilder(786432);
        return shared;
    }

    /** A batch of the pipeline's vertices (one at a time: a shared buffer). */
    public static BufferBuilder begin(RenderPipeline pipeline) {
        return new BufferBuilder(shared(), pipeline.getPrimitiveTopology(), pipeline.getVertexFormatBinding(0));
    }

    /** A batch of {@code format} in quads or triangles. */
    public static BufferBuilder begin(boolean quads, VertexFormat format) {
        return new BufferBuilder(shared(), quads ? PrimitiveTopology.QUADS : PrimitiveTopology.TRIANGLES, format);
    }

    public static boolean quads(MeshData mesh) {
        return mesh.drawState().primitiveTopology() == PrimitiveTopology.QUADS;
    }

    private static final Optional<Vector4fc> TRANSPARENT = Optional.of(new Vector4f(0f, 0f, 0f, 0f));

    /** A pass into {@code color}, cleared to transparent first when {@code clear}. */
    public static RenderPass pass(CommandEncoder encoder, String label, GpuTextureView color, boolean clear) {
        return encoder.createRenderPass(() -> label, color, clear ? TRANSPARENT : Optional.empty());
    }

    /** A pass into {@code color} tested against {@code depth}, nothing cleared. */
    public static RenderPass pass(CommandEncoder encoder, String label, GpuTextureView color, GpuTextureView depth) {
        return encoder.createRenderPass(() -> label, color, Optional.empty(), depth, OptionalDouble.empty());
    }

    /** Draws with {@code pipeline} in the pass. */
    public static void use(RenderPass pass, RenderPipeline pipeline) {
        pass.setPipeline(pipeline);
    }

    /** The texture {@code view} sampled by {@code sampler} as the shader's {@code name}. */
    public static void bind(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
        pass.bindTexture(name, view, sampler);
    }

    /** A mesh uploaded for drawing: made before the pass ({@link #upload}), drawn in it ({@link #draw}); reused. */
    public static final class Upload {
        private GpuBuffer vertices, indices;
        private IndexType type;
        private int count;
    }

    // The vertices of every draw, written again before each (like the game's weather): grown when too small.
    private static GpuBuffer vertexBuffer;

    public static void upload(Upload into, MeshData mesh, VertexFormat format) {
        ByteBuffer data = mesh.vertexBuffer();
        if (vertexBuffer == null || vertexBuffer.size() < data.remaining()) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = RenderSystem.getDevice().createBuffer(() -> "Teyvat Cartographia vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, Math.max(65536, data.remaining() * 2L));
        }
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(vertexBuffer.slice(), data);
        into.vertices = vertexBuffer;
        into.count = mesh.drawState().indexCount();
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology());
        into.indices = indices.getBuffer(into.count);
        into.type = indices.type();
    }

    public static void draw(RenderPass pass, Upload mesh) {
        pass.setVertexBuffer(0, mesh.vertices.slice());
        pass.setIndexBuffer(mesh.indices, mesh.type);
        pass.drawIndexed(mesh.count, 1, 0, 0, 0);
    }
}
