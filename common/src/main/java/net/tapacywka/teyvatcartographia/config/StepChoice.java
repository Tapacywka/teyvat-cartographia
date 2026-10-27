package net.tapacywka.teyvatcartographia.config;

/**
 * An on/off setting with its sub-settings as one choice slider (the settings screen): step 0 is off, the later steps
 * switch the sub-settings on one after another. The settings behind it stay the separate values they were, so older
 * settings files keep working; a combination no step gives (e.g. the minimap but not the map) reads as the step of
 * its last sub-setting that is on.
 */
public final class StepChoice {
    private StepChoice() {}

    /**
     * Pins of a kind: off, the map, both maps, in the world (the marker over a tracked one), everywhere (its beam too);
     * the path: off, the map, both maps, everywhere (the stars in the world).
     */
    public static final int OFF = 0, MAP = 1, BOTH_MAPS = 2, EVERYWHERE = 3, IN_WORLD = 3, BEAM = 4;

    /** The step for a kind switched {@code on} whose map, minimap and world (beam or stars) parts are as given. */
    public static int level(boolean on, boolean map, boolean minimap, boolean world) {
        if (!on) return OFF;
        if (world) return EVERYWHERE;
        if (minimap) return BOTH_MAPS;
        return MAP;
    }

    /** The step of pins whose kind is {@code on}, with its map, minimap, marker in the world and beam parts as given. */
    public static int level(boolean on, boolean map, boolean minimap, boolean marker, boolean beam) {
        if (on && marker && beam) return BEAM;
        return level(on, map, minimap, marker);
    }

    /** The parts a step switches on: the kind itself, then the map, the minimap and the world in turn. */
    public static boolean on(int step) {
        return step > OFF;
    }

    public static boolean map(int step) {
        return step >= MAP;
    }

    public static boolean minimap(int step) {
        return step >= BOTH_MAPS;
    }

    public static boolean world(int step) {
        return step >= EVERYWHERE;
    }

    public static boolean beam(int step) {
        return step >= BEAM;
    }

    /** The clock: off, then each time source in order (game, real, both). */
    public static int clock(boolean shown, MapConfig.TimeSource source) {
        return shown ? 1 + source.ordinal() : 0;
    }

    public static MapConfig.TimeSource clockSource(int step) {
        return MapConfig.TimeSource.values()[Math.max(0, Math.min(MapConfig.TimeSource.values().length - 1, step - 1))];
    }
}
