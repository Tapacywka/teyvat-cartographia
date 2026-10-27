package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.tapacywka.teyvatcartographia.map.data.BiomeIndex;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.marker.AutoMarkers;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.render.MapLayerRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The search of the pin list (one per world map opening): pins of every dimension by name (the own ones and the
 * automatic points) and the explored biomes by name ({@link BiomeIndex}). Pins are matched at once; the places of
 * the matching biomes are worked out on the search thread and arrive a moment later. Main thread only, apart from
 * that job.
 */
public final class MapSearch {
    /** At most this many biomes are listed (a short query matches many). */
    private static final int MAX_BIOMES = 12;
    /** How often {@link #update} looks again (it is called every frame while the list is open). */
    private static final long CHECK_NANOS = 100_000_000L, PINS_NANOS = 500_000_000L;

    /** A found biome: its places nearest first, its map colour, and which place the last click went to (-1: none yet). */
    public static final class BiomeHit {
        public final ResourceKey<Level> dimension;
        public final ResourceKey<Biome> biome;
        public final List<BiomeIndex.Place> places;
        public final int color;
        int current = -1;

        BiomeHit(ResourceKey<Level> dimension, ResourceKey<Biome> biome, List<BiomeIndex.Place> places, int color) {
            this.dimension = dimension;
            this.biome = biome;
            this.places = places;
            this.color = color;
        }

        /** The place shown in the row: the one the last click went to, else the nearest. */
        public BiomeIndex.Place shown() {
            return places.get(Math.max(0, current));
        }

        /** Number (from 1) of the place shown in the row. */
        public int shownNumber() {
            return Math.max(0, current) + 1;
        }

        public Component name() {
            return MapLayerRenderer.biomeName(biome);
        }
    }

    /** A biome to find the places of: its dimension and that dimension's index state. */
    private record Wanted(ResourceKey<Level> dimension, BiomeIndex.Snapshot snapshot, ResourceKey<Biome> biome) {}

    /** The places of the found biomes for one request (written by the search thread). */
    private record Answer(int generation, List<BiomeHit> hits) {}

    private String query = "";
    private String needle = "";
    private List<Marker> pins = List.of();
    private List<BiomeHit> biomes = List.of();
    /** Which place each biome's clicks reached, kept while the results are worked out again. */
    private final Map<String, Integer> cursors = new HashMap<>();
    /** Changes whenever the results change (the panel rebuilds its rows). */
    private int version;
    /** The dimensions with a map; read (with the index refresh) once per opening, at the first search. */
    private List<ResourceKey<Level>> dims;
    // The biome request: the query and the index states it was made for, and its generation (a newer one makes
    // the search thread drop an older job).
    private String requestNeedle;
    private final List<BiomeIndex.Snapshot> requestSnapshots = new ArrayList<>();
    private volatile int generation;
    private volatile Answer answer;
    private boolean reading, waiting;
    private int pinsStamp = Integer.MIN_VALUE;
    private long pinsNanos, checkNanos;

    public String query() {
        return query;
    }

    public boolean active() {
        return !needle.isEmpty();
    }

    public int version() {
        return version;
    }

    public List<Marker> pins() {
        return pins;
    }

    public List<BiomeHit> biomes() {
        return biomes;
    }

    /** True while the explored map is still being read or the places of the found biomes are being worked out. */
    public boolean searchingBiomes() {
        return active() && (waiting || reading);
    }

    public void setQuery(String q) {
        if (q.equals(query)) return;
        query = q;
        needle = needle(q);
        pinsStamp = Integer.MIN_VALUE;
        checkNanos = 0;
        update();
    }

    /** Lower case, and "ё" as "е" (Russian names are often typed without it). */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static boolean matches(String name, String needle) {
        return normalize(name).contains(needle);
    }

    /** Brings the results up to date; looks again at most ten times a second. Called by the open pin list. */
    public void update() {
        long now = System.nanoTime();
        if (now - checkNanos < CHECK_NANOS) return;
        checkNanos = now;
        if (!active()) {
            if (!pins.isEmpty() || !biomes.isEmpty()) {
                pins = List.of();
                biomes = List.of();
                version++;
            }
            return;
        }
        updatePins(now);
        updateBiomes();
    }

    /** A found pin with its sort key worked out once. */
    private record PinHit(Marker marker, boolean here, String dimension, double distance) {}

