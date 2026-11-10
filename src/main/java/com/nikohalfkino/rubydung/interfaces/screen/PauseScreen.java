package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.FramePanelScreen;
import com.nikohalfkino.rubydung.interfaces.GameFrame;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Column;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.net.Protocol;
import com.nikohalfkino.rubydung.render.Font;


/**
 * pause menu in the game frame: portrait on top of the right panel, buttons in the dark panel below, frozen world on the left
 *
 * everything is a widget pinned to the frame every frame (see FramePanelScreen): button column, big "Paused", "Level Saved!" note under Save
 *
 * the bar under the world view shows the world and its time of day, "Quit" is in the bottom-right corner
 */
public class PauseScreen extends FramePanelScreen {

    private static final float BUTTON_WIDTH = 140.0f;
    private static final float BUTTON_HEIGHT = 28.0f;
    private static final float BUTTON_GAP = 8.0f;

    private static final float PAUSED_SCALE = 4.0F;
    private static final int PAUSED_SHADOW = 4;

    // same dark overlay the chat screen puts over the world view
    private static final float OVERLAY_ALPHA = 0.55F;

    private static final int SAVED_MESSAGE_TICKS = 120;
    private static final int SAVED_FADE_TICKS = 30;

    // bottom of the world view to the "Currently playing" line in the bar below
    private static final int INFO_Y_OFFSET = 24;

    // between the Save button and the "Level Saved!" note
    private static final int SAVED_NOTE_GAP = 8;

    private final Column menu;
    private final Label savedText;
    private final Label worldInfo;

    private long saveMessageTicksRemaining = 0;

    // font is ignored, the game's own is used
    public PauseScreen(RubyDung game, Font font) {
        this(game);
    }

    public PauseScreen(RubyDung game) {
        super(game);

        onEscape(this.game::closeScreen);

        this.menu = add(new Column(BUTTON_GAP), Anchor.TOP_LEFT, 0, 0);

        this.menu.add(new Button("Resume", BUTTON_WIDTH, BUTTON_HEIGHT, true)
                .onClick(this.game::closeScreen));
        this.menu.add(new Button("Options", BUTTON_WIDTH, BUTTON_HEIGHT, true)
                .onClick(() -> this.game.openScreen(new OptionsScreen(this.game))));
        // a world downloaded from a server is not ours to save
        Button save = new Button("Save", BUTTON_WIDTH, BUTTON_HEIGHT, true);
        save.onClick(() -> {
            this.game.saveLevel();
            this.saveMessageTicksRemaining = SAVED_MESSAGE_TICKS;
        });
        save.setVisibleWhen(() -> !Multiplayer.isClient());
        this.menu.add(save);

        // Open to LAN: lets other players on the network join the world being played.
        // (not on a server of somebody else's, where there is no world of ours to open)
        Button openToLan = new Button("Open to LAN", BUTTON_WIDTH, BUTTON_HEIGHT, true);
        openToLan.onClick(() -> {
            String error = Multiplayer.hostLan(this.game, Protocol.DEFAULT_PORT);
            this.game.getChatLog().notice(error != null ? error : "The world is open to LAN.");
            this.game.closeScreen();
        });
        openToLan.setVisibleWhen(() -> !Multiplayer.isClient());
        this.menu.add(openToLan);

        // Quit (to the title screen) lives in the bottom-right corner of the frame.
        addCornerButton("Quit", this.game::quitToTitle);

        // Current World: "name" (or "Connected to ..." when joined), centred in the bar under the world view.
        this.worldInfo = add(new Label(this::worldInfoText).setColor(0xFFFFFF), Anchor.TOP_LEFT, 0, 0);
        this.worldInfo.setVisibleWhen(this::isFrameOpen);

        // "Level Saved!" goes right under the Save button, and fades out.
        this.savedText = add(new Label("Level Saved!")
                .setColor(0xFFFF00)
                .setAlpha(() -> this.saveMessageTicksRemaining < SAVED_FADE_TICKS
                        ? this.saveMessageTicksRemaining / (float) SAVED_FADE_TICKS
                        : 1.0F), Anchor.TOP_LEFT, 0, 0);
        this.savedText.setVisibleWhen(() -> this.saveMessageTicksRemaining > 0);
    }

    @Override
    public boolean showsSlots() {
        return false;
    }

    @Override
    public String getPanelTitle() {
        return "Game Menu";
    }

    @Override
    public int getFrameTint() {
        return 0xA8C0FF;
    }

    @Override
    protected void placeWidgets() {
        // Buttons centred in the panel under the portrait, so they slide in with it.
        placeCenteredInSection(this.menu, 0);

        placeInBar(this.worldInfo, INFO_Y_OFFSET);
        placeUnderSaveButton();
    }

    // centres the "Level Saved!" note under the Save button by hand (not part of the column) so showing it doesn't move the buttons
    private void placeUnderSaveButton() {
        GameFrame.SectionLayout section = this.layout.sections.get(0);

        this.menu.measure(this.font);
        this.savedText.measure(this.font);

        float menuTop = section.y + (section.height - this.menu.getHeight()) / 2.0F;
        this.savedText.setAnchor(Anchor.TOP_LEFT,
                section.x + (section.width - this.savedText.getWidth()) / 2.0F,
                menuTop + this.menu.getHeight() + SAVED_NOTE_GAP);
    }

    // centres a label under the world view, yOffset below its bottom edge
    private void placeInBar(Label label, int yOffset) {
        label.measure(this.font);
        label.setAnchor(Anchor.TOP_LEFT,
                this.layout.viewX + (this.layout.viewWidth - label.getWidth()) / 2.0F,
                this.layout.viewY + this.layout.viewHeight + yOffset);
    }

    private String worldInfoText() {
        // joined to a server: "Connected to Name" (or "Connected to: ip:port") takes the place of the world line
        String connected = Multiplayer.connectedTo();
        if (connected != null) {
            return connected;
        }

        Level level = this.game.getLevel();
        if (level == null) {
            return "";
        }

        return String.format("Current World: \"%s\"",
                level.getWorldName());
    }

    /** dark overlay with the big "Paused" on top, drawn after the widgets (like the chat screen) so the overlay doesn't dim it */
    @Override
    protected void renderForeground(int mouseX, int mouseY) {
        if (!isFrameOpen()) {
            return;
        }

        Font font = this.game.getFont();

        Gui2D.begin(this.game.getWidth(), this.game.getHeight());

        Gui2D.fillRect(this.layout.viewX, this.layout.viewY,
                this.layout.viewWidth, this.layout.viewHeight,
                0.0F, 0.0F, 0.0F, OVERLAY_ALPHA);

        // with other players the world keeps running, so it is not "Paused" (the menu still darkens it)
        if (!Multiplayer.isActive()) {
            String paused = "Paused";
            float textWidth = font.getWidth(paused) * PAUSED_SCALE;
            font.drawStringScaled(paused,
                    (int) (this.layout.viewX + (this.layout.viewWidth - textWidth) / 2.0F),
                    (int) (this.layout.viewY + this.layout.viewHeight / 2.0F - (int) (6 * PAUSED_SCALE)),
                    0xFFFFFF, PAUSED_SCALE);
        }

        Gui2D.end();
    }

    @Override
    public void render(int mouseX, int mouseY) {
        super.render(mouseX, mouseY);

        if (this.layout != null && this.saveMessageTicksRemaining > 0) {
            this.saveMessageTicksRemaining--;
        }
    }
}