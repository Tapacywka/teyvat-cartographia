package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;

/**
 * GPU texture for the icons with premultiplied alpha and a hand-built mip chain (64 -> 32 -> 16 -> 8 px per cell),
 * sampled trilinearly, so icons stay smooth at any on-screen size and have no dark fringes.
 * Draw with blend function (ONE, ONE_MINUS_SRC_ALPHA) and a premultiplied vertex colour (see {@link Draw#icon}).
 * <p>
 * Icons of the active theme: {@code assets/teyvatcartographia/themes/<theme>/icons/64/<name>.png}, 64 x 64 each;
 * missing ones fall back to the default {@code minecraft} theme. They are stitched into one atlas ({@link Icon} order) when
 * first used and again after a theme change or a resource reload ({@link #invalidate}).
 */
public final class IconAtlas {
    private IconAtlas() {}

    private static final int MAX_LEVEL = 3; // 8 px cells; the 8 px cell padding keeps levels from bleeding
    private static int textureId = -1;

    public static int textureId() {
        RenderSystem.assertOnRenderThread();
        if (textureId == -1) textureId = load();
        return textureId;
    }

    /** Drops the texture; the next draw stitches the (possibly changed) theme icons again. */
    public static void invalidate() {
        RenderSystem.assertOnRenderThread();
        if (textureId != -1) {
            TextureUtil.releaseTextureId(textureId);
            textureId = -1;
        }
    }

    private static int load() {
        int id = TextureUtil.generateTextureId();
        try {
            NativeImage base = stitch();
            premultiply(base);
            TextureUtil.prepareImage(id, MAX_LEVEL, base.getWidth(), base.getHeight());
            NativeImage level = base;
            for (int l = 0; l <= MAX_LEVEL; l++) {
                GlStateManager._bindTexture(id);
                level.upload(l, 0, 0, 0, 0, level.getWidth(), level.getHeight(), true, true, true, false);
                if (l < MAX_LEVEL) {
                    NativeImage next = halve(level);
                    level.close();
                    level = next;
                }
            }
            level.close();
            GlStateManager._bindTexture(id);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } catch (Exception e) {
            TeyvatCartographia.LOGGER.error("Could not build the icon atlas", e);
        }
        return id;
    }

    /** Copies every icon into its cell of a transparent atlas; missing or wrongly sized icons stay empty. */
    private static NativeImage stitch() {
        int cell = Icon.CELL;
        NativeImage atlas = new NativeImage(Icon.ATLAS_W, Icon.ATLAS_H, true);
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        for (Icon icon : Icon.values()) {
            String file = "icons/64/" + icon.name().toLowerCase(Locale.ROOT) + ".png";
            ResourceLocation loc = Theme.asset(file);
            Optional<Resource> res = rm.getResource(loc);
            if (res.isEmpty() && !Theme.id().equals(Theme.DEFAULT)) {
                loc = Theme.asset(Theme.DEFAULT, file); // a theme may leave icons out
                res = rm.getResource(loc);
            }
            if (res.isEmpty()) {
                TeyvatCartographia.LOGGER.warn("Missing icon {}", loc);
                continue;
            }
            try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in)) {
                if (img.getWidth() != cell || img.getHeight() != cell) {
                    TeyvatCartographia.LOGGER.warn("Icon {} must be {}x{} px, is {}x{}", loc, cell, cell, img.getWidth(), img.getHeight());
                    continue;
                }
                int ox = (icon.ordinal() % Icon.COLUMNS) * cell, oy = (icon.ordinal() / Icon.COLUMNS) * cell;
                for (int y = 0; y < cell; y++) {
                    for (int x = 0; x < cell; x++) atlas.setPixelRGBA(ox + x, oy + y, img.getPixelRGBA(x, y));
                }
            } catch (Exception e) {
                TeyvatCartographia.LOGGER.warn("Could not read icon {}", loc, e);
            }
        }
        return atlas;
    }

    /** Converts straight alpha to premultiplied alpha (NativeImage pixels are ABGR ints). */
    private static void premultiply(NativeImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getPixelRGBA(x, y);
                int a = (c >>> 24) & 0xFF;
                int b = ((c >> 16) & 0xFF) * a / 255, g = ((c >> 8) & 0xFF) * a / 255, r = (c & 0xFF) * a / 255;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
    }

    /** 2x2 box filter (correct for premultiplied data). */
    private static NativeImage halve(NativeImage src) {
        int w = src.getWidth() / 2, h = src.getHeight() / 2;
        NativeImage dst = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 0, b = 0, g = 0, r = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int c = src.getPixelRGBA(x * 2 + dx, y * 2 + dy);
                        a += (c >>> 24) & 0xFF;
                        b += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        r += c & 0xFF;
                    }
                }
                dst.setPixelRGBA(x, y, ((a + 2) / 4 << 24) | ((b + 2) / 4 << 16) | ((g + 2) / 4 << 8) | (r + 2) / 4);
            }
        }
        return dst;
    }
}
