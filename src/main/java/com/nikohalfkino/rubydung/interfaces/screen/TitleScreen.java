package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Column;
import com.nikohalfkino.rubydung.interfaces.widget.Image;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.Panel;
import com.nikohalfkino.rubydung.util.GameSettings;

public class TitleScreen extends MenuScreen {

    private static final String LOGO_TEXTURE = "/assets/rubydung/textures/gui/logo.png";
    private static final float LOGO_WIDTH = 249.0f * 2;
    private static final float LOGO_HEIGHT = 55.0f * 2;

    // logo to the version text under it
    private static final float VERSION_GAP = 6.0f;

    // panel bevel to the buttons inside it
    private static final float PANEL_PADDING = 20.0f;

    // panel to the "Logged in as" line under it
    private static final float LOGIN_GAP = 12.0f;

    private static final float FADE_IN_SECONDS = 0.8f;

    // 0 = no fade, otherwise when the fade in started
    private final long fadeStartNanos;

    public TitleScreen(RubyDung game) {
        this(game, false);
    }

    /** fadeIn = comes up out of black (after the start up screens) */
    public TitleScreen(RubyDung game, boolean fadeIn) {
        super(game);
        this.fadeStartNanos = fadeIn ? System.nanoTime() : 0L;

        // Logo: centred, a quarter of the way down the window.
        Image logo = add(new Image(LOGO_TEXTURE, LOGO_WIDTH, LOGO_HEIGHT), Anchor.TOP_CENTER, 0, 0);
        logo.setOffsetFraction(0.0f, 0.25f);

        // Version, centred just under the logo.
        Label version = add(new Label("- " + RubyDung.GAME_VERSION + " -").setColor(UiStyle.TEXT_DIM),
                Anchor.TOP_CENTER, 0, LOGO_HEIGHT + VERSION_GAP);
        version.setOffsetFraction(0.0f, 0.25f);

        // Panel with the buttons, and the "Logged in as" line under it, as one block half way down.
        Column block = add(new Column(LOGIN_GAP), Anchor.TOP_CENTER, 0, 0);
        block.setOffsetFraction(0.0f, 0.5f);

        Panel menu = block.add(new Panel(UiStyle.BUTTON_GAP));
        menu.setPadding(PANEL_PADDING);
        menu.setShadowAlpha(1.0f);
        menu.add(new Button("Play").onClick(() -> game.openScreen(new WorldListScreen(game))));
        menu.add(new Button("Multiplayer").onClick(() -> game.openScreen(new JoinServerScreen(game))));
        menu.add(new Button("Options").onClick(() -> game.openScreen(new OptionsScreen(game))));
        menu.add(new Button("Quit").onClick(game::quit));

        block.add(new Label(() -> "Logged in as: " + GameSettings.getUsername()).setColor(UiStyle.TEXT_DIM));
    }

    @Override
    protected void renderForeground(int mouseX, int mouseY) {
        if (this.fadeStartNanos == 0L) {
            return;
        }
        float t = (System.nanoTime() - this.fadeStartNanos) / 1_000_000_000.0f / FADE_IN_SECONDS;
        if (t < 1.0f) {
            Gui2D.fillRect(0, 0, this.width, this.height, 0.0f, 0.0f, 0.0f, 1.0f - t);
        }
    }
}
