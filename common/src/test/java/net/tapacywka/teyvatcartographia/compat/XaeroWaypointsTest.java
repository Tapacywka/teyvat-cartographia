package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Xaero's Minimap waypoint files: waypoints become star pins, death points and other lines are left out. */
class XaeroWaypointsTest {
    @TempDir
    Path dir;

    private static String line(String name, String initials, int x, String y, int z, int type) {
        return "waypoint:" + name + ":" + initials + ":" + x + ":" + y + ":" + z + ":5:false:" + type + ":gui.xaero_default:false:0:0:false";
    }

    @Test
    void aWaypointBecomesAStarPin() {
        Marker m = XaeroWaypoints.parse(line("Home", "H", 10, "64", -20, 0), Level.OVERWORLD);
        assertEquals("Home", m.name);
        assertEquals(MarkerType.STAR, m.type);
        assertEquals(Level.OVERWORLD, m.dimension);
        assertEquals(10, m.x);
        assertEquals(64, m.y);
        assertEquals(-20, m.z);
    }

    @Test
    void deathPointsAreLeftOut() {
        assertNull(XaeroWaypoints.parse(line("gui.xaero_deathpoint", "D", 1, "64", 1, 1), Level.OVERWORLD));
        assertNull(XaeroWaypoints.parse(line("gui.xaero_deathpoint_old", "D", 1, "64", 1, 2), Level.OVERWORLD));
    }

    @Test
    void noHeightAColonInTheNameAndAnEmptyName() {
        assertFalse(XaeroWaypoints.parse(line("Far", "F", 1, "~", 1, 0), Level.OVERWORLD).hasY());
        assertEquals("Base: north", XaeroWaypoints.parse(line("Base§§ north", "B", 1, "64", 1, 0), Level.OVERWORLD).name);
        assertEquals("X", XaeroWaypoints.parse(line("", "X", 1, "64", 1, 0), Level.OVERWORLD).name);
    }

    @Test
    void otherAndBrokenLinesAreLeftOut() {
        assertNull(XaeroWaypoints.parse("#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination", Level.OVERWORLD));
        assertNull(XaeroWaypoints.parse("sets:gui.xaero_default", Level.OVERWORLD));
        assertNull(XaeroWaypoints.parse("waypoint:Bad:B:not_a_number:64:1:5:false:0", Level.OVERWORLD));
        assertNull(XaeroWaypoints.parse("waypoint:Short:S:1:64", Level.OVERWORLD));
        assertNull(XaeroWaypoints.parse("", Level.OVERWORLD));
    }

    @Test
    void dimensionFolders() {
        assertEquals(Level.OVERWORLD, XaeroWaypoints.dimension(Path.of("dim%0")));
        assertEquals(Level.NETHER, XaeroWaypoints.dimension(Path.of("dim%-1")));
        assertEquals(Level.END, XaeroWaypoints.dimension(Path.of("dim%1")));
        assertEquals(ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("twilightforest", "twilight_forest")),
                XaeroWaypoints.dimension(Path.of("dim%twilightforest$twilight_forest")));
        assertNull(XaeroWaypoints.dimension(Path.of("waypoints")));
        assertNull(XaeroWaypoints.dimension(Path.of("dim%nonsense")));
    }

    @Test
    void aWholeWorldFolder() throws IOException {
        Path over = Files.createDirectories(dir.resolve("dim%0")), nether = Files.createDirectories(dir.resolve("dim%-1"));
        Files.createDirectories(dir.resolve("config"));
        Files.write(over.resolve("mw$default_1.txt"), List.of("#comment", "sets:gui.xaero_default", line("Home", "H", 1, "64", 2, 0),
                line("gui.xaero_deathpoint", "D", 3, "64", 4, 1)), StandardCharsets.UTF_8);
        Files.write(nether.resolve("mw$default_1.txt"), List.of(line("Portal", "P", 8, "70", 8, 0)), StandardCharsets.UTF_8);
        Files.writeString(over.resolve("notes.dat"), "not waypoints", StandardCharsets.UTF_8);
        List<Marker> pins = XaeroWaypoints.read(dir, XaeroWaypoints.Options.DEFAULT);
        assertEquals(2, pins.size());
        assertTrue(pins.stream().anyMatch(m -> m.name.equals("Home") && m.dimension == Level.OVERWORLD));
        assertTrue(pins.stream().anyMatch(m -> m.name.equals("Portal") && m.dimension == Level.NETHER));
        // One dimension folder alone, and the world folder of a picked file.
        assertEquals(1, XaeroWaypoints.read(nether, XaeroWaypoints.Options.DEFAULT).size());
        assertEquals(dir.toAbsolutePath(), XaeroWaypoints.worldFolderOf(over.resolve("mw$default_1.txt")));
        assertNull(XaeroWaypoints.worldFolderOf(dir.resolve("config/x.txt")));
    }

    @Test
    void theImportSettingsChooseTheTypeHiddenWaypointsAndDeathPoints() {
        String hidden = line("Old base", "O", 1, "64", 1, 0).replace(":5:false:", ":5:true:");
        String death = line("gui.xaero_deathpoint", "D", 7, "70", 8, 1);
        XaeroWaypoints.Options asIs = XaeroWaypoints.Options.DEFAULT;
        assertEquals("Old base", XaeroWaypoints.parse(hidden, Level.OVERWORLD, asIs).name, "hidden ones come by default");
        assertNull(XaeroWaypoints.parse(death, Level.OVERWORLD, asIs), "death points do not");
        var mine = new XaeroWaypoints.Options(MarkerType.FLOWER, false, true, "Death point");
        assertNull(XaeroWaypoints.parse(hidden, Level.OVERWORLD, mine), "hidden ones left out");
        Marker d = XaeroWaypoints.parse(death, Level.OVERWORLD, mine);
        assertEquals("Death point", d.name, "a death point gets a readable name");
        assertEquals(MarkerType.FLOWER, d.type);
        assertEquals(MarkerType.FLOWER, XaeroWaypoints.parse(line("Home", "H", 1, "64", 1, 0), Level.OVERWORLD, mine).type);
    }
}
