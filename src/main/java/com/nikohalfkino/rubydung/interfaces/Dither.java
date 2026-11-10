package com.nikohalfkino.rubydung.interfaces;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * the frame's dithered darkening as a reusable fill: black through a stipple mask in 2x2-pixel cells
 * (UI_SCALE), a checkerboard or a sparser quarter-filled pattern
 *
 * the stipple is aligned to the window, so every dithered area lines up with the world darkening
 * GameFrame draws behind the frame screens. call inside Gui2D.begin
 */
public final class Dither {

    private static ByteBuffer sparseMask;
    private static ByteBuffer checkerMask;

    private Dither() {
    }

    /** opacity of the dithered pixels (1 = solid black), checkerboard unless sparse */
    public static void fill(float x, float y, float width, float height, float alpha, boolean sparse) {
        if (sparseMask == null) {
            sparseMask = buildMask(false);
            checkerMask = buildMask(true);
        }

        glEnable(GL_POLYGON_STIPPLE);
        glPolygonStipple(sparse ? sparseMask : checkerMask);
        Gui2D.fillRect(x, y, width, height, 0.0F, 0.0F, 0.0F, alpha);
        glDisable(GL_POLYGON_STIPPLE);
    }

    /** turns the checkerboard stipple on for whatever is drawn next (a sprite that should look see-through), call disableStipple after */
    public static void enableStipple() {
        if (checkerMask == null) {
            sparseMask = buildMask(false);
            checkerMask = buildMask(true);
        }
        glEnable(GL_POLYGON_STIPPLE);
        glPolygonStipple(checkerMask);
    }

    public static void disableStipple() {
        glDisable(GL_POLYGON_STIPPLE);
    }

    // 32x32 stipple in 2x2-pixel cells (UI_SCALE = 2): a checkerboard, or a quarter filled
    private static ByteBuffer buildMask(boolean checker) {
        ByteBuffer mask = BufferUtils.createByteBuffer(128);
        for (int row = 0; row < 32; row++) {
            boolean evenBand = (row / GameFrame.UI_SCALE) % 2 == 0;
            byte value = evenBand ? (byte) 0xCC : (checker ? (byte) 0x33 : (byte) 0x00);
            for (int i = 0; i < 4; i++) {
                mask.put(value);
            }
        }
        mask.flip();
        return mask;
    }
}
