package net.tapacywka.teyvatcartographia.config;

import net.tapacywka.aacs.api.BoolValue;
import net.tapacywka.aacs.api.ConfigFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The boxes of the pins and the paths: none ticked turns the whole off, the first ticked again comes alone, the beam needs the marker. */
class PinPartsTest {
    private static final int BEAM = 3, MARKER = 2;

    private BoolValue whole;
    private BoolValue[] parts;

    private void kind() {
        ConfigFile.Builder b = ConfigFile.builder();
        whole = b.bool("enabled", true);
        parts = new BoolValue[] {b.bool("map", true), b.bool("minimap", true), b.bool("marker", true), b.bool("beam", true)};
        b.build();
    }

    @Test
    void clearingEveryBoxTurnsTheKindOff() {
        kind();
        for (int i = parts.length - 1; i >= 0; i--) PinParts.set(whole, parts, i, false, BEAM, MARKER);
        assertFalse(whole.get());
    }

    @Test
    void aBeamWithoutItsMarkerShowsNothing() {
        kind();
        PinParts.set(whole, parts, 0, false, BEAM, MARKER);
        PinParts.set(whole, parts, 1, false, BEAM, MARKER);
        PinParts.set(whole, parts, MARKER, false, BEAM, MARKER);
        assertFalse(whole.get(), "only the beam left, without its marker: the kind is off");
    }

    @Test
    void theFirstBoxAfterOffComesAlone() {
        kind();
        whole.set(false);
        PinParts.set(whole, parts, 1, true, BEAM, MARKER);
        assertTrue(whole.get() && parts[1].get());
        assertFalse(parts[0].get() || parts[2].get() || parts[3].get(), "the parts of before do not come back");
    }

    @Test
    void tickingTheBeamTicksItsMarker() {
        kind();
        whole.set(false);
        PinParts.set(whole, parts, BEAM, true, BEAM, MARKER);
        assertTrue(parts[BEAM].get() && parts[MARKER].get());
    }

    @Test
    void aPathHasNoPartThatNeedsAnother() {
        kind();
        PinParts.set(whole, parts, 0, false, -1, -1);
        assertTrue(whole.get());
        assertFalse(parts[0].get());
    }
}
