package com.nikohalfkino.rubydung.render;

import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * The vertex snap of an old console, in one place: a vertex is carried to the screen and moved to the nearest point of a coarse grid, so
 * whatever moves (or is looked at from a moving camera) crawls from one step to the next. ModelRenderer (on the CPU) and RagdollShader and
 * WorldBorderRenderer (in their vertex shaders, through GLSL) all take their grid from here, so they snap alike.
 *
 * The grid: SNAP_PIXELS pixels of the current viewport, but never finer than SNAP_MAX_ROWS rows over the height of the view, so on a big
 * window it does not shrink to nothing. Cells are square. A caller that wants a coarser grid (the world border, which gets coarser the
 * further it is from the real terrain) multiplies the step.
 */
public final class PsxSnap {

    /** the size of the snap grid in pixels of the viewport */
    public static final float SNAP_PIXELS = 1.5F;

    /** the grid is never finer than this many rows over the height of the view: on a tall viewport the step is height / SNAP_MAX_ROWS pixels */
    public static final float SNAP_MAX_ROWS = 540.0F;

    /** nearer to the camera than this (in clip-space w) a vertex is not snapped: dividing by it would throw it far off */
    public static final float MIN_W = 0.05F;

    /**
     * GLSL for a vertex shader: vec4 psxSnap(vec4 clip, vec2 step, float amount). step is the grid in NDC (see step()), possibly scaled
     * by the caller; amount is 0 (not snapped) to 1 (fully snapped), blended per vertex
     */
    public static final String GLSL =
            "vec4 psxSnap(vec4 c, vec2 gridStep, float amount) {\n" +
                    "    if (c.w > " + MIN_W + ") {\n" +
                    "        vec2 ndc = c.xy / c.w;\n" +
                    "        vec2 snapped = floor(ndc / gridStep + 0.5) * gridStep;\n" +
                    "        c.xy = mix(ndc, snapped, amount) * c.w;\n" +
                    "    }\n" +
                    "    return c;\n" +
                    "}\n";

    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);

    private PsxSnap() {
    }

    /** the snap grid of the current viewport in clip space: out[0] = x step, out[1] = y step. one viewport query, so once per frame, not once per vertex */
    public static void step(float[] out) {
        VIEWPORT.clear();
        glGetInteger(GL_VIEWPORT, VIEWPORT);
        float width = Math.max(1, VIEWPORT.get(2));
        float height = Math.max(1, VIEWPORT.get(3));
        float rows = Math.max(1.0F, Math.min(height / SNAP_PIXELS, SNAP_MAX_ROWS));
        out[1] = 2.0F / rows;
        out[0] = out[1] * height / width;   // square cells
    }

    /** snaps one clip-space coordinate (x or y) of a vertex with clip-space w = cw to the grid of the given step, as psxSnap does */
    public static float snap(float c, float cw, float step) {
        return (float) Math.floor(c / cw / step + 0.5F) * step * cw;
    }
}