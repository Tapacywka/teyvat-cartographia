package net.tapacywka.teyvatcartographia.config;

/**
 * An on/off setting with its kinds as one choice with arrows (the settings screen): option 0 is off, the later ones
 * name what it shows. The settings behind it stay the separate values they were, so older settings files keep
 * working. Only the clock since 13.11.2026: the pins and their paths, steps until then, are boxes ({@link PinParts}).
 */
public final class StepChoice {
    private StepChoice() {}

    /** The clock: off, then each time source in order (game, real, both). */
    public static int clock(boolean shown, MapConfig.TimeSource source) {
        return shown ? 1 + source.ordinal() : 0;
    }

    public static MapConfig.TimeSource clockSource(int step) {
        return MapConfig.TimeSource.values()[Math.max(0, Math.min(MapConfig.TimeSource.values().length - 1, step - 1))];
    }
}
