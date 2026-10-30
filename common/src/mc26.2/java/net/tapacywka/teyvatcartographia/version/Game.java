package net.tapacywka.teyvatcartographia.version;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;

/** The parts of the game that moved between the 26.x games: Minecraft 26.2's side (mc26.1.2 and mc26.3 have the others). */
public final class Game {
    private Game() {}

    public static Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    public static Overlay overlay() {
        return Minecraft.getInstance().gui.overlay();
    }

    /** F1: the HUD hidden. */
    public static boolean hideGui() {
        return Minecraft.getInstance().gui.hud.isHidden();
    }

    public static void setHideGui(boolean hidden) {
        if (Minecraft.getInstance().gui.hud.isHidden() != hidden) Minecraft.getInstance().gui.hud.toggle();
    }

    public static ChatComponent chat() {
        return Minecraft.getInstance().gui.hud.getChat();
    }

    public static Camera camera() {
        return Minecraft.getInstance().gameRenderer.mainCamera();
    }

    public static RenderTarget mainTarget() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }

    public static GameRenderState renderState() {
        return Minecraft.getInstance().gameRenderer.gameRenderState();
    }

    /** Every chunk section built again (after a graphics mode change). */
    public static void allChanged() {
        Minecraft.getInstance().levelExtractor.allChanged();
    }

    /** Fabulous: the particles' own target, else null. */
    public static RenderTarget particlesTarget() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameRenderer.gameRenderState().useShaderTransparency() ? mc.levelRenderer.particlesTarget() : null;
    }

    /** A PNG read as RGBA. */
    public static NativeImage readImage(InputStream in) throws IOException {
        return NativeImage.read(NativeImage.Format.RGBA, in);
    }

    /** Solid for movement (as the game asks it for suffocation and the like). */
    public static boolean blocksMotion(BlockState state) {
        return state.blocksMotion();
    }

    /** The key's second code: its scancode (26.3: its layout's keycode). */
    public static int scancode(KeyEvent event) {
        return event.scancode();
    }

    public static boolean fullscreen() {
        return Minecraft.getInstance().getWindow().isFullscreen();
    }

    public static void toggleFullscreen() {
        Minecraft.getInstance().getWindow().toggleFullScreen();
    }
}
