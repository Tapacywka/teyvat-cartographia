package net.tapacywka.teyvatcartographia.compat;

/**
 * The rules of Create's railways on the maps, apart from Create and the screen so they can be tested: how finely a
 * track is drawn, when it shows its rails and sleepers, when a train shows its carriages or its badge, and the state a
 * train is shown in.
 */
public final class CreateRules {
    private CreateRules() {}

    /** Blocks between the two rails of a track, as Create draws them. */
    public static final float GAUGE = 1.2f;
    /** Blocks between two sleepers. */
    public static final float SLEEPER_STEP = 1f;
    /** The narrowest a track is drawn, GUI units between its rails: farther out it stays this wide on screen. */
    public static final float MIN_GAUGE = 2.6f;
    /** Below this many GUI units a block, a track is one line: its rails would melt into one. */
    public static final float RAILS_FROM = 1 / 4f;
    /** From this many GUI units a block a train shows its carriages; farther out its badge. */
    public static final float CARRIAGES_FROM = 1 / 2f;

    /**
     * How many points a track between two nodes is drawn through: a straight one its two ends, a curve one every two
     * blocks of its length (at least 4, at most 64).
     */
    public static int points(double length, boolean turn) {
        if (!turn) return 2;
        return (int) Math.max(4, Math.min(64, Math.ceil(length / 2) + 1));
    }

    /** Whether tracks show their two rails and sleepers at a map scale (GUI units a block), else one line. */
    public static boolean rails(double scale) {
        return scale >= RAILS_FROM;
    }

    /** GUI units between the rails at a map scale: the track's own gauge, never narrower than {@link #MIN_GAUGE}. */
    public static float gauge(double scale) {
        return (float) Math.max(MIN_GAUGE, GAUGE * scale);
    }

    /** The closest sleepers are drawn on screen, GUI units: farther out they would run into one band. */
    public static final float MIN_SLEEPER_STEP = 3.5f;

    /** GUI units between sleepers at a map scale: a block, never closer than {@link #MIN_SLEEPER_STEP}. */
    public static float sleeperStep(double scale) {
        return (float) Math.max(MIN_SLEEPER_STEP, SLEEPER_STEP * scale);
    }

    /** Whether a train shows its carriages at a map scale, else one badge. */
    public static boolean carriages(double scale) {
        return scale >= CARRIAGES_FROM;
    }

    /** What a train is doing, as its colour on the map says. */
    public enum Look {
        /** Running. */
        MOVING,
        /** Waiting for a signal, redstone or another train. */
        WAITING,
        /** Derailed, lost its way or its conductor. */
        TROUBLE,
        /** Standing still, at a station or stopped by hand. */
        STOPPED
    }

    /**
     * A train's look from Create's own words for it: its state ({@code RUNNING}, {@code RUNNING_MANUALLY},
     * {@code DERAILED}, {@code SCHEDULE_INTERRUPTED}, {@code CONDUCTOR_MISSING}, {@code NAVIGATION_FAILED}), what it waits
     * for ({@code NOT_WAITING} or another) and whether it moved since the last update. Fuel plays no part: Create's
     * trains run without it, and its train map's {@code fueled} only says that fuel speeds one up.
     */
    public static Look look(String state, String signal, boolean moved) {
        if ("DERAILED".equals(state) || "NAVIGATION_FAILED".equals(state) || "CONDUCTOR_MISSING".equals(state)) {
            return Look.TROUBLE;
        }
        if (signal != null && !"NOT_WAITING".equals(signal)) return Look.WAITING;
        return moved ? Look.MOVING : Look.STOPPED;
    }

    /** Whether a train moved between two updates of its positions (more than a tenth of a block). */
    public static boolean moved(float[] before, float[] now) {
        if (before == null || now == null || before.length != now.length) return true;
        for (int i = 0; i < now.length; i++) if (Math.abs(before[i] - now[i]) > 0.1f) return true;
        return false;
    }

    /**
     * How long a train still counts as running after it last moved, ms. Create sends the trains' positions four times a
     * second and the map reads them every frame: between two updates a running train has not moved, and judged frame
     * by frame its colour flickered between running and standing.
     */
    public static final long RUNNING_HOLD = 1000;

