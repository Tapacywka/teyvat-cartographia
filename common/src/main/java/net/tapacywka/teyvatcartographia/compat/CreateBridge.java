package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.platform.Platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the client knows of Create's railways, read by reflection (no build dependency, as {@link WaystonesBridge}):
 * the track graphs Create keeps on the client ({@code CreateClient.RAILWAYS}), read again whenever their
 * {@code version} changes, with their stations and signals; the trains of the whole world as Create's own train map
 * sends them ({@code TrainMapSyncClient}, asked for only while the world map shows them); and the carriages near the
 * player, from the entities the client has anyway. Main thread only. Any other version of Create that does not fit
 * turns this off for the session with one line in the log.
 */
public final class CreateBridge {
    private CreateBridge() {}

    /** A track between two nodes: its points, x and z of each in turn, and the box round them. */
    public record Track(ResourceKey<Level> dimension, float[] xz, float minX, float minZ, float maxX, float maxZ) {
        static Track of(ResourceKey<Level> dimension, float[] xz) {
            float x0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
            for (int i = 0; i < xz.length; i += 2) {
                x0 = Math.min(x0, xz[i]);
                x1 = Math.max(x1, xz[i]);
                z0 = Math.min(z0, xz[i + 1]);
                z1 = Math.max(z1, xz[i + 1]);
            }
            return new Track(dimension, xz, x0, z0, x1, z1);
        }
    }

    /** A station or a signal on a track. */
    public record Point(ResourceKey<Level> dimension, String name, double x, double z) {}

    /** A train as the world map shows it: each carriage its leading and trailing wheels (x, z, x, z), interpolated. */
    public static final class Train {
        public UUID id;
        public String name, owner, target;
        public int targetDistance;
        public CreateRules.Look look = CreateRules.Look.MOVING;
        public ResourceKey<Level> dimension;
        /** Per carriage four numbers: leading x, z, trailing x, z; this update and the one before. */
        public float[] now = new float[0], before = new float[0];
        public int carriages;
        /** When it last moved (System.currentTimeMillis), 0 never. */
        public long movedAt;
    }

    /** A carriage near the player: where it is and which way it points. */
    public record Carriage(double x, double z, float yaw, float length) {}

    private static boolean broken, testing;
    private static int seenVersion = Integer.MIN_VALUE;
    private static final List<Track> tracks = new ArrayList<>();
    private static final List<Point> stations = new ArrayList<>(), signals = new ArrayList<>();
    private static final Map<UUID, Train> trains = new HashMap<>();
    private static final List<Train> trainList = new ArrayList<>();
    private static int generation;
    private static long receivedAt;

    // The reflection, found once.
    private static Field railwaysField, versionField, networksField, trainsField, trainName;
    private static Method getNodes, locateNode, connectionsFrom, edgeLength, edgeTurn, edgeInterDim, edgePosition;
    private static Field locationDim;
    private static Method getPoints, getLocationOn, coupleFirst, coupleSecond, getConnection;
    private static Field stationName, edgeLocation;
    private static Object stationType, signalType;
    private static Class<?> coupleClass;
    private static Method requestData, stopRequesting;
    private static Field currentData, entryPositions, entryDims, entryState, entrySignal, entryOwner, entryTarget, entryDistance;
    private static Class<?> carriageEntity;

    public static final String MOD_ID = "create";

    public static boolean loaded() {
        return Platform.get().isModLoaded(MOD_ID);
    }

    /** The tracks, read again when Create's graphs changed. */
    public static List<Track> tracks() {
        return tracks;
    }

    public static List<Point> stations() {
        return stations;
    }

    public static List<Point> signals() {
        return signals;
    }

    /** The trains Create's train map sent, nothing while it is not asked for. */
    public static List<Train> trains() {
        return trainList;
    }

    /** Raised whenever what is drawn changed: the minimap's cache key. */
    public static int generation() {
        return generation;
    }

