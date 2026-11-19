package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.render.Textures;

import static org.lwjgl.opengl.GL11.*;

public final class Gui2D {

    private Gui2D() {
    }

    // open begin() calls, widgets like TextInput nest them, so only the outermost end() restores the 3D state
    private static int depth = 0;

    /** whether a begin() is open: the 2D state (blending on, alpha-blend function set, no depth test) is then known without asking the driver */
    public static boolean isActive() {
        return depth > 0;
    }

    public static void begin(int width, int height) {
        depth++;

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0, width, height, 0, -1, 1);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public static void fillRect(float x, float y, float width, float height, float r, float g, float b, float a) {
        glDisable(GL_TEXTURE_2D);
        glColor4f(r, g, b, a);
        glBegin(GL_QUADS);
        glVertex2f(x, y);
        glVertex2f(x + width, y);
        glVertex2f(x + width, y + height);
        glVertex2f(x, y + height);
        glEnd();
    }

    public static void drawRectOutline(float x, float y, float width, float height, float r, float g, float b, float a) {
        fillRect(x, y, width, 1.0f, r, g, b, a);
        fillRect(x, y + height - 1.0f, width, 1.0f, r, g, b, a);
        fillRect(x, y, 1.0f, height, r, g, b, a);
        fillRect(x + width - 1.0f, y, 1.0f, height, r, g, b, a);
    }

    public static void drawNineSlice(String texturePath, float x, float y, float width, float height, int texWidth, int texHeight, int border) {
        drawNineSlice(texturePath, x, y, width, height, texWidth, texHeight, border, 1.0f);
    }

    public static void drawNineSlice(String texturePath, float x, float y, float width, float height, int texWidth, int texHeight, int border, float brightness) {
        int textureId = Textures.loadTexture(texturePath, GL_NEAREST);

        glEnable(GL_TEXTURE_2D);
        Textures.bind(textureId);
        glColor4f(brightness, brightness, brightness, 1.0f);

        float b = border * 2.0f;

        float u0 = 0.0f;
        float u1 = border / (float) texWidth;
        float u2 = (texWidth - border) / (float) texWidth;
        float u3 = 1.0f;

        float v0 = 0.0f;
        float v1 = border / (float) texHeight;
        float v2 = (texHeight - border) / (float) texHeight;
        float v3 = 1.0f;

        float x0 = x;
        float x1 = x + b;
        float x2 = x + width - b;
        float x3 = x + width;

        float y0 = y;
        float y1 = y + b;
        float y2 = y + height - b;
        float y3 = y + height;

        float[][] coords = {
                {x0, y0, x1, y1, u0, v0, u1, v1},
                {x1, y0, x2, y1, u1, v0, u2, v1},
                {x2, y0, x3, y1, u2, v0, u3, v1},
                {x0, y1, x1, y2, u0, v1, u1, v2},
                {x1, y1, x2, y2, u1, v1, u2, v2},
                {x2, y1, x3, y2, u2, v1, u3, v2},
                {x0, y2, x1, y3, u0, v2, u1, v3},
                {x1, y2, x2, y3, u1, v2, u2, v3},
                {x2, y2, x3, y3, u2, v2, u3, v3}
        };

        glBegin(GL_QUADS);
        for (float[] q : coords) {
            glTexCoord2f(q[4], q[5]); glVertex2f(q[0], q[1]);
            glTexCoord2f(q[6], q[5]); glVertex2f(q[2], q[1]);
            glTexCoord2f(q[6], q[7]); glVertex2f(q[2], q[3]);
            glTexCoord2f(q[4], q[7]); glVertex2f(q[0], q[3]);
        }
        glEnd();
    }

    public static void drawTexture(String texturePath, float x, float y, float width, float height) {
        int textureId = Textures.loadTexture(texturePath, GL_NEAREST);

        glEnable(GL_TEXTURE_2D);
        Textures.bind(textureId);
        glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        glBegin(GL_QUADS);
        glTexCoord2f(0.0f, 0.0f); glVertex2f(x, y);
        glTexCoord2f(1.0f, 0.0f); glVertex2f(x + width, y);
        glTexCoord2f(1.0f, 1.0f); glVertex2f(x + width, y + height);
        glTexCoord2f(0.0f, 1.0f); glVertex2f(x, y + height);
        glEnd();
    }

    /** like drawTexture, but only stretches the (u0, v0)-(u1, v1) part (0..1), e.g. to skip a sprite's empty border */
    public static void drawTextureRegion(String texturePath, float x, float y, float width, float height,
                                         float u0, float v0, float u1, float v1) {
        int textureId = Textures.loadTexture(texturePath, GL_NEAREST);

        glEnable(GL_TEXTURE_2D);
        Textures.bind(textureId);
        glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        glBegin(GL_QUADS);
        glTexCoord2f(u0, v0); glVertex2f(x, y);
        glTexCoord2f(u1, v0); glVertex2f(x + width, y);
        glTexCoord2f(u1, v1); glVertex2f(x + width, y + height);
        glTexCoord2f(u0, v1); glVertex2f(x, y + height);
        glEnd();
    }

    public static void end() {
        glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();

        depth = Math.max(0, depth - 1);

        // A nested end() leaves the 2D state alone: restoring culling here would make every
        // widget drawn afterwards (their quads are clockwise in this projection) vanish.
        if (depth == 0) {
            glDisable(GL_BLEND);
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }
    }
}