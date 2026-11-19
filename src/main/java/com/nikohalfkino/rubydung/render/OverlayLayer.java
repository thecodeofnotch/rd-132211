package com.nikohalfkino.rubydung.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * keeps the line overlays of the world (the ragdoll handles, the movement vectors) pixelated when the pixelation filter is off.
 *
 * with the filter on, the whole world is already drawn into a small viewport, so the lines are chunky for free and this class does nothing (begin() returns false and the caller draws as it always did). with the filter off, begin() redirects the drawing into a small off-screen buffer (1/PIXEL_SIZE of the view), end() stretches that buffer over the view with nearest-neighbour: the lines are antialiased (GL_LINE_SMOOTH) at the small size, so every big pixel of a line has its own soft alpha
 *
 * the world's depth is copied into the small buffer first (a nearest-neighbour blit), so what is behind a wall is still hidden
 *
 * use:  if (layer.begin()) { ... draw ...; layer.end(); }   (draw also when begin() is false: it is just not pixelated)
 * inside, call blend() instead of glBlendFunc, and draw with the camera's matrices active
 */
public final class OverlayLayer {

    /** the size of a pixel of the overlay, in window pixels, when the pixelation filter is off */
    public static final int PIXEL_SIZE = 2;

    private final IntBuffer ints = BufferUtils.createIntBuffer(16);

    private int framebuffer = 0;
    private int colorTexture = 0;
    private int depthBuffer = 0;
    private int lowWidth = 0;
    private int lowHeight = 0;

    // a driver that refuses the depth blit: the overlay then goes straight to the screen, unpixelated, instead of erroring every frame
    private boolean broken = false;

    private boolean active = false;
    private int viewX, viewY, viewWidth, viewHeight;
    private int previousFramebuffer;
    private int previousProgram;
    private float windowScale = 1.0F;

    /** the window's Fit scale (WindowManager.getScale), so a pixel of the overlay is as big as a pixel of the world filter would be */
    public void setWindowScale(float scale) {
        this.windowScale = scale;
    }

    /** whether the overlay is being drawn into the small buffer right now */
    public boolean isActive() {
        return this.active;
    }

    /**
     * starts the pixelated overlay. returns false when there is nothing to do (the pixelation filter is on, or the buffer can not be had): draw straight then, with the normal blend function
     *
     * pixelationOn: the world itself is being drawn small right now
     */
    public boolean begin(boolean pixelationOn) {
        if (pixelationOn || this.broken || this.active || !GLContext.getCapabilities().OpenGL30) {
            return false;
        }

        this.ints.clear();
        glGetInteger(GL_VIEWPORT, this.ints);
        this.viewX = this.ints.get(0);
        this.viewY = this.ints.get(1);
        this.viewWidth = this.ints.get(2);
        this.viewHeight = this.ints.get(3);
        int size = Math.max(1, Math.round(PIXEL_SIZE * this.windowScale));
        int w = Math.max(1, this.viewWidth / size);
        int h = Math.max(1, this.viewHeight / size);
        if (this.viewWidth < 2 || this.viewHeight < 2) {
            return false;
        }

        this.ints.clear();
        glGetInteger(GL30.GL_FRAMEBUFFER_BINDING, this.ints);
        this.previousFramebuffer = this.ints.get(0);
        this.ints.clear();
        glGetInteger(GL20.GL_CURRENT_PROGRAM, this.ints);
        this.previousProgram = this.ints.get(0);

        if (!ensureBuffer(w, h)) {
            this.broken = true;
            return false;
        }

        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_VIEWPORT_BIT | GL_SCISSOR_BIT
                | GL_TEXTURE_BIT | GL_CURRENT_BIT | GL_DEPTH_BUFFER_BIT);

        // the window's scissor box (the game area) means nothing in the small buffer
        glDisable(GL_SCISSOR_TEST);