    /** How far the trains are between their last two updates (0 to 1), for smooth movement between them. */
    public static float between() {
        return CreateRules.glide(System.currentTimeMillis() - receivedAt);
    }

    private static void off(Throwable e) {
        broken = true;
        tracks.clear();
        stations.clear();
        signals.clear();
        trainList.clear();
        generation++;
        TeyvatCartographia.LOGGER.warn("Create's railways cannot be read, the maps leave them out", e);
    }

    /**
     * The in-game tests: these trains and stations shown in place of Create's (a train needs a station and a hand to
     * assemble it); null gives Create's back.
     */
    public static void forTest(List<Train> testTrains, List<Point> testStations) {
        testing = testTrains != null;
        trainList.clear();
        if (testTrains != null) trainList.addAll(testTrains);
        if (testStations != null) stations.addAll(testStations);
        else seenVersion = Integer.MIN_VALUE;
        generation++;
    }

    /** Once a tick: the graphs read again when they changed, and the trains taken in while they are asked for. */
    public static void tick(Minecraft mc, boolean wantTracks, boolean wantTrains) {
        if (broken || !loaded() || mc.level == null) {
            if (mc.level == null && !tracks.isEmpty()) {
                tracks.clear();
                stations.clear();
                signals.clear();
                seenVersion = Integer.MIN_VALUE;
                generation++;
            }
            return;
        }
        try {
            Object railways = railways();
            if (wantTracks) {
                int version = versionField.getInt(railways);
                if (version != seenVersion) {
                    seenVersion = version;
                    readGraphs(railways);
                    generation++;
                }
            }
            if (wantTrains && !testing) {
                requestData.invoke(null);
                readTrains(railways);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            off(e);
        }
    }

    /** The world map closed or no longer shows trains: Create's train map is no longer asked for. */
    public static void stopTrains() {
        if (broken || testing || !loaded() || stopRequesting == null) return;
        try {
            stopRequesting.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            off(e);
        }
        trains.clear();
        trainList.clear();
    }

    private static Object railways() throws ReflectiveOperationException {
        if (railwaysField == null) {
            railwaysField = Class.forName("com.simibubi.create.CreateClient").getField("RAILWAYS");
            Class<?> manager = railwaysField.getType();
            versionField = manager.getField("version");
            networksField = manager.getField("trackNetworks");
            trainsField = manager.getField("trains");
            Class<?> graph = Class.forName("com.simibubi.create.content.trains.graph.TrackGraph");
            Class<?> location = Class.forName("com.simibubi.create.content.trains.graph.TrackNodeLocation");
            Class<?> node = Class.forName("com.simibubi.create.content.trains.graph.TrackNode");
            Class<?> edge = Class.forName("com.simibubi.create.content.trains.graph.TrackEdge");
            Class<?> pointType = Class.forName("com.simibubi.create.content.trains.graph.EdgePointType");
            Class<?> point = Class.forName("com.simibubi.create.content.trains.signal.TrackEdgePoint");
            getNodes = graph.getMethod("getNodes");
            locateNode = graph.getMethod("locateNode", location);
            connectionsFrom = graph.getMethod("getConnectionsFrom", node);
            getPoints = graph.getMethod("getPoints", pointType);
            edgeLength = edge.getMethod("getLength");
            edgeTurn = edge.getMethod("isTurn");
            edgeInterDim = edge.getMethod("isInterDimensional");
            edgePosition = edge.getMethod("getPosition", graph, double.class);
            locationDim = location.getField("dimension");
            stationType = pointType.getField("STATION").get(null);
            signalType = pointType.getField("SIGNAL").get(null);
            edgeLocation = point.getField("edgeLocation");
            getLocationOn = point.getMethod("getLocationOn", edge);
            stationName = Class.forName("com.simibubi.create.content.trains.station.GlobalStation").getField("name");
            coupleClass = edgeLocation.getType();
            coupleFirst = coupleClass.getMethod("getFirst");
            coupleSecond = coupleClass.getMethod("getSecond");
            trainName = Class.forName("com.simibubi.create.content.trains.entity.Train").getField("name");
            Class<?> sync = Class.forName("com.simibubi.create.compat.trainmap.TrainMapSyncClient");
            requestData = sync.getMethod("requestData");
            stopRequesting = sync.getMethod("stopRequesting");
            currentData = sync.getField("currentData");
            Class<?> entry = Class.forName("com.simibubi.create.compat.trainmap.TrainMapSync$TrainMapSyncEntry");
            entryPositions = entry.getField("positions");
            entryDims = entry.getField("dimensions");
            entryState = entry.getField("state");
            entrySignal = entry.getField("signalState");
            entryOwner = entry.getField("ownerName");
            entryTarget = entry.getField("targetStationName");
            entryDistance = entry.getField("targetStationDistance");
            carriageEntity = Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity");
        }
        return railwaysField.get(null);
    }

    @SuppressWarnings("unchecked")
    private static void readGraphs(Object railways) throws ReflectiveOperationException {
        tracks.clear();
        stations.clear();
        signals.clear();
        Map<UUID, Object> networks = (Map<UUID, Object>) networksField.get(railways);
        // The pieces between nodes, per dimension, joined into lines below (CreateRules.join).
        Map<ResourceKey<Level>, List<float[]>> pieces = new HashMap<>();
        for (Object graph : List.copyOf(networks.values())) {
            Set<Object> nodes = (Set<Object>) getNodes.invoke(graph);
            Map<Object, Integer> ids = new HashMap<>();
            for (Object loc : nodes) {
                Object node = locateNode.invoke(graph, loc);
                if (node != null) ids.putIfAbsent(node, ids.size());
            }
            for (Object loc : nodes) {
                Object node = locateNode.invoke(graph, loc);
                if (node == null) continue;
                @SuppressWarnings("unchecked")
                ResourceKey<Level> dim = (ResourceKey<Level>) locationDim.get(loc);
                Map<Object, Object> out = (Map<Object, Object>) connectionsFrom.invoke(graph, node);
                for (Map.Entry<Object, Object> c : out.entrySet()) {
                    // Each track once: from the node with the lower number.
                    if (ids.getOrDefault(c.getKey(), -1) < ids.get(node)) continue;
                    Object edge = c.getValue();
                    if ((Boolean) edgeInterDim.invoke(edge)) continue;
                    int n = CreateRules.points((Double) edgeLength.invoke(edge), (Boolean) edgeTurn.invoke(edge));
                    float[] xz = new float[n * 2];
                    for (int i = 0; i < n; i++) {
                        Vec3 p = (Vec3) edgePosition.invoke(edge, graph, i / (double) (n - 1));
                        xz[2 * i] = (float) p.x;
                        xz[2 * i + 1] = (float) p.z;
                    }
                    pieces.computeIfAbsent(dim, k -> new ArrayList<>()).add(xz);
                }
            }
            readPoints(graph, stationType, stations, true);
            readPoints(graph, signalType, signals, false);
        }
        for (Map.Entry<ResourceKey<Level>, List<float[]>> e : pieces.entrySet()) {
            for (float[] line : CreateRules.join(e.getValue())) tracks.add(Track.of(e.getKey(), line));
        }
    }

    private static void readPoints(Object graph, Object type, List<Point> into, boolean named) throws ReflectiveOperationException {
        for (Object p : (Collection<?>) getPoints.invoke(graph, type)) {
            Object couple = edgeLocation.get(p);
            if (couple == null) continue;
            Object a = locateNode.invoke(graph, coupleFirst.invoke(couple)), b = locateNode.invoke(graph, coupleSecond.invoke(couple));
            if (a == null || b == null) continue;
            Object edge = ((Map<?, ?>) connectionsFrom.invoke(graph, a)).get(b);
            if (edge == null) continue;
            double length = (Double) edgeLength.invoke(edge);
            double t = length <= 0 ? 0 : (Double) getLocationOn.invoke(p, edge) / length;
            Vec3 pos = (Vec3) edgePosition.invoke(edge, graph, t);
            @SuppressWarnings("unchecked")
            ResourceKey<Level> dim = (ResourceKey<Level>) locationDim.get(coupleFirst.invoke(couple));
            into.add(new Point(dim, named ? (String) stationName.get(p) : null, pos.x, pos.z));
        }
    }

    @SuppressWarnings("unchecked")
    private static void readTrains(Object railways) throws ReflectiveOperationException {
        Map<UUID, Object> data = (Map<UUID, Object>) currentData.get(null);
        Map<UUID, Object> own = (Map<UUID, Object>) trainsField.get(railways);
        boolean changed = false;
        float along = between();
        for (Train t : trains.values()) t.carriages = -1;
        for (Map.Entry<UUID, Object> e : data.entrySet()) {
            Object entry = e.getValue();
            Float[] pos = (Float[]) entryPositions.get(entry);
            if (pos == null) continue;
            Train t = trains.computeIfAbsent(e.getKey(), k -> new Train());
            t.id = e.getKey();
            int carriages = pos.length / 6;
            float[] now = new float[carriages * 4];
            for (int c = 0; c < carriages; c++) {
                now[4 * c] = pos[6 * c];
                now[4 * c + 1] = pos[6 * c + 2];
                now[4 * c + 2] = pos[6 * c + 3];
                now[4 * c + 3] = pos[6 * c + 5];
            }
            boolean moved = CreateRules.moved(t.now, now);
            long clock = System.currentTimeMillis();
            // The first update, or a carriage added, is no move.
            if (moved && t.now.length == now.length) t.movedAt = clock;
            if (moved || t.now.length != now.length) {
                // The new glide starts where the train is drawn now, not at its last position: no jump when an update
                // comes early or late.
                t.before = t.now.length == now.length ? CreateRules.between(t.before, t.now, along) : now;
                t.now = now;
                changed = true;
                receivedAt = System.currentTimeMillis();
            }
            t.carriages = carriages;
            List<ResourceKey<Level>> dims = (List<ResourceKey<Level>>) entryDims.get(entry);
            t.dimension = dims == null || dims.isEmpty() ? null : dims.get(0);
            Object state = entryState.get(entry), signal = entrySignal.get(entry);
            t.look = CreateRules.look(state == null ? null : state.toString(), signal == null ? null : signal.toString(),
                    CreateRules.running(t.movedAt, clock));
            t.owner = (String) entryOwner.get(entry);
            t.target = (String) entryTarget.get(entry);
            t.targetDistance = entryDistance.getInt(entry);
            Object train = own == null ? null : own.get(e.getKey());
            Object name = train == null ? null : trainName.get(train);
            t.name = CreateRules.name(name instanceof Component c ? c.getString() : null);
        }
        if (trains.values().removeIf(t -> t.carriages < 0)) changed = true;
        if (changed || trainList.size() != trains.size()) {
            trainList.clear();
            trainList.addAll(trains.values());
            generation++;
        }
    }

    /** The carriages of trains near the player, from the entities the client has anyway: no request to the server. */
    public static void nearbyCarriages(Minecraft mc, double range, List<Carriage> into) {
        into.clear();
        if (broken || !loaded() || mc.level == null || mc.player == null || carriageEntity == null && !bindCarriages()) return;
        double r2 = range * range;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!carriageEntity.isInstance(e) || e.distanceToSqr(mc.player) > r2) continue;
            // Its box is as long as the carriage along the track it stands on.
            var box = e.getBoundingBox();
            float length = (float) Math.max(2, Math.max(box.getXsize(), box.getZsize()));
            into.add(new Carriage(e.getX(), e.getZ(), e.getYRot(), length));
        }
    }

    private static boolean bindCarriages() {
        try {
            railways();
            return carriageEntity != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            off(e);
            return false;
        }
    }
}
