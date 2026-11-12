package net.tapacywka.teyvatcartographia.radar;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Team;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.tapacywka.teyvatcartographia.version.Game;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The radar of players and entities on both maps (user decisions 12.11.2026, docs/RADAR_SPEC.md): the entities around
 * the player, gathered once a tick, drawn as dots in the colour of their kind (players as an arrow in their team's
 * colour), fainter with a small arrow when well above or below. The rules are in {@link RadarRules}.
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
    /** The ids of the entities the client knows (the game's server gives them the same ids). */
    private static final IntOpenHashSet known = new IntOpenHashSet();
    /** The server forbade a radar (Xaero's fair play code in its chat) since the player joined. */
    private static boolean forbidden;

    /** What the game's own server had around the player (single player), written on its thread. */
    private record Far(int n, int[] id, RadarRules.Kind[] kind, double[] x0, double[] z0, double[] x1, double[] z1, double[] y,
                       float[] yaw0, float[] yaw1, int[] team, Component[] name) {}
    private static volatile Far far;
    private static final AtomicBoolean asking = new AtomicBoolean();

    /** Sizes in GUI units: the dots' radius and the players' arrows, on the minimap and on the world map. */
    public static final float DOT_MINIMAP = 1.5f, DOT_MAP = 2f, ARROW_MINIMAP = 10f, ARROW_MAP = 13f;
    /** Opacity of what is well above or below. */
    private static final float OTHER_HEIGHT_ALPHA = 0.5f;

    /** The setting of a kind: where it shows. */
    public static RadarRules.Show show(RadarRules.Kind kind) {
        return MapConfig.get(switch (kind) {
            case PLAYERS -> MapConfig.RADAR_PLAYERS;
            case HOSTILE -> MapConfig.RADAR_HOSTILE;
            case PASSIVE -> MapConfig.RADAR_PASSIVE;
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
                colours[i] = c == null ? Theme.RADAR_PLAYER : 0xFF000000 | c;
            } else {
                names[i] = null;
                colours[i] = colour(kind);
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
                colours[i] = kind == RadarRules.Kind.PLAYERS ? (f.team[j] == -1 ? Theme.RADAR_PLAYER : 0xFF000000 | f.team[j]) : colour(kind);
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
            n++;
        }
        return new Far(n, id, kind, ax, az, bx, bz, y, ya, yb, team, name);
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
        }
        return count++;
    }

    /** The kind of an entity on the radar, or null for one it never shows (paintings, arrows, the like). */
    static RadarRules.Kind kindOf(Entity e, UUID me) {
        if (e instanceof Player p) return p.isSpectator() ? null : RadarRules.Kind.PLAYERS;
        if (e instanceof OwnableEntity o && o.getOwnerReference() != null && me.equals(o.getOwnerReference().getUUID())) return RadarRules.Kind.PETS;
        if (e instanceof Enemy) return RadarRules.Kind.HOSTILE;
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
        far = null;
        forbidden = false;
    }

    /** The colour of a kind but players (theirs is their team's). */
    private static int colour(RadarRules.Kind kind) {
        return switch (kind) {
            case PLAYERS -> Theme.RADAR_PLAYER;
            case HOSTILE -> Theme.RADAR_HOSTILE;
            case PASSIVE -> Theme.RADAR_PASSIVE;
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

    /**
     * A number that changes whenever what the minimap's radar shows moves by half a GUI unit or turns (its layer is
     * redrawn then, at the minimap's update rate), 0 with nothing to show.
     */
    public static long signature(MapView view, float pt, float cx, float cy, float r, boolean square, double playerY) {
        long h = SableRadar.signature(view, pt, cx, cy, r, square);
        boolean arrows = MapConfig.get(MapConfig.RADAR_HEIGHT);
        for (int i = 0; i < count; i++) {
            RadarRules.Kind kind = kinds[i];
            if (!show(kind).on(true)) continue;
            double x = Mth.lerp(pt, x0[i], x1[i]), z = Mth.lerp(pt, z0[i], z1[i]);
            float sx = view.screenX(x, z), sy = view.screenY(x, z);
            if (!inside(sx, sy, cx, cy, r, square)) continue;
            h = h * 31 + Math.round(sx * 2);
            h = h * 31 + Math.round(sy * 2);
            if (kind == RadarRules.Kind.PLAYERS) h = h * 31 + Math.round(yaw1[i] / 6f);
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
        // Sable's sub-levels first, under everything that may stand on them.
        SableRadar.draw(g, view, pt, minimap, cx, cy, r, square, alpha);
        boolean arrows = MapConfig.get(MapConfig.RADAR_HEIGHT);
        // Players last, over the mobs.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                RadarRules.Kind kind = kinds[i];
                if ((kind == RadarRules.Kind.PLAYERS) != (pass == 1) || !show(kind).on(minimap)) continue;
                double x = Mth.lerp(pt, x0[i], x1[i]), z = Mth.lerp(pt, z0[i], z1[i]);
                float sx = view.screenX(x, z), sy = view.screenY(x, z);
                if (r > 0 && !inside(sx, sy, cx, cy, r, square)) continue;
                int level = arrows ? RadarRules.height(ys[i] - playerY) : 0;
                float a = alpha * (level == 0 ? 1f : OTHER_HEIGHT_ALPHA);
                int colour = colours[i];
                if (kind == RadarRules.Kind.PLAYERS) {
                    float heading = (float) view.headingOnScreen(Mth.rotLerp(pt, yaw0[i], yaw1[i]));
                    Draw.icon(g, Icon.PLAYER, sx, sy, arrow + 2.5f, Theme.withAlpha(Theme.RADAR_OUTLINE, a), heading);
                    Draw.icon(g, Icon.PLAYER, sx, sy, arrow, Theme.withAlpha(colour, a), heading);
                    if (!minimap && Draw.inCircle(mx, my, sx, sy, arrow * 0.6f)) pointed = names[i];
                } else {
                    Draw.circle(g, sx, sy, dot + 0.6f, Theme.withAlpha(Theme.RADAR_OUTLINE, a));
                    Draw.circle(g, sx, sy, dot, Theme.withAlpha(colour, a));
                }
                if (level != 0) heightArrow(g, sx + (kind == RadarRules.Kind.PLAYERS ? arrow * 0.45f : dot) + 1.4f,
                        sy - (kind == RadarRules.Kind.PLAYERS ? arrow * 0.3f : dot) + 0.4f, minimap ? 1.3f : 1.7f, level, alpha);
            }
        }
        return pointed;
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
