package com.nikohalfkino.rubydung.level;

/**
 * How the fake world past the edge falls apart, on the CPU: the faceted wave, which triangles are gone and how the rest come loose. WorldBorderRenderer
 * does the same in its vertex shader (keep the two in step: the wave and the hash are the same numbers), OceanSurface uses this for the sea past
 * the edge, whose display lists are built here and drawn by the liquid shader (so the screen-space snap and the affine texture mapping, which
 * need a vertex shader of the border's own, are only on the blocks).
 *
 * Everything is a function of t = distance past the real edge / WorldBorder.FRINGE (0 at the edge, 1 at the outer rim).
 */
public final class BorderWarp {

    public static final float WAVE_FROM = 0.05F;            // the surface starts to bend ...
    public static final float WAVE_FULL = 0.55F;            // ... and is at full strength here. it keeps that to the rim
    public static final float WAVE = 0.7F;                  // about this many blocks of push at most
    public static final float WAVE_CELL = 5.0F;             // the facets of the field are this many blocks across
    public static final float BREAK_FROM = 0.45F;           // triangles start to disappear and the rest to come loose
    public static final float KILL_MAX = 0.94F;             // share of triangles gone at the rim (the rest are the floating shards)
    public static final float SHIFT_XZ = 2.0F;              // how far a shard can sit off its place sideways (blocks, at the rim)
    public static final float SHIFT_Y = 3.0F;               // ... and up or down
    public static final float TURN = 2.0F;                  // ... how far it can turn (radians)

    private BorderWarp() {
    }

    public static float smoothstep(float a, float b, float x) {
        float t = Math.max(0.0F, Math.min(1.0F, (x - a) / (b - a)));
        return t * t * (3.0F - 2.0F * t);
    }

    /** 0 on the real world's edge (and inside it), 1 at the outer rim: the Chebyshev distance the mesh is built by */
    public static float edgeT(float x, float z, int width, int height) {
        float dx = Math.max(Math.max(-x, x - width), 0.0F);
        float dz = Math.max(Math.max(-z, z - height), 0.0F);
        return Math.min(Math.max(dx, dz) / WorldBorder.FRINGE, 1.0F);
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    /** hash33 of the shader, into out (x, y, z in 0..1) */
    private static void hash33(float px, float py, float pz, float[] out) {
        px = fract(px * 0.1031F);
        py = fract(py * 0.1030F);
        pz = fract(pz * 0.0973F);
        float d = px * (py + 33.33F) + py * (px + 33.33F) + pz * (pz + 33.33F);
        px += d;
        py += d;
        pz += d;
        out[0] = fract((px + py) * pz);
        out[1] = fract((px + px) * py);
        out[2] = fract((py + px) * px);
    }

    private static final float[] H = new float[3];

    private static float latticeX, latticeY, latticeZ;

    private static void lattice(float x, float y, float z) {
        hash33(x, y, z, H);
        latticeX = H[0] * 2.0F - 1.0F;
        latticeY = H[1] * 2.0F - 1.0F;
        latticeZ = H[2] * 2.0F - 1.0F;
    }

    /** the still distortion of the shader: random offsets on a lattice, blended linearly so the field is faceted. into out (x, y, z), not yet scaled by t */
    public static void wave(float x, float y, float z, float[] out) {
        float qx = x / WAVE_CELL, qy = y / WAVE_CELL, qz = z / WAVE_CELL;
        float ix = (float) Math.floor(qx), iy = (float) Math.floor(qy), iz = (float) Math.floor(qz);
        float fx = qx - ix, fy = qy - iy, fz = qz - iz;
        float[][] c = new float[8][3];
        for (int k = 0; k < 8; k++) {
            lattice(ix + (k & 1), iy + ((k >> 1) & 1), iz + ((k >> 2) & 1));
            c[k][0] = latticeX;
            c[k][1] = latticeY;
            c[k][2] = latticeZ;
        }
        for (int a = 0; a < 3; a++) {
            float x00 = c[0][a] + (c[1][a] - c[0][a]) * fx;
            float x10 = c[2][a] + (c[3][a] - c[2][a]) * fx;
            float x01 = c[4][a] + (c[5][a] - c[4][a]) * fx;
            float x11 = c[6][a] + (c[7][a] - c[6][a]) * fx;
            float y0 = x00 + (x10 - x00) * fy;
            float y1 = x01 + (x11 - x01) * fy;
            out[a] = y0 + (y1 - y0) * fz;
        }
        out[0] *= 0.9F;
        out[2] *= 0.9F;
    }

    /** the breakdown: does this triangle (named by its centroid) fall away? more of them the further out, the same ones every time the mesh is built */
    public static boolean brokenOff(float cx, float cy, float cz, int width, int height) {
        float chance = KILL_MAX * smoothstep(BREAK_FROM, 1.0F, edgeT(cx, cz, width, height));
        if (chance <= 0.0F) {
            return false;
        }
        int h = Float.floatToIntBits(cx) * 73856093 ^ Float.floatToIntBits(cy) * 19349663 ^ Float.floatToIntBits(cz) * 83492791;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return ((h & 0xFFFFFF) / 16777216.0F) < chance;
    }

    /**
     * Where the corners of a triangle end up: the wave pushes each, then the whole triangle comes loose about its (pushed) centroid, shifted and turned
     * by how far out it is. p holds the three corners (x, y, z each, 9 floats) and is changed in place; cen is the unpushed centroid
     */
    public static void warpTriangle(float[] p, float cx, float cy, float cz, int width, int height) {
        float[] w = new float[3];
        float tC = edgeT(cx, cz, width, height);

        wave(cx, cy, cz, w);
        float kc = smoothstep(WAVE_FROM, WAVE_FULL, tC) * WAVE;
        float wcx = cx + w[0] * kc, wcy = cy + w[1] * kc, wcz = cz + w[2] * kc;

        float e = smoothstep(BREAK_FROM, 1.0F, tC);
        float e2 = e * e;
        hash33(cx + 17.0F, cy + 17.0F, cz + 17.0F, H);
        float rx = H[0] - 0.5F, ry = H[1] - 0.5F, rz = H[2] - 0.5F;
        float ay = rx * TURN * e2;
        float ax = ry * TURN * e2;
        float cosY = (float) Math.cos(ay), sinY = (float) Math.sin(ay);
        float cosX = (float) Math.cos(ax), sinX = (float) Math.sin(ax);

        for (int i = 0; i < 3; i++) {
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            float tV = edgeT(x, z, width, height);
            wave(x, y, z, w);
            float k = smoothstep(WAVE_FROM, WAVE_FULL, tV) * WAVE;
            x += w[0] * k;
            y += w[1] * k;
            z += w[2] * k;

            float rxp = x - wcx, ryp = y - wcy, rzp = z - wcz;
            float tx = cosY * rxp + sinY * rzp;                 // rotY
            float tz = -sinY * rxp + cosY * rzp;
            rxp = tx;
            rzp = tz;
            float ty = cosX * ryp - sinX * rzp;                 // rotX
            tz = sinX * ryp + cosX * rzp;
            ryp = ty;
            rzp = tz;

            p[i * 3] = wcx + rxp + rx * SHIFT_XZ * e2;
            p[i * 3 + 1] = wcy + ryp + ry * SHIFT_Y * e2;
            p[i * 3 + 2] = wcz + rzp + rz * SHIFT_XZ * e2;
        }
    }
}