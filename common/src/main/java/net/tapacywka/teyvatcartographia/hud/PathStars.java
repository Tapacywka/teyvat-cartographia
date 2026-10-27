package net.tapacywka.teyvatcartographia.hud;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.nav.Route;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.tapacywka.teyvatcartographia.render.WorldGlow;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The path to the tracked target drawn in the world as small golden sparkles, along the whole line of the world map
 * ({@link Route}: the near path, then the far one) but only within {@code RADIUS} blocks of the
 * camera: from afar nothing shows, and while the player walks on they appear ahead and fade behind by themselves. They
 * live on the {@code WINDOW} blocks of the line ahead of the player (measured along it from where the walked part ends,
 * {@link Route#cut}): each appears somewhere there, scattered up to {@code SPREAD} blocks to the side, twinkles while it drifts a
 * little towards the target and upwards, and fades out; then it appears again elsewhere. Every one of the {@code SLOTS}
 * sparkles has its own life length; where a life starts (and its height) is kept for that life, so a sparkle stays in its
 * place while the player walks. Its height is the ground there under any leaves (so none floats over a tree crown), or
 * underground the cave floor of the path. Each faces the camera: a large four-pointed sparkle (about a third) or a small
 * mote, like Genshin Impact's path stars, added to the scene in
 * the beam's golden colour; terrain hides it. They thin out just ahead of the player, at the target and towards the edge of the
 * radius.
 */
final class PathStars {
    private PathStars() {}

    /**
     * Sparkles show within this distance of the camera and fade over the last EDGE_FADE blocks (32, as vanilla particles,
     * until 26.10.2026: Genshin Impact shows its path stars much farther; twice the sparkles keep the same density).
     */
    private static final float RADIUS = 64, EDGE_FADE = 10;
    /** The part of the line ahead of the player they live on (blocks along it), and the fades after the player and before the target. */
    private static final float WINDOW = RADIUS + 8, START = 1.5f, FADE_IN = 3f, TARGET_FADE = 3f;
    /** Sparkles at a time (about 1.6 per block of the window), and their life length range (seconds). */
    private static final int SLOTS = 128;
    /** Per sparkle: the life it is in, where on the line (blocks along it) that life started and its height there; the line they belong to. */
    private static final int[] lifeOf = new int[SLOTS];
    private static final float[] bornAt = new float[SLOTS], bornY = new float[SLOTS];
    private static int routeVersion = Integer.MIN_VALUE;
    private static final float LIFE_MIN = 1.4f, LIFE_MAX = 2.4f;
    /** Within a life: fades in for this share, fades out for the last share. */
    private static final float LIFE_IN = 0.25f, LIFE_OUT = 0.4f;
    /** Scatter to the side and above the ground, and the drift over a life (blocks, blocks a second). */
    private static final float SPREAD = 0.35f, LIFT = 0.25f, LIFT_RANGE = 0.3f, DRIFT_FORWARD = 0.4f, DRIFT_UP = 0.1f, DRIFT_SIDE = 0.05f;
    /**
     * Like Genshin Impact's path stars (references {@code ingame_path_stars_01} to {@code 04}, variant B of the mockup,
     * user choice 26.10.2026): this share of them are large four-pointed sparkles, the rest small motes.
     */
    private static final float SPARKLE_SHARE = 0.35f;
    /**
     * Sparkle: the vertical arm, the horizontal arm, their width, the short diagonal rays and their width, the light core
     * and the round glow behind them; mote: its core and glow (blocks).
     */
    private static final float LONG = 0.34f, SHORT = 0.26f, ARM = 0.035f, DIAG = 0.1f, DIAG_W = 0.02f, CORE = 0.05f, GLOW = 0.16f,
            MOTE = 0.03f, MOTE_GLOW = 0.07f;

    /** Draws the sparkles with {@code alpha} (the marker's visibility), after the whole level (over the water in Fabulous too). */
    static void render(Camera camera, float alpha) {
        int n = Route.collect();
        ClientLevel level = Minecraft.getInstance().level;
        if (n < 2 || alpha <= 0.01f || level == null) return;
        float total = Route.along(n - 1);
        if (Route.version() != routeVersion) {
            // A new line: every sparkle starts a new life on it.
            routeVersion = Route.version();
            java.util.Arrays.fill(lifeOf, Integer.MIN_VALUE);
        }
        float pt = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        Vec3 me = camera.getEntity().getPosition(pt);
        // From where the walked part ends (moving forward only, shared with the map lines).
        Route.cut(me.x, me.z);
        // Only on the part of a new line drawn in so far (3 s after a new search, Route.shownTo).
        float from = Route.cutAlong() + START, end = Math.min(Math.min(from - START + WINDOW, total), Route.shownTo());
        if (end <= from) return;
        boolean cave = MapDataManager.get().undergroundBand() != null;
        Vec3 cam = camera.getPosition();
        Vector3f left = camera.getLeftVector(), up = camera.getUpVector();
        float lx = left.x(), ly = left.y(), lz = left.z(), ux = up.x(), uy = up.y(), uz = up.z();
        float time = (Util.getMillis() % 1_000_000L) / 1000f;
        int color = Theme.worldMarkerPulse(), coreColor = Theme.WORLD_MARKER_BEAM_CORE | 0xFF000000; // golden, a light core

        BufferBuilder b = WorldGlow.begin(VertexFormat.Mode.TRIANGLES);
        for (int j = 0; j < SLOTS; j++) {
            float life = LIFE_MIN + (LIFE_MAX - LIFE_MIN) * unit(hash(j, -1));
            float local = time / life + unit(hash(j, -2)); // each slot starts at its own point of its life
            int cycle = (int) Math.floor(local);
            float age = local - cycle; // 0..1 through this life
            int h = hash(j, cycle);
            float side = (unit(hash(h, 1)) * 2 - 1) * SPREAD;
            if (lifeOf[j] != cycle) {
                lifeOf[j] = cycle;
                bornAt[j] = from + (end - from) * unit(h);
                bornY[j] = groundAt(level, bornAt[j], side, n, cave);
            }
            if (Float.isNaN(bornY[j])) continue; // not loaded there
            float s = bornAt[j] + DRIFT_FORWARD * age * life;
            if (s >= total || s < 0) continue;
            float a = alpha * smooth((s - from) / FADE_IN) * smooth((total - s) / TARGET_FADE)
                    * smooth(age / LIFE_IN) * smooth((1 - age) / LIFE_OUT);
            float twinkle = 0.6f + 0.4f * (float) Math.sin(time * 9f + j * 2.3f);
            a *= twinkle;
            if (a <= 0.01f) continue;
            int i = segment(s, n);
            float t = segmentT(s, i);
            float dx = Route.x(i + 1) - Route.x(i), dz = Route.z(i + 1) - Route.z(i);
            float dl = (float) Math.sqrt(dx * dx + dz * dz);
            float sideX = dl < 1e-4f ? 1 : -dz / dl, sideZ = dl < 1e-4f ? 0 : dx / dl;
            side += Math.signum(side) * DRIFT_SIDE * age * life;
            float lift = LIFT + LIFT_RANGE * unit(hash(h, 2)) + DRIFT_UP * age * life;
            float x = lerp(Route.x(i), Route.x(i + 1), t) + sideX * side - (float) cam.x;
            float y = bornY[j] + lift - (float) cam.y;
            float z = lerp(Route.z(i), Route.z(i + 1), t) + sideZ * side - (float) cam.z;
            // Like particles: nothing beyond the radius around the camera, fading towards its edge.
            float d = (float) Math.sqrt(x * x + y * y + z * z);
            if (d >= RADIUS) continue;
            a *= smooth((RADIUS - d) / EDGE_FADE);
            if (a <= 0.01f) continue;
            int gold = Theme.withAlpha(color, a), glow = Theme.withAlpha(color, a * 0.45f), light = Theme.withAlpha(coreColor, a);
            if (unit(hash(h, 3)) < SPARKLE_SHARE) {
                // A large sparkle: round glow, the vertical (long) and horizontal arms, two short diagonal rays, a light core.
                float k = (0.8f + 0.2f * twinkle) * (0.8f + 0.4f * unit(hash(h, 4)));
                halo(b, x, y, z, lx, ly, lz, ux, uy, uz, GLOW * k, glow);
                sparkle(b, x, y, z, lx, ly, lz, ux, uy, uz, ARM * k, LONG * k, gold);
                sparkle(b, x, y, z, lx, ly, lz, ux, uy, uz, SHORT * k, ARM * k, gold);
                // The diagonals: the camera's left and up turned by 45 degrees.
                float ax = (lx + ux) * 0.7071f, ay = (ly + uy) * 0.7071f, az = (lz + uz) * 0.7071f;
                float bx = (ux - lx) * 0.7071f, by = (uy - ly) * 0.7071f, bz = (uz - lz) * 0.7071f;
                sparkle(b, x, y, z, ax, ay, az, bx, by, bz, DIAG_W * k, DIAG * k, gold);
                sparkle(b, x, y, z, ax, ay, az, bx, by, bz, DIAG * k, DIAG_W * k, gold);
                sparkle(b, x, y, z, lx, ly, lz, ux, uy, uz, CORE * k, CORE * k, light);
            } else {
                // A small mote: a soft round glow round a light dot.
                float k = 0.8f + 0.2f * twinkle;
                halo(b, x, y, z, lx, ly, lz, ux, uy, uz, MOTE_GLOW * k, glow);
                sparkle(b, x, y, z, lx, ly, lz, ux, uy, uz, MOTE * k, MOTE * k, light);
            }
        }
        WorldGlow.draw(b);
    }

    /**
     * Height of the ground at {@code s} blocks along the line, {@code side} blocks across it: underground the path's own
     * (the cave floor), else the surface under any leaves (water counts as ground); NaN where the chunk is not loaded.
     */
    private static float groundAt(ClientLevel level, float s, float side, int n, boolean cave) {
        int i = segment(s, n);
        float t = segmentT(s, i);
        if (cave) {
            float y0 = Route.y(i), y1 = Route.y(i + 1);
            if (!Float.isNaN(y0) && !Float.isNaN(y1)) return lerp(y0, y1, t);
        }
        float dx = Route.x(i + 1) - Route.x(i), dz = Route.z(i + 1) - Route.z(i);
        float dl = (float) Math.sqrt(dx * dx + dz * dz);
        float sideX = dl < 1e-4f ? 1 : -dz / dl, sideZ = dl < 1e-4f ? 0 : dx / dl;
        int bx = (int) Math.floor(lerp(Route.x(i), Route.x(i + 1), t) + sideX * side);
        int bz = (int) Math.floor(lerp(Route.z(i), Route.z(i + 1), t) + sideZ * side);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return Float.NaN;
        // From the top (the client keeps only this heightmap) down through leaves and plants, like the path search.
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz), bottom = level.getMinBuildHeight();
        for (int k = 0; k < 24 && y > bottom; k++, y--) {
            BlockState st = level.getBlockState(POS.set(bx, y - 1, bz));
            // Trunks too: a sparkle beside a tree belongs at its foot, not on top of the trunk.
            if (!st.getFluidState().isEmpty() || st.blocksMotion() && !st.is(BlockTags.LEAVES) && !st.is(BlockTags.LOGS)) return y;
        }
        return Float.NaN;
    }

    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    /** The segment (its first point) holding the point {@code s} blocks along the line: binary search, 0..n-2. */
    private static int segment(float s, int n) {
        int lo = 0, hi = n - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (Route.along(mid) <= s) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** Where {@code s} lies within segment {@code i}, 0..1. */
    private static float segmentT(float s, int i) {
        float a = Route.along(i), len = Route.along(i + 1) - a;
        return len < 1e-6f ? 0 : Math.max(0, Math.min(1, (s - a) / len));
    }

    /** A diamond with half-width {@code w} along the camera's left and half-height {@code h} along its up: bright centre, clear tips. */
    private static void sparkle(BufferBuilder b, float x, float y, float z, float lx, float ly, float lz, float ux, float uy, float uz,
                                float w, float h, int color) {
        int clear = color & 0x00FFFFFF;
        float[] tx = TIPS_X, ty = TIPS_Y;
        for (int q = 0; q < 4; q++) {
            float ax = tx[q] * w, ay = ty[q] * h, bx = tx[q + 1] * w, by = ty[q + 1] * h;
            b.addVertex(x, y, z).setColor(color);
            b.addVertex(x + lx * ax + ux * ay, y + ly * ax + uy * ay, z + lz * ax + uz * ay).setColor(clear);
            b.addVertex(x + lx * bx + ux * by, y + ly * bx + uy * by, z + lz * bx + uz * by).setColor(clear);
        }
    }

    private static final float[] TIPS_X = {0, 1, 0, -1, 0}, TIPS_Y = {1, 0, -1, 0, 1};

    /** A round glow of radius {@code r} facing the camera: bright in the middle, clear at the rim (an octagon). */
    private static void halo(BufferBuilder b, float x, float y, float z, float lx, float ly, float lz, float ux, float uy, float uz,
                             float r, int color) {
        int clear = color & 0x00FFFFFF;
        for (int q = 0; q < 8; q++) {
            float ax = RIM_X[q] * r, ay = RIM_Y[q] * r, bx = RIM_X[q + 1] * r, by = RIM_Y[q + 1] * r;
            b.addVertex(x, y, z).setColor(color);
            b.addVertex(x + lx * ax + ux * ay, y + ly * ax + uy * ay, z + lz * ax + uz * ay).setColor(clear);
            b.addVertex(x + lx * bx + ux * by, y + ly * bx + uy * by, z + lz * bx + uz * by).setColor(clear);
        }
    }

    private static final float[] RIM_X = new float[9], RIM_Y = new float[9];

    static {
        for (int q = 0; q <= 8; q++) {
            RIM_X[q] = (float) Math.cos(q * Math.PI / 4);
            RIM_Y[q] = (float) Math.sin(q * Math.PI / 4);
        }
    }

    /** A well mixed hash of a slot and one of its lives (murmur3 finaliser). */
    private static int hash(int slot, int cycle) {
        int h = slot * 0x9E3779B1 ^ cycle * 0x85EBCA77;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    /** The top 24 bits of {@code h} as 0..1. */
    private static float unit(int h) {
        return (h >>> 8) / (float) (1 << 24);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smooth(float t) {
        float x = Math.max(0, Math.min(1, t));
        return x * x * (3 - 2 * x);
    }
}
