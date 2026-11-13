package net.tapacywka.teyvatcartographia.radar;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the face of each mob of the game lies in its textures (user decision 13.11.2026: mobs as heads like players,
 * from the game's own textures, no pictures of the mod's): a table of numbers only, so resource packs change the faces
 * with the mobs. A face is drawn from parts, each seven numbers: which of the face's textures, its place there (u, v,
 * width, height) in pixels of the texture and where it lies on the face (dx, dy) in halves of them (a wolf's nose lies 1.5
 * pixels in, as the game draws it: user report 13.11.2026); the first part is the front of the
 * head, the rest lie over it (a nose, a snout, a hat layer, a rabbit's ears, an overlay like the bogged's moss or the
 * enderman's eyes). The front of a head box made by {@code texOffs(u, v).addBox(.., w, h, d)} is at (u + d, v + d), w by h.
 * <p>
 * The first texture is the one the mob's renderer gives (its variant: a wolf, a cat, a fox of snow) unless the face is
 * {@code fixed} (the snow golem's pumpkin, a block), the table's for a mob the client does not see; variants share their
 * layout. Mobs not in the table (other mods, those without a face like a slime or a squid) stay dots. Checked against
 * the textures of 1.21.1 with a contact sheet of every face (13.11.2026; the bogged, enderman, snow golem and rabbit
 * redone after the user's look at them). On 26.x (13.11.2026) the textures' new names and the game's own new models: the
 * cows' muzzle, the warm and cold farm animals by their model ({@code minecraft:cow/cold}), the babies of the Tiny
 * Takeover ({@code minecraft:cow/baby}), the grown rabbit; the faces as Vanilla Backport and Tiny Takeover Backport have
 * them on 1.21.1, which copy these models.
 */
public final class MobFaces {
    private MobFaces() {}

    /** Numbers per part: texture, u, v, width, height, dx, dy. */
    public static final int PART = 7;

    /**
     * A face: its textures (paths in the game's namespace, under {@code textures/}, and their sizes), its parts as above,
     * its size on the screen in pixels of the texture ({@code w} by {@code h}, every part inside), whether the first
     * texture is always the table's, and whether a part lies half a pixel off.
     */
    public record Face(String[] textures, int[] texW, int[] texH, int[] parts, int w, int h, boolean fixed, boolean halves) {}

    /** A player's head: the face of the skin and its hat layer. */
    public static final Face PLAYER = new Face(new String[]{null}, new int[]{64}, new int[]{64},
            new int[]{0, 8, 8, 8, 8, 0, 0, 0, 40, 8, 8, 8, 0, 0}, 8, 8, false, false);

    private static final Map<String, Face> FACES = new HashMap<>();

    /** A part: texture {@code t}, its place (u, v, w, h), where it lies on the face (dx, dy, whole pixels). */
    private static int[] part(int t, int u, int v, int w, int h, int dx, int dy) {
        return new int[]{t, u, v, w, h, 2 * dx, 2 * dy};
    }

    /** A part half a pixel off: where it lies in halves of a pixel ({@code dx2}, {@code dy2}). */
    private static int[] half(int t, int u, int v, int w, int h, int dx2, int dy2) {
        return new int[]{t, u, v, w, h, dx2, dy2};
    }

    /** A front at the face's corner, of the first texture. */
    private static int[] front(int u, int v, int w, int h) {
        return part(0, u, v, w, h, 0, 0);
    }

    private static void face(String id, boolean fixed, String[] textures, int[] size, int[]... parts) {
        int[] flat = new int[PART * parts.length];
        int w = 0, h = 0;
        boolean halves = false;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, flat, PART * i, PART);
            w = Math.max(w, (parts[i][5] + 2 * parts[i][3] + 1) / 2);
            h = Math.max(h, (parts[i][6] + 2 * parts[i][4] + 1) / 2);
            halves |= parts[i][5] % 2 != 0 || parts[i][6] % 2 != 0;
        }
        String[] paths = new String[textures.length];
        for (int i = 0; i < textures.length; i++) paths[i] = textures[i].startsWith(LAYER) ? textures[i] : "textures/" + textures[i] + ".png";
        int[] tw = new int[textures.length], th = new int[textures.length];
        for (int i = 0; i < textures.length; i++) {
            tw[i] = size[2 * i];
            th[i] = size[2 * i + 1];
        }
        FACES.put("minecraft:" + id, new Face(paths, tw, th, flat, w, h, fixed, halves));
    }

    /** A face from one texture of the entity folder. */
    private static void face(String id, String texture, int texW, int texH, int[]... parts) {
        face(id, false, new String[]{"entity/" + texture}, new int[]{texW, texH}, parts);
    }

    /**
     * A texture named after the first one as the mob has it ({@code LAYER}, then what to replace in its file name and with
     * what, "from>to"): the copper golem's eyes follow how far it has oxidized ({@code copper_golem_exposed.png},
     * {@code copper_golem_eyes_exposed.png}).
     */
    public static final String LAYER = "+";

    /** The path of a texture named after the first one: in the file name of {@code first}, the layer's "from" replaced. */
    public static String layerPath(String first, String layer) {
        int arrow = layer.indexOf('>'), slash = first.lastIndexOf('/') + 1;
        String from = layer.substring(LAYER.length(), arrow), to = layer.substring(arrow + 1);
        String name = first.substring(slash);
        int at = name.indexOf(from);
        return at < 0 ? first : first.substring(0, slash) + name.substring(0, at) + to + name.substring(at + from.length());
    }

    /**
     * The mods whose faces the 1.21.1 build marks as theirs (Vanilla Backport, Tiny Takeover Backport); on 26.x the
     * game has these mobs, variants and babies itself, so no face is a mod's (kept for the suite, shared by both builds).
     */
    public static final String VANILLA_BACKPORT = "vanillabackport", TINY_TAKEOVER = "tiny_takeover_backport";

    /** The ids of the faces of a baby ({@code minecraft:cow/baby}) and, on 1.21.1, of an adult's new look. */
    public static final String BABY = "baby", ADULT = "adult";

    /** Whether a face is of a mob another mod adds: never on 26.x. */
    public static boolean fromMod(String id) {
        return false;
    }

    /** The mod a face of the table belongs to: none on 26.x. */
    public static String modOf(String id) {
        return null;
    }

    /** The mod a face belongs to: none on 26.x. */
    public static String modOf(Face face) {
        return null;
    }

    static {
        int[] cube = front(8, 8, 8, 8), tall = front(8, 8, 8, 10);
        int[] hat = part(0, 40, 8, 8, 8, 0, 0);
        // The nose of villagers, illagers and golems, its upper three rows (the fourth hangs below the face).
        int[] nose = part(0, 26, 2, 2, 3, 3, 7);
        int[] snout = part(0, 32, 2, 4, 4, 3, 4);
        face("zombie", "zombie/zombie", 64, 64, cube, hat);
        face("husk", "zombie/husk", 64, 64, cube, hat);
        face("drowned", "zombie/drowned", 64, 64, cube, hat);
        face("zombie_villager", "zombie_villager/zombie_villager", 64, 64, tall, nose);
        face("skeleton", "skeleton/skeleton", 64, 32, cube, hat);
        face("stray", "skeleton/stray", 64, 32, cube, hat);
        face("wither_skeleton", "skeleton/wither_skeleton", 64, 32, cube, hat);
        face("parched", "skeleton/parched", 64, 64, cube, part(0, 8, 40, 8, 8, 0, 0));
        // The bogged's moss and eyes are a layer of their own texture over the skull.
        face("bogged", false, new String[]{"entity/skeleton/bogged", "entity/skeleton/bogged_overlay"}, new int[]{64, 32, 64, 32},
                cube, part(1, 8, 8, 8, 8, 0, 0));
        face("creeper", "creeper/creeper", 64, 32, cube);
        // The enderman's lower face is its jaw, a layer of its own (texOffs(0, 16)); its purple eyes a texture of their own
        // (drawn glowing in the game).
        face("enderman", false, new String[]{"entity/enderman/enderman", "entity/enderman/enderman_eyes"}, new int[]{64, 32, 64, 32},
                cube, part(0, 8, 24, 8, 8, 0, 0), part(1, 8, 8, 8, 8, 0, 0));
        face("spider", "spider/spider", 64, 32, front(40, 12, 8, 8));
        face("cave_spider", "spider/cave_spider", 64, 32, front(40, 12, 8, 8));
        face("piglin", "piglin/piglin", 64, 64, front(8, 8, 10, 8), snout);
        face("zombified_piglin", "piglin/zombified_piglin", 64, 64, front(8, 8, 10, 8), snout);
        face("piglin_brute", "piglin/piglin_brute", 64, 64, front(8, 8, 10, 8), snout);
        face("villager", "villager/villager", 64, 64, tall, nose);
        face("wandering_trader", "wandering_trader/wandering_trader", 64, 64, tall, nose);
        face("witch", "witch/witch", 64, 128, tall, nose);
        face("pillager", "illager/pillager", 64, 64, tall, nose);
        face("vindicator", "illager/vindicator", 64, 64, tall, nose);
        face("evoker", "illager/evoker", 64, 64, tall, nose);
        face("illusioner", "illager/illusioner", 64, 64, tall, nose);
        face("iron_golem", "iron_golem/iron_golem", 128, 128, tall, nose);
        // The copper golem's head with its antenna above it and its nose (the row hanging below the face left out, as the
        // villagers'); its eyes a texture of their own that follows its oxidation.
        face("copper_golem", false, new String[]{"entity/copper_golem/copper_golem", LAYER + "copper_golem>copper_golem_eyes"},
                new int[]{64, 64, 64, 64}, part(0, 10, 10, 8, 5, 0, 8), part(1, 10, 10, 8, 5, 0, 8), part(0, 58, 2, 2, 2, 3, 11),
                part(0, 39, 10, 2, 4, 3, 4), part(0, 41, 4, 4, 4, 2, 0));
        // The snow golem wears a carved pumpkin; sheared, its face of snow shows.
        face("snow_golem", true, new String[]{"block/carved_pumpkin"}, new int[]{16, 16}, front(0, 0, 16, 16));
        face("snow_golem_sheared", "snow_golem/snow_golem", 64, 64, cube);
        // The farm animals by the model of their variant (a variant with the usual model keeps the usual face, its texture
        // the variant's): the cows' muzzle, the cold chicken's cap of feathers.
        face("cow", "cow/cow_temperate", 64, 64, front(6, 6, 8, 8), part(0, 2, 34, 6, 3, 1, 5));
        face("cow/cold", "cow/cow_cold", 64, 64, front(6, 6, 8, 8), part(0, 10, 34, 6, 3, 1, 5));
        face("cow/warm", "cow/cow_warm", 64, 64, front(6, 6, 8, 8), part(0, 2, 34, 6, 3, 1, 5));
        face("mooshroom", "cow/mooshroom_red", 64, 64, front(6, 6, 8, 8), part(0, 2, 34, 6, 3, 1, 5));
        face("pig", "pig/pig_temperate", 64, 64, cube, part(0, 17, 17, 4, 3, 2, 4));
        face("pig/cold", "pig/pig_cold", 64, 64, cube, part(0, 17, 17, 4, 3, 2, 4));
        face("sheep", "sheep/sheep", 64, 32, front(8, 8, 6, 6));
        face("chicken", "chicken/chicken_temperate", 64, 32, front(3, 3, 4, 6), part(0, 16, 2, 4, 2, 0, 2), part(0, 16, 6, 2, 2, 1, 4));
        face("chicken/cold", "chicken/chicken_cold", 64, 32, part(0, 3, 3, 4, 6, 1, 1), part(0, 48, 4, 6, 3, 0, 0),
                part(0, 16, 2, 4, 2, 1, 3), part(0, 16, 6, 2, 2, 2, 5));
        // The wolf's nose 1.5 pixels in from each side of its face, as the game draws it.
        face("wolf", "wolf/wolf", 64, 32, front(4, 4, 6, 6), half(0, 4, 14, 3, 3, 3, 6));
        face("cat", "cat/cat_tabby", 64, 32, front(5, 5, 5, 4), part(0, 2, 26, 3, 2, 1, 2));
        face("ocelot", "cat/ocelot", 64, 32, front(5, 5, 5, 4), part(0, 2, 26, 3, 2, 1, 2));
        face("fox", "fox/fox", 48, 32, front(7, 11, 8, 6), part(0, 9, 21, 4, 2, 2, 4));
        // The rabbit's head with its ears above it (its new model of 26.1).
        face("rabbit", "rabbit/rabbit_brown", 64, 64, part(0, 5, 21, 5, 5, 0, 5), part(0, 27, 1, 2, 5, 0, 0),
                part(0, 33, 1, 2, 5, 3, 0));
        face("blaze", "blaze/blaze", 64, 32, cube);
        face("polar_bear", "bear/polarbear", 128, 64, front(7, 7, 7, 7), part(0, 3, 47, 5, 3, 1, 4));
        face("panda", "panda/panda", 64, 64, front(9, 15, 13, 10), part(0, 47, 18, 7, 5, 3, 5));
        // The ghasts' models are 64 by 32 and 64 by 64, their textures twice as fine (the places in the models' pixels).
        face("ghast", "ghast/ghast", 64, 32, front(16, 16, 16, 16));
        face("happy_ghast", "ghast/happy_ghast", 64, 64, front(16, 16, 16, 16));
        face("allay", "allay/allay", 32, 32, front(5, 5, 5, 5));
        face("vex", "illager/vex", 32, 32, front(5, 5, 5, 5));
        // The creaking's head with the crown of bark above it (its eyes are in the texture; the glowing layer only repeats
        // them).
        face("creaking", "creaking/creaking", 64, 64, part(0, 6, 6, 6, 10, 0, 3), part(0, 34, 37, 6, 3, 0, 0));

        // The babies of the Tiny Takeover (26.1): the texture is the usual variant's baby, the drawn one the mob's own.
        // Ears and horns beside or above the head, behind it where they meet; piglins' ears hang turned and are left out.
        int[][] cow = {part(0, 5, 23, 6, 6, 1, 1), part(0, 5, 30, 1, 2, 0, 0), part(0, 9, 30, 1, 2, 7, 0), part(0, 13, 30, 4, 3, 2, 4)};
        face("cow/baby", "cow/cow_temperate_baby", 64, 64, cow);
        face("mooshroom/baby", "cow/mooshroom_red_baby", 64, 64, cow);
        face("pig/baby", "pig/pig_temperate_baby", 32, 32, front(6, 21, 7, 6), part(0, 7, 28, 3, 2, 2, 3));
        face("chicken/baby", "chicken/chicken_temperate_baby", 16, 16, front(4, 4, 4, 4), part(0, 11, 9, 2, 1, 1, 2));
        face("sheep/baby", "sheep/sheep_baby", 64, 32, front(5, 5, 5, 5));
        face("wolf/baby", "wolf/wolf_baby", 32, 32, part(0, 5, 17, 6, 5, 0, 2), half(0, 19, 14, 3, 2, 3, 10),
                part(0, 1, 6, 2, 2, 0, 0), part(0, 21, 6, 2, 2, 4, 0));
        int[][] cat = {part(0, 4, 4, 5, 4, 0, 1), half(0, 20, 2, 1, 1, 1, 0), half(0, 26, 2, 1, 1, 7, 0), part(0, 19, 4, 3, 2, 1, 3)};
        face("cat/baby", "cat/cat_tabby_baby", 32, 32, cat);
        face("ocelot/baby", "cat/ocelot_baby", 32, 32, cat);
        face("fox/baby", "fox/fox_baby", 32, 32, part(0, 5, 5, 6, 5, 0, 2), part(0, 20, 22, 2, 2, 2, 5),
                part(0, 23, 9, 2, 2, 0, 0), part(0, 23, 12, 2, 2, 4, 0));
        face("panda/baby", "panda/panda_baby", 64, 64, part(0, 25, 1, 3, 3, 0, 0), part(0, 34, 1, 3, 3, 6, 0),
                part(0, 5, 5, 7, 6, 1, 1), half(0, 25, 7, 4, 2, 5, 10));
        face("polar_bear/baby", "bear/polarbear_baby", 64, 64, part(0, 21, 1, 2, 2, 0, 0), part(0, 27, 1, 2, 2, 6, 0),
                part(0, 4, 4, 6, 5, 1, 1), part(0, 22, 5, 4, 2, 2, 4));
        face("rabbit/baby", "rabbit/rabbit_brown_baby", 32, 32, part(0, 4, 4, 5, 4, 0, 4), part(0, 19, 1, 2, 4, 0, 0),
                part(0, 25, 1, 2, 4, 3, 0));
        face("villager/baby", "villager/villager_baby", 64, 64, front(7, 7, 8, 8), part(0, 7, 37, 8, 8, 0, 0), part(0, 24, 1, 2, 2, 3, 6));
        face("zombie_villager/baby", "zombie_villager/zombie_villager_baby", 64, 64, front(7, 7, 8, 8), part(0, 7, 38, 8, 8, 0, 0),
                part(0, 24, 1, 2, 2, 3, 6));
        for (String zombie : new String[]{"zombie", "husk", "drowned"})
            face(zombie + "/baby", "zombie/" + zombie + "_baby", 64, 64, front(9, 9, 6, 6), part(0, 41, 9, 6, 6, 0, 0));
        for (String piglin : new String[]{"piglin", "zombified_piglin"})
            face(piglin + "/baby", "piglin/" + piglin + "_baby", 64, 64, front(7, 7, 9, 6), part(0, 22, 31, 3, 3, 3, 3));
    }

    /** The face of a mob by its id ("minecraft:zombie"), or null for one without a face in the table. */
    public static Face of(String id) {
        return FACES.get(id);
    }

    /** Every face in the table, by id (for the tests). */
    static Map<String, Face> all() {
        return FACES;
    }

    /** The ids of every face in the table, sorted (for the in-game suite). */
    public static java.util.List<String> ids() {
        return FACES.keySet().stream().sorted().toList();
    }

    /**
     * Screen pixels for each pixel of a face's texture: about {@code size} GUI units for its longer side, a whole
     * number of pixels, at least one ({@code scale} the GUI scale).
     */
    public static int texel(float size, float scale, int longerSide) {
        return Math.max(1, Math.round(size * scale / longerSide));
    }

    /**
     * Screen pixels of every face's longer side (user choice 13.11.2026, variant B: one size for every head, like the
     * pins): what a face of 8 pixels gets on whole screen pixels a pixel, so the usual heads (a player's, a zombie's)
     * stay exact and the others (a chicken's 6, a ghast's 16) are drawn as large, their pixels at most one screen pixel
     * apart in width.
     */
    public static int side(float size, float scale) {
        return 8 * texel(size, scale, 8);
    }

    /**
     * Where a point of a face lies on the screen, in whole screen pixels from its corner: {@code halves} in halves of a
     * pixel of its texture, the face's longer side {@code side} screen pixels.
     */
    public static int at(int halves, int side, Face face) {
        return Math.round(halves * side / (2f * Math.max(face.w(), face.h())));
    }
}