    /** Whether a train that last moved at {@code movedAt} (0: never) still counts as running at {@code now}, both ms. */
    public static boolean running(long movedAt, long now) {
        return movedAt > 0 && now - movedAt < RUNNING_HOLD;
    }

    /**
     * Ms a train is moved over from where it is drawn to its newest position: longer than Create's 250 ms between
     * updates, as the map reads them on the client's ticks, which do not keep step with the server's: one update in a
     * few comes a tick or two later, and a glide of 250 ms stopped the train for those ms about once a second.
     */
    public static final float GLIDE = 350;

    /** How far along its glide a train is {@code ms} after its newest position came, 0 to 1. */
    public static float glide(long ms) {
        return Math.max(0, Math.min(1, ms / GLIDE));
    }

    /**
     * Positions {@code f} of the way from {@code from} to {@code to} (same length; else {@code to}): where a train is
     * drawn now, and where its next glide starts when a new position comes, so a late update never makes it jump.
     */
    public static float[] between(float[] from, float[] to, float f) {
        if (from == null || from.length != to.length) return to;
        float[] out = new float[to.length];
        for (int i = 0; i < to.length; i++) out[i] = from[i] + (to[i] - from[i]) * f;
        return out;
    }

    /** Where a station's name goes beside its plate on the map. */
    public enum Side { ABOVE, BELOW, LEFT, RIGHT }

