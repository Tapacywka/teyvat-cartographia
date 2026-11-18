package net.tapacywka.teyvatcartographia.map.data;

/**
 * Whether an entity is underground (the radar's layers), as the player is ({@link Underground}): a run of natural
 * blocks over its head, looked for up to the mapped ground there. At or over the mapped ground it is not; where the
 * client has no blocks (a mob the game's server told of), more than {@link Underground#ROOF} blocks under the mapped
 * ground is. The world is read through {@link Column}, so the rule runs without a game; one roof is reused, so asking
 * allocates nothing. One thread.
 */
final class EntityDepth {
    /** Natural blocks above an entity looked for at most, from its head (less than the player's: many a tick). */
    static final int ROOF_SCAN = 64;
    /** The mapped ground's height where it is not mapped. */
    static final int UNMAPPED = Integer.MIN_VALUE;

    /** Reads a block of the world {@code W}: whether it is natural ground. */
    interface Column<W> {
        boolean natural(W world, int x, int y, int z);
    }

    private final Underground.Roof roof = new Underground.Roof();

    /**
     * Whether an entity in block ({@code x}, {@code y}, {@code z}) is underground: {@code ground} is the mapped
     * ground's height there (or {@link #UNMAPPED}), {@code loaded} whether the client has the blocks there,
     * {@code maxBuild} the world's top.
     */
    <W> boolean underground(W world, Column<W> column, int x, int y, int z, int ground, boolean loaded, int maxBuild) {
        if (ground != UNMAPPED && y >= ground - 1) return false;
        if (!loaded) return ground != UNMAPPED && y < ground - Underground.ROOF;
        int top = Math.min(maxBuild, Math.min(y + 2 + ROOF_SCAN, ground == UNMAPPED ? Integer.MAX_VALUE : ground + 1));
        roof.reset();
        for (int yy = y + 2; yy < top && roof.best() < Underground.ROOF; yy++) roof.add(column.natural(world, x, yy, z));
        return roof.best() >= Underground.ROOF;
    }
}
