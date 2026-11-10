package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.entity.CubeEntity;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

/**
 * floating text over things in the world (player names, where a door leads, the number a dice shows), with an optional
 * chat bubble above it. main thread only
 *
 * two passes, like the HUD tags always were:
 *  1. 3D pass, while the world matrices are active: begin(), then add(...) for everything that gets a tag. add() projects
 *     the anchor to the screen and keeps it (anything out of range or off screen is dropped)
 *  2. HUD pass: render(font, width, height) draws what was kept
 */
public final class NameTags {

    public static final double RANGE = 64.0D;

    // gap in screen pixels between the anchor and the bottom of the tag
    public static final int GAP = 6;

    // the tag is scaled by distance (a tag far away is smaller) but never outside these bounds,
    // so it stays readable in the distance and doesn't fill the screen up close
    // (a glyph is 8 px at scale 1, the old fixed tag was drawn at scale 2)
    public static final float REFERENCE_DISTANCE = 10.0F;
    public static final float BASE_SCALE = 2.0F;
    public static final float MIN_SCALE = 1.5F;
    public static final float MAX_SCALE = 3.0F;

    // a dice's number floats this far (blocks) above the top of the cube
    private static final double DICE_TAG_GAP = 0.35D;
    private static final int DICE_TAG_COLOR = 0xFFE64D;

    // chat bubble: how wide it gets (px at scale 1) and how many lines
    private static final int BUBBLE_WIDTH = 110;
    private static final int BUBBLE_MAX_LINES = 4;
    private static final int BUBBLE_GAP = 6;
    private static final long TYPING_DOT_NANOS = 400_000_000L;

    private static final class Tag {
        final String text;
        final int color;
        final float scale;
        final float fx, fy;
        // what they said, null when there is no bubble to show
        final String bubble;
        // typing a message: a bubble with "..." (only when there is no bubble)
        final boolean typing;

        Tag(String text, int color, float scale, float fx, float fy, String bubble, boolean typing) {
            this.text = text;
            this.color = color;
            this.scale = scale;
            this.fx = fx;
            this.fy = fy;
            this.bubble = bubble;
            this.typing = typing;
        }
    }

    private final List<Tag> tags = new ArrayList<>();

    private final FloatBuffer model = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer window = BufferUtils.createFloatBuffer(3);

    /** starts a frame: forgets last frame's tags and reads the active matrices. call at the start of the 3D pass */
    public void begin() {
        this.tags.clear();
        this.model.clear();
        this.projection.clear();
        this.viewport.clear();
        glGetFloat(GL_MODELVIEW_MATRIX, this.model);
        glGetFloat(GL_PROJECTION_MATRIX, this.projection);
        glGetInteger(GL_VIEWPORT, this.viewport);
    }

    public void clear() {
        this.tags.clear();
    }

    /** a plain tag at a world position (matrices are active). false if it is out of range or not on screen */
    public boolean add(double x, double y, double z, PlayerCamera camera, String text, int color) {
        return add(x, y, z, camera, text, color, null, false);
    }

    /**
     * a tag at a world position (matrices are active), with the bubble over it if there is one (or the typing dots if
     * typing is set and there is no bubble). false if it is out of range or not on screen
     */
    public boolean add(double x, double y, double z, PlayerCamera camera, String text, int color,
                       String bubble, boolean typing) {
        return add(x, y, z, camera.getX(), camera.getY(), camera.getZ(), text, color, bubble, typing);
    }

