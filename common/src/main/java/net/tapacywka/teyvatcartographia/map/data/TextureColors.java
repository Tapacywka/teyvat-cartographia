package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.MapColor;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;

import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The colour of each block as its texture shows it, like Xaero's Minimap in its default mode: the average of the
 * non-transparent pixels of the texture on the block's top face. Read again after every resource reload: the models
 * are looked at on the main thread (a few milliseconds), the textures are read and averaged on a background thread.
 * A block whose top face is tinted (grass, leaves) keeps the tint to apply ({@link Tint}); any other tinted block asks
 * its colour handler in the world when mapped ({@link Tint#WORLD}: lily pads, stems, redstone, the leaf litter of Vanilla
 * Backport, the blocks of other mods), as Xaero's Minimap does. Blocks without a model (air, chests) have none: the usual map colour.
 */
public final class TextureColors {
    private TextureColors() {}

    /**
     * Which tint a block's texture still needs: none, the biome's grass or foliage colour (sampled per chunk), or
     * {@code WORLD}: what the block's colour handler gives at its place (asked per column).
     */
    public enum Tint { NONE, GRASS, FOLIAGE, WORLD }

    /**
     * One block: its colour (ARGB; for {@code WORLD} with the tint its handler gives without a world, used where the
     * world cannot be asked, as in caves), the tint to apply, and for {@code WORLD} the untinted texture colour and the
     * tint index to ask the handler for.
     */
    public record Entry(int argb, Tint tint, int texture, int tintIndex) {}

    private static volatile Map<Block, Entry> blocks = Map.of();
    /** Plants whose look changes with their state (crops by age, double plants by half): one colour per state. */
    private static volatile Map<BlockState, Entry> states = Map.of();
    private static volatile int water, lava, snow, ichor;
    /** A reload happened: read again once the game is out of its loading screen (the models are baked by then). */
    private static volatile boolean pending = true;
    /** New colours arrived: the map draws the loaded chunks again. */
    private static volatile boolean fresh;

    /** The texture colour of a block, or null without one. */
    public static Entry get(Block block) {
        return blocks.get(block);
    }

    /**
     * The texture colour of a block in this state: a plant whose look changes with its state (wheat green, then
     * yellow; the flowers on the upper half of a rose bush) has one per state, any other block its block's.
     */
    public static Entry get(BlockState state) {
        Entry e = states.get(state);
        return e != null ? e : blocks.get(state.getBlock());
    }

    /** The water, lava and snow textures (ARGB), 0 until read. */
    public static int water() {
        return water;
    }

    public static int lava() {
        return lava;
    }

    public static int snow() {
        return snow;
    }

    /** The Sift's ichor (its still texture, ARGB), 0 without the mod. */
    public static int ichor() {
        return ichor;
    }

    /** Called after a resource reload (packs, F3+T). */
    public static void reloaded() {
        pending = true;
    }

    /**
     * Once per client tick: starts the reading after a reload, and returns true once when new colours arrived (the
     * caller maps the loaded chunks again).
     */
    public static boolean tick() {
        Minecraft mc = Minecraft.getInstance();
        if (pending && mc.getOverlay() == null) {
            pending = false;
            start(mc);
        }
        if (fresh) {
            fresh = false;
            return true;
        }
        return false;
    }

    private record Found(ResourceLocation texture, Tint tint, int fixedTint, int index) {}

    private static void start(Minecraft mc) {
        RandomSource random = RandomSource.create(42L);
        Map<Block, Found> found = new IdentityHashMap<>();
        Map<BlockState, Found> foundStates = new IdentityHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            try {
                Found f = top(mc, upper(mc, block.defaultBlockState(), random), random);
                if (f != null) found.put(block, f);
            } catch (RuntimeException e) {
                // A model that cannot answer outside a world: the block keeps its map colour.
            }
            // A plant with a few looks (crops by age, double plants by half; at most MAX_STATES, not every state of a
            // mod's block): the colour of each state, the upper half for both halves (seen from above it covers the
            // lower one). Plants by class only: the block tags are not loaded yet.
            var all = block.getStateDefinition().getPossibleStates();
            if (all.size() > 1 && all.size() <= MAX_STATES && Plants.plantByClass(block)) {
                for (BlockState s : all) {
                    try {
                        Found f = top(mc, upper(mc, s, random), random);
                        if (f != null) foundStates.put(s, f);
                    } catch (RuntimeException e) {
                        // as above
                    }
                }
            }
        }
        ResourceManager rm = mc.getResourceManager();
        Util.backgroundExecutor().execute(() -> {
            Map<ResourceLocation, Integer> averages = new HashMap<>();
            Map<Block, Entry> out = new IdentityHashMap<>();
            for (Map.Entry<Block, Found> e : found.entrySet()) {
                Entry en = entry(e.getValue(), averages, rm);
                if (en != null) out.put(e.getKey(), en);
            }
            Map<BlockState, Entry> outStates = new IdentityHashMap<>();
            for (Map.Entry<BlockState, Found> e : foundStates.entrySet()) {
                Entry en = entry(e.getValue(), averages, rm);
                if (en != null) outStates.put(e.getKey(), en);
            }
            water = averages.computeIfAbsent(ResourceLocation.withDefaultNamespace("block/water_still"), t -> read(rm, t));
            lava = averages.computeIfAbsent(ResourceLocation.withDefaultNamespace("block/lava_still"), t -> read(rm, t));
            snow = averages.computeIfAbsent(ResourceLocation.withDefaultNamespace("block/snow"), t -> read(rm, t));
            ichor = averages.computeIfAbsent(ResourceLocation.fromNamespaceAndPath("the_sift", "block/ichor_still"), t -> read(rm, t));
            blocks = out;
            states = outStates;
            fresh = true;
            TeyvatCartographia.LOGGER.info("Teyvat Cartographia: texture colours of {} blocks, {} plant states ({} textures)", out.size(), outStates.size(), averages.size());
        });
    }

    /** At most this many states for the colours per state of one plant (wheat 8, a rose bush 2, sweet berries 4). */
    private static final int MAX_STATES = 32;

    /**
     * The upper half of a double plant (seen from above, it covers the lower one), unless that half draws nothing in
     * this state (a young pitcher crop is one block tall); any other state as it is.
     */
    private static BlockState upper(Minecraft mc, BlockState s, RandomSource random) {
        if (!s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return s;
        BlockState u = s.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        BakedModel m = mc.getBlockRenderer().getBlockModel(u);
        return m.getQuads(u, null, random).isEmpty() && m.getQuads(u, Direction.UP, random).isEmpty() ? s : u;
    }

    /** A colour from its texture (averaged once per texture) and its fixed tint; null when the texture has none. */
    private static Entry entry(Found f, Map<ResourceLocation, Integer> averages, ResourceManager rm) {
        int argb = averages.computeIfAbsent(f.texture(), t -> read(rm, t));
        if (argb == 0) return null;
        int texture = argb;
        if (f.fixedTint() != -1) argb = multiply(argb, f.fixedTint());
        return new Entry(argb, f.tint(), texture, f.index());
    }

    /** The texture on a block's top face and its tint, or null for a block drawn without a model. */
    private static Found top(Minecraft mc, BlockState state, RandomSource random) {
        if (state.getRenderShape() != RenderShape.MODEL) return null;
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        BakedQuad quad = first(model.getQuads(state, Direction.UP, random), true);
        if (quad == null) quad = first(model.getQuads(state, null, random), true);
        if (quad == null) quad = first(model.getQuads(state, null, random), false); // plants: their crossed planes
        TextureAtlasSprite sprite = quad != null ? quad.getSprite() : model.getParticleIcon();
        if (sprite == null) return null;
        int index = quad != null ? quad.getTintIndex() : -1;
        Tint tint = Tint.NONE;
        int fixed = -1;
        MapColor mc0 = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        boolean vanilla = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
        if (index >= 0 && vanilla && state.is(Blocks.SPRUCE_LEAVES)) fixed = 0x619961;
        else if (index >= 0 && vanilla && state.is(Blocks.BIRCH_LEAVES)) fixed = 0x80A755;
        // Mangrove leaves fall through to the biome's foliage: in the world the game tints them so since 1.21 (their fixed
        // colour only without one; on the map they were too light, 24.10.2026).
        // The game's own leaves, vines and grass: the biome's colour, sampled per chunk (fast). Asked per column they
        // gave the same colour at twice the scan time (other mods, like Vanilla Backport, replace their handlers). By class,
        // not tag: the colours are read at the title screen, before the world brings the block tags.
        else if (index >= 0 && vanilla && (state.getBlock() instanceof LeavesBlock || state.is(Blocks.VINE))) tint = leafTint(fixedTint(mc, state, index));
        else if (index >= 0 && vanilla && (mc0 == MapColor.GRASS || GRASSY.contains(state.getBlock()))) tint = Tint.GRASS;
        else if (index >= 0) {
            // What the block's colour handler gives without a world tells the kind of tint. No handler: the game does
            // not tint it (the willow leaves of The Sift keep their cyan texture). The default foliage or grass colour
            // (oak leaves, vines, grass, sugar cane): the biome's, sampled per chunk. A handler that needs a world: a
            // vanilla leaf, plant or grass block takes the biome's; anything else (leaves and grass of mods with a palette
            // of their own, seasons) asks the handler at its place. Any other colour (lily pads, stems, redstone, leaf
            // litter): the handler at its place too, that colour as the fallback.
            int c = fixedTint(mc, state, index);
            int rgb = c & 0xFFFFFF;
            if (c == NEEDS_WORLD) {
                if (vanilla && (state.getBlock() instanceof LeavesBlock || mc0 == MapColor.PLANT && !GRASSY.contains(state.getBlock()))) tint = Tint.FOLIAGE;
                else if (vanilla && (mc0 == MapColor.GRASS || GRASSY.contains(state.getBlock()))) tint = Tint.GRASS;
                else tint = Tint.WORLD;
            } else if (c != -1 && rgb == FoliageColor.getDefaultColor()) tint = Tint.FOLIAGE;
            else if (c != -1 && rgb == GrassColor.getDefaultColor()) tint = Tint.GRASS;
            else if (c != -1) {
                fixed = c;
                tint = Tint.WORLD;
            }
        }
        ResourceLocation name = sprite.contents().name();
        return name.equals(MissingTextureAtlasSprite.getLocation()) ? null : new Found(name, tint, fixed, index);
    }

    /**
     * The tint of the game's own leaves and vines from what their colour handler gives ({@link #fixedTint}): none
     * without a handler. Cherry leaves have a tinted model but no handler, so the game leaves them pink; tinted with the
     * biome's foliage they turned beige on the map (fixed 24.10.2026).
     */
    static Tint leafTint(int handler) {
        return handler == -1 ? Tint.NONE : Tint.FOLIAGE;
    }

    /** {@link #fixedTint}: the block's colour handler cannot answer without a world. */
    private static final int NEEDS_WORLD = Integer.MIN_VALUE;

    /** The tint a block gives itself without a world: -1 without a colour handler (not tinted), or {@link #NEEDS_WORLD}. */
    private static int fixedTint(Minecraft mc, BlockState state, int index) {
        try {
            return mc.getBlockColors().getColor(state, null, null, index);
        } catch (RuntimeException e) {
            return NEEDS_WORLD;
        }
    }

    /** Plants that take the grass tint (their map colour says plant). */
    private static final Set<Block> GRASSY = Set.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.SUGAR_CANE);

    private static BakedQuad first(List<BakedQuad> quads, boolean upOnly) {
        for (BakedQuad q : quads) if (!upOnly || q.getDirection() == Direction.UP) return q;
        return null;
    }

    /** The average colour of a texture ({@code block/stone}: {@code textures/block/stone.png}), 0 if unreadable. */
    private static int read(ResourceManager rm, ResourceLocation texture) {
        ResourceLocation file = texture.withPath(p -> "textures/" + p + ".png");
        Optional<Resource> res = rm.getResource(file);
        if (res.isEmpty()) return 0;
        try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            int w = img.getWidth(), h = img.getHeight();
            int frame = h > w && h % w == 0 ? w : h; // an animated texture: its first frame
            int[] abgr = new int[w * frame];
            for (int y = 0; y < frame; y++) for (int x = 0; x < w; x++) abgr[y * w + x] = img.getPixelRGBA(x, y);
            return average(abgr);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The average of the non-transparent pixels (NativeImage ABGR) as an opaque ARGB colour, or 0 when all are
     * transparent.
     */
    static int average(int[] abgr) {
        int c = average(abgr, SEEN_ALPHA);
        return c != 0 ? c : average(abgr, 1); // all faint (glass and the like): every pixel that shows at all
    }

    /**
     * Pixels at least this opaque count: the game draws leaves and plants cut out at half opacity, so the faint dark
     * pixels of some leaf textures do not show (they made every kind of leaves 14% darker on the map; 24.10.2026).
     */
    static final int SEEN_ALPHA = 128;

    private static int average(int[] abgr, int minAlpha) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int p : abgr) {
            if ((p >>> 24) < minAlpha) continue;
            r += p & 0xFF;
            g += (p >> 8) & 0xFF;
            b += (p >> 16) & 0xFF;
            n++;
        }
        if (n == 0) return 0;
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    /** A colour multiplied by a tint channel by channel, as the game tints a texture (ARGB, the tint's alpha ignored). */
    public static int multiply(int argb, int tint) {
        int r = ((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
