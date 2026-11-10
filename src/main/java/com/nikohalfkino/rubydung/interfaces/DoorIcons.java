package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile;
import com.nikohalfkino.rubydung.level.tiles.special.DoorColor;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * flat 2d door icon (world list rows, colour picker), loads its own textures so it works without a world
 *
 * render thread only, inside a Gui2D pass
 */
public final class DoorIcons {

    private static final String TEXTURE_PATH = "/assets/rubydung/textures/block/";

    // gl texture id per name, 0 = failed to load (don't retry every frame)
    private static final Map<String, Integer> TEXTURES = new HashMap<>();

    private DoorIcons() {
    }

    /** size x size square with its top-left at (x, y) */
    public static void draw(DoorColor color, boolean open, float x, float y, float size, float alpha) {
        int texture = texture(DimensionalDoorTile.textureName(color, open));
        if (texture == 0) {
            return;
        }

        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_CURRENT_BIT | GL_TEXTURE_BIT);

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindTexture(GL_TEXTURE_2D, texture);
        glColor4f(1.0F, 1.0F, 1.0F, alpha);

        glBegin(GL_QUADS);
        glTexCoord2f(0.0F, 0.0F);
        glVertex2f(x, y);
        glTexCoord2f(1.0F, 0.0F);
        glVertex2f(x + size, y);
        glTexCoord2f(1.0F, 1.0F);
        glVertex2f(x + size, y + size);
        glTexCoord2f(0.0F, 1.0F);
        glVertex2f(x, y + size);
        glEnd();

        glPopAttrib();
    }

    private static int texture(String name) {
        Integer cached = TEXTURES.get(name);
        if (cached != null) {
            return cached;
        }

        int id = load(name);
        TEXTURES.put(name, id);
        return id;
    }

    private static int load(String name) {
        BufferedImage image;
        try (InputStream in = DoorIcons.class.getResourceAsStream(TEXTURE_PATH + name + ".png")) {
            if (in == null) {
                return 0;
            }
            image = ImageIO.read(in);
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }

        if (image == null) {
            return 0;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >> 16) & 0xFF));
            buffer.put((byte) ((pixel >> 8) & 0xFF));
            buffer.put((byte) (pixel & 0xFF));
            buffer.put((byte) ((pixel >> 24) & 0xFF));
        }
        buffer.flip();

        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        return id;
    }
}