    /**
     * The side of its plate a station's name goes to: the side of the track its station block stands, ({@code dx},
     * {@code dy}) on screen from the track to the block. Above a track running up and down the name lay on the rails.
     * Not known (no offset, NaN): above.
     */
    public static Side labelSide(double dx, double dy) {
        if (Double.isNaN(dx) || Double.isNaN(dy) || Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return Side.ABOVE;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? Side.RIGHT : Side.LEFT;
        return dy > 0 ? Side.BELOW : Side.ABOVE;
    }

    /** The name a train is shown with: its own, else none (the caller names it after its owner). */
    public static String name(String own) {
        return own == null || own.isBlank() ? null : own.strip();
    }

    /**
     * The line through {@code n} points of {@code xy} (x, y each) moved {@code d} to its left on screen (y down), into
     * {@code out}: one rail of a track. Each point moves along the mean of its two pieces' normals, as far as keeps the
     * moved line parallel to both (at most twice {@code d}, so a sharp corner does not shoot out).
     */
    public static void offset(float[] xy, int n, float d, float[] out) {
        for (int i = 0; i < n; i++) {
            float nx = 0, ny = 0, ax = 0, ay = 0;
            boolean before = i > 0, after = i < n - 1;
            if (before) {
                float dx = xy[2 * i] - xy[2 * i - 2], dy = xy[2 * i + 1] - xy[2 * i - 1], len = (float) Math.hypot(dx, dy);
                if (len > 1e-6f) {
                    ax = dy / len;
                    ay = -dx / len;
                    nx += ax;
                    ny += ay;
                }
            }
            if (after) {
                float dx = xy[2 * i + 2] - xy[2 * i], dy = xy[2 * i + 3] - xy[2 * i + 1], len = (float) Math.hypot(dx, dy);
                if (len > 1e-6f) {
                    nx += dy / len;
                    ny += -dx / len;
                }
            }
            float len = (float) Math.hypot(nx, ny), k = 1;
            if (len < 1e-6f) {
                nx = 0;
                ny = 0;
            } else {
                nx /= len;
                ny /= len;
                if (before && after) k = 1 / Math.max(0.5f, nx * ax + ny * ay);
            }
            out[2 * i] = xy[2 * i] + nx * d * k;
            out[2 * i + 1] = xy[2 * i + 1] + ny * d * k;
        }
    }

    /**
     * The sleepers along a line through {@code n} points of {@code xy}: a bar {@code 2 half} long and {@code thick} wide
     * across it every {@code step}, the first {@code phase} along it, as the four corners of each (x, y each) into
     * {@code quads} from quad {@code from}, at most up to quad {@code max}. Returns the number of quads now in it.
     */
    public static int sleepers(float[] xy, int n, float step, float phase, float half, float thick, float[] quads, int from, int max) {
        int count = from;
        float next = phase, at = 0, w = thick / 2;
        for (int i = 0; i + 1 < n && count < max; i++) {
            float x0 = xy[2 * i], y0 = xy[2 * i + 1], dx = xy[2 * i + 2] - x0, dy = xy[2 * i + 3] - y0, len = (float) Math.hypot(dx, dy);
            if (len < 1e-6f) continue;
            float ux = dx / len, uy = dy / len, nx = uy, ny = -ux;
            while (next <= at + len && count < max) {
                float s = next - at, cx = x0 + ux * s, cy = y0 + uy * s;
                int o = count * 8;
                quads[o] = cx - ux * w + nx * half;
                quads[o + 1] = cy - uy * w + ny * half;
                quads[o + 2] = cx + ux * w + nx * half;
                quads[o + 3] = cy + uy * w + ny * half;
                quads[o + 4] = cx + ux * w - nx * half;
                quads[o + 5] = cy + uy * w - ny * half;
                quads[o + 6] = cx - ux * w - nx * half;
                quads[o + 7] = cy - uy * w - ny * half;
                count++;
                next += step;
            }
            at += len;
        }
        return count;
    }

    /**
     * Pieces of track (x, z of each point in turn) joined into lines wherever exactly two of them meet: Create splits a
     * track into pieces between the nodes of its graph, and drawn apart each piece ended its rails in a gap and put a
     * sleeper at both sides of the joint. At a switch or a crossing (three or more pieces at a point) lines stay apart.
     * Ends meet when they are within a hundredth of a block. Closed loops come out as one line from any of their joints.
     */
    public static java.util.List<float[]> join(java.util.List<float[]> pieces) {
        int n = pieces.size();
        java.util.Map<Long, java.util.List<Integer>> at = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            float[] p = pieces.get(i);
            at.computeIfAbsent(key(p[0], p[1]), k -> new java.util.ArrayList<>()).add(i);
            at.computeIfAbsent(key(p[p.length - 2], p[p.length - 1]), k -> new java.util.ArrayList<>()).add(i);
        }
        boolean[] used = new boolean[n];
        java.util.List<float[]> out = new java.util.ArrayList<>();
        // Lines start at ends that do not join (dead ends, switches), then whatever is left (loops).
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                float[] p = pieces.get(i);
                boolean startFree = at.get(key(p[0], p[1])).size() != 2, endFree = at.get(key(p[p.length - 2], p[p.length - 1])).size() != 2;
                if (pass == 0 && !startFree && !endFree) continue;
                float[] line = endFree && !startFree ? reverse(p) : p;
                used[i] = true;
                while (true) {
                    long end = key(line[line.length - 2], line[line.length - 1]);
                    java.util.List<Integer> there = at.get(end);
                    if (there.size() != 2) break;
                    int next = used[there.get(0)] ? there.get(1) : there.get(0);
                    if (used[next]) break;
                    float[] q = pieces.get(next);
                    if (key(q[0], q[1]) != end) q = reverse(q);
                    float[] joined = new float[line.length + q.length - 2];
                    System.arraycopy(line, 0, joined, 0, line.length);
                    System.arraycopy(q, 2, joined, line.length, q.length - 2);
                    line = joined;
                    used[next] = true;
                }
                out.add(line);
            }
        }
        return out;
    }

    private static long key(float x, float z) {
        return ((long) Math.round(x * 100) << 32) ^ (Math.round(z * 100) & 0xFFFFFFFFL);
    }

    private static float[] reverse(float[] p) {
        float[] r = new float[p.length];
        for (int i = 0; i < p.length; i += 2) {
            r[p.length - 2 - i] = p[i];
            r[p.length - 1 - i] = p[i + 1];
        }
        return r;
    }

    /**
     * The distance at which a point lies from a line from (ax, ay) to (bx, by), all in GUI units: what the pointer has to
     * be within to point at a train.
     */
    public static float distanceToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        float t = len2 < 1e-6f ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        float x = ax + t * dx - px, y = ay + t * dy - py;
        return (float) Math.sqrt(x * x + y * y);
    }
}
