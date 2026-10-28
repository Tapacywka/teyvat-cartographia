package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;

/**
 * The GPU copy of an image the caller keeps (a map region, a LOD tile): unlike the game's DynamicTexture it never
 * closes the image. Sampled with clamped edges, smoothed or sharp ({@link #tex}).
 */
public final class GpuImage {
    private final GpuTexture texture;
    private final GpuTextureView view;
    private final Tex sharp, smooth;

    /** A texture of the image's size with the image uploaded. */
    public GpuImage(String label, NativeImage image) {
        RenderSystem.assertOnRenderThread();
        texture = RenderSystem.getDevice().createTexture(() -> label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8, image.getWidth(), image.getHeight(), 1, 1);
        view = RenderSystem.getDevice().createTextureView(texture);
        sharp = Tex.clamped(view, false);
        smooth = Tex.clamped(view, true);
        upload(image);
    }

    /** Uploads the image again (same size). */
    public void upload(NativeImage image) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
    }

    public Tex tex(boolean linear) {
        return linear ? smooth : sharp;
    }

    public void close() {
        view.close();
        texture.close();
    }
}
