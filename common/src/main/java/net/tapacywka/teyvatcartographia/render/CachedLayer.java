package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * An off-screen texture that a HUD or screen part is drawn into only when its inputs change, then composited every
 * frame as one textured quad (the performance budget relies on this). The owner keeps the cache key; this class
 * keeps the texture: {@link #bind} before a redraw, {@link #unbind} after it.
 * <p>
 * Since 26.1 the game's GUI is collected first and drawn later, and only ever into the game's own frame. While a layer
 * is bound, {@link Draw} and {@link DrawText} hand their elements to it instead of to the GUI; {@link #unbind} draws
 * them into the texture at once with the game's GUI pipelines, so the texture is ready before the GUI that shows it.
 * As before, the whole GUI coordinate space of the screen spans the texture (callers scale their part onto it).
 */
public final class CachedLayer {
    private GpuTexture texture;
    private GpuTextureView view;
    private Tex tex;
    private boolean linear;
    private final List<GuiElementRenderState> elements = new ArrayList<>();

    /**
     * Starts a redraw at {@code pw} x {@code ph} physical pixels: creates or resizes the texture and collects what is
     * drawn until {@link #unbind}.
     */
    public void bind(GuiGraphicsExtractor g, int pw, int ph, boolean linear) {
        if (texture == null || texture.getWidth(0) != pw || texture.getHeight(0) != ph) {
            destroy();
            texture = RenderSystem.getDevice().createTexture(() -> "Teyvat Cartographia layer",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RGBA8, pw, ph, 1, 1);
            view = RenderSystem.getDevice().createTextureView(texture);
            tex = null;
        }
        if (tex == null || this.linear != linear) tex = Tex.clamped(view, linear);
        this.linear = linear;
        elements.clear();
        Draw.pushLayer(this);
    }

    /** An element drawn while the layer is bound. */
    void add(GuiElementRenderState element) {
        elements.add(element);
    }

    /** Ends a redraw: draws what was collected into the texture (cleared to transparent first). */
    public void unbind() {
        Draw.popLayer(this);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(texture, 0);
        if (elements.isEmpty()) return;
        Window window = Minecraft.getInstance().getWindow();
        float guiW = (float) (window.getWidth() / window.getGuiScale()), guiH = (float) (window.getHeight() / window.getGuiScale());
        // The game's GUI projection (as GuiRenderer sets it), spanning the screen's GUI size over the texture.
        PROJECTION.setupOrtho(1000f, 11000f, guiW, guiH, true);
        RenderSystem.backupProjectionMatrix();
        if (projectionBuffer == null) projectionBuffer = new ProjectionMatrixBuffer("Teyvat Cartographia layer");
        RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(PROJECTION), ProjectionType.ORTHOGRAPHIC);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(MODEL_VIEW, COLOR, OFFSET, TEXTURE_MATRIX);
        try {
            int pw = texture.getWidth(0), ph = texture.getHeight(0);
            for (int i = 0; i < elements.size(); ) {
                GuiElementRenderState first = elements.get(i);
                RenderPipeline pipeline = first.pipeline();
                TextureSetup textures = first.textureSetup();
                ScreenRectangle scissor = first.scissorArea();
                BufferBuilder buffer = Tesselator.getInstance().begin(pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
                int j = i;
                // Consecutive elements of one pipeline, texture and clip go into one draw.
                while (j < elements.size()) {
                    GuiElementRenderState e = elements.get(j);
                    if (e.pipeline() != pipeline || !e.textureSetup().equals(textures) || !Objects.equals(e.scissorArea(), scissor)) break;
                    e.buildVertices(buffer);
                    j++;
                }
                i = j;
                MeshData mesh = buffer.build();
                if (mesh != null) draw(encoder, mesh, pipeline, textures, scissor, transforms, pw, ph, guiW, guiH);
            }
        } finally {
            RenderSystem.restoreProjectionMatrix();
            elements.clear();
        }
    }

    private void draw(CommandEncoder encoder, MeshData mesh, RenderPipeline pipeline, TextureSetup textures, ScreenRectangle scissor,
                      GpuBufferSlice transforms, int pw, int ph, float guiW, float guiH) {
        try (mesh) {
            GpuBuffer vertices = pipeline.getVertexFormat().uploadImmediateVertexBuffer(mesh.vertexBuffer());
            int count = mesh.drawState().indexCount();
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(mesh.drawState().mode());
            GpuBuffer indexBuffer = indices.getBuffer(count);
            VertexFormat.IndexType indexType = indices.type();
            try (RenderPass pass = encoder.createRenderPass(() -> "Teyvat Cartographia layer", view, OptionalInt.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                if (textures.texure0() != null) pass.bindTexture("Sampler0", textures.texure0(), textures.sampler0());
                if (textures.texure1() != null) pass.bindTexture("Sampler1", textures.texure1(), textures.sampler1());
                if (textures.texure2() != null) pass.bindTexture("Sampler2", textures.texure2(), textures.sampler2());
                if (scissor != null) {
                    float sx = pw / guiW, sy = ph / guiH;
                    pass.enableScissor((int) (scissor.left() * sx), (int) (ph - scissor.bottom() * sy),
                            Math.max(0, (int) (scissor.width() * sx)), Math.max(0, (int) (scissor.height() * sy)));
                }
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indexBuffer, indexType);
                pass.drawIndexed(0, 0, count, 1);
            }
        }
    }

    public boolean exists() {
        return texture != null;
    }

    /** The texture with its sampler (smoothed or sharp as bound). */
    public Tex tex() {
        return tex;
    }

    public void destroy() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null;
        texture = null;
        tex = null;
    }

    private static final Projection PROJECTION = new Projection();
    // Made on first use: the GPU device does not exist yet when the HUD layers are created.
    private static ProjectionMatrixBuffer projectionBuffer;
    // The model view of the game's GUI: drawn at z -11000 between its near and far planes.
    private static final Matrix4f MODEL_VIEW = new Matrix4f().setTranslation(0f, 0f, -11000f), TEXTURE_MATRIX = new Matrix4f();
    private static final Vector4f COLOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f OFFSET = new Vector3f();
}
