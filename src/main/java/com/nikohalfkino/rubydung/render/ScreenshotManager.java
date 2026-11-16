package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.entity.player.Player;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Queue;

import static org.lwjgl.opengl.GL11.*;

/**
 * screenshots are the world drawn a second time by GameRenderer.renderWorld, into an offscreen framebuffer, from the same camera as the screen (minus the HUD, the frame and the pixelation). there is no pipeline of its own here
 *
 * taking one only queues a request. GameRenderer runs the queue in the middle of a frame, right after the world is on screen, so the picture is the frame the player is looking at (the camera pose is the one of that frame) and the GL state is a known one
 */
public final class ScreenshotManager {

    private static final File SCREENSHOTS_DIR = new File("screenshots");

    /** a request: draws what it wants with the renderer and writes the files. live is the player's camera of the current frame */
    private interface Job {
        void run(GameRenderer renderer, WorldView live, float partialTicks);
    }

    private final RubyDung game;
    private final Queue<Job> pending = new ArrayDeque<>();

    public ScreenshotManager(RubyDung game, GameRenderer gameRenderer) {
        this.game = game;
        gameRenderer.setScreenshotManager(this);
    }

    // ---------------------------------------------------------------- requests

    /** what the screen shows, scale times as large as the window */
    public void takeScreenshot(int scale) {
        if (!this.game.hasWorld()) {
            return;
        }
        this.pending.add((renderer, live, partialTicks) -> {
            BufferedImage image = capture(renderer, live, this.game.getWidth() * scale, this.game.getHeight() * scale, partialTicks);
            writeImage(image, newTimestamp(), scaleSuffix(scale));
        });
    }

    /** the six faces of a cube map around the player, each a square */
    public void takePanoramaScreenshot(int scale) {
        if (!this.game.hasWorld()) {
            return;
        }
        float[][] faces = {
                {0.0F, 0.0F}, {90.0F, 0.0F}, {180.0F, 0.0F}, {270.0F, 0.0F}, {0.0F, -90.0F}, {0.0F, 90.0F}
        };
        this.pending.add((renderer, live, partialTicks) -> {
            int size = Math.min(this.game.getWidth(), this.game.getHeight()) * scale;
            Player player = this.game.getPlayer();
            String timestamp = newTimestamp();
            for (int i = 0; i < faces.length; i++) {
                WorldView face = FixedViews.panoramaFace(player, partialTicks, faces[i][0], faces[i][1]);
                writeImage(capture(renderer, face, size, size, partialTicks), timestamp, "_panorama_" + i + scaleSuffix(scale));
            }
        });
    }

    /** a view from above at an angle, direction is NE, SE, SW or NW */
    public void takeIsometricScreenshot(String direction, int scale) {
        if (!this.game.hasWorld()) {
            return;
        }
        this.pending.add((renderer, live, partialTicks) -> {
            int size = Math.min(this.game.getWidth(), this.game.getHeight()) * scale;
            WorldView view = FixedViews.isometric(this.game.getPlayer(), partialTicks, FixedViews.isometricYaw(direction));
            writeImage(capture(renderer, view, size, size, partialTicks), newTimestamp(),
                    "_isometric_" + direction.toLowerCase() + scaleSuffix(scale));
        });
    }

    // ---------------------------------------------------------------- called by GameRenderer

    boolean hasPending() {
        return !this.pending.isEmpty();
    }

    /** runs the requests, the world of this frame is drawn and the HUD is not yet */
    void processPending(GameRenderer renderer, WorldView live, float partialTicks) {
        Job job;
        while ((job = this.pending.poll()) != null) {
            try {
                job.run(renderer, live, partialTicks);
            } catch (RuntimeException e) {
                System.out.println("Screenshot failed: " + e);
            }
        }
    }

    // ---------------------------------------------------------------- capturing

    private BufferedImage capture(GameRenderer renderer, WorldView view, int width, int height, float partialTicks) {
        int fbo = GL30.glGenFramebuffers();
        int colorTexture = glGenTextures();
        int depthRenderbuffer = GL30.glGenRenderbuffers();

        // the frame is not over: everything the world pass touches (viewport, scissor, matrices, fog, texture binding) is given back as it was
        int previousFramebuffer = glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        glPushAttrib(GL_ALL_ATTRIB_BITS);
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();

        try {
            glDisable(GL_SCISSOR_TEST); // the frame's clip to the game area must not cut the offscreen image
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

            glBindTexture(GL_TEXTURE_2D, colorTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);

            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRenderbuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRenderbuffer);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                System.out.println("Screenshot framebuffer incomplete (status " + status + "), image may be blank.");
            }

            glViewport(0, 0, width, height);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            // no highlights, no debug overlays and not "live": the players' smoothing and name tags must not be stepped a second time
            renderer.renderWorld(view, width, height, partialTicks, false, false);

            glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            return readPixels(width, height);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
            GL30.glDeleteFramebuffers(fbo);
            glDeleteTextures(colorTexture);
            GL30.glDeleteRenderbuffers(depthRenderbuffer);

            glMatrixMode(GL_PROJECTION);
            glPopMatrix();
            glMatrixMode(GL_MODELVIEW);
            glPopMatrix();
            glPopAttrib();
        }
    }

    private BufferedImage readPixels(int width, int height) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        // OpenGL's first row is the bottom one
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            int sourceRow = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int i = (sourceRow * width + x) * 4;
                int r = buffer.get(i) & 0xFF;
                int g = buffer.get(i + 1) & 0xFF;
                int b = buffer.get(i + 2) & 0xFF;
                argb[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }

    // ---------------------------------------------------------------- output

    private static String scaleSuffix(int scale) {
        return scale > 1 ? "_" + scale + "x" : "";
    }

    private static String newTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
    }

    private static void writeImage(BufferedImage image, String timestamp, String suffix) {
        SCREENSHOTS_DIR.mkdirs();

        File file = new File(SCREENSHOTS_DIR, timestamp + suffix + ".png");

        try {
            ImageIO.write(image, "png", file);
            System.out.println("Saved screenshot to " + file.getPath());
        } catch (IOException e) {
            System.out.println("Failed to save screenshot: " + e.getMessage());
        }

        com.nikohalfkino.rubydung.util.ImageTransferable.copyToClipboard(image);
        System.out.println("Copied screenshot to clipboard.");
    }
}