    private void updatePins(long now) {
        // Again when pins change, and twice a second for the distance order.
        if (MarkerStore.version() == pinsStamp && now - pinsNanos < PINS_NANOS) return;
        pinsStamp = MarkerStore.version();
        pinsNanos = now;
        LocalPlayer p = Minecraft.getInstance().player;
        List<Marker> all = new ArrayList<>();
        for (int i = 0, n = MarkerStore.count(); i < n; i++) all.add(MarkerStore.get(i));
        all.addAll(AutoMarkers.everywhere());
        List<Marker> list = p == null ? match(all, needle, null, 0, 0, 0)
                : match(all, needle, p.level().dimension(), p.getX(), p.getY(), p.getZ());
        // By id: the automatic points are made anew each time, and an equal list must not rebuild the rows.
        boolean same = list.size() == pins.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).id.equals(pins.get(i).id);
        if (!same) version++;
        pins = list;
    }

    /**
     * The pins whose names hold {@code needle} (normalized): those of the player's dimension {@code here} (null: no
     * player) first, nearest to (px, py, pz) first; then the others by dimension and name.
     */
    static List<Marker> match(List<Marker> pins, String needle, ResourceKey<Level> here, double px, double py, double pz) {
        List<PinHit> found = new ArrayList<>();
        for (Marker m : pins) {
            if (!matches(m.name, needle)) continue;
            boolean h = here != null && m.dimension == here;
            found.add(new PinHit(m, h, h ? "" : m.dimension.location().toString(), h ? Navigation.distance(px, py, pz, m.centerX(), m.y, m.centerZ()) : 0));
        }
        found.sort(Comparator.comparing((PinHit h) -> !h.here).thenComparing(PinHit::dimension).thenComparingDouble(PinHit::distance)
                .thenComparing(h -> h.marker.name, String.CASE_INSENSITIVE_ORDER));
        List<Marker> list = new ArrayList<>(found.size());
        for (PinHit h : found) list.add(h.marker);
        return list;
    }

    /** A query as the search compares it: trimmed, lower case, "ё" as "е". */
    static String needle(String query) {
        return normalize(query.trim());
    }

    private void updateBiomes() {
        MapDataManager maps = MapDataManager.get();
        // The first search of an opening reads the map folders and the regions saved since the last time.
        boolean refresh = dims == null;
        if (refresh) dims = maps.knownDimensions();
        List<BiomeIndex.Snapshot> snapshots = new ArrayList<>(dims.size());
        reading = false;
        for (ResourceKey<Level> d : dims) {
            BiomeIndex index = maps.biomeIndex(d, refresh);
            BiomeIndex.Snapshot s = index == null ? null : index.snapshot();
            reading |= index != null && (index.busy() || s == null);
            snapshots.add(s);
        }
        Answer a = answer;
        if (a != null && a.generation == generation) {
            answer = null;
            for (BiomeHit h : a.hits) {
                Integer c = cursors.get(cursorKey(h));
                if (c != null && c < h.places.size()) h.current = c;
            }
            biomes = a.hits;
            version++;
            waiting = false;
        }
        if (needle.equals(requestNeedle) && sameSnapshots(snapshots)) return;
        requestNeedle = needle;
        requestSnapshots.clear();
        requestSnapshots.addAll(snapshots);
        waiting = true;
        // Names are looked up here (the language lives on the main thread); places on the search thread.
        List<Wanted> wanted = new ArrayList<>();
        for (int i = 0; i < dims.size() && wanted.size() < MAX_BIOMES; i++) {
            BiomeIndex.Snapshot s = snapshots.get(i);
            if (s == null) continue;
            for (ResourceKey<Biome> b : s.palette()) {
                if (wanted.size() >= MAX_BIOMES) break;
                String path = b.location().getPath().replace('_', ' ');
                if (matches(MapLayerRenderer.biomeName(b).getString(), needle) || matches(path, needle)) wanted.add(new Wanted(dims.get(i), s, b));
            }
        }
        LocalPlayer p = Minecraft.getInstance().player;
        ResourceKey<Level> here = p == null ? null : p.level().dimension();
        double px = p == null ? 0 : p.getX(), pz = p == null ? 0 : p.getZ();
        int job = ++generation;
        BiomeIndex.run(() -> {
            List<BiomeHit> hits = new ArrayList<>();
            for (Wanted w : wanted) {
                if (job != generation) return; // typed on: a newer request is queued behind this one
                boolean h = w.dimension == here;
                BiomeIndex.Places places = BiomeIndex.places(w.snapshot, w.biome, h ? px : 0, h ? pz : 0);
                if (!places.places().isEmpty()) hits.add(new BiomeHit(w.dimension, w.biome, places.places(), places.color()));
            }
            // The player's dimension first, by the distance to the nearest place.
            hits.sort(Comparator.comparing((BiomeHit h) -> h.dimension != here)
                    .thenComparingDouble(h -> h.dimension == here ? Math.hypot(h.places.get(0).x() - px, h.places.get(0).z() - pz) : 0));
            answer = new Answer(job, hits);
        });
    }

    private boolean sameSnapshots(List<BiomeIndex.Snapshot> snapshots) {
        if (snapshots.size() != requestSnapshots.size()) return false;
        for (int i = 0; i < snapshots.size(); i++) if (snapshots.get(i) != requestSnapshots.get(i)) return false;
        return true;
    }

    private static String cursorKey(BiomeHit h) {
        return h.dimension.location() + "|" + h.biome.location();
    }

    /** A click on a biome row: the next place (the nearest the first time). Returns the place to fly to. */
    public BiomeIndex.Place next(BiomeHit h) {
        h.current = (h.current + 1) % h.places.size();
        cursors.put(cursorKey(h), h.current);
        version++;
        return h.places.get(h.current);
    }
}
