package net.tapacywka.teyvatcartographia.map.data;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The places of a biome that the map search offers: connected patches, small ones dropped, nearest first. */
class BiomeIndexPlacesTest {
    private static final ResourceKey<Biome> PLAINS = ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("plains"));
    private static final ResourceKey<Biome> FOREST = ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("forest"));
    private static final int N = MapTile.CHUNKS;

    /** One region at the origin, all forest, with plains on the given chunks (x, z pairs) in colour 0xFF102030. */
    private static BiomeIndex.Snapshot snapshot(int... plainsChunks) {
        short[] biome = new short[N * N];
        int[] color = new int[N * N];
        Arrays.fill(biome, (short) 1);
        for (int i = 0; i < plainsChunks.length; i += 2) {
            int k = plainsChunks[i + 1] * N + plainsChunks[i];
            biome[k] = 0;
            color[k] = 0xFF102030;
        }
        Long2ObjectOpenHashMap<BiomeIndex.Region> regions = new Long2ObjectOpenHashMap<>();
        regions.put(0L, new BiomeIndex.Region(0, 0, 0, biome, color));
        return new BiomeIndex.Snapshot(List.of(PLAINS, FOREST), regions);
    }

    /** A square patch of side {@code s} chunks from (x, z). */
    private static int[] square(int x, int z, int s) {
        int[] out = new int[2 * s * s];
        int i = 0;
        for (int dz = 0; dz < s; dz++) for (int dx = 0; dx < s; dx++) {
            out[i++] = x + dx;
            out[i++] = z + dz;
        }
        return out;
    }

    private static int[] concat(int[]... parts) {
        int[] out = new int[Arrays.stream(parts).mapToInt(p -> p.length).sum()];
        int i = 0;
        for (int[] p : parts) for (int v : p) out[i++] = v;
        return out;
    }

    @Test
    void aPatchIsOnePlaceInsideIt() {
        BiomeIndex.Places p = BiomeIndex.places(snapshot(square(2, 2, 3)), PLAINS, 0, 0);
        assertEquals(1, p.places().size());
        BiomeIndex.Place place = p.places().get(0);
        assertEquals(9, place.chunks());
        // The middle chunk (3, 3): block 3 * 16 + 8.
        assertEquals(56, place.x());
        assertEquals(56, place.z());
        assertEquals(0xFF102030, p.color());
    }

    @Test
    void smallPatchesAreDroppedWhenLargerOnesExist() {
        BiomeIndex.Places p = BiomeIndex.places(snapshot(concat(square(2, 2, 3), new int[]{12, 12})), PLAINS, 0, 0);
        assertEquals(1, p.places().size());
        assertEquals(9, p.places().get(0).chunks());
    }

    @Test
    void onlySmallPatchesAreStillOffered() {
        BiomeIndex.Places p = BiomeIndex.places(snapshot(5, 5, 12, 12), PLAINS, 0, 0);
        assertEquals(2, p.places().size());
    }

    @Test
    void chunksTouchingOnlyAtACornerAreTwoPlaces() {
        BiomeIndex.Places p = BiomeIndex.places(snapshot(concat(square(0, 0, 2), square(2, 2, 2))), PLAINS, 0, 0);
        assertEquals(2, p.places().size());
    }

    @Test
    void theNearestPlaceComesFirst() {
        int[] chunks = concat(square(1, 1, 2), square(12, 12, 2));
        List<BiomeIndex.Place> nearOrigin = BiomeIndex.places(snapshot(chunks), PLAINS, 0, 0).places();
        assertTrue(nearOrigin.get(0).x() < 100);
        List<BiomeIndex.Place> nearFar = BiomeIndex.places(snapshot(chunks), PLAINS, 210, 210).places();
        assertTrue(nearFar.get(0).x() > 150);
    }

    @Test
    void aBiomeNotOnTheMapHasNoPlaces() {
        ResourceKey<Biome> desert = ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("desert"));
        BiomeIndex.Places p = BiomeIndex.places(snapshot(square(2, 2, 3)), desert, 0, 0);
        assertTrue(p.places().isEmpty());
        assertEquals(0, p.color());
    }
}
