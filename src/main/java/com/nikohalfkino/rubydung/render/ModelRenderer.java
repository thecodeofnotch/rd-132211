package com.nikohalfkino.rubydung.render;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * draws 3D models (ObjModel) with the wobble of an old console: every vertex is snapped to a coarse grid on the screen, so a model that moves or turns crawls a little from one step to the next, the way the first 3D consoles did. this is the one place that does it, so every model of the game (the ragdolls now, the players later) has the same very slight snap
 *
 * how coarse: see PsxSnap (the grid rule is shared with the shaders that snap, so they all wobble alike). change the two numbers there to make it stronger or weaker
 *
 * the snapping is done here on the CPU: a vertex is carried through the camera's matrices, snapped, and handed to OpenGL already in clip space (which keeps the depth and the perspective of the texture right). that is why it needs no shader and works with the fixed-function drawing the cubes use
 *
 * use: begin() while the camera's matrices are active, any number of draw() calls (the caller binds the texture and sets the state), end()
 */
public final class ModelRenderer {

    // the snap grid and the rule for it live in PsxSnap, shared with the shaders that snap (RagdollShader, WorldBorderRenderer)

    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final float[] STEP = new float[2];

    // projection * modelview of the camera, column-major
    private static final float[] MVP = new float[16];
    private static float stepX = 0.0F;
    private static float stepY = 0.0F;
    private static boolean active = false;

    private static float[] clip = new float[0];

    private ModelRenderer() {
    }

    /**
     * reads the camera's matrices and the viewport, then switches both matrices to the identity: the vertices go to OpenGL in clip space. the pair of end() puts them back
     */
    public static void begin() {
        PROJECTION.clear();
        MODELVIEW.clear();
        glGetFloat(GL_PROJECTION_MATRIX, PROJECTION);
        glGetFloat(GL_MODELVIEW_MATRIX, MODELVIEW);
        multiply(PROJECTION, MODELVIEW, MVP);

        PsxSnap.step(STEP);
        stepX = STEP[0];
        stepY = STEP[1];

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        active = true;
    }

    /**
     * the snap grid of the current viewport, the same numbers begin() works out (out[0] = x step, out[1] = y step, in clip space). for RagdollShader, which snaps in its vertex shader and so needs no matrices read back: one viewport query per call, so once per frame, not once per model
     */
    public static void snapStep(float[] out) {
        PsxSnap.step(out);
    }

    public static void end() {
        if (!active) {
            return;
        }
        active = false;
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();
    }

    /**
     * draws the triangles of a model. positions are the vertices as they are to be drawn, in the model's own units (the model's own or ones bent by the caller, 3 floats per vertex). each is taken as (p - offset) * scale, turned by rotation (a column-major 4x4 matrix, see WorldEntity.writeMatrix) and put at (x, y, z) in the world
     *
     * shaded: lit like the cubes, brighter on top, with brightness as the light of the place (glColor per triangle). else the caller's colour is used as it is (a highlight)
     */
    public static void draw(ObjModel model, float[] positions, float[] rotation, float scale,
                            float offsetX, float offsetY, float offsetZ,
                            double x, double y, double z, float brightness, boolean shaded) {
        if (!active) {
            throw new IllegalStateException("ModelRenderer.begin() was not called");
        }

        int vertices = model.vertexCount();
        if (clip.length < vertices * 4) {
            clip = new float[vertices * 4];
        }

        float[] m = MVP;
        for (int i = 0; i < vertices; i++) {
            float lx = (positions[i * 3] - offsetX) * scale;
            float ly = (positions[i * 3 + 1] - offsetY) * scale;
            float lz = (positions[i * 3 + 2] - offsetZ) * scale;
            float wx = (float) (rotation[0] * lx + rotation[4] * ly + rotation[8] * lz + x);
            float wy = (float) (rotation[1] * lx + rotation[5] * ly + rotation[9] * lz + y);
            float wz = (float) (rotation[2] * lx + rotation[6] * ly + rotation[10] * lz + z);

            float cx = m[0] * wx + m[4] * wy + m[8] * wz + m[12];
            float cy = m[1] * wx + m[5] * wy + m[9] * wz + m[13];
            float cz = m[2] * wx + m[6] * wy + m[10] * wz + m[14];
            float cw = m[3] * wx + m[7] * wy + m[11] * wz + m[15];

            if (cw > PsxSnap.MIN_W) {
                cx = PsxSnap.snap(cx, cw, stepX);
                cy = PsxSnap.snap(cy, cw, stepY);
            }
            clip[i * 4] = cx;
            clip[i * 4 + 1] = cy;
            clip[i * 4 + 2] = cz;
            clip[i * 4 + 3] = cw;
        }

        int[] tri = model.triangles;
        float[] uv = model.uvs;
        float[] normals = model.normals;
        glBegin(GL_TRIANGLES);
        for (int t = 0; t < tri.length; t += 3) {
            if (shaded) {
                float nx = normals[t], ny = normals[t + 1], nz = normals[t + 2];
                float wnx = rotation[0] * nx + rotation[4] * ny + rotation[8] * nz;
                float wny = rotation[1] * nx + rotation[5] * ny + rotation[9] * nz;
                float shade = 0.8F + 0.2F * wny - 0.1F * Math.abs(wnx);
                shade = Math.max(0.45F, Math.min(1.0F, shade)) * brightness;
                glColor3f(shade, shade, shade);
            }
            for (int k = 0; k < 3; k++) {
                int i = tri[t + k];
                glTexCoord2f(uv[i * 2], uv[i * 2 + 1]);
                glVertex4f(clip[i * 4], clip[i * 4 + 1], clip[i * 4 + 2], clip[i * 4 + 3]);
            }
        }
        glEnd();
    }

