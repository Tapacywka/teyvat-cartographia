package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * Create's railways: Create is not made for 26.x yet, so there is nothing to read (the branch main reads its tracks,
 * stations and trains; this keeps the same names on both branches, for the maps and the in-game tests).
 */
public final class CreateBridge {
    private CreateBridge() {}

    /** A track between two nodes: its points, x and z of each in turn, and the box round them. */
    public record Track(ResourceKey<Level> dimension, float[] xz, float minX, float minZ, float maxX, float maxZ) {}

    /** A station or a signal on a track. */
    public record Point(ResourceKey<Level> dimension, String name, double x, double z) {}

    /** A train as the world map shows it. */
    public static final class Train {
        public UUID id;
        public String name, owner, target;
        public int targetDistance;
        public CreateRules.Look look = CreateRules.Look.MOVING;
        public ResourceKey<Level> dimension;
        public float[] now = new float[0], before = new float[0];
        public int carriages;
    }

    public static boolean loaded() {
        return false;
    }

    public static List<Track> tracks() {
        return List.of();
    }

    public static List<Point> stations() {
        return List.of();
    }

    public static List<Train> trains() {
        return List.of();
    }

    public static int generation() {
        return 0;
    }

    public static float between() {
        return 1;
    }

    public static void forTest(List<Train> testTrains, List<Point> testStations) {}
}
