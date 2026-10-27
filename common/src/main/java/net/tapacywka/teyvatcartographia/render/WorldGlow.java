package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Light drawn into the world (the marker's beam, the path stars): camera-relative position + colour vertices, added to
 * the scene (additive blending; the beam's tint over it with normal blending), hidden by terrain (depth test) without hiding anything (no depth writes), both faces.
 */
public final class WorldGlow {
    private WorldGlow() {}

    /** Starts a batch of {@code mode} (quads or triangles). */
    public static BufferBuilder begin(VertexFormat.Mode mode) {
        return Tesselator.getInstance().begin(mode, DefaultVertexFormat.POSITION_COLOR);
    }

    /** Draws the batch added to the scene (nothing when it is empty) and restores the default state. */
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
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, additive ? GlStateManager.DestFactor.ONE : GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
