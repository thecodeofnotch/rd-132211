package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.interfaces.Gui2D;

import java.awt.image.BufferedImage;

import static org.lwjgl.opengl.GL11.*;

public class Font {

    private static final int GLYPHS_PER_ROW = 16;
    private static final int GLYPH_SIZE = 8;
    private static final int ATLAS_SIZE = 128;

    private final int defaultTextureId;
    private final int monospaceTextureId;

    private final int[] charWidths = new int[256];
    private boolean monospace = false;

    public Font(String defaultResource, String monospaceResource) {
        BufferedImage defaultImg = Textures.readImage(defaultResource);
        this.defaultTextureId = Textures.loadTexture(defaultImg, GL_NEAREST);
        measureGlyphWidths(defaultImg);

        BufferedImage monoImg = Textures.readImage(monospaceResource);
        this.monospaceTextureId = Textures.loadTexture(monoImg, GL_NEAREST);
    }

    public Font(String defaultResource) {
        this(defaultResource, defaultResource.replace("default.png", "monospace.png"));
    }

    public boolean isMonospace() {
        return this.monospace;
    }

    public void setMonospace(boolean monospace) {
        this.monospace = monospace;
    }

    private void measureGlyphWidths(BufferedImage image) {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();

        int[] pixels = new int[imageWidth * imageHeight];
        image.getRGB(0, 0, imageWidth, imageHeight, pixels, 0, imageWidth);

        for (int code = 0; code < 256; code++) {
            int column = code % GLYPHS_PER_ROW;
            int row = code / GLYPHS_PER_ROW;

            int lastOpaqueColumn = -1;
            for (int px = 0; px < GLYPH_SIZE; px++) {
                for (int py = 0; py < GLYPH_SIZE; py++) {
                    int x = column * GLYPH_SIZE + px;
                    int y = row * GLYPH_SIZE + py;
                    int alpha = (pixels[x + y * imageWidth] >>> 24) & 0xFF;

                    if (alpha > 0) {
                        lastOpaqueColumn = px;
                        break;
                    }
                }
            }

            this.charWidths[code] = (lastOpaqueColumn == -1) ? 4 : lastOpaqueColumn + 2;
        }

        this.charWidths[' '] = 4;
    }

    private int widthOf(char character) {
        if (this.monospace) {
            return 6;
        }

        if (character >= 256) {
            return 6;
        }

        return this.charWidths[character];
    }

    public int getWidth(String text) {
        if (text == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += widthOf(text.charAt(i));
        }
        return total;
    }

    public int width(String text) {
        return getWidth(text) * 2;
    }

    public void drawString(String text, int x, int y, int color) {
        drawStringScaled(text, x, y, color, 2.0F, 1.0F);
    }

    public void drawString(String text, int x, int y, int color, float alpha) {
        drawStringScaled(text, x, y, color, 2.0F, alpha);
    }

    public void drawStringScaled(String text, int x, int y, int color, float scale) {
        drawStringScaled(text, x, y, color, scale, 1.0F);
    }

    public void drawStringScaled(String text, int x, int y, int color, float scale, float alpha) {
        if (text == null || text.isEmpty()) {
            return;
        }

        // inside a Gui2D pass blending is known to be on, so the driver is not asked (a glIsEnabled per string was a stall per string).
        // outside one it is still asked, once per string, and put back as it was
        boolean blendWasOn = Gui2D.isActive() || glIsEnabled(GL_BLEND);

        glPushMatrix();
        glTranslatef((float) x, (float) y, 0.0F);
        glScalef(scale, scale, 1.0F);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        Textures.bind(this.monospace ? this.monospaceTextureId : this.defaultTextureId);

        // the shadow and the text in one batch: the colour changes between the two runs of glyphs
        glBegin(GL_QUADS);
        emit(text, 1.0F, 1.0F, shade(color), alpha);
        emit(text, 0.0F, 0.0F, color, alpha);
        glEnd();

        glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        if (!blendWasOn) {
            glDisable(GL_BLEND);
        }

        glPopMatrix();
    }

    // inside glBegin(GL_QUADS): the quads of a string in one colour
    private void emit(String text, float x, float y, int color, float alpha) {
        glColor4f(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, alpha);

        float cursorX = x;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            drawGlyph(character, cursorX, y);
            cursorX += widthOf(character);
        }
    }

    private void drawGlyph(char character, float x, float y) {
        if (character >= 256) {
            character = '?';
        }

        int column = character % GLYPHS_PER_ROW;
        int row = character / GLYPHS_PER_ROW;

        float minU = column * GLYPH_SIZE / (float) ATLAS_SIZE;
        float maxU = minU + GLYPH_SIZE / (float) ATLAS_SIZE;
        float minV = row * GLYPH_SIZE / (float) ATLAS_SIZE;
        float maxV = minV + GLYPH_SIZE / (float) ATLAS_SIZE;

        glTexCoord2f(minU, minV);
        glVertex2f(x, y);
        glTexCoord2f(minU, maxV);
        glVertex2f(x, y + GLYPH_SIZE);
        glTexCoord2f(maxU, maxV);
        glVertex2f(x + GLYPH_SIZE, y + GLYPH_SIZE);
        glTexCoord2f(maxU, minV);
        glVertex2f(x + GLYPH_SIZE, y);
    }

    private int shade(int color) {
        int r = ((color >> 16) & 0xFF) / 4;
        int g = ((color >> 8) & 0xFF) / 4;
        int b = (color & 0xFF) / 4;
        return (r << 16) | (g << 8) | b;
    }
}