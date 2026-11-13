package net.tapacywka.teyvatcartographia.radar;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Team;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Tex;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.tapacywka.teyvatcartographia.version.Game;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The radar of players and entities on both maps (user decisions 12.11.2026, docs/RADAR_SPEC.md): the entities around
 * the player, gathered once a tick, drawn as dots in the colour of their kind (players as an arrow in their team's
 * colour, or the head of their skin in a frame of it), fainter with a small arrow when well above or below. The rules are in {@link RadarRules}.
 * <p>
 * The client knows of mobs only within the server's tracking range (8 chunks for most of them, whatever the radar's
 * range), so in single player those farther are read from the game's own server, once a tick on its thread.
 */
public final class Radar {
    private Radar() {}

    /** The dots of the last tick (reused arrays, nothing made per frame): where they were and are, kind, colour. */
    private static int count;
    private static double[] x0 = new double[64], z0 = new double[64], x1 = new double[64], z1 = new double[64], ys = new double[64];
    private static float[] yaw0 = new float[64], yaw1 = new float[64];
    private static RadarRules.Kind[] kinds = new RadarRules.Kind[64];
    private static int[] colours = new int[64];
    /** A player's name for the tooltip on the world map, null for the rest. */
    private static Component[] names = new Component[64];
    /**
     * The face drawn (players and mobs as heads) and its texture (a player's skin, a mob's texture); null for a dot or an
     * arrow. A player's team's colour, -1 without one or for the rest.
     */
    private static MobFaces.Face[] faces = new MobFaces.Face[64];
    private static Identifier[] skins = new Identifier[64];
    private static int[] teams = new int[64];
    /** The ids of the entities the client knows (the game's server gives them the same ids). */
    private static final IntOpenHashSet known = new IntOpenHashSet();
    /** The server forbade a radar (Xaero's fair play code in its chat) since the player joined. */
    private static boolean forbidden;

    /** What the game's own server had around the player (single player), written on its thread. */
    private record Far(int n, int[] id, RadarRules.Kind[] kind, double[] x0, double[] z0, double[] x1, double[] z1, double[] y,
                       float[] yaw0, float[] yaw1, int[] team, Component[] name, UUID[] uuid, EntityType<?>[] type) {}
    private static volatile Far far;
    private static final AtomicBoolean asking = new AtomicBoolean();

    /** Sizes in GUI units: the dots' radius and the players' arrows, on the minimap and on the world map. */
    public static final float DOT_MINIMAP = 1.5f, DOT_MAP = 2f, ARROW_MINIMAP = 10f, ARROW_MAP = 13f;
    /** Players' heads (look Heads): the face's side, the frame around it and its dark rim, in GUI units (whole screen pixels when drawn). */
    public static final float HEAD_MINIMAP = 7f, HEAD_MAP = 10f, HEAD_FRAME = 0.4f, HEAD_RIM = 0.25f;
    /** Mobs' heads (look Heads), the longer side of the face: smaller than players' (user choice 13.11.2026). */
    public static final float MOB_HEAD_MINIMAP = 5.3f, MOB_HEAD_MAP = 8f;
    /** The face of each kind of mob, found once (none: {@link #NO_FACE}); the texture of each face by its path. */
    private static final IdentityHashMap<EntityType<?>, MobFaces.Face> FACE_OF = new IdentityHashMap<>(), BABY_FACE_OF = new IdentityHashMap<>();
    private static final MobFaces.Face NO_FACE = new MobFaces.Face(new String[0], new int[0], new int[0], new int[0], 1, 1, true, false);
    private static final IdentityHashMap<MobFaces.Face, Identifier[]> TEXTURES_OF = new IdentityHashMap<>();
    /** The outline of each face by its size on the screen ({@link MobFaces#side} over 8), for the GUI scale {@link #outlineScale}. */
    private static final IdentityHashMap<MobFaces.Face, FaceOutline[]> OUTLINES = new IdentityHashMap<>();
    private static float outlineScale;
    /**
     * A mob's texture as its renderer gives it, by the mob's id: since 26.1 a renderer tells it only from a render state,
     * so it is read into one state per renderer (reused) and kept for {@link #TEXTURE_TICKS} ticks, or until the mob
     * grows up.
     */
    private record Seen(Identifier texture, boolean baby, int tick) {}
    private static final Int2ObjectOpenHashMap<Seen> SEEN = new Int2ObjectOpenHashMap<>();
    private static final IdentityHashMap<EntityRenderer<?, ?>, EntityRenderState> STATES = new IdentityHashMap<>();
    private static final int TEXTURE_TICKS = 40;
    private static int ticks;
    /** Opacity of what is well above or below. */
    private static final float OTHER_HEIGHT_ALPHA = 0.5f;

