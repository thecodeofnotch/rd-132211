package com.nikohalfkino.rubydung.render;

import org.lwjgl.opengl.GL20;

/**
 * the colour the light of the world is multiplied by (the sun phase's tint, see DayNightCycle.getWorldTint). GameRenderer sets
 * it every frame, the terrain, billboard and liquid shaders upload it as their uSunTint uniform
 */
public final class WorldTint {

    private static float r = 1.0F;
    private static float g = 1.0F;
    private static float b = 1.0F;

    private WorldTint() {
    }

    public static void set(float[] rgb) {
        r = rgb[0];
        g = rgb[1];
        b = rgb[2];
    }

    /** location -1 (the shader doesn't use it) is ignored */
    static void upload(int location) {
        if (location >= 0) {
            GL20.glUniform3f(location, r, g, b);
        }
    }
}
