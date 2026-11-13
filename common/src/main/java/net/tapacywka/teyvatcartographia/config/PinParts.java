package net.tapacywka.teyvatcartographia.config;

import net.tapacywka.aacs.api.BoolValue;

/**
 * The parts of a setting shown as check boxes (user decision 13.11.2026: the pins' and the paths' steps became a box per
 * place): a switch of the whole ({@code whole}, a kind of pins on or off) and the places it shows in ({@code parts}).
 * A box reads as ticked while the whole is on and its part too; with none ticked the whole goes off, and the first box
 * ticked again starts from that one alone (the parts of before do not come back with it). {@code needs} names a part
 * that counts only with another one (the beam with the marker), -1 for none.
 */
public final class PinParts {
    private PinParts() {}

    /** Ticks or clears box {@code i}; a part that needs another ticks that one too. */
    public static void set(BoolValue whole, BoolValue[] parts, int i, boolean on, int needs, int needed) {
        if (on) {
            if (!whole.get()) {
                for (BoolValue p : parts) p.set(false);
                whole.set(true);
            }
            parts[i].set(true);
            if (i == needs) parts[needed].set(true);
            return;
        }
        parts[i].set(false);
        if (!anyOn(parts, needs, needed)) whole.set(false);
    }

    /** Whether a part shows anything: one ticked that does not need another, or does and has it. */
    static boolean anyOn(BoolValue[] parts, int needs, int needed) {
        for (int j = 0; j < parts.length; j++) if (parts[j].get() && (j != needs || parts[needed].get())) return true;
        return false;
    }
}