    /** The setting of a kind: where it shows. */
    public static RadarRules.Show show(RadarRules.Kind kind) {
        return MapConfig.get(switch (kind) {
            case PLAYERS -> MapConfig.RADAR_PLAYERS;
            case HOSTILE -> MapConfig.RADAR_HOSTILE;
            case PASSIVE -> MapConfig.RADAR_PASSIVE;
            case VILLAGERS -> MapConfig.RADAR_VILLAGERS;
            case PETS -> MapConfig.RADAR_PETS;
            case OTHER -> MapConfig.RADAR_OTHER;
        });
    }

    /** The radar's range in blocks (the setting is in chunks). */
    public static int rangeBlocks() {
        return MapConfig.get(MapConfig.RADAR_RANGE) * 16;
    }

    /** Gathers the entities around the player (once a tick; the client's list is walked here, never while drawing). */
    public static void tick(Minecraft mc) {
        count = 0;
        SableRadar.tick(mc);
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !MapConfig.get(MapConfig.RADAR_ENABLED)) {
            far = null;
            return;
        }
        boolean single = mc.hasSingleplayerServer();
        boolean serverMobs = MapConfig.get(MapConfig.RADAR_SERVER_MOBS);
        int range = rangeBlocks();
        UUID me = player.getUUID();
        Entity vehicle = player.getVehicle();
        boolean mobHeads = MapConfig.get(MapConfig.RADAR_MOB_LOOK) == RadarRules.MobLook.HEADS;
        ticks++;
        // the textures of mobs gone long ago forgotten now and then
        if (SEEN.size() > 4096) SEEN.clear();
        known.clear();
        for (Entity e : mc.level.entitiesForRendering()) {
            known.add(e.getId());
            if (e == player || e == vehicle || e.isRemoved() || e.isInvisibleTo(player)) continue;
            RadarRules.Kind kind = kindOf(e, me);
            if (kind == null || show(kind) == RadarRules.Show.OFF || !RadarRules.allowed(kind, single, serverMobs, forbidden)) continue;
            if (!RadarRules.inRange(e.getX() - player.getX(), e.getZ() - player.getZ(), range)) continue;
            int i = add();
            x0[i] = e.xo;
            z0[i] = e.zo;
            x1[i] = e.getX();
            z1[i] = e.getZ();
            ys[i] = e.getY();
            kinds[i] = kind;
            if (kind == RadarRules.Kind.PLAYERS) {
                yaw0[i] = e.yRotO;
                yaw1[i] = e.getYRot();
                names[i] = e.getDisplayName();
                Team team = e.getTeam();
                Integer c = team == null ? null : Game.teamColour(team);
                teams[i] = c == null ? -1 : c;
                colours[i] = c == null ? Theme.RADAR_PLAYER : 0xFF000000 | c;
                skins[i] = e instanceof AbstractClientPlayer p ? p.getSkin().body().texturePath() : skin(mc, e.getUUID());
                faces[i] = MobFaces.PLAYER;
            } else {
                names[i] = null;
                teams[i] = -1;
                colours[i] = colour(kind);
                MobFaces.Face face = mobHeads && kind != RadarRules.Kind.OTHER ? faceOf(e) : null;
                faces[i] = face;
                skins[i] = face == null ? null : texture(mc, e, face);
            }
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            far = null;
            return;
        }
        // Single player: what the client does not know of, from the game's server (its answer of the tick before).
        Far f = far;
        if (f != null) {
            for (int j = 0; j < f.n; j++) {
                if (known.contains(f.id[j])) continue;
                RadarRules.Kind kind = f.kind[j];
                if (show(kind) == RadarRules.Show.OFF || !RadarRules.inRange(f.x1[j] - player.getX(), f.z1[j] - player.getZ(), range)) continue;
                int i = add();
                x0[i] = f.x0[j];
                z0[i] = f.z0[j];
                x1[i] = f.x1[j];
                z1[i] = f.z1[j];
                ys[i] = f.y[j];
                kinds[i] = kind;
                yaw0[i] = f.yaw0[j];
                yaw1[i] = f.yaw1[j];
                names[i] = f.name[j];
                boolean isPlayer = kind == RadarRules.Kind.PLAYERS;
                teams[i] = isPlayer ? f.team[j] : -1;
                MobFaces.Face face = isPlayer ? MobFaces.PLAYER : mobHeads && kind != RadarRules.Kind.OTHER ? faceOf(f.type[j]) : null;
                faces[i] = face;
                skins[i] = isPlayer ? skin(mc, f.uuid[j]) : face == null ? null : texture(face);
                colours[i] = isPlayer ? (f.team[j] == -1 ? Theme.RADAR_PLAYER : 0xFF000000 | f.team[j]) : colour(kind);
            }
        }
        if (asking.compareAndSet(false, true)) {
            ResourceKey<Level> dim = mc.level.dimension();
            double px = player.getX(), pz = player.getZ();
            server.execute(() -> {
                try {
                    far = askServer(server, dim, me, px, pz, range);
                } catch (RuntimeException e) {
                    far = null;
                } finally {
                    asking.set(false);
                }
            });
        }
    }

    /** On the server's thread: the entities around the player there, as the client would see them. */
    private static Far askServer(IntegratedServer server, ResourceKey<Level> dim, UUID me, double px, double pz, int range) {
        ServerLevel level = server.getLevel(dim);
        ServerPlayer player = server.getPlayerList().getPlayer(me);
        if (level == null || player == null || player.level() != level) return null;
        Entity vehicle = player.getVehicle();
        List<Entity> found = level.getEntities(player, new AABB(px - range, level.getMinY(), pz - range,
                px + range, level.getMaxY(), pz + range), e -> e != vehicle && !e.isRemoved() && !e.isInvisibleTo(player));
        int n = 0, size = found.size();
        int[] id = new int[size], team = new int[size];
        RadarRules.Kind[] kind = new RadarRules.Kind[size];
        double[] ax = new double[size], az = new double[size], bx = new double[size], bz = new double[size], y = new double[size];
        float[] ya = new float[size], yb = new float[size];
        Component[] name = new Component[size];
        UUID[] uuid = new UUID[size];
        EntityType<?>[] type = new EntityType<?>[size];
        for (Entity e : found) {
            RadarRules.Kind k = kindOf(e, me);
            if (k == null) continue;
            id[n] = e.getId();
            kind[n] = k;
            ax[n] = e.xo;
            az[n] = e.zo;
            bx[n] = e.getX();
            bz[n] = e.getZ();
            y[n] = e.getY();
            ya[n] = e.yRotO;
            yb[n] = e.getYRot();
            Team t = e.getTeam();
            Integer c = t == null ? null : Game.teamColour(t);
            team[n] = c == null ? -1 : c;
            name[n] = k == RadarRules.Kind.PLAYERS ? e.getDisplayName() : null;
            uuid[n] = k == RadarRules.Kind.PLAYERS ? e.getUUID() : null;
            type[n] = e.getType();
            n++;
        }
        return new Far(n, id, kind, ax, az, bx, bz, y, ya, yb, team, name, uuid, type);
    }

    /** One more dot; the arrays grow when full. */
    private static int add() {
        if (count == x0.length) {
            int size = count * 2;
            x0 = Arrays.copyOf(x0, size);
            z0 = Arrays.copyOf(z0, size);
            x1 = Arrays.copyOf(x1, size);
            z1 = Arrays.copyOf(z1, size);
            ys = Arrays.copyOf(ys, size);
            yaw0 = Arrays.copyOf(yaw0, size);
            yaw1 = Arrays.copyOf(yaw1, size);
            kinds = Arrays.copyOf(kinds, size);
            colours = Arrays.copyOf(colours, size);
            names = Arrays.copyOf(names, size);
            skins = Arrays.copyOf(skins, size);
            faces = Arrays.copyOf(faces, size);
            teams = Arrays.copyOf(teams, size);
        }
        return count++;
    }

    /** The face of a kind of mob from {@link MobFaces} (a baby's with {@code baby}), or null for one without. */
    private static MobFaces.Face faceOf(EntityType<?> type, boolean baby) {
        IdentityHashMap<EntityType<?>, MobFaces.Face> faces = baby ? BABY_FACE_OF : FACE_OF;
        MobFaces.Face face = faces.get(type);
        if (face == null) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            face = MobFaces.of(baby ? id + "/" + MobFaces.BABY : id);
            if (face == null) face = NO_FACE;
            faces.put(type, face);
        }
        return face == NO_FACE ? null : face;
    }

    /** The face of a kind of mob as a grown one (a mob the client does not see), or null for one without (a dot). */
    private static MobFaces.Face faceOf(EntityType<?> type) {
        return faceOf(type, false);
    }

    /**
     * The face of a mob the client sees: a baby's (since 26.1 babies have models of their own), the sheared snow golem's,
     * the model of a farm animal's variant (a cold cow), else its kind's; null for one without (a dot).
     */
    private static MobFaces.Face faceOf(Entity e) {
        EntityType<?> type = e.getType();
        MobFaces.Face face = faceOf(type, false);
        if (face == null) return null;
        if (e instanceof LivingEntity living && living.isBaby()) {
            MobFaces.Face baby = faceOf(type, true);
            return baby != null ? baby : face;
        }
        if (e instanceof SnowGolem golem && !golem.hasPumpkin()) return MobFaces.of("minecraft:snow_golem_sheared");
        Enum<?> model = e instanceof Cow cow ? cow.getVariant().value().modelAndTexture().model()
                : e instanceof Pig pig ? pig.getVariant().value().modelAndTexture().model()
                : e instanceof Chicken chicken ? chicken.getVariant().value().modelAndTexture().model() : null;
        if (model != null && model.ordinal() != 0) {
            MobFaces.Face variant = VARIANT_FACES.computeIfAbsent(model,
                    m -> MobFaces.of(BuiltInRegistries.ENTITY_TYPE.getKey(type) + "/" + m.name().toLowerCase(Locale.ROOT)));
            if (variant != null) return variant;
        }
        return face;
    }

    /** The face of each model of the farm animals' variants (the first, NORMAL, is the kind's own). */
    private static final IdentityHashMap<Enum<?>, MobFaces.Face> VARIANT_FACES = new IdentityHashMap<>();

    /** Texture {@code t} of a face as the table names it. */
    private static Identifier texture(MobFaces.Face face, int t) {
        Identifier[] r = TEXTURES_OF.get(face);
        if (r == null) {
            r = new Identifier[face.textures().length];
            for (int i = 0; i < r.length; i++) {
                String path = face.textures()[i];
                // a texture named after the first one: as the table's first here (the outline), as the mob's when drawn
                r[i] = Identifier.withDefaultNamespace(path.startsWith(MobFaces.LAYER) ? MobFaces.layerPath(face.textures()[0], path) : path);
            }
            TEXTURES_OF.put(face, r);
        }
        return r[t];
    }

    /** The first texture of a face as the table names it (a mob the client does not see). */
    private static Identifier texture(MobFaces.Face face) {
        return texture(face, 0);
    }

    /**
     * A mob's texture as its renderer gives it (its variant, a baby's), else (or for a fixed face) the table's. Read from
     * a render state, kept for a while per mob (see {@link Seen}).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Identifier texture(Minecraft mc, Entity e, MobFaces.Face face) {
        if (face.fixed()) return texture(face);
        boolean baby = e instanceof LivingEntity living && living.isBaby();
        Seen seen = SEEN.get(e.getId());
        if (seen != null && seen.baby == baby && ticks - seen.tick < TEXTURE_TICKS) return seen.texture;
        Identifier r = null;
        try {
            EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(e);
            if (renderer instanceof LivingEntityRenderer living) {
                EntityRenderState state = STATES.computeIfAbsent(renderer, x -> renderer.createRenderState());
                renderer.extractRenderState(e, state, 1f);
                r = living.getTextureLocation((net.minecraft.client.renderer.entity.state.LivingEntityRenderState) state);
            }
        } catch (RuntimeException ignored) {
            // a renderer that cannot tell without drawing: the table's texture
        }
        if (r == null) r = texture(face);
        SEEN.put(e.getId(), new Seen(r, baby, ticks));
        return r;
    }

    /** A player's skin from the server's list of players (one the client does not see), or null. */
    private static Identifier skin(Minecraft mc, UUID uuid) {
        ClientPacketListener connection = mc.getConnection();
        PlayerInfo info = connection == null || uuid == null ? null : connection.getPlayerInfo(uuid);
        return info == null ? null : info.getSkin().body().texturePath();
    }

    /** The kind of an entity on the radar, or null for one it never shows (paintings, arrows, the like). */
    static RadarRules.Kind kindOf(Entity e, UUID me) {
        if (e instanceof Player p) return p.isSpectator() ? null : RadarRules.Kind.PLAYERS;
        if (e instanceof OwnableEntity o && o.getOwnerReference() != null && me.equals(o.getOwnerReference().getUUID())) return RadarRules.Kind.PETS;
        if (e instanceof Enemy) return RadarRules.Kind.HOSTILE;
        if (e instanceof AbstractVillager) return RadarRules.Kind.VILLAGERS;
        if (e instanceof Mob) return RadarRules.Kind.PASSIVE;
        if (e instanceof VehicleEntity || e instanceof ItemEntity) return RadarRules.Kind.OTHER;
        return null;
    }

    /** A chat message from the server: Xaero's codes forbid the radar or allow it again. */
    public static void chat(Component message) {
        if (message == null) return;
        String s = message.getString();
        if (s.indexOf('§') < 0) return;
        forbidden = RadarRules.forbiddenAfter(s, forbidden);
    }

    /** The player left the world: the entities and the server's signal are forgotten. */
    public static void leave() {
        SableRadar.leave();
        count = 0;
        Arrays.fill(names, null);
        Arrays.fill(skins, null);
        Arrays.fill(faces, null);
        // the textures may change (resource packs) before the next world
        OUTLINES.clear();
        SEEN.clear();
        STATES.clear();
        far = null;
        forbidden = false;
    }

    /** The colour of a kind but players (theirs is their team's). */
    private static int colour(RadarRules.Kind kind) {
        return switch (kind) {
            case PLAYERS -> Theme.RADAR_PLAYER;
            case HOSTILE -> Theme.RADAR_HOSTILE;
            case PASSIVE -> Theme.RADAR_PASSIVE;
            case VILLAGERS -> Theme.RADAR_VILLAGER;
            case PETS -> Theme.RADAR_PET;
            case OTHER -> Theme.RADAR_OTHER;
        };
    }

    /** Whether anything is on the radar at all. */
    public static boolean any() {
        return count > 0 || SableRadar.any();
    }

    /** A shape the drawn radar must stay in: inside {@code r} of ({@code cx}, {@code cy}), a circle or a square. */
    private static boolean inside(float sx, float sy, float cx, float cy, float r, boolean square) {
        float dx = sx - cx, dy = sy - cy;
        return square ? Math.abs(dx) <= r && Math.abs(dy) <= r : dx * dx + dy * dy <= r * r;
    }

    /** Whether entry {@code i} is drawn as a head: a player or a mob with a face and a texture, in the look Heads. */
    private static boolean asHead(int i, boolean playerHeads) {
        if (faces[i] == null || skins[i] == null) return false;
        return kinds[i] != RadarRules.Kind.PLAYERS || playerHeads;
    }

    /**
     * A number that changes whenever what the minimap's radar shows moves by half a GUI unit or turns (its layer is
     * redrawn then, at the minimap's update rate), 0 with nothing to show.
     */
    public static long signature(MapView view, float pt, float cx, float cy, float r, boolean square, double playerY) {
        long h = SableRadar.signature(view, pt, cx, cy, r, square);
        boolean arrows = MapConfig.get(MapConfig.RADAR_HEIGHT);
        boolean heads = MapConfig.get(MapConfig.RADAR_PLAYER_LOOK) == RadarRules.PlayerLook.HEADS;
        for (int i = 0; i < count; i++) {
            RadarRules.Kind kind = kinds[i];
            if (!show(kind).on(true)) continue;
            double x = Mth.lerp(pt, x0[i], x1[i]), z = Mth.lerp(pt, z0[i], z1[i]);
            float sx = view.screenX(x, z), sy = view.screenY(x, z);
            if (!inside(sx, sy, cx, cy, r, square)) continue;
            h = h * 31 + Math.round(sx * 2);
            h = h * 31 + Math.round(sy * 2);
            if (asHead(i, heads)) h = h * 31 + skins[i].hashCode() * 31 + System.identityHashCode(faces[i]);
            else if (kind == RadarRules.Kind.PLAYERS) h = h * 31 + Math.round(yaw1[i] / 6f);
            if (arrows) h = h * 31 + RadarRules.height(ys[i] - playerY);
            h = h * 31 + colours[i];
            h = h * 31 + kind.ordinal() + 1;
        }
        return h;
    }

    /**
     * Draws the radar over a map: the minimap (inside {@code r} of its centre, round or square) or the world map (the
     * whole screen, {@code r} 0). Returns the name of the player pointed at ({@code mx}, {@code my}; world map only),
     * or null.
     */
    public static Component draw(GuiGraphicsExtractor g, MapView view, float pt, boolean minimap, float cx, float cy, float r, boolean square,
                                 double playerY, float alpha, int mx, int my) {
        Component pointed = null;
        float dot = minimap ? DOT_MINIMAP : DOT_MAP, arrow = minimap ? ARROW_MINIMAP : ARROW_MAP;
        float playerHead = minimap ? HEAD_MINIMAP : HEAD_MAP, mobHead = minimap ? MOB_HEAD_MINIMAP : MOB_HEAD_MAP;
        // Sable's sub-levels first, under everything that may stand on them.
        SableRadar.draw(g, view, pt, minimap, cx, cy, r, square, alpha);
        boolean arrows = MapConfig.get(MapConfig.RADAR_HEIGHT);
        boolean heads = MapConfig.get(MapConfig.RADAR_PLAYER_LOOK) == RadarRules.PlayerLook.HEADS;
        // Players last, over the mobs.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                RadarRules.Kind kind = kinds[i];
                boolean player = kind == RadarRules.Kind.PLAYERS;
                if (player != (pass == 1) || !show(kind).on(minimap)) continue;
                double x = Mth.lerp(pt, x0[i], x1[i]), z = Mth.lerp(pt, z0[i], z1[i]);
                float sx = view.screenX(x, z), sy = view.screenY(x, z);
                if (r > 0 && !inside(sx, sy, cx, cy, r, square)) continue;
                int level = arrows ? RadarRules.height(ys[i] - playerY) : 0;
                float a = alpha * (level == 0 ? 1f : OTHER_HEIGHT_ALPHA);
                int colour = colours[i];
                boolean asHead = asHead(i, heads);
                float ox, oy;
                if (asHead) {
                    float size = player ? playerHead : mobHead;
                    int frame = !player ? colour : teams[i] == -1 ? Theme.RADAR_PLAYER_FRAME : 0xFF000000 | teams[i];
                    head(g, skins[i], faces[i], sx, sy, size, frame, a);
                    ox = reach(faces[i], size, true);
                    oy = reach(faces[i], size, false);
                    if (player && !minimap && Math.abs(mx - sx) <= ox && Math.abs(my - sy) <= oy) pointed = names[i];
                } else if (player) {
                    float heading = (float) view.headingOnScreen(Mth.rotLerp(pt, yaw0[i], yaw1[i]));
                    Draw.icon(g, Icon.PLAYER, sx, sy, arrow + 2.5f, Theme.withAlpha(Theme.RADAR_OUTLINE, a), heading);
                    Draw.icon(g, Icon.PLAYER, sx, sy, arrow, Theme.withAlpha(colour, a), heading);
                    if (!minimap && Draw.inCircle(mx, my, sx, sy, arrow * 0.6f)) pointed = names[i];
                    ox = arrow * 0.45f;
                    oy = arrow * 0.3f;
                } else {
                    Draw.circle(g, sx, sy, dot + 0.6f, Theme.withAlpha(Theme.RADAR_OUTLINE, a));
                    Draw.circle(g, sx, sy, dot, Theme.withAlpha(colour, a));
                    ox = oy = dot;
                }
                if (level != 0) heightArrow(g, sx + ox + 1.4f, sy - oy + 0.4f, minimap ? 1.3f : 1.7f, level, alpha);
            }
        }
        return pointed;
    }

    /**
     * A head (look Heads): the face from its texture and the parts over it (a player's hat layer, a mob's nose or snout),
     * its longer side about {@code size} GUI units, in a frame of {@code frame} with a dark rim (user choices
     * 13.11.2026, variant B of the players' mockup, variant A of the mobs': the frame in the colour of the kind). Laid
     * on whole screen pixels, the frame and rim as thick on every side (user report 13.11.2026: at 7 units, 21 pixels
     * for 8 of the skin and edges rounded each on its own, the pixels and the frame came out uneven). Every head's
     * longer side is as long ({@link MobFaces#side}: a face of 8 pixels on whole screen pixels a pixel, the others as
     * large, user choice 13.11.2026, variant B), each part from and to whole screen pixels. The frame and the rim a
     * screen pixel each at GUI 2 and 3, together within a pixel of the GUI (user request 13.11.2026), the frame two at
     * GUI 4.
     */
    private static void head(GuiGraphicsExtractor g, Identifier texture, MobFaces.Face face, float x, float y, float size, int frame, float alpha) {
        float s = Draw.guiScale();
        int side = MobFaces.side(size, s);
        int pw = MobFaces.at(2 * face.w(), side, face), ph = MobFaces.at(2 * face.h(), side, face);
        float x0 = Math.round(x * s - pw / 2f) / s, y0 = Math.round(y * s - ph / 2f) / s;
        // The rim, then the frame, along the shape of the seen pixels (user report 13.11.2026).
        FaceOutline outline = outline(face, side, s);
        rects(g, outline.rim, x0, y0, s, Theme.withAlpha(Theme.RADAR_OUTLINE, alpha));
        rects(g, outline.frame, x0, y0, s, Theme.withAlpha(frame, alpha));
        int white = Theme.withAlpha(0xFFFFFFFF, alpha);
        int[] p = face.parts();
        for (int k = 0; k + MobFaces.PART <= p.length; k += MobFaces.PART) {
            int n = p[k];
            Identifier location = n == 0 ? texture : face.textures()[n].startsWith(MobFaces.LAYER) ? layer(texture, face.textures()[n]) : texture(face, n);
            Tex tex = Tex.clamped(Minecraft.getInstance().getTextureManager().getTexture(location), false);
            float tw = face.texW()[n], th = face.texH()[n];
            int u = p[k + 1], v = p[k + 2], sw = p[k + 3], sh = p[k + 4];
            // Where it lies is in halves of a pixel of the texture; the texture moved a little (MobFaces.lead) so no
            // screen pixel samples on the line between two of its pixels, nor any pixel beside the part.
            int ax = MobFaces.at(p[k + 5], side, face), ay = MobFaces.at(p[k + 6], side, face);
            int bx = MobFaces.at(p[k + 5] + 2 * sw, side, face), by = MobFaces.at(p[k + 6] + 2 * sh, side, face);
            float lu = MobFaces.lead(bx - ax), lv = MobFaces.lead(by - ay);
            Draw.texturedQuad(g, tex, x0 + ax / s, y0 + ay / s, x0 + bx / s, y0 + by / s,
                    (u + lu) / tw, (v + lv) / th, (u + sw + lu) / tw, (v + sh + lv) / th, white);
        }
    }

    /** Textures named after a mob's own (its first texture, the layer's "from>to"), made once each. */
    private static final IdentityHashMap<Identifier, Identifier> LAYERS = new IdentityHashMap<>();

    /** The texture of a layer named after the mob's own texture {@code first} (the copper golem's eyes as it oxidizes). */
    private static Identifier layer(Identifier first, String layer) {
        Identifier r = LAYERS.get(first);
        if (r == null) {
            r = first.withPath(MobFaces.layerPath(first.getPath(), layer));
            LAYERS.put(first, r);
        }
        return r;
    }

    /** Rectangles of screen pixels from a head's corner ({@code x0}, {@code y0}). */
    private static void rects(GuiGraphicsExtractor g, int[] r, float x0, float y0, float s, int argb) {
        for (int i = 0; i + 3 < r.length; i += 4) Draw.rect(g, x0 + r[i] / s, y0 + r[i + 1] / s, x0 + r[i + 2] / s, y0 + r[i + 3] / s, argb);
    }

    /**
     * The outline of a face whose longer side is {@code side} screen pixels, made once (the seen pixels read from the
     * textures as the game has them, so resource packs count) and again when the GUI scale changes.
     */
    private static FaceOutline outline(MobFaces.Face face, int side, float s) {
        if (s != outlineScale) {
            OUTLINES.clear();
            outlineScale = s;
        }
        FaceOutline[] bySide = OUTLINES.get(face);
        if (bySide == null) {
            bySide = new FaceOutline[17];
            OUTLINES.put(face, bySide);
        }
        int slot = Math.min(side / 8, bySide.length - 1);
        FaceOutline o = bySide[slot];
        if (o == null || slot != side / 8) {
            o = FaceOutline.of(face == MobFaces.PLAYER ? null : seen(face, side), MobFaces.at(2 * face.w(), side, face),
                    MobFaces.at(2 * face.h(), side, face), frameLine(s), rimLine(s));
            bySide[slot] = o;
        }
        return o;
    }

    /**
     * Which screen pixels of a face whose longer side is {@code side} of them are seen (any part not see-through): each
     * part's screen pixels taken at their middles, as the game's nearest sampling of {@link #head} takes them.
     */
    private static boolean[] seen(MobFaces.Face face, int side) {
        int pw = MobFaces.at(2 * face.w(), side, face), ph = MobFaces.at(2 * face.h(), side, face);
        boolean[] seen = new boolean[pw * ph];
        NativeImage[] images = new NativeImage[face.textures().length];
        try {
            int[] p = face.parts();
            for (int k = 0; k + MobFaces.PART <= p.length; k += MobFaces.PART) {
                int n = p[k];
                if (images[n] == null) images[n] = image(texture(face, n));
                NativeImage image = images[n];
                // a texture finer than its model (the ghasts' since 26.1, packs of higher resolution): the middle of each
                // of the model's pixels
                float fu = image == null ? 1 : image.getWidth() / (float) face.texW()[n];
                float fv = image == null ? 1 : image.getHeight() / (float) face.texH()[n];
                int sw = p[k + 3], sh = p[k + 4];
                int ax = MobFaces.at(p[k + 5], side, face), ay = MobFaces.at(p[k + 6], side, face);
                int nx = MobFaces.at(p[k + 5] + 2 * sw, side, face) - ax, ny = MobFaces.at(p[k + 6] + 2 * sh, side, face) - ay;
                for (int y = 0; y < ny && ay + y < ph; y++)
                    for (int x = 0; x < nx && ax + x < pw; x++) {
                        // the pixel of the texture this screen pixel samples, as head() draws it
                        int u = (int) ((p[k + 1] + MobFaces.sample(x, nx, sw)) * fu);
                        int v = (int) ((p[k + 2] + MobFaces.sample(y, ny, sh)) * fv);
                        // getPixel is ARGB since 26.1: the alpha is the high byte as before
                        if (image == null || u >= image.getWidth() || v >= image.getHeight() || (image.getPixel(u, v) >>> 24) != 0)
                            seen[(ay + y) * pw + ax + x] = true;
                    }
            }
        } finally {
            for (NativeImage image : images) if (image != null) image.close();
        }
        return seen;
    }

    /** A texture as the game's resources have it now (resource packs included), or null. */
    private static NativeImage image(Identifier location) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open()) {
            return Game.readImage(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static int frameLine(float scale) {
        return Math.max(1, Math.round(HEAD_FRAME * scale));
    }

    private static int rimLine(float scale) {
        return Math.max(1, Math.round(HEAD_RIM * scale));
    }

    /** Half the width ({@code across}) or height of a head with its frame and rim, in GUI units. */
    private static float reach(MobFaces.Face face, float size, boolean across) {
        float s = Draw.guiScale();
        int side = MobFaces.side(size, s);
        return (MobFaces.at(2 * (across ? face.w() : face.h()), side, face) / 2f + frameLine(s) + rimLine(s)) / s;
    }

    /** A small white triangle pointing up ({@code dir} 1) or down (-1), with a dark rim, like the pins of other layers. */
    private static void heightArrow(GuiGraphicsExtractor g, float x, float y, float s, int dir, float alpha) {
        int rim = Theme.withAlpha(Theme.RADAR_OUTLINE, alpha), fill = Theme.withAlpha(Theme.RADAR_HEIGHT, alpha);
        float o = s + 0.8f;
        if (dir > 0) {
            Draw.triangle(g, x, y - o, x + o, y + o * 0.7f, x - o, y + o * 0.7f, rim);
            Draw.triangle(g, x, y - s, x + s, y + s * 0.7f, x - s, y + s * 0.7f, fill);
        } else {
            Draw.triangle(g, x, y + o, x - o, y - o * 0.7f, x + o, y - o * 0.7f, rim);
            Draw.triangle(g, x, y + s, x - s, y - s * 0.7f, x + s, y - s * 0.7f, fill);
        }
    }
}
