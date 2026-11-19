package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.audio.SoundEffects;

/** black screen with the logo in the centre and the intro sound, fades in and out. turned off with the "Splash Screen" option */
public class SplashScreen extends StartupScreen {

    private static final String LOGO_TEXTURE = "/assets/rubydung/textures/gui/tbd3k.png";

    // the logo is scaled up by a whole number as far as it fits in this box
    private static final int MAX_WIDTH = 400;
    private static final int MAX_HEIGHT = 400;

    private static final float FADE_IN_SECONDS = 0.8f;
    private static final float FADE_OUT_SECONDS = 0.8f;
    // ui/intro.ogg is 5.1 s long
    private static final float TOTAL_SECONDS = 5.5f;

    public SplashScreen(RubyDung game, Runnable next) {
        super(game, next, LOGO_TEXTURE, MAX_WIDTH, MAX_HEIGHT, FADE_IN_SECONDS, 0.5f, TOTAL_SECONDS);
    }

    @Override
    protected void onShown() {
        SoundEffects.play("intro");
    }

    @Override
    protected float visibility() {
        float fadeOut = Math.min(1.0f, (TOTAL_SECONDS - elapsedSeconds()) / FADE_OUT_SECONDS);
        return Math.max(0.0f, Math.min(super.visibility(), fadeOut));
    }
}
