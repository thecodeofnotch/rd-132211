package com.nikohalfkino.rubydung.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

public final class Fog {

    private static final float START_FRACTION = 0.55F;
    private static final float FAR_START = 1.0E9F;
    private static final float FAR_END = 2.0E9F;

    private static boolean enabled = false;
    private static float start = FAR_START;
    private static float end = FAR_END;
    private static final float[] COLOR = {0.7F, 0.8F, 1.0F};
    private static double eyeX = 0.0;
    private static double eyeZ = 0.0;

    private static final FloatBuffer COLOR_BUFFER = BufferUtils.createFloatBuffer(4);

    private Fog() {
    }

    /** the distance fog is measured from when there is no render distance limit and the world still has to fade out (falling into the void) */
    private static final float UNLIMITED_FADE_BASE = 256.0F;

    public static void update(float renderDistanceBlocks, float[] color, double x, double z) {
        update(renderDistanceBlocks, color, x, z, 1.0F);
    }

    /** visibility: 1 = the render distance as it is, towards 0 the fog closes in on the eye (the world fades out, see WorldBorder.voidVisibility) */
    public static void update(float renderDistanceBlocks, float[] color, double x, double z, float visibility) {
        if (visibility < 1.0F) {
            float base = renderDistanceBlocks > 0.0F ? renderDistanceBlocks : UNLIMITED_FADE_BASE;
            renderDistanceBlocks = base * Math.max(visibility, 0.03F);
        }
        enabled = renderDistanceBlocks > 0.0F;
        end = enabled ? renderDistanceBlocks : FAR_END;
        start = enabled ? renderDistanceBlocks * START_FRACTION : FAR_START;
        COLOR[0] = color[0];
        COLOR[1] = color[1];
        COLOR[2] = color[2];
        eyeX = x;
        eyeZ = z;
    }

    /**
     * whether something at (x, z) with the given radius is entirely past the fog's end, as seen from the eye: the terrain and the sprites fade out
     * there (the dither band), so a body out there is not drawn at all
     */
    public static boolean beyond(double x, double z, double radius) {
        if (!enabled) {
            return false;
        }
        double dx = x - eyeX;
        double dz = z - eyeZ;
        double reach = end + radius;
        return dx * dx + dz * dz > reach * reach;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    static void upload(int paramsLocation, int colorLocation, int eyeLocation) {
        if (paramsLocation >= 0) {
            GL20.glUniform2f(paramsLocation, start, end);
        }
        if (colorLocation >= 0) {
            GL20.glUniform3f(colorLocation, COLOR[0], COLOR[1], COLOR[2]);
        }
        if (eyeLocation >= 0) {
            GL20.glUniform2f(eyeLocation, (float) eyeX, (float) eyeZ);
        }
    }

    public static void applyFixedFunction() {
        if (!enabled) {
            glDisable(GL_FOG);
            return;
        }
        COLOR_BUFFER.clear();
        COLOR_BUFFER.put(COLOR[0]).put(COLOR[1]).put(COLOR[2]).put(1.0F).flip();
        glEnable(GL_FOG);
        glFogi(GL_FOG_MODE, GL_LINEAR);
        glFogf(GL_FOG_START, start);
        glFogf(GL_FOG_END, end);
        glFog(GL_FOG_COLOR, COLOR_BUFFER);
    }

    static final String GLSL_VERTEX_DECLARATIONS =
            "varying vec2 vFogWorldXZ;\n";

    static final String GLSL_FRAGMENT_DECLARATIONS =
            "uniform vec2 uFogParams;\n" +
                    "uniform vec3 uFogColor;\n" +
                    "uniform vec2 uFogEye;\n" +
                    "varying vec2 vFogWorldXZ;\n" +
                    "vec3 applyFog(vec3 rgb) {\n" +
                    "    float d = length(vFogWorldXZ - uFogEye);\n" +
                    "    float f = clamp((uFogParams.y - d) / (uFogParams.y - uFogParams.x), 0.0, 1.0);\n" +
                    "    return mix(uFogColor, rgb, f);\n" +
                    "}\n";

    // Distance-based dither fade near the render distance edge (16 block fade band)
    static final String GLSL_DITHER_DECLARATIONS =
            "void applyDither() {\n" +
                    "    float d = length(vFogWorldXZ - uFogEye);\n" +
                    "    float fadeRange = 16.0;\n" +
                    "    float uFade = clamp((uFogParams.y - d) / fadeRange, 0.0, 1.0);\n" +
                    "    if (uFade >= 1.0) return;\n" +
                    "    \n" +
                    "    // Adjust pixelSize for chunkier retro dither pixels (e.g., 1.0, 2.0, 4.0)\n" +
                    "    float pixelSize = 1.0;\n" +
                    "    vec2 coord = floor(gl_FragCoord.xy / pixelSize);\n" +
                    "    \n" +
                    "    // Interleaved Gradient Noise (Procedural Blue Noise approximation)\n" +
                    "    float noise = fract(52.9829189 * fract(dot(coord, vec2(0.06711056, 0.00583715))));\n" +
                    "    \n" +
                    "    if (uFade < noise) {\n" +
                    "        discard;\n" +
                    "    }\n" +
                    "}\n";
}