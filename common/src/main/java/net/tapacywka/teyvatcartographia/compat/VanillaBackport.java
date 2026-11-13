package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.gui.TeyvatConfigScreen;
import net.tapacywka.teyvatcartographia.platform.Platform;
import net.tapacywka.teyvatcartographia.radar.MobFaces;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;

/**
 * Vanilla Backport (BlackGear, user request 13.11.2026): the warm and cold cows, pigs and chickens of later games. It
 * keeps a farm animal's variant in data of its own and draws it with its own code, while the game's renderer still names
 * the usual texture, so the radar's heads read the variant here: its model (cold, warm or the usual) picks the face of the
 * table ({@code minecraft:cow/cold}), its texture the colours. Read by reflection through the mod's own names (no build
 * dependency; its names are the same on both loaders); anything unexpected turns it off for the session.
 */
public final class VanillaBackport {
    private VanillaBackport() {}

    public static final String MOD_ID = MobFaces.VANILLA_BACKPORT;
    private static final String API = "com.blackgear.vanillabackport.common.api.modules.mob_variant.";

    /** A variant as the radar draws it: the face of its model (null: the mob's usual face) and its texture. */
    public record Variant(MobFaces.Face face, ResourceLocation texture) {}

    private static Boolean on;
    private static Class<?> holder;
    private static Method variantData, model, asset, path;
    /** Each variant (the mod's registered objects) as the radar draws it, by mob type. */
    private static final IdentityHashMap<EntityType<?>, IdentityHashMap<Object, Variant>> VARIANTS = new IdentityHashMap<>();

    /**
     * A sub-section of Compatibility for each mod, named after it and shown with it (user decision 13.11.2026, after one
     * shared "Backports"): Vanilla Backport > Mob faces, Tiny Takeover Backport > Baby faces. The mods in the game are
     * known when the game starts, before this.
     */
    public static void settings() {
        String k = "teyvatcartographia.config.";
        TeyvatConfigScreen.compatPart("compat_vanilla_backport", VanillaBackport::loaded,
                t -> t.toggle(k + "compat.vanilla_backport_faces", MapConfig.VANILLA_BACKPORT_FACES));
        TeyvatConfigScreen.compatPart("compat_tiny_takeover", TinyTakeover::loaded,
                t -> t.toggle(k + "compat.tiny_takeover_faces", MapConfig.TINY_TAKEOVER_FACES));
    }

    /** Whether the mod is in the game. */
    public static boolean loaded() {
        return Platform.get() != null && Platform.get().isModLoaded(MOD_ID);
    }

    /** Whether the radar draws the faces of the mod's mobs and variants (the mod there, its switch in Compatibility on). */
    public static boolean faces() {
        return loaded() && MapConfig.get(MapConfig.VANILLA_BACKPORT_FACES);
    }

    private static boolean on() {
        if (on == null) {
            on = false;
            if (!loaded()) return false;
            try {
                ClassLoader loader = VanillaBackport.class.getClassLoader();
                holder = Class.forName(API + "VariantDataHolder", false, loader);
                variantData = holder.getMethod("getVariantData");
                Class<?> mat = Class.forName(API + "ModelAndTexture", false, loader);
                model = mat.getMethod("model");
                asset = mat.getMethod("asset");
                path = Class.forName(API + "ClientAsset", false, loader).getMethod("path");
                on = true;
            } catch (ReflectiveOperationException | LinkageError e) {
                on = false;
            }
        }
        return on;
    }

    /** The variant of a cow, pig or chicken of Vanilla Backport, or null (no such mod, another mob, no variant yet). */
    public static Variant variant(Entity e) {
        EntityType<?> type = e.getType();
        if (type != EntityType.COW && type != EntityType.PIG && type != EntityType.CHICKEN) return null;
        if (!on() || !MapConfig.get(MapConfig.VANILLA_BACKPORT_FACES) || !holder.isInstance(e)) return null;
        try {
            Optional<?> data = (Optional<?>) variantData.invoke(e);
            if (data == null || data.isEmpty()) return null;
            Object v = data.get();
            IdentityHashMap<Object, Variant> byVariant = VARIANTS.computeIfAbsent(type, t -> new IdentityHashMap<>());
            Variant known = byVariant.get(v);
            if (known == null) {
                known = resolve(type, v);
                byVariant.put(v, known);
            }
            return known;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError x) {
            on = false;
            return null;
        }
    }

    private static Variant resolve(EntityType<?> type, Object v) throws ReflectiveOperationException {
        // each animal's variant is a class of its own (CowVariant, PigVariant, ChickenVariant)
        Object mat = v.getClass().getMethod("modelAndTexture").invoke(v);
        Object kind = model.invoke(mat);
        ResourceLocation texture = (ResourceLocation) path.invoke(asset.invoke(mat));
        if (texture != null && !texture.getPath().endsWith(".png"))
            texture = texture.withPath("textures/" + texture.getPath() + ".png");
        String name = kind instanceof Enum<?> en ? en.name().toLowerCase(Locale.ROOT) : "";
        MobFaces.Face face = MobFaces.of(BuiltInRegistries.ENTITY_TYPE.getKey(type) + "/" + name);
        return new Variant(face, texture);
    }

    /** Forgets the variants (the textures may change with resource packs; a new world). */
    public static void reset() {
        VARIANTS.clear();
    }
}