        // the world's depth, shrunk, so the clear part of the overlay is hidden behind what is in front of it
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.previousFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.framebuffer);
        while (glGetError() != GL_NO_ERROR) {
            // clear any old error so the check below is about the blit
        }
        GL30.glBlitFramebuffer(this.viewX, this.viewY, this.viewX + this.viewWidth, this.viewY + this.viewHeight,
                0, 0, w, h, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        if (glGetError() != GL_NO_ERROR) {
            this.broken = true;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.previousFramebuffer);
            glPopAttrib();
            return false;
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        glViewport(0, 0, w, h);
        glColorMask(true, true, true, true);
        glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        glClear(GL_COLOR_BUFFER_BIT);

        this.active = true;
        return true;
    }

    /** puts the small picture over the view. matrices, program and GL state are left as they were before begin() */
    public void end() {
        if (!this.active) {
            return;
        }
        this.active = false;

        // back to the screen, and every attribute begin() saved (scissor test, viewport, blending, depth) comes back
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.previousFramebuffer);
        glPopAttrib();
        GL20.glUseProgram(0);

        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_LIGHTING);
        glDisable(GL_LINE_SMOOTH);
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        // the small picture's colour is premultiplied (see blend())
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glBindTexture(GL_TEXTURE_2D, this.colorTexture);
        glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, 1.0, 0.0, 1.0, -1.0, 1.0);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        glBegin(GL_QUADS);
        glTexCoord2f(0.0F, 0.0F);
        glVertex2f(0.0F, 0.0F);
        glTexCoord2f(1.0F, 0.0F);
        glVertex2f(1.0F, 0.0F);
        glTexCoord2f(1.0F, 1.0F);
        glVertex2f(1.0F, 1.0F);
        glTexCoord2f(0.0F, 1.0F);
        glVertex2f(0.0F, 1.0F);
        glEnd();

        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);

        glPopAttrib();
        GL20.glUseProgram(this.previousProgram);
    }

    /**
     * the blend function of the overlay's lines: normal alpha blending, and inside the small buffer (which starts out see-through) the colour comes out premultiplied and the alpha accumulates, which is what end() expects
     */
    public void blend() {
        if (this.active) {
            GL14.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    public void dispose() {
        if (this.framebuffer != 0) {
            GL30.glDeleteFramebuffers(this.framebuffer);
            this.framebuffer = 0;
        }
        if (this.colorTexture != 0) {
            glDeleteTextures(this.colorTexture);
            this.colorTexture = 0;
        }
        if (this.depthBuffer != 0) {
            GL30.glDeleteRenderbuffers(this.depthBuffer);
            this.depthBuffer = 0;
        }
        this.lowWidth = 0;
        this.lowHeight = 0;
    }

    // (re)allocates the buffer whenever the small size changes. false if the driver says it is not complete
    private boolean ensureBuffer(int w, int h) {
        if (this.framebuffer != 0 && this.lowWidth == w && this.lowHeight == h) {
            return true;
        }
        if (this.framebuffer == 0) {
            this.framebuffer = GL30.glGenFramebuffers();
            this.colorTexture = glGenTextures();
            this.depthBuffer = GL30.glGenRenderbuffers();
        }

        glBindTexture(GL_TEXTURE_2D, this.colorTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, this.depthBuffer);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24, w, h);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.colorTexture, 0);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, this.depthBuffer);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.previousFramebuffer);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[OverlayLayer] framebuffer incomplete (" + status + "), the overlay is not pixelated");
            dispose();
            return false;
        }
        this.lowWidth = w;
        this.lowHeight = h;
        return true;
    }

    // ---------------------------------------------------------------- shapes shared by the overlays

    /**
     * the four-barbed arrow head the overlays use, inside glBegin(GL_LINES): the tip at (px, py, pz), pointing along the unit vector (dx, dy, dz). the barbs go back from the tip by `back` and out by `side`, in two directions at right angles to each other and to the arrow (s1 and s2, unit vectors, each given as 3 numbers)
     */
    public static void arrowBarbs(double px, double py, double pz, double dx, double dy, double dz,
                                  double s1x, double s1y, double s1z, double s2x, double s2y, double s2z,
                                  double back, double side) {
        for (int k = 0; k < 4; k++) {
            double sx = k < 2 ? s1x : s2x;
            double sy = k < 2 ? s1y : s2y;
            double sz = k < 2 ? s1z : s2z;
            double sgn = (k & 1) == 0 ? 1.0D : -1.0D;
            glVertex3d(px, py, pz);
            glVertex3d(px - dx * back + sx * side * sgn, py - dy * back + sy * side * sgn, pz - dz * back + sz * side * sgn);
        }
    }
}
