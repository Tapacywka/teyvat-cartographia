package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.platform.Platform;
import net.tapacywka.teyvatcartographia.radar.MobFaces;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;

/**
 * Tiny Takeover Backport (evandev, user request 13.11.2026): the babies of later games and the new look of grown
 * rabbits, with models and textures of their own. It swaps the texture only while drawing the mob, so the game's renderer
 * still names the usual one; the radar asks the mod's own registry for the texture it draws instead
 * ({@code ModBabyTextureRegistry.getBabyTexture}, which also follows Vanilla Backport's warm and cold variants and the
 * mod's settings) and draws the face of that model ({@code minecraft:cow/baby}, {@code minecraft:rabbit/adult} in the
 * table). Where both mods have a say (a baby of a cold cow) this one comes first (user decision 13.11.2026). A mob whose
 * kind has no such face, or that the mod draws as before, keeps its usual face and texture. Read by reflection (no build
 * dependency); anything unexpected turns it off for the session. Settings > Compatibility > Backports can switch it off.
 */
public final class TinyTakeover {
    private TinyTakeover() {}

    private static Boolean on;
    private static Method drawnTexture;
    private static final MobFaces.Face NONE = new MobFaces.Face(new String[0], new int[0], new int[0], new int[0], 1, 1, true, false);
    /** The face of each kind of mob as a baby and as a grown one ({@link #NONE}: none). */
    private static final IdentityHashMap<EntityType<?>, MobFaces.Face> BABY_FACES = new IdentityHashMap<>(), ADULT_FACES = new IdentityHashMap<>();
    /** The texture the mod draws for the usual one, for babies and for grown mobs (the same object: the usual one). */
    private static final IdentityHashMap<ResourceLocation, ResourceLocation> BABY_TEXTURES = new IdentityHashMap<>(),
            ADULT_TEXTURES = new IdentityHashMap<>();

    /** Whether the mod is in the game. */
    public static boolean loaded() {
        return Platform.get() != null && Platform.get().isModLoaded(MobFaces.TINY_TAKEOVER);
    }

    private static boolean on() {
        if (on == null) {
            on = false;
            if (!loaded()) return false;
            try {
                Class<?> registry = Class.forName("com.evandev.tiny_takeover_backport.client.ModBabyTextureRegistry", false,
                        TinyTakeover.class.getClassLoader());
                drawnTexture = registry.getMethod("getBabyTexture", LivingEntity.class, ResourceLocation.class);
                on = true;
            } catch (ReflectiveOperationException | LinkageError e) {
                on = false;
            }
        }
        return on && MapConfig.get(MapConfig.TINY_TAKEOVER_FACES);
    }

    /** The face of this kind of mob, a baby or grown, from the table, or null (no such mod, no face of its). */
    public static MobFaces.Face face(EntityType<?> type, boolean baby) {
        if (!on()) return null;
        IdentityHashMap<EntityType<?>, MobFaces.Face> faces = baby ? BABY_FACES : ADULT_FACES;
        MobFaces.Face face = faces.get(type);
        if (face == null) {
            face = MobFaces.of(BuiltInRegistries.ENTITY_TYPE.getKey(type) + "/" + (baby ? MobFaces.BABY : MobFaces.ADULT));
            if (face == null) face = NONE;
            faces.put(type, face);
        }
        return face == NONE ? null : face;
    }

    /** The texture the mod draws a mob with instead of the usual {@code usual}, or null when it draws the usual one. */
    public static ResourceLocation texture(LivingEntity mob, ResourceLocation usual, boolean baby) {
        if (usual == null || !on()) return null;
        IdentityHashMap<ResourceLocation, ResourceLocation> textures = baby ? BABY_TEXTURES : ADULT_TEXTURES;
        ResourceLocation known = textures.get(usual);
        if (known == null) {
            try {
                known = (ResourceLocation) drawnTexture.invoke(null, mob, usual);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                on = false;
                return null;
            }
            if (known == null) known = usual;
            textures.put(usual, known);
        }
        return known == usual || known.equals(usual) ? null : known;
    }

    /** Forgets what was found (the mod's settings and the textures may change before the next world). */
    public static void reset() {
        BABY_FACES.clear();
        ADULT_FACES.clear();
        BABY_TEXTURES.clear();
        ADULT_TEXTURES.clear();
    }
}
