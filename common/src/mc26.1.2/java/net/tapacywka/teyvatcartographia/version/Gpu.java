package net.tapacywka.teyvatcartographia.version;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import com.mojang.blaze3d.shaders.UniformType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The GPU calls that changed between the 26.x games: Minecraft 26.1.2's side (mc26.2 and mc26.3 have the others). */
public final class Gpu {
    private Gpu() {}

    /** Light added to the picture (glows, sparkles). */
    public static final BlendFunction ADD_LIGHT = new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE);

    /** The pipeline's vertices: {@code format} in quads or triangles. */
    public static RenderPipeline.Builder vertices(RenderPipeline.Builder builder, VertexFormat format, boolean quads) {
        return builder.withVertexFormat(format, quads ? VertexFormat.Mode.QUADS : VertexFormat.Mode.TRIANGLES);
    }

    /** A pipeline drawn into the world: the level's matrices and projection. */
    public static RenderPipeline.Builder world() {
        return RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET);
    }

    /** The vertex format of the pipeline's vertices. */
    public static VertexFormat format(RenderPipeline pipeline) {
        return pipeline.getVertexFormat();
    }

    /** An RGBA texture of 8 bits per channel. */
    public static GpuTexture texture(String label, int usage, int width, int height, int mipLevels) {
        return RenderSystem.getDevice().createTexture(() -> label, usage, TextureFormat.RGBA8, width, height, 1, mipLevels);
    }

    /** The whole image into the texture's mip level {@code level}. */
    public static void write(CommandEncoder encoder, GpuTexture texture, NativeImage image, int level) {
        encoder.writeToTexture(texture, image, level, 0, 0, 0, image.getWidth(), image.getHeight(), 0, 0);
    }

    /** A batch of the pipeline's vertices (one at a time: the game's shared buffer). */
    public static BufferBuilder begin(RenderPipeline pipeline) {
        return Tesselator.getInstance().begin(pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
    }

    /** A batch of {@code format} in quads or triangles. */
    public static BufferBuilder begin(boolean quads, VertexFormat format) {
        return Tesselator.getInstance().begin(quads ? VertexFormat.Mode.QUADS : VertexFormat.Mode.TRIANGLES, format);
    }

    public static boolean quads(MeshData mesh) {
        return mesh.drawState().mode() == VertexFormat.Mode.QUADS;
    }

    /** A pass into {@code color}, cleared to transparent first when {@code clear}. */
    public static RenderPass pass(CommandEncoder encoder, String label, GpuTextureView color, boolean clear) {
        return encoder.createRenderPass(() -> label, color, clear ? OptionalInt.of(0) : OptionalInt.empty());
    }

    /** A pass into {@code color} tested against {@code depth}, nothing cleared. */
    public static RenderPass pass(CommandEncoder encoder, String label, GpuTextureView color, GpuTextureView depth) {
        return encoder.createRenderPass(() -> label, color, OptionalInt.empty(), depth, OptionalDouble.empty());
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
        private VertexFormat.IndexType type;
        private int count;
    }

    public static void upload(Upload into, MeshData mesh, VertexFormat format) {
        into.vertices = format.uploadImmediateVertexBuffer(mesh.vertexBuffer());
        into.count = mesh.drawState().indexCount();
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(mesh.drawState().mode());
        into.indices = indices.getBuffer(into.count);
        into.type = indices.type();
    }

    public static void draw(RenderPass pass, Upload mesh) {
        pass.setVertexBuffer(0, mesh.vertices);
        pass.setIndexBuffer(mesh.indices, mesh.type);
        pass.drawIndexed(0, 0, mesh.count, 1);
    }

    /** The edge shader's bindings beside the GUI's own: its second texture and its block of values. */
    public static RenderPipeline.Builder edgeBindings(RenderPipeline.Builder builder, String sampler, String uniforms) {
        return builder.withSampler(sampler).withUniform(uniforms, UniformType.UNIFORM_BUFFER);
    }

    /** A small block of float values for a pipeline, written whole before each pass that binds it. */
    public static final class Uniforms {
        private final GpuBuffer buffer;
        private final ByteBuffer data;

        public Uniforms(String label, int floats) {
            buffer = RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, floats * 4L);
            data = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder());
        }

        /** Writes the values (outside a render pass). */
        public void write(CommandEncoder encoder, float[] values) {
            data.clear();
            for (float v : values) data.putFloat(v);
            data.flip();
            encoder.writeToBuffer(buffer.slice(), data);
        }

        public void bind(RenderPass pass, String name) {
            pass.setUniform(name, buffer);
        }
    }
}
