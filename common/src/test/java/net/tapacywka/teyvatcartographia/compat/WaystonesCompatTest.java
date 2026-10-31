package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waystones on the maps: which blocks and types are stones, dungeons, the seen stones of a chunk, what shows. */
class WaystonesCompatTest {
    private static final WaystonesCompat.Shown ALL = new WaystonesCompat.Shown(true, true, true, true);

    private static WaystonesCompat.Stone seen(int x, int z, WaystonesCompat.Kind kind, boolean dungeon) {
        return new WaystonesCompat.Stone(Level.OVERWORLD, new BlockPos(x, 70, z), kind, dungeon, null, null);
    }

    private static WaystonesCompat.Stone active(int x, int z, WaystonesCompat.Kind kind, String name) {
        return new WaystonesCompat.Stone(Level.OVERWORLD, new BlockPos(x, 70, z), kind, false, UUID.randomUUID(), name);
    }

    private static List<Marker> markers(Map<Long, WaystonesCompat.Stone> seen, List<WaystonesCompat.Stone> active, WaystonesCompat.Shown shown) {
        return WaystonesCompat.markers(Level.OVERWORLD, seen, active, shown, s -> s.name() == null ? "unnamed" : s.name());
    }

    @Test
    void blockEntitiesAndTypesOfWaystones() {
        assertEquals(WaystonesCompat.Kind.WAYSTONE, WaystonesCompat.kindOfBlockEntity("waystones:waystone"));
        assertEquals(WaystonesCompat.Kind.SHARESTONE, WaystonesCompat.kindOfBlockEntity("waystones:sharestone"));
        assertEquals(WaystonesCompat.Kind.WARP_PLATE, WaystonesCompat.kindOfBlockEntity("waystones:warp_plate"));
        assertNull(WaystonesCompat.kindOfBlockEntity("waystones:portstone"), "kinds the map does not show");
        assertNull(WaystonesCompat.kindOfBlockEntity("minecraft:chest"));
        assertEquals(WaystonesCompat.Kind.WAYSTONE, WaystonesCompat.kindOfType("waystones:waystone"));
        assertEquals(WaystonesCompat.Kind.SHARESTONE, WaystonesCompat.kindOfType("waystones:light_blue_sharestone"), "every dyed sharestone");
        assertEquals(WaystonesCompat.Kind.WARP_PLATE, WaystonesCompat.kindOfType("waystones:warp_plate"));
        assertNull(WaystonesCompat.kindOfType("waystones:fleeting_memorial"));
        assertNull(WaystonesCompat.kindOfType(null));
    }

    @Test
    void theBlockStateSaysDungeonAndHalf() {
        assertEquals(Boolean.TRUE, WaystonesCompat.dungeonOfState("Block{waystones:mossy_waystone}[half=lower,origin=dungeon,waterlogged=false]"));
        assertEquals(Boolean.FALSE, WaystonesCompat.dungeonOfState("Block{waystones:waystone}[half=lower,origin=village,waterlogged=false]"));
        assertNull(WaystonesCompat.dungeonOfState("Block{waystones:waystone}[half=upper,origin=dungeon,waterlogged=false]"), "the lower half stands for the stone");
        assertEquals(Boolean.FALSE, WaystonesCompat.dungeonOfState("Block{waystones:warp_plate}"), "no properties at all");
    }

    @Test
    void aChunkReplacesItsOwnStonesOnly() {
        Map<Long, WaystonesCompat.Stone> seen = new HashMap<>();
        WaystonesCompat.Stone kept = seen(100, 100, WaystonesCompat.Kind.WAYSTONE, false);
        WaystonesCompat.Stone broken = seen(5, 5, WaystonesCompat.Kind.WAYSTONE, false);
        seen.put(kept.key(), kept);
        seen.put(broken.key(), broken);
        WaystonesCompat.Stone placed = seen(8, 9, WaystonesCompat.Kind.SHARESTONE, false);
        Map<Long, WaystonesCompat.Stone> after = WaystonesCompat.replaceChunk(seen, 0, 0, List.of(placed));
        assertTrue(after.containsKey(kept.key()), "another chunk's stone stays");
        assertFalse(after.containsKey(broken.key()), "a stone no longer in its chunk is gone");
        assertTrue(after.containsKey(placed.key()));
        assertEquals(2, seen.size(), "the map given is not changed");
    }

