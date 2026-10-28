package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

/**
 * One draw of the mod's own triangles as a GUI element: the game sorts it among its own elements by its bounds (one
 * overlapping an earlier element goes above it) and batches it with others of the same pipeline and texture.
 */
final class Shape implements GuiElementRenderState {
    private final RenderPipeline pipeline;
    private final TextureSetup textures;
    private final float[] data;
    private final int vertices;
    private final boolean textured;
    private final ScreenRectangle scissor, bounds;

    private Shape(RenderPipeline pipeline, TextureSetup textures, float[] data, int vertices, boolean textured,
                  ScreenRectangle scissor, ScreenRectangle bounds) {
        this.pipeline = pipeline;
        this.textures = textures;
        this.data = data;
        this.vertices = vertices;
        this.textured = textured;
        this.scissor = scissor;
        this.bounds = bounds;
    }

    /** The mesh's triangles as an element cut to {@code scissor} (null: none), or null when nothing of it is visible. */
    static Shape of(Mesh mesh, RenderPipeline pipeline, TextureSetup textures, boolean textured, ScreenRectangle scissor) {
        int n = mesh.vertices;
        if (n < 3) return null;
        float[] d = mesh.data;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0, k = 0; i < n; i++, k += Mesh.STRIDE) {
            x0 = Math.min(x0, d[k]);
            x1 = Math.max(x1, d[k]);
            y0 = Math.min(y0, d[k + 1]);
            y1 = Math.max(y1, d[k + 1]);
        }
        int bx = (int) Math.floor(x0), by = (int) Math.floor(y0);
        ScreenRectangle bounds = new ScreenRectangle(bx, by, Math.max(1, (int) Math.ceil(x1) - bx), Math.max(1, (int) Math.ceil(y1) - by));
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
            if (bounds == null) return null;
        }
        return new Shape(pipeline, textures, java.util.Arrays.copyOf(d, n * Mesh.STRIDE), n, textured, scissor, bounds);
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        // Each triangle as a quad with its third corner twice: the game's GUI renderer indexes every draw as quads.
        for (int i = 0; i + 2 < vertices; i += 3) {
            vertex(consumer, i);
            vertex(consumer, i + 1);
            vertex(consumer, i + 2);
            vertex(consumer, i + 2);
        }
    }

    private void vertex(VertexConsumer consumer, int i) {
        float[] d = data;
        int k = i * Mesh.STRIDE;
        consumer.addVertex(d[k], d[k + 1], 0f);
        if (textured) consumer.setUv(d[k + 2], d[k + 3]);
        consumer.setColor(Float.floatToRawIntBits(d[k + 4]));
    }

    @Override
    public RenderPipeline pipeline() {
        return pipeline;
    }

    @Override
    public TextureSetup textureSetup() {
        return textures;
    }

    @Override
    public ScreenRectangle scissorArea() {
        return scissor;
    }

    @Override
    public ScreenRectangle bounds() {
        return bounds;
    }
}