    /** the same with the camera position given as numbers, for callers that have no PlayerCamera at hand */
    public boolean add(double x, double y, double z, double cameraX, double cameraY, double cameraZ,
                       String text, int color, String bubble, boolean typing) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq > RANGE * RANGE) {
            return false;
        }
        float distance = (float) Math.sqrt(distanceSq);
        float scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE,
                BASE_SCALE * REFERENCE_DISTANCE / Math.max(distance, 0.001F)));

        this.window.clear();
        boolean ok = GLU.gluProject((float) x, (float) y, (float) z,
                this.model, this.projection, this.viewport, this.window);
        float depth = this.window.get(2);
        if (!ok || depth <= 0.0F || depth >= 1.0F) {
            return false;
        }
        float fx = (this.window.get(0) - this.viewport.get(0)) / this.viewport.get(2);
        float fy = 1.0F - (this.window.get(1) - this.viewport.get(1)) / this.viewport.get(3);
        this.tags.add(new Tag(text, color, scale, fx, fy, bubble, typing && bubble == null));
        return true;
    }

    /**
     * the number a dice shows, floating over it (position blended between the last two ticks). nothing for a cube that
     * is no dice, has not rolled or is being carried
     */
    public boolean addDice(CubeEntity dice, float partialTicks, double cameraX, double cameraY, double cameraZ) {
        int value = dice.diceValue();
        if (value <= 0 || dice.held) {
            return false;
        }
        double x = dice.prevX + (dice.x - dice.prevX) * partialTicks;
        double y = dice.prevY + (dice.y - dice.prevY) * partialTicks + CubeEntity.HALF + DICE_TAG_GAP;
        double z = dice.prevZ + (dice.z - dice.prevZ) * partialTicks;
        return add(x, y, z, cameraX, cameraY, cameraZ, Integer.toString(value), DICE_TAG_COLOR, null, false);
    }

    /** HUD pass: draws the tags found since begin(), width and height are the HUD's size */
    public void render(Font font, int width, int height) {
        if (this.tags.isEmpty()) {
            return;
        }

        Gui2D.begin(width, height);
        for (Tag tag : this.tags) {
            int textWidth = Math.round(font.getWidth(tag.text) * tag.scale);
            int textHeight = Math.round(8 * tag.scale);
            int x = Math.round(tag.fx * width) - textWidth / 2;
            int y = Math.round(tag.fy * height) - textHeight - GAP;

            Gui2D.fillRect(x - 3, y - 2, textWidth + 6, textHeight + 4, 0.0F, 0.0F, 0.0F, 0.4F);
            font.drawStringScaled(tag.text, x, y, tag.color, tag.scale);

            if (tag.bubble != null) {
                drawBubble(font, tag, Math.round(tag.fx * width), y - 2 - BUBBLE_GAP, tag.bubble);
            } else if (tag.typing) {
                // three dots that fill in one by one; the bubble is as wide as all three so it does not jump
                int dots = 1 + (int) ((System.nanoTime() / TYPING_DOT_NANOS) % 3);
                drawBubble(font, tag, Math.round(tag.fx * width), y - 2 - BUBBLE_GAP, "...".substring(0, dots), "...");
            }
        }
        Gui2D.end();
    }

    private static void drawBubble(Font font, Tag tag, int centerX, int bottomY, String text) {
        drawBubble(font, tag, centerX, bottomY, text, text);
    }

    /**
     * a chat bubble drawn exactly like a name tag (the same dark translucent box, the same padding, the tag's colour
     * for the text), with its bottom edge at bottomY and centred on centerX. sizeText decides the size (the typing
     * dots are sized for all three so the box does not change width)
     */
    private static void drawBubble(Font font, Tag tag, int centerX, int bottomY, String text, String sizeText) {
        float scale = tag.scale;
        int maxWidth = Math.round(BUBBLE_WIDTH * scale);
        List<String> lines = wrap(font, text, scale, maxWidth);
        List<String> sizing = text == sizeText ? lines : wrap(font, sizeText, scale, maxWidth);

        int textWidth = 0;
        for (String line : sizing) {
            textWidth = Math.max(textWidth, Math.round(font.getWidth(line) * scale));
        }
        int lineHeight = Math.round(8 * scale) + Math.max(1, Math.round(scale));
        int textHeight = sizing.size() * lineHeight - Math.max(1, Math.round(scale));

        int boxX = centerX - textWidth / 2 - 3;
        int boxY = bottomY - textHeight - 4;

        Gui2D.fillRect(boxX, boxY, textWidth + 6, textHeight + 4, 0.0F, 0.0F, 0.0F, 0.4F);

        int y = boxY + 2;
        for (String line : lines) {
            font.drawStringScaled(line, boxX + 3, y, tag.color, scale);
            y += lineHeight;
        }
    }

    // greedy word wrap to maxWidth px (at the given scale), long words are cut, at most BUBBLE_MAX_LINES lines
    private static List<String> wrap(Font font, String text, float scale, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            while (Math.round(font.getWidth(word) * scale) > maxWidth && word.length() > 1) {
                // a word wider than the bubble: put as much of it on the line as fits
                int fit = word.length() - 1;
                while (fit > 1 && Math.round(font.getWidth(word.substring(0, fit)) * scale) > maxWidth) {
                    fit--;
                }
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                lines.add(word.substring(0, fit));
                word = word.substring(fit);
            }

            String candidate = current.length() == 0 ? word : current + " " + word;
            if (Math.round(font.getWidth(candidate) * scale) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                if (current.length() > 0) {
                    lines.add(current.toString());
                }
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }

        if (lines.size() > BUBBLE_MAX_LINES) {
            lines = new ArrayList<>(lines.subList(0, BUBBLE_MAX_LINES));
            lines.set(BUBBLE_MAX_LINES - 1, lines.get(BUBBLE_MAX_LINES - 1) + "...");
        }
        return lines;
    }
}