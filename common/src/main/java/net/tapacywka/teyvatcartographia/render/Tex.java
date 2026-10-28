package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * A texture as the mod draws it: its view and how it is sampled (since 26.1 the filter and the edges belong to a
 * sampler, not to the texture).
 */
public record Tex(GpuTextureView view, GpuSampler sampler) {
    /** A texture sampled with its edges clamped, smoothed ({@code linear}) or sharp. */
    public static Tex clamped(GpuTextureView view, boolean linear) {
        return new Tex(view, RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST));
    }

    /** A texture repeated over the area it is drawn on (the fog of war). */
    public static Tex repeated(GpuTextureView view, boolean linear) {
        return new Tex(view, RenderSystem.getSamplerCache().getRepeat(linear ? FilterMode.LINEAR : FilterMode.NEAREST));
    }

    public static Tex clamped(AbstractTexture texture, boolean linear) {
        return clamped(texture.getTextureView(), linear);
    }

    TextureSetup setup() {
        return TextureSetup.singleTexture(view, sampler);
    }
}
