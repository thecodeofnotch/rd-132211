package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.util.GameSettings;
import com.nikohalfkino.rubydung.util.GameSettings.DisplayType;
import com.nikohalfkino.rubydung.util.GameSettings.LayoutMode;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import static org.lwjgl.opengl.GL11.*;

/**
 * keeps the game's logical size (what the UI lays out in) apart from the real window size, and works out where the game is drawn inside the window
 *
 * two display states (DisplayType): plain resizable window (the window's own maximize button works as usual) and fullscreen. windowed always uses Expand, fullscreen has its own layout:
 *
 * Expand: logical size is the whole window / screen, the UI fills it
 *
 * Fit: the 800x600 game scaled by the largest whole number that fits, centred, black bars (keeps every game pixel the same size)
 *
 * Bordered: 800x600 at 1:1, centred, black around it (shrinks to fit a smaller window)
 */
public final class WindowManager {

    public static final int BASE_WIDTH = 800;
    public static final int BASE_HEIGHT = 600;

    private int logicalWidth = BASE_WIDTH;
    private int logicalHeight = BASE_HEIGHT;

    // where the logical area sits in the window, GL pixels (origin bottom-left)
    private int vpX, vpY, vpW = BASE_WIDTH, vpH = BASE_HEIGHT;
    private float scale = 1.0f;

    // state last applied to the real window
    private DisplayType applied = DisplayType.WINDOWED;

    // size and position of the plain window, restored when coming back from fullscreen
    private int windowedWidth = BASE_WIDTH;
    private int windowedHeight = BASE_HEIGHT;
    private int windowedX, windowedY;
    private boolean hasWindowedPosition = false;

    public int getLogicalWidth() {
        return this.logicalWidth;
    }

    public int getLogicalHeight() {
        return this.logicalHeight;
    }

    public float getScale() {
        return this.scale;
    }

    /** call before Display.create() so the window starts in the saved state */
    public void applyInitialMode() throws LWJGLException {
        Display.setResizable(true);

        DisplayType type = GameSettings.getDisplayType();
        if (type == DisplayType.FULLSCREEN) {
            Display.setDisplayModeAndFullscreen(Display.getDesktopDisplayMode());
        } else {
            Display.setDisplayMode(new DisplayMode(BASE_WIDTH, BASE_HEIGHT));
        }
        this.applied = type;
    }

    /** applies the current settings to an already-created window (display state, layouts) */
    public void applyMode() throws LWJGLException {
        DisplayType want = GameSettings.getDisplayType();
        boolean fullscreenNow = Display.isFullscreen();

        // Leaving the plain window: remember where it was so it can come back there.
        if (this.applied == DisplayType.WINDOWED && !fullscreenNow && want == DisplayType.FULLSCREEN) {
            this.windowedX = Display.getX();
            this.windowedY = Display.getY();
            this.windowedWidth = Display.getWidth();
            this.windowedHeight = Display.getHeight();
            this.hasWindowedPosition = true;
        }

        boolean changed = want != this.applied || (want == DisplayType.FULLSCREEN) != fullscreenNow;

        if (changed) {
            if (want == DisplayType.FULLSCREEN) {
                Display.setDisplayModeAndFullscreen(Display.getDesktopDisplayMode());
            } else {
                Display.setDisplayMode(new DisplayMode(this.windowedWidth, this.windowedHeight));
                if (fullscreenNow) {
                    Display.setFullscreen(false);
                    // leaving fullscreen makes LWJGL build a new window, and that one came back without its maximize
                    // button (greyed out). setResizable ignores a value it already holds, so it has to be turned off and on
                    Display.setResizable(false);
                    Display.setResizable(true);
                }

                if (this.hasWindowedPosition) {
                    Display.setLocation(this.windowedX, this.windowedY);
                }
            }
            Display.setVSyncEnabled(GameSettings.isVsyncEnabled());
            this.applied = want;
        }

        recompute();
    }

    /** works out the logical size and viewport rectangle from the current window and layout */
    public void recompute() {
        int sw = Display.getWidth();
        int sh = Display.getHeight();
        if (sw < 1 || sh < 1) {
            return; // minimised: keep the old layout
        }

        DisplayType type = GameSettings.getDisplayType();
        if (type == DisplayType.WINDOWED && !Display.isFullscreen()) {
            this.windowedWidth = sw;
            this.windowedHeight = sh;
        }

        LayoutMode mode = type == DisplayType.FULLSCREEN ? GameSettings.getFullscreenMode() : LayoutMode.EXPAND;

        if (mode == LayoutMode.EXPAND) {
            this.logicalWidth = sw;
            this.logicalHeight = sh;
            this.scale = 1.0f;
            this.vpW = sw;
            this.vpH = sh;
        } else {
            this.logicalWidth = BASE_WIDTH;
            this.logicalHeight = BASE_HEIGHT;

            float fitScale = Math.min(sh / (float) BASE_HEIGHT, sw / (float) BASE_WIDTH);
            if (mode == LayoutMode.FIT) {
                // Whole numbers only, so a game pixel is always N x N screen pixels. Below 1x the
                // game shrinks to fit (the only time the scale is fractional).
                this.scale = fitScale >= 1.0f ? (float) Math.floor(fitScale) : fitScale;
            } else {
                this.scale = Math.min(1.0f, fitScale);
            }
            this.vpW = Math.round(BASE_WIDTH * this.scale);
            this.vpH = Math.round(BASE_HEIGHT * this.scale);
        }

        this.vpX = (sw - this.vpW) / 2;
        this.vpY = (sh - this.vpH) / 2;
    }

    /** black everywhere, then restricts all drawing to the game area */
    public void beginFrame() {
        glViewport(0, 0, Display.getWidth(), Display.getHeight());
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glClearColor(0.5F, 0.8F, 1.0F, 1.0F); // the game's normal clear colour

        glEnable(GL_SCISSOR_TEST);
        glScissor(this.vpX, this.vpY, this.vpW, this.vpH);
        glViewport(this.vpX, this.vpY, this.vpW, this.vpH);
    }

    /** call before Display.update() */
    public void endFrame() {
        glDisable(GL_SCISSOR_TEST);
    }

    /** glViewport in logical coordinates (origin bottom-left of the game area) */
    public void viewport(int x, int y, int w, int h) {
        glViewport(glX(x), glY(y), size(w), size(h));
    }

    /** logical x -> window pixel x */
    public int glX(int x) {
        return this.vpX + Math.round(x * this.scale);
    }

    /** logical y (bottom-up) -> window pixel y */
    public int glY(int y) {
        return this.vpY + Math.round(y * this.scale);
    }

    /** logical length -> window pixels */
    public int size(int value) {
        return Math.round(value * this.scale);
    }

    /** raw window mouse x -> logical UI x */
    public int toLogicalX(int rawX) {
        return (int) ((rawX - this.vpX) / this.scale);
    }

    /** raw window mouse y (bottom-up) -> logical UI y (top-down) */
    public int toLogicalY(int rawY) {
        return this.logicalHeight - (int) ((rawY - this.vpY) / this.scale) - 1;
    }
}
