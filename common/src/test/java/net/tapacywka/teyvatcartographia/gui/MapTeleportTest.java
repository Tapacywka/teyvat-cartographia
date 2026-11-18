package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The commands of the world map's "Teleport", and the context menu's rows on empty ground. */
class MapTeleportTest {
    private static final ResourceKey<Level> AETHER = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("aether", "the_aether"));

    @Test
    void knownGroundOneStep() {
        assertEquals("execute in minecraft:overworld run tp @s 10.5 65 21.5", MapTeleport.command(Level.OVERWORLD, 10, 65, 21));
        assertFalse(MapTeleport.twoSteps(Level.OVERWORLD, 65));
    }

    @Test
    void unknownGroundUpFirstThenDown() {
        assertEquals("execute in minecraft:overworld run tp @s 10.5 320 21.5", MapTeleport.command(Level.OVERWORLD, 10, MapTeleport.UNKNOWN, 21));
        assertTrue(MapTeleport.twoSteps(Level.OVERWORLD, MapTeleport.UNKNOWN));
        assertEquals("execute in minecraft:overworld positioned 10.5 0 21.5 positioned over motion_blocking run tp @s ~ ~ ~",
                MapTeleport.landCommand(Level.OVERWORLD, 10, 21));
        assertTrue(MapTeleport.command(AETHER, 1, MapTeleport.UNKNOWN, 2).startsWith("execute in aether:the_aether run tp @s 1.5 320 2.5"),
                "a modded dimension like the Overworld");
        assertTrue(MapTeleport.twoSteps(AETHER, MapTeleport.UNKNOWN));
    }

    @Test
    void netherAndEndLetTheServerFindTheGround() {
        assertEquals("execute in minecraft:the_nether run spreadplayers 10.5 21.5 0 8 under 120 false @s",
                MapTeleport.command(Level.NETHER, 10, MapTeleport.UNKNOWN, 21));
        assertEquals("execute in minecraft:the_end run spreadplayers 10.5 21.5 0 8 false @s",
                MapTeleport.command(Level.END, 10, MapTeleport.UNKNOWN, 21));
        assertFalse(MapTeleport.twoSteps(Level.NETHER, MapTeleport.UNKNOWN));
        assertFalse(MapTeleport.twoSteps(Level.END, MapTeleport.UNKNOWN));
        assertEquals("execute in minecraft:the_nether run tp @s 10.5 70 21.5", MapTeleport.command(Level.NETHER, 10, 70, 21),
                "known ground in the Nether: straight there");
    }

    /** Block -21 runs from -21 to -20: its middle is -20.5 (written as the block and ".5" it was -21.5, block -22's). */
    @Test
    void negativeBlocksTheirOwnMiddle() {
        assertEquals("execute in minecraft:overworld run tp @s -9.5 65 -20.5", MapTeleport.command(Level.OVERWORLD, -10, 65, -21));
        assertEquals("execute in minecraft:overworld run tp @s -0.5 320 0.5", MapTeleport.command(Level.OVERWORLD, -1, MapTeleport.UNKNOWN, 0));
        assertEquals("execute in minecraft:the_nether run spreadplayers -9.5 -20.5 0 8 under 120 false @s",
                MapTeleport.command(Level.NETHER, -10, MapTeleport.UNKNOWN, -21));
        assertEquals("execute in minecraft:overworld positioned -9.5 0 -20.5 positioned over motion_blocking run tp @s ~ ~ ~",
                MapTeleport.landCommand(Level.OVERWORLD, -10, -21));
    }

    @Test
    void farCoordinatesStayPlainNumbers() {
        assertEquals("29999999.5", MapTeleport.mid(29_999_999));
        assertEquals("-29999999.5", MapTeleport.mid(-30_000_000));
        assertEquals("10000000.5", MapTeleport.mid(10_000_000));
    }

    @Test
    void groundRowsTeleportOnlyForOperators() {
        Runnable pin = () -> {}, tp = () -> {};
        List<MapContextMenu.Item> player = MapContextMenu.forGround(pin, null), op = MapContextMenu.forGround(pin, tp);
        assertEquals(1, player.size());
        assertEquals(2, op.size());
        assertEquals(pin, op.get(0).action());
        assertEquals(tp, op.get(1).action());
    }
}
