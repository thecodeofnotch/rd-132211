package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.Row;
import com.nikohalfkino.rubydung.util.KeyMap;

/**
 * black screen with a warning picture, two lines of text and Exit / Continue. Continue fades into the next screen,
 * Exit closes the game. turned off with the "Flashing Lights Warning" option
 */
public class FlashingLightsScreen extends StartupScreen {

    private static final String WARNING_TEXTURE = "/assets/rubydung/textures/gui/flashing_lights.png";

    private static final int MAX_WIDTH = 320;
    private static final int MAX_HEIGHT = 200;

    // picture a bit above the middle, the text under it
    private static final float CENTER_FRACTION = 0.42f;
    private static final float TEXT_GAP = 28.0f;
    private static final float LINE_GAP = 22.0f;
    private static final float BUTTON_GAP_Y = 34.0f;

    private static final float BUTTON_WIDTH = 160.0f;
    private static final float BUTTON_SPACING = 40.0f;

    private static final float FADE_IN_SECONDS = 0.4f;

    public FlashingLightsScreen(RubyDung game, Runnable next) {
        // no timeout, it waits for a button
        super(game, next, WARNING_TEXTURE, MAX_WIDTH, MAX_HEIGHT, FADE_IN_SECONDS, 0.0f, 0.0f);

        float textY = this.imageHeight / 2.0f + TEXT_GAP;

        Label warning = add(new Label("This game contains flashing lights. Proceed with caution"),
                Anchor.TOP_CENTER, 0, textY);
        warning.setOffsetFraction(0.0f, CENTER_FRACTION);

        Label hint = add(new Label("(This warning can be turned off in the Options menu)")
                .setColor(UiStyle.TEXT_DIM), Anchor.TOP_CENTER, 0, textY + LINE_GAP);
        hint.setOffsetFraction(0.0f, CENTER_FRACTION);

        Row buttons = add(new Row(BUTTON_SPACING), Anchor.TOP_CENTER, 0, textY + LINE_GAP + BUTTON_GAP_Y);
        buttons.setOffsetFraction(0.0f, CENTER_FRACTION);
        buttons.add(new Button("Exit", BUTTON_WIDTH, UiStyle.BUTTON_HEIGHT, true)
                .onClick(() -> {
                    if (!isLeaving()) {
                        game.quit();
                    }
                }));
        buttons.add(new Button("Continue", BUTTON_WIDTH, UiStyle.BUTTON_HEIGHT, true)
                .onClick(this::leave));
    }

    @Override
    protected boolean anyInputLeaves() {
        return false;
    }

    @Override
    protected float imageCenterFraction() {
        return CENTER_FRACTION;
    }

    // Enter = Continue
    @Override
    public boolean keyPressed(int key, char character) {
        if (!isLeaving() && (key == KeyMap.ENTER || key == KeyMap.NUMPAD_ENTER)) {
            leave();
        }
        return true;
    }
}
