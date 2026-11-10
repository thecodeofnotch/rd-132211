package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.Panel;
import com.nikohalfkino.rubydung.interfaces.widget.Row;
import com.nikohalfkino.rubydung.interfaces.widget.TextInput;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.net.Protocol;
import com.nikohalfkino.rubydung.util.GameSettings;

/** type a server's address (host or host:port) and join it. also shows why the last connection ended */
public class JoinServerScreen extends MenuScreen {

    private static final float PANEL_WIDTH = UiStyle.PANEL_WIDTH + 80.0f;
    private static final float PANEL_PADDING = 20.0f;
    private static final float PANEL_TOP = 110.0f;
    private static final float FIELD_HEIGHT = UiStyle.ROW_HEIGHT;
    private static final float BUTTON_HEIGHT = UiStyle.BUTTON_HEIGHT;
    private static final float ITEM_GAP = 8.0f;
    private static final float SECTION_GAP = 16.0f;
    private static final int MAX_ADDRESS_LENGTH = 64;

    private final TextInput addressInput;
    // asked for when the server has one, never saved
    private final TextInput passwordInput;
    private String message;

    public JoinServerScreen(RubyDung game) {
        this(game, null);
    }

    /** message: shown in red, e.g. why the connection failed or ended */
    public JoinServerScreen(RubyDung game, String message) {
        super(game);
        this.message = message;

        setKeyRepeat(true);
        setSwallowKeys(true);
        setStickyFocus(true);
        onEscape(this::back);
        onEnter(this::join);

        addTitle("Multiplayer");

        Panel panel = add(new Panel(ITEM_GAP), Anchor.TOP_CENTER, 0, PANEL_TOP);
        panel.setPadding(PANEL_PADDING);
        panel.setShadowAlpha(1.0f);

        panel.add(new Label("Server address").setColor(UiStyle.TEXT_DIM));
        this.addressInput = panel.add(
                new TextInput("", PANEL_WIDTH, FIELD_HEIGHT, MAX_ADDRESS_LENGTH), ITEM_GAP);
        // remembered between runs: GameSettings keeps it in options.txt
        this.addressInput.setText(GameSettings.getLastServer());

        panel.add(new Label("Password (if the server has one)").setColor(UiStyle.TEXT_DIM), SECTION_GAP);
        this.passwordInput = panel.add(
                new TextInput("", PANEL_WIDTH, FIELD_HEIGHT, Protocol.MAX_PASSWORD_LENGTH).setMasked(true), ITEM_GAP);

        panel.add(new Label(() -> "Port " + Protocol.DEFAULT_PORT + " if you leave it out. Playing as "
                + GameSettings.getUsername()).setColor(UiStyle.TEXT_DIM), SECTION_GAP);

        float buttonWidth = (PANEL_WIDTH - UiStyle.BUTTON_GAP) / 2.0f;
        Row bottom = panel.add(new Row(UiStyle.BUTTON_GAP), ITEM_GAP);
        bottom.add(new Button("Join", buttonWidth, BUTTON_HEIGHT, true).onClick(this::join));
        bottom.add(new Button("Back", buttonWidth, BUTTON_HEIGHT, true).onClick(this::back));

        Label messageLabel = panel.add(new Label(() -> this.message)
                .setColor(UiStyle.TEXT_ERROR)
                .setMaxWidth((int) PANEL_WIDTH));
        messageLabel.setSize(PANEL_WIDTH, -1.0f);
        messageLabel.setVisibleWhen(() -> this.message != null);

        setFocus(this.addressInput);
    }

    private void join() {
        String address = this.addressInput.getText().trim();
        if (address.isEmpty()) {
            this.message = "Enter the server's address";
            return;
        }
        // saved before connecting, so a server that is down or refuses us is still there to retry next time
        GameSettings.setLastServer(address);
        GameSettings.save();
        Multiplayer.connect(this.game, address, this.passwordInput.getText());
    }

    private void back() {
        this.game.openScreen(new TitleScreen(this.game));
    }
}