    /**
     * draws a model whose vertices were already posed (RagdollSkin.pose): positions are in blocks relative to (x, y, z), 3 floats per vertex, the world is where they land. there is no model rotation, the pose has it. the normals are worked out from the posed triangles, so the shading follows the bend
     *
     * only the triangles whose first vertex follows `bone` in groups (one entry per vertex) are drawn, bone < 0 draws all of them (groups may be null then)
     */
    public static void drawSkinned(ObjModel model, float[] positions, double x, double y, double z,
                                   float brightness, boolean shaded, int[] groups, int bone) {
        if (!active) {
            throw new IllegalStateException("ModelRenderer.begin() was not called");
        }

        int vertices = model.vertexCount();
        if (clip.length < vertices * 4) {
            clip = new float[vertices * 4];
        }

        float[] m = MVP;
        for (int i = 0; i < vertices; i++) {
            float wx = (float) (positions[i * 3] + x);
            float wy = (float) (positions[i * 3 + 1] + y);
            float wz = (float) (positions[i * 3 + 2] + z);

            float cx = m[0] * wx + m[4] * wy + m[8] * wz + m[12];
            float cy = m[1] * wx + m[5] * wy + m[9] * wz + m[13];
            float cz = m[2] * wx + m[6] * wy + m[10] * wz + m[14];
            float cw = m[3] * wx + m[7] * wy + m[11] * wz + m[15];

            if (cw > PsxSnap.MIN_W) {
                cx = PsxSnap.snap(cx, cw, stepX);
                cy = PsxSnap.snap(cy, cw, stepY);
            }
            clip[i * 4] = cx;
            clip[i * 4 + 1] = cy;
            clip[i * 4 + 2] = cz;
            clip[i * 4 + 3] = cw;
        }

        int[] tri = model.triangles;
        float[] uv = model.uvs;
        ensureBatch(tri.length);
        int count = 0;
        for (int t = 0; t < tri.length; t += 3) {
            int a = tri[t], b = tri[t + 1], c = tri[t + 2];
            if (bone >= 0 && groups != null && groups[a] != bone) {
                continue;
            }
            float shade = 1.0F;
            if (shaded) {
                float e1x = positions[b * 3] - positions[a * 3];
                float e1y = positions[b * 3 + 1] - positions[a * 3 + 1];
                float e1z = positions[b * 3 + 2] - positions[a * 3 + 2];
                float e2x = positions[c * 3] - positions[a * 3];
                float e2y = positions[c * 3 + 1] - positions[a * 3 + 1];
                float e2z = positions[c * 3 + 2] - positions[a * 3 + 2];
                float nx = e1y * e2z - e1z * e2y;
                float ny = e1z * e2x - e1x * e2z;
                float nz = e1x * e2y - e1y * e2x;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1.0E-12F) {
                    nx /= len;
                    ny /= len;
                } else {
                    nx = 0.0F;
                    ny = 1.0F;
                }
                shade = 0.8F + 0.2F * ny - 0.1F * Math.abs(nx);
                shade = Math.max(0.45F, Math.min(1.0F, shade)) * brightness;
            }
            for (int k = 0; k < 3; k++) {
                int i = tri[t + k];
                batchPositions.put(clip[i * 4]).put(clip[i * 4 + 1]).put(clip[i * 4 + 2]).put(clip[i * 4 + 3]);
                batchUvs.put(uv[i * 2]).put(uv[i * 2 + 1]);
                if (shaded) {
                    batchColors.put(shade).put(shade).put(shade);
                }
            }
            count += 3;
        }
        flushBatch(count, shaded);
    }

    // the triangles of one model are collected here and drawn with one glDrawArrays, instead of a glTexCoord2f and a glVertex4f
    // call (two native calls) for every corner of every triangle, which was thousands of calls per ragdoll per frame.
    // direct buffers, reused and only grown
    private static FloatBuffer batchPositions = BufferUtils.createFloatBuffer(4 * 3 * 2048);
    private static FloatBuffer batchUvs = BufferUtils.createFloatBuffer(2 * 3 * 2048);
    private static FloatBuffer batchColors = BufferUtils.createFloatBuffer(3 * 3 * 2048);

    // room for indexCount corners (one per triangle index)
    private static void ensureBatch(int indexCount) {
        if (batchPositions.capacity() < indexCount * 4) {
            batchPositions = BufferUtils.createFloatBuffer(indexCount * 4);
            batchUvs = BufferUtils.createFloatBuffer(indexCount * 2);
            batchColors = BufferUtils.createFloatBuffer(indexCount * 3);
        }
        batchPositions.clear();
        batchUvs.clear();
        batchColors.clear();
    }

    // draws what was collected (vertices already in clip space, the matrices are the identity). colored = a colour per corner,
    // otherwise the caller's current colour is used as it is (a highlight)
    private static void flushBatch(int vertexCount, boolean colored) {
        if (vertexCount == 0) {
            return;
        }
        batchPositions.flip();
        batchUvs.flip();
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(4, 0, batchPositions);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(2, 0, batchUvs);
        if (colored) {
            batchColors.flip();
            glEnableClientState(GL_COLOR_ARRAY);
            glColorPointer(3, 0, batchColors);
        }
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        if (colored) {
            glDisableClientState(GL_COLOR_ARRAY);
        }
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
    }

    // out = a * b, all column-major 4x4
    private static void multiply(FloatBuffer a, FloatBuffer b, float[] out) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float sum = 0.0F;
                for (int k = 0; k < 4; k++) {
                    sum += a.get(k * 4 + r) * b.get(c * 4 + k);
                }
                out[c * 4 + r] = sum;
            }
        }
    }
}