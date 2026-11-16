package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.entity.phys.AABB;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

public class Frustum {

    public static final int RIGHT = 0;
    public static final int LEFT = 1;
    public static final int BOTTOM = 2;
    public static final int TOP = 3;
    public static final int BACK = 4;
    public static final int FRONT = 5;

    public static final int A = 0;
    public static final int B = 1;
    public static final int C = 2;
    public static final int D = 3;

    private static Frustum frustum = new Frustum();

    float[][] m_Frustum = new float[6][4];

    FloatBuffer modl_b;

    FloatBuffer proj_b;


    public Frustum() {
        modl_b = BufferUtils.createFloatBuffer(16);
        proj_b = BufferUtils.createFloatBuffer(16);
    }

    public static Frustum getFrustum() {
        Frustum.frustum.calculateFrustum();
        return Frustum.frustum;
    }

    public void normalizePlane(float[][] frustum, int side) {
        float magnitude = (float) Math.sqrt(frustum[side][A] * frustum[side][A] +
                frustum[side][B] * frustum[side][B] + frustum[side][C] * frustum[side][C]);

        frustum[side][A] /= magnitude;
        frustum[side][B] /= magnitude;
        frustum[side][C] /= magnitude;
        frustum[side][D] /= magnitude;
    }


    public void calculateFrustum() {
        float[] proj = new float[16];
        float[] modl = new float[16];
        float[] clip = new float[16];


        proj_b.rewind();
        glGetFloat(GL_PROJECTION_MATRIX, proj_b);
        proj_b.rewind();
        proj_b.get(proj);

        modl_b.rewind();
        glGetFloat(GL_MODELVIEW_MATRIX, modl_b);
        modl_b.rewind();
        modl_b.get(modl);

        clip[0] = modl[0] * proj[0] + modl[1] * proj[4] + modl[2] * proj[8] + modl[3] * proj[12];
        clip[1] = modl[0] * proj[1] + modl[1] * proj[5] + modl[2] * proj[9] + modl[3] * proj[13];
        clip[2] = modl[0] * proj[2] + modl[1] * proj[6] + modl[2] * proj[10] + modl[3] * proj[14];
        clip[3] = modl[0] * proj[3] + modl[1] * proj[7] + modl[2] * proj[11] + modl[3] * proj[15];

        clip[4] = modl[4] * proj[0] + modl[5] * proj[4] + modl[6] * proj[8] + modl[7] * proj[12];
        clip[5] = modl[4] * proj[1] + modl[5] * proj[5] + modl[6] * proj[9] + modl[7] * proj[13];
        clip[6] = modl[4] * proj[2] + modl[5] * proj[6] + modl[6] * proj[10] + modl[7] * proj[14];
        clip[7] = modl[4] * proj[3] + modl[5] * proj[7] + modl[6] * proj[11] + modl[7] * proj[15];

        clip[8] = modl[8] * proj[0] + modl[9] * proj[4] + modl[10] * proj[8] + modl[11] * proj[12];
        clip[9] = modl[8] * proj[1] + modl[9] * proj[5] + modl[10] * proj[9] + modl[11] * proj[13];
        clip[10] = modl[8] * proj[2] + modl[9] * proj[6] + modl[10] * proj[10] + modl[11] * proj[14];
        clip[11] = modl[8] * proj[3] + modl[9] * proj[7] + modl[10] * proj[11] + modl[11] * proj[15];

        clip[12] = modl[12] * proj[0] + modl[13] * proj[4] + modl[14] * proj[8] + modl[15] * proj[12];
        clip[13] = modl[12] * proj[1] + modl[13] * proj[5] + modl[14] * proj[9] + modl[15] * proj[13];
        clip[14] = modl[12] * proj[2] + modl[13] * proj[6] + modl[14] * proj[10] + modl[15] * proj[14];
        clip[15] = modl[12] * proj[3] + modl[13] * proj[7] + modl[14] * proj[11] + modl[15] * proj[15];

        m_Frustum[RIGHT][A] = clip[3] - clip[0];
        m_Frustum[RIGHT][B] = clip[7] - clip[4];
        m_Frustum[RIGHT][C] = clip[11] - clip[8];
        m_Frustum[RIGHT][D] = clip[15] - clip[12];

        normalizePlane(m_Frustum, RIGHT);

        m_Frustum[LEFT][A] = clip[3] + clip[0];
        m_Frustum[LEFT][B] = clip[7] + clip[4];
        m_Frustum[LEFT][C] = clip[11] + clip[8];
        m_Frustum[LEFT][D] = clip[15] + clip[12];

        normalizePlane(m_Frustum, LEFT);

        m_Frustum[BOTTOM][A] = clip[3] + clip[1];
        m_Frustum[BOTTOM][B] = clip[7] + clip[5];
        m_Frustum[BOTTOM][C] = clip[11] + clip[9];
        m_Frustum[BOTTOM][D] = clip[15] + clip[13];

        normalizePlane(m_Frustum, BOTTOM);

        m_Frustum[TOP][A] = clip[3] - clip[1];
        m_Frustum[TOP][B] = clip[7] - clip[5];
        m_Frustum[TOP][C] = clip[11] - clip[9];
        m_Frustum[TOP][D] = clip[15] - clip[13];

        normalizePlane(m_Frustum, TOP);

        m_Frustum[BACK][A] = clip[3] - clip[2];
        m_Frustum[BACK][B] = clip[7] - clip[6];
        m_Frustum[BACK][C] = clip[11] - clip[10];
        m_Frustum[BACK][D] = clip[15] - clip[14];

        normalizePlane(m_Frustum, BACK);

        m_Frustum[FRONT][A] = clip[3] + clip[2];
        m_Frustum[FRONT][B] = clip[7] + clip[6];
        m_Frustum[FRONT][C] = clip[11] + clip[10];
        m_Frustum[FRONT][D] = clip[15] + clip[14];

        normalizePlane(m_Frustum, FRONT);
    }

    public boolean pointInFrustum(float x, float y, float z) {
        for (int i = 0; i < 6; i++) {
            if (m_Frustum[i][A] * x + m_Frustum[i][B] * y + m_Frustum[i][C] * z + m_Frustum[i][D] <= 0) {
                return false;
            }
        }

        return true;
    }


    public boolean sphereInFrustum(float x, float y, float z, float radius) {
        for (int i = 0; i < 6; i++) {
            if (m_Frustum[i][A] * x + m_Frustum[i][B] * y + m_Frustum[i][C] * z + m_Frustum[i][D] <= -radius) {
                return false;
            }
        }

        return true;
    }

    /**
     * a box is outside as soon as, for one plane, even its corner farthest along the plane's normal is behind it. That
     * corner is picked from the signs of the normal, so each plane costs one dot product instead of up to eight
     * (same answer as testing all eight corners)
     */
    public boolean cubeInFrustum(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 6; i++) {
            float[] plane = m_Frustum[i];
            float px = plane[A] > 0 ? maxX : minX;
            float py = plane[B] > 0 ? maxY : minY;
            float pz = plane[C] > 0 ? maxZ : minZ;
            if (plane[A] * px + plane[B] * py + plane[C] * pz + plane[D] <= 0) {
                return false;
            }
        }
        return true;
    }

    public boolean cubeInFrustum(AABB aabb) {
        return cubeInFrustum((float) aabb.minX, (float) aabb.minY, (float) aabb.minZ,
                (float) aabb.maxX, (float) aabb.maxY, (float) aabb.maxZ);
    }
}