package net.tapacywka.teyvatcartographia.marker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.UUID;

/** A point on the map bound to a dimension. Custom markers are mutable (name/type) and persisted. */
public final class Marker {
    public final UUID id;
    public String name;
    public MarkerType type;
    public final ResourceKey<Level> dimension;
    public final int x, z;
    /** Block Y if known, otherwise {@link Integer#MIN_VALUE}. */
    public final int y;
    public final long created;
    /** The cave layer the pin belongs to (a band of {@code CaveScanner}), or null for the surface. */
    public Integer band;
    /** True for automatic, non-persisted points (spawn, last death). */
    public final boolean automatic;

    public Marker(UUID id, String name, MarkerType type, ResourceKey<Level> dimension, int x, int y, int z, long created, boolean automatic) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.created = created;
        this.automatic = automatic;
    }

    /** A saved pin, as read by {@link #CODEC}. */
    private static Marker saved(UUID id, String name, MarkerType type, ResourceKey<Level> dimension, int x, int y, int z, long created, Optional<Integer> band) {
        Marker m = new Marker(id, name, type, dimension, x, y, z, created, false);
        m.band = band.orElse(null);
        return m;
    }

    /**
     * A saved pin in {@code markers.json}. The optional fields are lenient: a broken one is dropped, not the pin; an
     * unknown height is left out.
     */
    public static final Codec<Marker> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(m -> m.id),
            Codec.STRING.fieldOf("name").forGetter(m -> m.name),
            MarkerType.PIN_CODEC.fieldOf("type").forGetter(m -> m.type),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(m -> m.dimension),
            Codec.INT.fieldOf("x").forGetter(m -> m.x),
            Codec.INT.lenientOptionalFieldOf("y", Integer.MIN_VALUE).forGetter(m -> m.y),
            Codec.INT.fieldOf("z").forGetter(m -> m.z),
            Codec.LONG.lenientOptionalFieldOf("created", 0L).forGetter(m -> m.created),
            Codec.INT.lenientOptionalFieldOf("band").forGetter(m -> Optional.ofNullable(m.band))
    ).apply(i, Marker::saved));

    public static Marker custom(String name, MarkerType type, ResourceKey<Level> dimension, int x, int y, int z) {
        return new Marker(UUID.randomUUID(), name, type, dimension, x, y, z, System.currentTimeMillis(), false);
    }

    public boolean hasY() {
        return y != Integer.MIN_VALUE;
    }

    public double centerX() {
        return x + 0.5;
    }

    public double centerZ() {
        return z + 0.5;
    }
}
