package net.tapacywka.teyvatcartographia.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;

import java.util.ArrayDeque;

/**
 * GUI drawing in GUI coordinates, as the mod's own GUI elements (since 26.1 the game collects a frame's GUI first
 * and draws it later): into the frame, or into a {@link CachedLayer} being redrawn.
 * <ul>
 *   <li>Shapes (circles, rounded rectangles, capsules, diamonds, triangles, lines) are anti-aliased with a
 *       one-physical-pixel feathered edge, so they look clean at every GUI scale.</li>
 *   <li>Text is drawn by {@link DrawText}.</li>
 *   <li>Icons come from the mip-mapped, premultiplied {@link IconAtlas}.</li>
 * </ul>
 */
public final class Draw {
    private Draw() {}

    // ================================================================== pixel grid

    public static float guiScale() {
        return (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Size of one physical pixel in GUI units. */
    public static float px() {
        return 1f / guiScale();
    }

    /** Rounds a GUI coordinate to the nearest physical pixel. */
    public static float snap(float v) {
        float s = guiScale();
        return Math.round(v * s) / s;
    }

    // ================================================================== where shapes go

    /** The layers being redrawn, innermost last; shapes go to the innermost, else to the GUI of the frame. */
    private static final ArrayDeque<CachedLayer> LAYERS = new ArrayDeque<>();

    static void pushLayer(CachedLayer layer) {
        LAYERS.addLast(layer);
    }

    static void popLayer(CachedLayer layer) {
        if (LAYERS.peekLast() == layer) LAYERS.removeLast();
        else LAYERS.remove(layer);
    }

    /**
     * Hands an element to the layer being redrawn or to the frame's GUI, cut to the clip rectangle in force. The game
     * orders the GUI's elements by their bounds: one overlapping an earlier element is drawn above it.
     */
    static void submit(GuiGraphicsExtractor g, GuiElementRenderState element) {
        if (element == null) return;
        CachedLayer layer = LAYERS.peekLast();
        if (layer != null) layer.add(element);
        else g.guiRenderState.addGuiElement(element);
    }

    static ScreenRectangle scissor(GuiGraphicsExtractor g) {
        return g.scissorStack.peek();
    }

    /** The scratch mesh every shape is built in (render thread only); {@link #submit} keeps a copy. */
    private static final Mesh MESH = new Mesh();

    private static Mesh beginColor() {
        return MESH.begin();
    }

    private static void endColor(GuiGraphicsExtractor g, Mesh mesh) {
        submit(g, Shape.of(mesh, Pipelines.COLOR, TextureSetup.noTexture(), false, scissor(g)));
    }

    public static Mesh beginTex() {
        return MESH.begin();
    }

    public static void endTex(GuiGraphicsExtractor g, Mesh mesh, Tex tex) {
        submit(g, Shape.of(mesh, Pipelines.TEX, tex.setup(), true, scissor(g)));
    }

    static void endPremultiplied(GuiGraphicsExtractor g, Mesh mesh, Tex tex) {
        submit(g, Shape.of(mesh, Pipelines.TEX_PREMULTIPLIED, tex.setup(), true, scissor(g)));
    }

    public static void vtx(Mesh b, Matrix3x2fc m, float x, float y, int argb) {
        b.add(m, x, y, 0f, 0f, argb);
    }

    public static void vtx(Mesh b, Matrix3x2fc m, float x, float y, float u, float v, int argb) {
        b.add(m, x, y, u, v, argb);
    }

    private static void tri(Mesh b, Matrix3x2fc m, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        vtx(b, m, x1, y1, argb);
        vtx(b, m, x2, y2, argb);
        vtx(b, m, x3, y3, argb);
    }

    public static void quad(Mesh b, Matrix3x2fc m, float x0, float y0, float x1, float y1, int argb) {
        tri(b, m, x0, y0, x0, y1, x1, y1, argb);
        tri(b, m, x0, y0, x1, y1, x1, y0, argb);
    }

    private static Matrix3x2fc mat(GuiGraphicsExtractor g) {
        return g.pose();
    }

    private static int transparent(int argb) {
        return argb & 0x00FFFFFF;
    }

    // ================================================================== anti-aliased convex polygons

    private static final int MAX_POLY = 512;
    // Scratch buffers (render thread only): drawing never allocates.
    private static final float[] PX = new float[MAX_POLY], PY = new float[MAX_POLY];
    private static final float[] IX = new float[MAX_POLY], IY = new float[MAX_POLY], OX = new float[MAX_POLY], OY = new float[MAX_POLY];
    private static final float[] BX = new float[MAX_POLY], BY = new float[MAX_POLY], CX = new float[MAX_POLY], CY = new float[MAX_POLY];
    /** Cached unit-circle tables per segment count (index n holds n + 1 entries, the last equal to the first). */
    private static final float[][] COS = new float[MAX_POLY + 1][], SIN = new float[MAX_POLY + 1][];

    private static float[] cosTable(int n) {
        if (COS[n] == null) unitCircle(n);
        return COS[n];
    }

    private static float[] sinTable(int n) {
        if (SIN[n] == null) unitCircle(n);
        return SIN[n];
    }

    private static void unitCircle(int n) {
        float[] c = new float[n + 1], s = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            c[i] = (float) Math.cos(a);
            s[i] = (float) Math.sin(a);
        }
        COS[n] = c;
        SIN[n] = s;
    }

    /**
     * Fills the convex polygon (PX, PY)[0..n) with a feathered edge one physical pixel wide, centred on the outline
     * (so the shape keeps its nominal size).
     */
    private static void fillConvex(GuiGraphicsExtractor g, int n, int argb) {
        if (n < 3) return;
        Mesh b = beginColor();
        fillConvexInto(b, mat(g), n, argb);
        endColor(g, b);
    }

    /** As {@link #fillConvex}, appending to an open colour buffer (several shapes in one draw call). */
    private static void fillConvexInto(Mesh b, Matrix3x2fc m, int n, int argb) {
        if (n < 3) return;
        float f = px() * 0.5f;
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += PX[i];
            cy += PY[i];
        }
        cx /= n;
        cy /= n;
        for (int i = 0; i < n; i++) {
            int p = (i + n - 1) % n, q = (i + 1) % n;
            // outward unit normals of the two edges meeting at vertex i
            float n1x = PY[p] - PY[i], n1y = PX[i] - PX[p];
            float l1 = (float) Math.sqrt(n1x * n1x + n1y * n1y);
            if (l1 > 1e-6f) { n1x /= l1; n1y /= l1; } else { n1x = 0; n1y = 0; }
            if (n1x * ((PX[p] + PX[i]) / 2 - cx) + n1y * ((PY[p] + PY[i]) / 2 - cy) < 0) { n1x = -n1x; n1y = -n1y; }
            float n2x = PY[i] - PY[q], n2y = PX[q] - PX[i];
            float l2 = (float) Math.sqrt(n2x * n2x + n2y * n2y);
            if (l2 > 1e-6f) { n2x /= l2; n2y /= l2; } else { n2x = 0; n2y = 0; }
            if (n2x * ((PX[i] + PX[q]) / 2 - cx) + n2y * ((PY[i] + PY[q]) / 2 - cy) < 0) { n2x = -n2x; n2y = -n2y; }
            float nx = n1x + n2x, ny = n1y + n2y;
            float len = (float) Math.sqrt(nx * nx + ny * ny);
            if (len < 1e-5f) {
                nx = n2x;
                ny = n2y;
            } else {
                nx /= len;
                ny /= len;
            }
            float miter = Math.min(2.5f, 1f / Math.max(0.2f, nx * n2x + ny * n2y));
            IX[i] = PX[i] - nx * f * miter;
            IY[i] = PY[i] - ny * f * miter;
            OX[i] = PX[i] + nx * f * miter;
            OY[i] = PY[i] + ny * f * miter;
        }
        int clear = transparent(argb);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            tri(b, m, cx, cy, IX[i], IY[i], IX[j], IY[j], argb);
            vtx(b, m, IX[i], IY[i], argb); vtx(b, m, OX[i], OY[i], clear); vtx(b, m, OX[j], OY[j], clear);
            vtx(b, m, IX[i], IY[i], argb); vtx(b, m, OX[j], OY[j], clear); vtx(b, m, IX[j], IY[j], argb);
        }
    }

    // ================================================================== shapes

    /** Axis-aligned rectangle, snapped to physical pixels (crisp, no feathering needed). */
    public static void rect(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int argb) {
        Mesh b = beginColor();
        quad(b, mat(g), snap(x0), snap(y0), snap(x1), snap(y1), argb);
        endColor(g, b);
    }

    /** Outline of {@code t} units drawn inside the rectangle (one draw call). */
    public static void frame(GuiGraphicsExtractor g, float x, float y, float w, float h, float t, int argb) {
        float x0 = snap(x), y0 = snap(y), x1 = snap(x + w), y1 = snap(y + h);
        t = Math.max(px(), snap(t));
        Matrix3x2fc m = mat(g);
        Mesh b = beginColor();
        quad(b, m, x0, y0, x1, y0 + t, argb);
        quad(b, m, x0, y1 - t, x1, y1, argb);
        quad(b, m, x0, y0 + t, x0 + t, y1 - t, argb);
        quad(b, m, x1 - t, y0 + t, x1, y1 - t, argb);
        endColor(g, b);
    }

    public static void gradientRectV(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int top, int bottom) {
        Matrix3x2fc m = mat(g);
        x0 = snap(x0); y0 = snap(y0); x1 = snap(x1); y1 = snap(y1);
        Mesh b = beginColor();
        vtx(b, m, x0, y0, top); vtx(b, m, x0, y1, bottom); vtx(b, m, x1, y1, bottom);
        vtx(b, m, x0, y0, top); vtx(b, m, x1, y1, bottom); vtx(b, m, x1, y0, top);
        endColor(g, b);
    }

    public static void gradientRectH(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int left, int right) {
        Matrix3x2fc m = mat(g);
        x0 = snap(x0); y0 = snap(y0); x1 = snap(x1); y1 = snap(y1);
        Mesh b = beginColor();
        vtx(b, m, x0, y0, left); vtx(b, m, x0, y1, left); vtx(b, m, x1, y1, right);
        vtx(b, m, x0, y0, left); vtx(b, m, x1, y1, right); vtx(b, m, x1, y0, right);
        endColor(g, b);
    }

    private static int cornerSegments(float r) {
        return r <= 0 ? 0 : Math.max(3, Math.min(16, (int) (r * guiScale() / 2f) + 2));
    }

    private static int roundRectPath(float x, float y, float w, float h, float r) {
        return roundRectPath(x, y, w, h, r, cornerSegments(r));
    }

    private static int roundRectPath(float x, float y, float w, float h, float r, int seg) {
        r = Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
        int q = Math.max(1, seg), steps = 4 * q; // quarter arcs read from a full-circle table of 4*q steps
        float[] cs = cosTable(steps), sn = sinTable(steps);
        int n = 0;
        for (int c = 0; c < 4; c++) {
            float ccx = (c == 0 || c == 1) ? x + w - r : x + r;
            float ccy = (c == 1 || c == 2) ? y + h - r : y + r;
            int base = (3 + c) * q; // start angles -90, 0, 90, 180 degrees
            for (int i = 0; i <= seg; i++) {
                int k = (base + i) % steps;
                PX[n] = ccx + cs[k] * r;
                PY[n] = ccy + sn[k] * r;
                n++;
            }
        }
        return n;
    }

    public static void roundRect(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int argb) {
        if (w <= 0 || h <= 0) return;
        fillConvex(g, roundRectPath(snap(x), snap(y), snap(x + w) - snap(x), snap(y + h) - snap(y), r), argb);
    }

    /**
     * Outline of a callout: a rounded rectangle with a pointer on its right side (base on the right edge from
     * {@code cy - halfH} to {@code cy + halfH}, tip at {@code tipX}), as one band of {@code thickness} starting
     * {@code inset} inside the shape, so the box and the pointer read as one piece.
     */
    public static void calloutOutline(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, float tipX, float cy, float halfH,
                                      float inset, float thickness, int argb) {
        if (w <= 0 || h <= 0) return;
        int seg = r > 0 ? Math.max(3, cornerSegments(r)) : 1;
        int n = calloutPath(x, y, w, h, r, seg, tipX, cy, halfH, inset);
        System.arraycopy(PX, 0, CX, 0, n);
        System.arraycopy(PY, 0, CY, 0, n);
        calloutPath(x, y, w, h, r, seg, tipX, cy, halfH, inset + thickness);
        System.arraycopy(PX, 0, BX, 0, n);
        System.arraycopy(PY, 0, BY, 0, n);
        band(g, CX, CY, BX, BY, n, argb);
    }

    /**
     * The callout's loop moved {@code d} inwards into PX/PY: the corners as in {@link #roundRectPath}, the pointer's
     * three points after the top right corner. The pointer's sides move along their normals, so its tip goes back by
     * {@code d * sqrt(L^2 + H^2) / H} and its half height shrinks by {@code d * (sqrt(1 + s^2) - s)}, s = H / L.
     */
    private static int calloutPath(float x, float y, float w, float h, float r, int seg, float tipX, float cy, float halfH, float d) {
        float len = tipX - (x + w), s = halfH / len;
        float tip = tipX - d * (float) Math.sqrt(len * len + halfH * halfH) / halfH;
        float half = halfH - d * ((float) Math.sqrt(1 + s * s) - s);
        x += d; y += d; w -= 2 * d; h -= 2 * d;
        r = Math.max(0f, Math.min(r - d, Math.min(w, h) / 2f));
        int q = Math.max(1, seg), steps = 4 * q;
        float[] cs = cosTable(steps), sn = sinTable(steps);
        int n = 0;
        for (int c = 0; c < 4; c++) {
            float ccx = (c == 0 || c == 1) ? x + w - r : x + r;
            float ccy = (c == 1 || c == 2) ? y + h - r : y + r;
            int base = (3 + c) * q;
            for (int i = 0; i <= seg; i++) {
                int k = (base + i) % steps;
                PX[n] = ccx + cs[k] * r;
                PY[n] = ccy + sn[k] * r;
                n++;
            }
            if (c == 0) {
                PX[n] = x + w; PY[n] = cy - half; n++;
                PX[n] = tip; PY[n] = cy; n++;
                PX[n] = x + w; PY[n] = cy + half; n++;
            }
        }
        return n;
    }

    /** Outline of a rounded rectangle; drawn as an anti-aliased band of the given thickness inside the bounds. */
    public static void roundRectOutline(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, float thickness, int argb) {
        if (w <= 0 || h <= 0) return;
        x = snap(x); y = snap(y); w = snap(x + w) - x; h = snap(y + h) - y;
        int seg = Math.max(3, cornerSegments(r));
        int n = roundRectPath(x, y, w, h, r, seg);
        System.arraycopy(PX, 0, CX, 0, n);
        System.arraycopy(PY, 0, CY, 0, n);
        roundRectPath(x + thickness, y + thickness, w - 2 * thickness, h - 2 * thickness, Math.max(0, r - thickness), seg);
        System.arraycopy(PX, 0, BX, 0, n);
        System.arraycopy(PY, 0, BY, 0, n);
        band(g, CX, CY, BX, BY, n, argb);
    }

    /**
     * Closed band between an outer loop (ox, oy) and an inner loop (ix, iy) with feathered outer and inner edges.
     * Allocation-free; the inputs must not be PX/PY/IX/IY/OX/OY (they are used as scratch here).
     */
    private static void band(GuiGraphicsExtractor g, float[] ox, float[] oy, float[] ix, float[] iy, int n, int argb) {
        float f = px() * 0.5f;
        for (int i = 0; i < n; i++) {
            float dx = ox[i] - ix[i], dy = oy[i] - iy[i];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float ux = len < 1e-6f ? 0 : dx / len * f, uy = len < 1e-6f ? 0 : dy / len * f;
            OX[i] = ox[i] + ux; OY[i] = oy[i] + uy; // outer feather edge (transparent)
            PX[i] = ox[i] - ux; PY[i] = oy[i] - uy; // outer core edge
            IX[i] = ix[i] - ux; IY[i] = iy[i] - uy; // inner feather edge (transparent)
        }
        Matrix3x2fc m = mat(g);
        int clear = transparent(argb);
        Mesh b = beginColor();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            // inner core edge = inner loop moved outwards by f
            float qix = ix[i] + (ix[i] - IX[i]), qiy = iy[i] + (iy[i] - IY[i]);
            float qjx = ix[j] + (ix[j] - IX[j]), qjy = iy[j] + (iy[j] - IY[j]);
            // solid core
            vtx(b, m, PX[i], PY[i], argb); vtx(b, m, PX[j], PY[j], argb); vtx(b, m, qjx, qjy, argb);
            vtx(b, m, PX[i], PY[i], argb); vtx(b, m, qjx, qjy, argb); vtx(b, m, qix, qiy, argb);
            // outer feather
            vtx(b, m, PX[i], PY[i], argb); vtx(b, m, OX[i], OY[i], clear); vtx(b, m, OX[j], OY[j], clear);
            vtx(b, m, PX[i], PY[i], argb); vtx(b, m, OX[j], OY[j], clear); vtx(b, m, PX[j], PY[j], argb);
            // inner feather
            vtx(b, m, qix, qiy, argb); vtx(b, m, IX[i], IY[i], clear); vtx(b, m, IX[j], IY[j], clear);
            vtx(b, m, qix, qiy, argb); vtx(b, m, IX[j], IY[j], clear); vtx(b, m, qjx, qjy, argb);
        }
        endColor(g, b);
    }

    public static void capsule(GuiGraphicsExtractor g, float x, float y, float w, float h, int argb) {
        roundRect(g, x, y, w, h, h / 2f, argb);
    }

    public static void capsuleOutline(GuiGraphicsExtractor g, float x, float y, float w, float h, float thickness, int argb) {
        roundRectOutline(g, x, y, w, h, h / 2f, thickness, argb);
    }

    private static int segmentsFor(float radius) {
        return Math.max(24, Math.min(160, (int) (radius * guiScale() * 1.2f)));
    }

    public static void circle(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
        int n = Math.min(MAX_POLY, segmentsFor(r));
        float[] cs = cosTable(n), sn = sinTable(n);
        for (int i = 0; i < n; i++) {
            PX[i] = cx + cs[i] * r;
            PY[i] = cy + sn[i] * r;
        }
        fillConvex(g, n, argb);
    }

    /**
     * Annulus with a radial gradient (inner colour at rInner, outer colour at rOuter). Used for glows and
     * vignettes: soft by design, so it is not feathered.
     */
    public static void ring(GuiGraphicsExtractor g, float cx, float cy, float rInner, float rOuter, int inner, int outer) {
        int seg = Math.min(MAX_POLY, segmentsFor(rOuter));
        float[] cs = cosTable(seg), sn = sinTable(seg);
        Matrix3x2fc m = mat(g);
        Mesh b = beginColor();
        for (int i = 0; i < seg; i++) {
            float c0 = cs[i], s0 = sn[i], c1 = cs[i + 1], s1 = sn[i + 1];
            if (rInner <= 0f) {
                vtx(b, m, cx, cy, inner);
                vtx(b, m, cx + c0 * rOuter, cy + s0 * rOuter, outer);
                vtx(b, m, cx + c1 * rOuter, cy + s1 * rOuter, outer);
            } else {
                vtx(b, m, cx + c0 * rInner, cy + s0 * rInner, inner);
                vtx(b, m, cx + c0 * rOuter, cy + s0 * rOuter, outer);
                vtx(b, m, cx + c1 * rOuter, cy + s1 * rOuter, outer);
                vtx(b, m, cx + c0 * rInner, cy + s0 * rInner, inner);
                vtx(b, m, cx + c1 * rOuter, cy + s1 * rOuter, outer);
                vtx(b, m, cx + c1 * rInner, cy + s1 * rInner, inner);
            }
        }
        endColor(g, b);
    }

    /**
     * Square counterpart of {@link #ring}: the band between the squares of half sizes {@code rInner} and
     * {@code rOuter} around (cx, cy), with mitred corners so the gradient does not double up there.
     */
    public static void squareRing(GuiGraphicsExtractor g, float cx, float cy, float rInner, float rOuter, int inner, int outer) {
        Matrix3x2fc m = mat(g);
        Mesh b = beginColor();
        for (int side = 0; side < 4; side++) {
            float ix0 = cx + SQUARE_X[side] * rInner, iy0 = cy + SQUARE_Y[side] * rInner;
            float ix1 = cx + SQUARE_X[side + 1] * rInner, iy1 = cy + SQUARE_Y[side + 1] * rInner;
            float ox0 = cx + SQUARE_X[side] * rOuter, oy0 = cy + SQUARE_Y[side] * rOuter;
            float ox1 = cx + SQUARE_X[side + 1] * rOuter, oy1 = cy + SQUARE_Y[side + 1] * rOuter;
            vtx(b, m, ix0, iy0, inner); vtx(b, m, ox0, oy0, outer); vtx(b, m, ox1, oy1, outer);
            vtx(b, m, ix0, iy0, inner); vtx(b, m, ox1, oy1, outer); vtx(b, m, ix1, iy1, inner);
        }
        endColor(g, b);
    }

    /** Unit square corners, clockwise from the top left (the first repeated at the end). Read only. */
    public static final float[] SQUARE_X = {-1, 1, 1, -1, -1}, SQUARE_Y = {-1, -1, 1, 1, -1};

    /** Crisp anti-aliased circle outline of the given thickness centred on radius r. */
    public static void circleOutline(GuiGraphicsExtractor g, float cx, float cy, float r, float thickness, int argb) {
        int n = Math.min(MAX_POLY, segmentsFor(r + thickness));
        float[] cs = cosTable(n), sn = sinTable(n);
        float ro = r + thickness / 2f, ri = Math.max(0, r - thickness / 2f);
        for (int i = 0; i < n; i++) {
            CX[i] = cx + cs[i] * ro;
            CY[i] = cy + sn[i] * ro;
            BX[i] = cx + cs[i] * ri;
            BY[i] = cy + sn[i] * ri;
        }
        band(g, CX, CY, BX, BY, n, argb);
    }

    /** Pie slice (e.g. the view cone): angle in radians, 0 = up (screen -Y), clockwise positive. Soft gradient. */
    public static void sector(GuiGraphicsExtractor g, float cx, float cy, float r, float centerAngle, float halfWidth, int centerColor, int edgeColor) {
        int seg = 32;
        Matrix3x2fc m = mat(g);
        Mesh b = beginColor();
        for (int i = 0; i < seg; i++) {
            float a0 = centerAngle - halfWidth + 2 * halfWidth * i / seg;
            float a1 = centerAngle - halfWidth + 2 * halfWidth * (i + 1) / seg;
            vtx(b, m, cx, cy, centerColor);
            vtx(b, m, cx + (float) Math.sin(a0) * r, cy - (float) Math.cos(a0) * r, edgeColor);
            vtx(b, m, cx + (float) Math.sin(a1) * r, cy - (float) Math.cos(a1) * r, edgeColor);
        }
        endColor(g, b);
    }

    /**
     * Arc of a ring (thickness centred on radius r) from angle {@code a0} to {@code a1}, radians, same convention as
     * {@link #sector}: 0 = up, clockwise positive.
     */
    public static void arc(GuiGraphicsExtractor g, float cx, float cy, float r, float thickness, float a0, float a1, int argb) {
        int seg = Math.max(4, Math.min(48, (int) (Math.abs(a1 - a0) * r * guiScale() / 3f) + 4));
        float ro = r + thickness / 2f, ri = Math.max(0, r - thickness / 2f);
        Matrix3x2fc m = mat(g);
        Mesh b = beginColor();
        for (int i = 0; i < seg; i++) {
            float t0 = a0 + (a1 - a0) * i / seg, t1 = a0 + (a1 - a0) * (i + 1) / seg;
            float s0 = (float) Math.sin(t0), c0 = (float) -Math.cos(t0), s1 = (float) Math.sin(t1), c1 = (float) -Math.cos(t1);
            vtx(b, m, cx + s0 * ri, cy + c0 * ri, argb);
            vtx(b, m, cx + s0 * ro, cy + c0 * ro, argb);
            vtx(b, m, cx + s1 * ro, cy + c1 * ro, argb);
            vtx(b, m, cx + s0 * ri, cy + c0 * ri, argb);
            vtx(b, m, cx + s1 * ro, cy + c1 * ro, argb);
            vtx(b, m, cx + s1 * ri, cy + c1 * ri, argb);
        }
        endColor(g, b);
    }

    /** Diamond (square rotated 45 deg) with half-diagonal r. */
    public static void diamond(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
        PX[0] = cx; PY[0] = cy - r;
        PX[1] = cx + r; PY[1] = cy;
        PX[2] = cx; PY[2] = cy + r;
        PX[3] = cx - r; PY[3] = cy;
        fillConvex(g, 4, argb);
    }

    public static void diamondOutline(GuiGraphicsExtractor g, float cx, float cy, float r, float t, int argb) {
        float ri = r - t * 1.4142f;
        CX[0] = cx; CY[0] = cy - r; CX[1] = cx + r; CY[1] = cy; CX[2] = cx; CY[2] = cy + r; CX[3] = cx - r; CY[3] = cy;
        BX[0] = cx; BY[0] = cy - ri; BX[1] = cx + ri; BY[1] = cy; BX[2] = cx; BY[2] = cy + ri; BX[3] = cx - ri; BY[3] = cy;
        band(g, CX, CY, BX, BY, 4, argb);
    }

    /**
     * Light added to the picture, as the path's sparkles are added to the world (the settings preview draws them the
     * same way): triangles from (cx, cy) in {@code argb} to each pair of neighbouring rim points ({@code n} of them in
     * {@code xs}, {@code ys}, the last joined to the first), fading to clear at the rim.
     */
    public static void glowFan(GuiGraphicsExtractor g, float cx, float cy, float[] xs, float[] ys, int n, int argb) {
        Matrix3x2fc m = mat(g);
        Mesh b = beginColor();
        int clear = argb & 0x00FFFFFF;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            vtx(b, m, cx, cy, argb);
            vtx(b, m, xs[i], ys[i], clear);
            vtx(b, m, xs[j], ys[j], clear);
        }
        submit(g, Shape.of(b, Pipelines.COLOR_ADD, TextureSetup.noTexture(), false, scissor(g)));
    }

    public static void triangle(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        PX[0] = x1; PY[0] = y1;
        PX[1] = x2; PY[1] = y2;
        PX[2] = x3; PY[2] = y3;
        fillConvex(g, 3, argb);
    }

    /** The dash-dot pattern of {@link #route} along the line (GUI units): dash, gap, dot, gap. */
    private static final float ROUTE_DASH = 5f, ROUTE_GAP = 2.5f, ROUTE_DOT = 1.4f;
    private static final float ROUTE_PERIOD = ROUTE_DASH + ROUTE_GAP + ROUTE_DOT + ROUTE_GAP;

    /**
     * A dash-dot path through {@code n} points ({@code xy} = x0, y0, x1, y1, ...) of {@code width}, in one draw call:
     * the pattern runs on along the whole line, in {@code fogArgb} next to the points marked in {@code fog} and in
     * {@code argb} elsewhere.
     */
    public static void route(GuiGraphicsExtractor g, float[] xy, boolean[] fog, int n, float width, int argb, int fogArgb) {
        route(g, xy, fog, n, width, argb, fogArgb, 0f);
    }

    /**
     * As above, the pattern starting {@code phase} GUI units into the line: a line drawn from a point partway along
     * keeps its dashes in place on the map instead of crawling as that point moves.
     */
    public static void route(GuiGraphicsExtractor g, float[] xy, boolean[] fog, int n, float width, int argb, int fogArgb, float phase) {
        route(g, xy, fog, null, n, width, argb, fogArgb, argb, phase);
    }

    /**
     * As above, and in {@code seaArgb} between two points marked in {@code water} (deep water: the colour of the sea,
     * user decision 26.10.2026); the fog colour goes first ({@code water} null: none).
     */
    public static void route(GuiGraphicsExtractor g, float[] xy, boolean[] fog, boolean[] water, int n, float width, int argb, int fogArgb,
                             int seaArgb, float phase) {
        if (n < 2) return;
        Matrix3x2fc m = mat(g);
        Mesh b = beginColor();
        float along = phase;
        for (int i = 0; i + 1 < n; i++) {
            float x1 = xy[2 * i], y1 = xy[2 * i + 1], x2 = xy[2 * i + 2], y2 = xy[2 * i + 3];
            float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-4f) continue;
            float ux = dx / len, uy = dy / len;
            int color = fog[i] || fog[i + 1] ? fogArgb : water != null && water[i] && water[i + 1] ? seaArgb : argb;
            // The dash and the dot of every period that overlaps this piece, cut to it.
            for (float s = -(along % ROUTE_PERIOD); s < len; s += ROUTE_PERIOD) {
                dash(b, m, x1, y1, ux, uy, s, s + ROUTE_DASH, len, width, color);
                float dot = s + ROUTE_DASH + ROUTE_GAP;
                dash(b, m, x1, y1, ux, uy, dot, dot + ROUTE_DOT, len, width, color);
            }
            along += len;
        }
        endColor(g, b);
    }

    private static void dash(Mesh b, Matrix3x2fc m, float x, float y, float ux, float uy, float s0, float s1, float len, float width, int argb) {
        s0 = Math.max(0, s0);
        s1 = Math.min(len, s1);
        if (s1 > s0) piece(b, m, x, y, ux, uy, s0, s1, width, argb);
    }
    /** One straight piece of {@link #route} from {@code s0} to {@code s1} along (ux, uy) from (x, y). */
    private static void piece(Mesh b, Matrix3x2fc m, float x, float y, float ux, float uy, float s0, float s1, float width, int argb) {
        float hx = ux * width / 2f, hy = uy * width / 2f, nx = -hy, ny = hx;
        float ax = x + ux * s0, ay = y + uy * s0, bx = x + ux * s1, by = y + uy * s1;
        PX[0] = ax + nx; PY[0] = ay + ny;
        PX[1] = bx + nx; PY[1] = by + ny;
        PX[2] = bx - nx; PY[2] = by - ny;
        PX[3] = ax - nx; PY[3] = ay - ny;
        fillConvexInto(b, m, 4, argb);
    }

    /**
     * Many axis-aligned rectangles in one draw call: {@code rects} holds x0, y0, x1, y1 per rectangle and
     * {@code colors} one ARGB colour each (e.g. the map grid).
     */
    public static void rects(GuiGraphicsExtractor g, float[] rects, int[] colors, int count) {
        if (count <= 0) return;
        Matrix3x2fc m = mat(g);
        Mesh b = beginColor();
        for (int i = 0; i < count; i++) {
            quad(b, m, snap(rects[i * 4]), snap(rects[i * 4 + 1]), snap(rects[i * 4 + 2]), snap(rects[i * 4 + 3]), colors[i]);
        }
        endColor(g, b);
    }

    // ================================================================== textures

    /**
     * Whether unrotated icons are moved onto the physical pixel grid. Off while drawing pins over a smoothly moving
     * picture (the minimap): snapped, they shook by a pixel against the ground under them.
     */
    private static boolean snapIcons = true;

    public static void snapIcons(boolean on) {
        snapIcons = on;
    }

    /**
     * Draws an atlas icon centred at (cx, cy) in a square of {@code size} GUI units, tinted with {@code argb},
     * rotated by {@code angle} radians clockwise. Unrotated icons are aligned to the physical pixel grid (see {@link #snapIcons}).
     */
    public static void icon(GuiGraphicsExtractor g, Icon icon, float cx, float cy, float size, int argb, float angle) {
        float h = size / 2f;
        float[] x = IX, y = IY; // scratch (4 entries)
        if (angle == 0f) {
            float s = Math.max(px(), snap(size));
            float x0 = snapIcons ? snap(cx - s / 2f) : cx - s / 2f, y0 = snapIcons ? snap(cy - s / 2f) : cy - s / 2f;
            x[0] = x0; y[0] = y0; x[1] = x0; y[1] = y0 + s; x[2] = x0 + s; y[2] = y0 + s; x[3] = x0 + s; y[3] = y0;
        } else {
            float c = (float) Math.cos(angle), sn = (float) Math.sin(angle);
            // corners (-h,-h), (-h,h), (h,h), (h,-h) rotated around the centre
            x[0] = cx - h * c + h * sn; y[0] = cy - h * sn - h * c;
            x[1] = cx - h * c - h * sn; y[1] = cy - h * sn + h * c;
            x[2] = cx + h * c - h * sn; y[2] = cy + h * sn + h * c;
            x[3] = cx + h * c + h * sn; y[3] = cy + h * sn - h * c;
        }
        float u0 = icon.u0(), v0 = icon.v0(), u1 = icon.u1(), v1 = icon.v1();
        int a = (argb >>> 24) & 0xFF;
        int pm = (a << 24) | ((((argb >> 16) & 0xFF) * a / 255) << 16) | ((((argb >> 8) & 0xFF) * a / 255) << 8) | ((argb & 0xFF) * a / 255);
        Matrix3x2fc m = mat(g);
        Mesh b = beginTex();
        vtx(b, m, x[0], y[0], u0, v0, pm); vtx(b, m, x[1], y[1], u0, v1, pm); vtx(b, m, x[2], y[2], u1, v1, pm);
        vtx(b, m, x[0], y[0], u0, v0, pm); vtx(b, m, x[2], y[2], u1, v1, pm); vtx(b, m, x[3], y[3], u1, v0, pm);
        endPremultiplied(g, b, IconAtlas.tex());
    }

    public static void icon(GuiGraphicsExtractor g, Icon icon, float cx, float cy, float size, int argb) {
        icon(g, icon, cx, cy, size, argb, 0f);
    }

    public static void texturedQuad(GuiGraphicsExtractor g, Tex tex, float x0, float y0, float x1, float y1,
                                    float u0, float v0, float u1, float v1, int argb) {
        Matrix3x2fc m = mat(g);
        Mesh b = beginTex();
        vtx(b, m, x0, y0, u0, v0, argb); vtx(b, m, x0, y1, u0, v1, argb); vtx(b, m, x1, y1, u1, v1, argb);
        vtx(b, m, x0, y0, u0, v0, argb); vtx(b, m, x1, y1, u1, v1, argb); vtx(b, m, x1, y0, u1, v0, argb);
        endTex(g, b, tex);
    }

    /** As {@link #texturedQuad} for a texture holding premultiplied colour (e.g. an off-screen layer). */
    public static void texturedQuadPremultiplied(GuiGraphicsExtractor g, Tex tex, float x0, float y0, float x1, float y1,
                                                 float u0, float v0, float u1, float v1) {
        texturedQuadPremultiplied(g, tex, x0, y0, x1, y1, u0, v0, u1, v1, 1f);
    }

    /** As above, faded by {@code alpha} (all four channels are scaled, as a premultiplied texture needs). */
    public static void texturedQuadPremultiplied(GuiGraphicsExtractor g, Tex tex, float x0, float y0, float x1, float y1,
                                                 float u0, float v0, float u1, float v1, float alpha) {
        Matrix3x2fc m = mat(g);
        Mesh b = beginTex();
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        int c = a << 24 | a << 16 | a << 8 | a;
        vtx(b, m, x0, y0, u0, v0, c); vtx(b, m, x0, y1, u0, v1, c); vtx(b, m, x1, y1, u1, v1, c);
        vtx(b, m, x0, y0, u0, v0, c); vtx(b, m, x1, y1, u1, v1, c); vtx(b, m, x1, y0, u1, v0, c);
        endPremultiplied(g, b, tex);
    }

    // ================================================================== hit tests

    public static boolean inRect(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static boolean inCircle(double mx, double my, float cx, float cy, float r) {
        double dx = mx - cx, dy = my - cy;
        return dx * dx + dy * dy <= r * r;
    }
}
