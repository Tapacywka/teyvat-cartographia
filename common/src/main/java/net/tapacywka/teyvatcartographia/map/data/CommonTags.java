package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * The common "c:" block tags the map reads (ores for the cave map, natural ground for the cave mode): the same ids as
 * NeoForge's {@code Tags.Blocks} and Fabric's {@code ConventionalBlockTags}, filled by both loaders and by mods.
 */
public final class CommonTags {
    private CommonTags() {}

    public static final TagKey<Block> ORES = c("ores");
    public static final TagKey<Block> DIAMOND_ORES = c("ores/diamond");
    public static final TagKey<Block> EMERALD_ORES = c("ores/emerald");
    public static final TagKey<Block> LAPIS_ORES = c("ores/lapis");
    public static final TagKey<Block> STONES = c("stones");
    public static final TagKey<Block> GRAVELS = c("gravels");
    public static final TagKey<Block> SANDS = c("sands");
    public static final TagKey<Block> NETHERRACKS = c("netherracks");
    public static final TagKey<Block> END_STONES = c("end_stones");

    private static TagKey<Block> c(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", path));
    }
}
