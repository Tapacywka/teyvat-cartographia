package net.tapacywka.teyvatcartographia.marker;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins and the import (no world open, so nothing is saved): the limit of 300, repeats left out, interactive pins
 * for every player, ids kept unless taken, the shared pins of an old single player world read.
 */
class ImportRulesTest {
    @BeforeEach
    @SuppressWarnings("unchecked")
    void noPins() throws ReflectiveOperationException {
        Field f = MarkerStore.class.getDeclaredField("markers");
        f.setAccessible(true);
        ((List<Marker>) f.get(null)).clear();
    }

    private static Marker pin(String name, MarkerType type, int x) {
        return Marker.custom(name, type, Level.OVERWORLD, x, 64, 0);
    }

    @Test
    void pinsStopAt300() {
        for (int i = 0; i < MarkerStore.MAX_MARKERS; i++) assertTrue(MarkerStore.add(pin("Pin " + i, MarkerType.STAR, i)));
        assertEquals(300, MarkerStore.count());
        assertFalse(MarkerStore.add(pin("One more", MarkerType.STAR, 5000)), "the 301st is refused");
    }

    @Test
    void importLeavesOutRepeats() {
        MarkerStore.add(pin("Camp", MarkerType.DEMON, 10));
        List<Marker> file = List.of(pin("Camp", MarkerType.DEMON, 10), pin("Camp", MarkerType.DEMON, 11), pin("Other", MarkerType.DEMON, 10));
        MarkerStore.Added r = MarkerStore.addAll(file);
        assertEquals(2, r.added(), "same name elsewhere and another name here are new");
        assertEquals(1, r.duplicates(), "same place and name");
        assertEquals(3, MarkerStore.count());
    }

    @Test
    void repeatsInsideOneFileCountOnce() {
        MarkerStore.Added r = MarkerStore.addAll(List.of(pin("A", MarkerType.STAR, 1), pin("A", MarkerType.STAR, 1)));
        assertEquals(1, r.added());
        assertEquals(1, r.duplicates());
    }

    @Test
    void importStopsAtTheLimit() {
        for (int i = 0; i < MarkerStore.MAX_MARKERS - 2; i++) MarkerStore.add(pin("Pin " + i, MarkerType.STAR, i));
        List<Marker> file = new ArrayList<>();
        for (int i = 0; i < 5; i++) file.add(pin("New " + i, MarkerType.STAR, 1000 + i));
        MarkerStore.Added r = MarkerStore.addAll(file);
        assertEquals(2, r.added());
        assertEquals(3, r.overLimit());
        assertEquals(MarkerStore.MAX_MARKERS, MarkerStore.count());
    }

    @Test
    void interactivePinsForEveryPlayer() {
        List<Marker> file = List.of(pin("Statue", MarkerType.PARTY, 1), pin("Camp", MarkerType.DEMON, 2));
        assertEquals(2, MarkerStore.addAll(file).added(), "no operator rights needed since 24.10.2026");
    }

    @Test
    void theSharedPinsOfAnOldWorldAreRead() {
        // As a 0.1.2 server saved them: data/teyvatcartographia_pins.dat = {DataVersion, data: {pins: [...]}}.
        Marker sign = pin("Sign", MarkerType.SIGN, 4);
        sign.band = -1;
        net.minecraft.nbt.CompoundTag data = new net.minecraft.nbt.CompoundTag();
        data.put("pins", Marker.CODEC.listOf().encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, List.of(sign, pin("Party", MarkerType.PARTY, 5))).getOrThrow());
        net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
        root.putInt("DataVersion", 3955);
        root.put("data", data);
        List<Marker> read = MarkerStore.readOldShared(root);
        assertEquals(2, read.size());
        assertEquals(sign.id, read.get(0).id);
        assertEquals(-1, read.get(0).band);
        assertEquals(MarkerType.PARTY, read.get(1).type);
        assertTrue(MarkerStore.readOldShared(new net.minecraft.nbt.CompoundTag()).isEmpty(), "a file without pins: none");
    }

    @Test
    void anImportedPinKeepsItsIdUnlessItIsTaken() {
        Marker mine = pin("Mine", MarkerType.PICKAXE, 5);
        MarkerStore.add(mine);
        Marker sameId = new Marker(mine.id, "Elsewhere", MarkerType.PICKAXE, Level.OVERWORLD, 900, 64, 900, 0, false);
        Marker fresh = pin("Fresh", MarkerType.STAR, 7);
        MarkerStore.addAll(List.of(sameId, fresh));
        assertTrue(MarkerStore.find(fresh.id) != null, "its own id");
        Marker copy = null;
        for (Marker m : MarkerStore.all()) if (m.name.equals("Elsewhere")) copy = m;
        assertTrue(copy != null);
        assertNotEquals(mine.id, copy.id, "a new id when the id is taken");
        assertEquals("Mine", MarkerStore.find(mine.id).name, "the pin already here is unchanged");
    }

    @Test
    void theCaveLayerComesAlong() {
        Marker cave = pin("Cave", MarkerType.STAR, 3);
        cave.band = -2;
        MarkerStore.addAll(List.of(cave));
        assertEquals(-2, MarkerStore.find(cave.id).band);
    }
}
