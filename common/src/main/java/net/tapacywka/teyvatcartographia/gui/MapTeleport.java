package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.marker.MarkerType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * "Teleport" of the world map's context menu: the vanilla /tp in the viewed dimension, sent for an operator (the server
 * checks the rights again). Where the map does not know the ground (unexplored, the height {@link #UNKNOWN}) the server
 * finds it: in the Nether (under its roof) and the End /spreadplayers (it refuses the void and lava, then the player
 * stays); elsewhere the player goes up to {@link #HIGH} over the spot first (the server loads the chunk; "positioned
 * over" needs it loaded) and down onto its top block once the chunk has reached this client ({@link #tick}).
 */
public final class MapTeleport {
    private MapTeleport() {}

    /** The height of ground the map does not know. */
    static final int UNKNOWN = Integer.MIN_VALUE;
    /** Height of the first step of a teleport to unexplored ground, and how long the second step waits (ticks). */
    static final int HIGH = 320, LAND_WAIT = 200;

    private static ResourceKey<Level> landDim;
    private static int landX, landZ, landTicks;

    /**
     * The middle of block {@code b} as a command reads it: -21 is -20.5, not "-21" and ".5" (the middle of -22); one
     * decimal, so a coordinate in the millions is no "1.0E7".
     */
    static String mid(int b) {
        return String.format(Locale.ROOT, "%.1f", b + 0.5);
    }

    /** The command that goes there, or up over the spot when its ground is not known and there is no other way. */
    static String command(ResourceKey<Level> dim, int bx, int y, int bz) {
        String in = "execute in " + dim.location() + " ";
        if (y != UNKNOWN) return in + "run tp @s " + mid(bx) + " " + y + " " + mid(bz);
        if (dim == Level.NETHER) return in + "run spreadplayers " + mid(bx) + " " + mid(bz) + " 0 8 under 120 false @s";
        if (dim == Level.END) return in + "run spreadplayers " + mid(bx) + " " + mid(bz) + " 0 8 false @s";
        return in + "run tp @s " + mid(bx) + " " + HIGH + " " + mid(bz);
    }

    /** Whether {@link #command} only went up, so the player is to be set down once the chunk is here. */
    static boolean twoSteps(ResourceKey<Level> dim, int y) {
        return y == UNKNOWN && dim != Level.NETHER && dim != Level.END;
    }

    /** The second step: onto the top block of the column. */
    static String landCommand(ResourceKey<Level> dim, int bx, int bz) {
        return "execute in " + dim.location() + " positioned " + mid(bx) + " 0 " + mid(bz) + " positioned over motion_blocking run tp @s ~ ~ ~";
    }

    /** Sends the teleport; true if it was sent (the map closes then). */
    static boolean to(ResourceKey<Level> dim, int bx, int y, int bz) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !MarkerType.isOperator()) return false; // re-checked: /deop meanwhile
        p.connection.sendCommand(command(dim, bx, y, bz));
        if (twoSteps(dim, y)) {
            landDim = dim;
            landX = bx;
            landZ = bz;
            landTicks = LAND_WAIT;
        }
        return true;
    }

    /** Client tick: the second step of a teleport to unexplored ground, onto the top block, once its chunk is here. */
    public static void tick(Minecraft mc) {
        if (landTicks <= 0) return;
        landTicks--;
        LocalPlayer p = mc.player;
        if (p == null || p.level().dimension() != landDim || !p.level().hasChunk(landX >> 4, landZ >> 4)
                || Math.abs(p.getX() - landX - 0.5) > 1 || Math.abs(p.getZ() - landZ - 0.5) > 1) return;
        landTicks = 0;
        p.connection.sendCommand(landCommand(landDim, landX, landZ));
    }
}
