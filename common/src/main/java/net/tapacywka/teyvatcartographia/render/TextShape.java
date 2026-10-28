package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The glyphs of one prepared text (of one pipeline and font texture) as one GUI element, drawn once or at several
 * offsets (the 8 copies of an outline). Built from the game's own glyphs, so text looks as the game's does, and it can
 * go into a {@link CachedLayer} as well as into the frame.
 */
final class TextShape implements GuiElementRenderState {
    private final Matrix3x2fc pose;
    private final List<TextRenderable> glyphs;
    private final RenderPipeline pipeline;
    private final TextureSetup textures;
    private final float[] offsets;
    private final ScreenRectangle scissor, bounds;

    private TextShape(Matrix3x2fc pose, List<TextRenderable> glyphs, RenderPipeline pipeline, TextureSetup textures, float[] offsets,
                      ScreenRectangle scissor, ScreenRectangle bounds) {
        this.pose = pose;
        this.glyphs = glyphs;
        this.pipeline = pipeline;
        this.textures = textures;
        this.offsets = offsets;
        this.scissor = scissor;
        this.bounds = bounds;
    }

    /** One copy at the text's place. */
    static final float[] ONCE = {0f, 0f};
    /** The 8 neighbours one unit away: the outline of map labels (as the game's 8x outline). */
    static final float[] OUTLINE = {-1, -1, 0, -1, 1, -1, -1, 0, 1, 0, -1, 1, 0, 1, 1, 1};

    /**
     * Hands the prepared text to {@link Draw#submit} as one element per pipeline and font texture, at {@code offsets}
     * (pairs of x, y) from {@code pose}.
     */
    static void submit(net.minecraft.client.gui.GuiGraphicsExtractor g, Font.PreparedText text, Matrix3x2f pose, float[] offsets) {
        ScreenRectangle local = text.bounds();
        if (local == null) return;
        int grow = offsets == OUTLINE ? 1 : 0;
        ScreenRectangle bounds = new ScreenRectangle(local.left() - grow, local.top() - grow, local.width() + 2 * grow, local.height() + 2 * grow)
                .transformMaxBounds(pose);
        ScreenRectangle scissor = Draw.scissor(g);
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
            if (bounds == null) return;
        }
        List<TextRenderable> all = new ArrayList<>();
        text.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptGlyph(TextRenderable.Styled glyph) {
                all.add(glyph);
            }

            @Override
            public void acceptEffect(TextRenderable effect) {
                all.add(effect);
            }
        });
        // Usually one group: every glyph of the vanilla font on one texture with one pipeline.
        while (!all.isEmpty()) {
            TextRenderable first = all.getFirst();
            RenderPipeline pipeline = first.guiPipeline();
            GpuTextureView view = first.textureView();
            List<TextRenderable> group = new ArrayList<>();
            all.removeIf(r -> {
                if (r.guiPipeline() != pipeline || r.textureView() != view) return false;
                group.add(r);
                return true;
            });
            TextureSetup textures = TextureSetup.singleTextureWithLightmap(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            Draw.submit(g, new TextShape(pose, group, pipeline, textures, offsets, scissor, bounds));
        }
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        Matrix4f m = new Matrix4f();
        for (int k = 0; k < offsets.length; k += 2) {
            m.identity().mul(pose).translate(offsets[k], offsets[k + 1], 0f);
            for (TextRenderable glyph : glyphs) glyph.render(m, consumer, LightCoordsUtil.FULL_BRIGHT, false);
        }
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
