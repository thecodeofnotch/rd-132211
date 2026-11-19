package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * base of the start up screens (splash, flashing lights warning): black background, one picture in the centre,
 * fades in from black, any key or click fades out once minSkipSeconds have passed, and it moves on by itself after totalSeconds (0 = never)
 */
abstract class StartupScreen extends Screen {

    private static final int FALLBACK_SIZE = 128;

    protected final RubyDung game;
    private final Runnable next;
    private final String texturePath;
    private final float fadeInSeconds;
    private final float minSkipSeconds;
    private final float totalSeconds;

    // picture size on screen: native size times a whole number so the pixels stay sharp
    protected final float imageWidth;
    protected final float imageHeight;

    // fade to black when leaving, before the next screen opens
    private static final float LEAVE_FADE_SECONDS = 0.5f;

    private long startNanos;
    private long leaveNanos = 0L;
    private boolean leaving = false;
    private boolean finished = false;

    protected StartupScreen(RubyDung game, Runnable next, String texturePath, int maxImageWidth, int maxImageHeight,
                            float fadeInSeconds, float minSkipSeconds, float totalSeconds) {
        super(game.getFont());
        this.game = game;
        this.next = next;
        this.texturePath = texturePath;
        this.fadeInSeconds = fadeInSeconds;
        this.minSkipSeconds = minSkipSeconds;
        this.totalSeconds = totalSeconds;

        int[] size = nativeSize(texturePath);
        int scale = Math.max(1, Math.min(maxImageWidth / size[0], maxImageHeight / size[1]));
        this.imageWidth = size[0] * scale;
        this.imageHeight = size[1] * scale;

        setSwallowKeys(true);
    }

    /** reads the picture's size from the resource, so any size of image works */
    private static int[] nativeSize(String path) {
        try (InputStream in = StartupScreen.class.getResourceAsStream(path)) {
            if (in != null) {
                BufferedImage image = ImageIO.read(in);
                if (image != null) {
                    return new int[]{image.getWidth(), image.getHeight()};
                }
            }
        } catch (Exception e) {
            System.err.println("[startup] could not read " + path + ": " + e);
        }
        return new int[]{FALLBACK_SIZE, FALLBACK_SIZE};
    }

    @Override
    public void init(int width, int height) {
        super.init(width, height);
        this.startNanos = System.nanoTime();
        onShown();
    }

    /** the screen just appeared (play a sound here) */
    protected void onShown() {
    }

    /** where the middle of the picture is, as a fraction of the window height */
    protected float imageCenterFraction() {
        return 0.5f;
    }

    protected float elapsedSeconds() {
        return (System.nanoTime() - this.startNanos) / 1_000_000_000.0f;
    }

    /** 0 = black, 1 = fully visible. subclasses can add a fade out */
    protected float visibility() {
        float in = this.fadeInSeconds <= 0.0f ? 1.0f : Math.min(1.0f, elapsedSeconds() / this.fadeInSeconds);
        if (!this.leaving) {
            return in;
        }
        float out = 1.0f - (System.nanoTime() - this.leaveNanos) / 1_000_000_000.0f / LEAVE_FADE_SECONDS;
        return Math.max(0.0f, Math.min(in, out));
    }

    /** fades to black, then moves on. calling it again does nothing */
    protected void leave() {
        if (!this.leaving) {
            this.leaving = true;
            this.leaveNanos = System.nanoTime();
        }
    }

    protected boolean isLeaving() {
        return this.leaving;
    }

    /** whether any key or click leaves the screen (a screen with its own buttons says no) */
    protected boolean anyInputLeaves() {
        return true;
    }

    /** moves on to the next screen, once */
    protected void finish() {
        if (this.finished) {
            return;
        }
        this.finished = true;
        this.next.run();
    }

    @Override
    public boolean pausesGame() {
        return false;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        boolean timedOut = this.totalSeconds > 0.0f && elapsedSeconds() >= this.totalSeconds;
        boolean fadedOut = this.leaving && visibility() <= 0.0f;
        if (timedOut || fadedOut) {
            finish();
            return;
        }
        super.render(mouseX, mouseY);
    }

    @Override
    protected void renderBackground() {
        Gui2D.fillRect(0, 0, this.width, this.height, 0.0f, 0.0f, 0.0f, 1.0f);

        float x = (this.width - this.imageWidth) / 2.0f;
        float y = this.height * imageCenterFraction() - this.imageHeight / 2.0f;
        Gui2D.drawTexture(this.texturePath, x, y, this.imageWidth, this.imageHeight);
    }

    // black over everything = fade. the labels are drawn before this, so they fade with the picture
    @Override
    protected void renderForeground(int mouseX, int mouseY) {
        float visible = visibility();
        if (visible < 1.0f) {
            Gui2D.fillRect(0, 0, this.width, this.height, 0.0f, 0.0f, 0.0f, 1.0f - visible);
        }
    }

    private boolean canSkip() {
        return elapsedSeconds() >= this.minSkipSeconds;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        if (this.leaving) {
            return;
        }
        if (anyInputLeaves()) {
            if (canSkip()) {
                leave();
            }
        } else {
            super.mouseClicked(mouseX, mouseY);
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (!this.leaving && anyInputLeaves() && canSkip()) {
            leave();
        }
        return true;
    }
}