    @Test
    void activatedStonesAreBrightTheOthersGrey() {
        WaystonesCompat.Stone a = active(10, 10, WaystonesCompat.Kind.WAYSTONE, "Home");
        Map<Long, WaystonesCompat.Stone> seen = new HashMap<>();
        WaystonesCompat.Stone sameSpot = seen(10, 10, WaystonesCompat.Kind.WAYSTONE, false);
        WaystonesCompat.Stone other = seen(40, 40, WaystonesCompat.Kind.WAYSTONE, true);
        seen.put(sameSpot.key(), sameSpot);
        seen.put(other.key(), other);
        List<Marker> ms = markers(seen, List.of(a), ALL);
        assertEquals(2, ms.size(), "the seen stone at the activated one's place is not shown twice");
        Marker home = ms.stream().filter(m -> !m.inactive).findFirst().orElseThrow();
        assertEquals("Home", home.name);
        assertEquals(MarkerType.WAYSTONE, home.type);
        assertTrue(home.automatic);
        Marker dungeon = ms.stream().filter(m -> m.inactive).findFirst().orElseThrow();
        assertEquals(MarkerType.DUNGEON, dungeon.type, "a stone of a dungeon is a dungeon");
    }

    @Test
    void theSettingsChooseWhatShows() {
        Map<Long, WaystonesCompat.Stone> seen = new HashMap<>();
        WaystonesCompat.Stone grey = seen(1, 1, WaystonesCompat.Kind.WAYSTONE, false);
        seen.put(grey.key(), grey);
        List<WaystonesCompat.Stone> act = List.of(active(20, 20, WaystonesCompat.Kind.SHARESTONE, "S"),
                active(30, 30, WaystonesCompat.Kind.WARP_PLATE, "P"));
        assertEquals(3, markers(seen, act, ALL).size());
        assertEquals(2, markers(seen, act, new WaystonesCompat.Shown(true, true, true, false)).size(), "no grey ones");
        assertEquals(2, markers(seen, act, new WaystonesCompat.Shown(true, false, true, true)).size(), "no sharestones");
        assertEquals(2, markers(seen, act, new WaystonesCompat.Shown(true, true, false, true)).size(), "no warp plates");
        assertEquals(2, markers(seen, act, new WaystonesCompat.Shown(false, true, true, true)).size(), "no waystones");
        assertEquals(0, markers(null, List.of(), ALL).size(), "a dimension without stones");
    }

    @Test
    void stonesOfAnotherDimensionStayThere() {
        WaystonesCompat.Stone nether = new WaystonesCompat.Stone(Level.NETHER, new BlockPos(0, 64, 0), WaystonesCompat.Kind.WAYSTONE, false,
                UUID.randomUUID(), "N");
        assertEquals(0, markers(null, List.of(nether), ALL).size());
    }

    @Test
    void aBrokenStoneLeavesNoGreyPointBehind() {
        WaystonesCompat.clearForTest();
        WaystonesCompat.Stone a = active(10, 10, WaystonesCompat.Kind.WAYSTONE, "Home");
        WaystonesCompat.listReceived("waystones:waystone", List.of(a));
        WaystonesCompat.seenForTest(seen(10, 10, WaystonesCompat.Kind.WAYSTONE, false));
        assertEquals(1, WaystonesCompat.markersForTest(Level.OVERWORLD).size(), "seen and activated: one point");
        WaystonesCompat.removeReceived(a.id());
        assertEquals(0, WaystonesCompat.markersForTest(Level.OVERWORLD).size(), "removed by Waystones: the seen one goes too");
        WaystonesCompat.removeReceived(UUID.randomUUID());
        WaystonesCompat.clearForTest();
    }

    @Test
    void aListReplacesOnlyStonesOfItsType() {
        WaystonesCompat.clearForTest();
        WaystonesCompat.listReceived("waystones:waystone", List.of(active(1, 1, WaystonesCompat.Kind.WAYSTONE, "A")));
        WaystonesCompat.listReceived("waystones:warp_plate", List.of(active(2, 2, WaystonesCompat.Kind.WARP_PLATE, "P")));
        WaystonesCompat.listReceived("waystones:waystone", List.of(active(3, 3, WaystonesCompat.Kind.WAYSTONE, "B")));
        List<Marker> ms = WaystonesCompat.markersForTest(Level.OVERWORLD);
        assertEquals(2, ms.size(), "the new list of waystones replaced A, the warp plate stayed");
        assertTrue(ms.stream().noneMatch(m -> m.x == 1), "A is gone");
        WaystonesCompat.clearForTest();
    }

    @Test
    void aSeenStoneKeepsItsIdBetweenBuilds() {
        Map<Long, WaystonesCompat.Stone> seen = new HashMap<>();
        WaystonesCompat.Stone s = seen(3, 4, WaystonesCompat.Kind.WAYSTONE, false);
        seen.put(s.key(), s);
        assertEquals(markers(seen, List.of(), ALL).get(0).id, markers(seen, List.of(), ALL).get(0).id,
                "the same id every time, so a selected or tracked stone stays the same");
    }
}
