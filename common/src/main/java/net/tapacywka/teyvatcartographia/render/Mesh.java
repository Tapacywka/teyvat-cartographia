package net.tapacywka.teyvatcartographia.render;

import org.joml.Matrix3x2fc;

/**
 * Triangles being built for one draw: position (already moved by the GUI pose), texture coordinates and colour per
 * vertex. {@link Draw} keeps one and hands a copy of what was built to the GUI (or to a layer) as a {@link Shape}, so
 * building never allocates once the array has grown.
 */
public final class Mesh {
    /** Floats per vertex: x, y, u, v, colour (ARGB bits). */
    static final int STRIDE = 5;
    float[] data = new float[STRIDE * 1024];
    int vertices;

    Mesh begin() {
        vertices = 0;
        return this;
    }

    public void add(Matrix3x2fc pose, float x, float y, float u, float v, int argb) {
        int k = vertices * STRIDE;
        if (k + STRIDE > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
        data[k] = pose.m00() * x + pose.m10() * y + pose.m20();
        data[k + 1] = pose.m01() * x + pose.m11() * y + pose.m21();
        data[k + 2] = u;
        data[k + 3] = v;
        data[k + 4] = Float.intBitsToFloat(argb);
        vertices++;
    }
}
