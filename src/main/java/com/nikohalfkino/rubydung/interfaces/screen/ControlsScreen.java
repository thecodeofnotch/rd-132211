package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Bevel;
import com.nikohalfkino.rubydung.interfaces.GameFrame;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Column;
import com.nikohalfkino.rubydung.interfaces.widget.Group;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.Panel;
import com.nikohalfkino.rubydung.interfaces.widget.Row;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;

import java.util.ArrayList;
import java.util.List;

/**
 * key bindings laid out like Options: a hint line, a page of binding slots (same sunken slots as the world list), page buttons and Reset All / Done
 */
public class ControlsScreen extends MenuScreen {

    private static final float PANEL_WIDTH   = UiStyle.PANEL_WIDTH;
    private static final float PANEL_PADDING = 20.0f;
    private static final float PANEL_TOP     = 90.0f;

    private static final float ITEM_GAP    = 8.0f;
    private static final float SECTION_GAP = 16.0f;

    private static final float ROW_HEIGHT = 30.0f;
    private static final float ROW_SPACING = 6.0f;
    private static final float REBIND_BUTTON_WIDTH = 120.0f;
    private static final float REBIND_BUTTON_HEIGHT = 26.0f;
    private static final float RESET_ZONE_WIDTH = 55.0f;
    private static final float ROW_INNER_GAP = 6.0f;

    // slot bevel greys, same as the world list's slots
    private static final int SLOT_SHADOW = 12;
    private static final int SLOT_LIGHT = 112;

    private static final int ROWS_PER_PAGE = 7;

    private static final float PAGE_BUTTON_WIDTH = 90.0f;
    private static final float PAGE_BUTTON_HEIGHT = 26.0f;

    private final List<KeyBinding> bindings = new ArrayList<>(KeyBinding.all());

    private KeyBinding listeningFor = null;

    private KeyBinding.Device pendingModifierDevice = null;
    private int pendingModifierCode;

    private int currentPage = 0;
    private final int totalPages;

    // true after the first click on Reset All, which then asks for a second
    private boolean confirmResetAll = false;
    private final Button resetAllButton;

    public ControlsScreen(RubyDung game) {
        super(game);

        setSwallowKeys(true);
        onEscape(this::goBack);

        this.totalPages = Math.max(1, (this.bindings.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);

        addTitle("Controls");

        Panel panel = add(new Panel(ITEM_GAP), Anchor.TOP_CENTER, 0, PANEL_TOP);
        panel.setPadding(PANEL_PADDING);
        panel.setShadowAlpha(1.0f);
        panel.setCrossAlign(0.0f);

        // Hint line, centred over the panel's content width.
        Label hint = panel.add(new Label(() -> this.listeningFor != null
                        ? (this.listeningFor.isMouseBindable() ? "Press any key or mouse button" : "Press a key (this one can't use the mouse)")
                        : "Click a key to rebind it")
                        .setColor(UiStyle.TEXT_DIM)
                        .setMaxWidth((int) PANEL_WIDTH),
                ITEM_GAP);
        hint.setSize(PANEL_WIDTH, UiStyle.LINE_HEIGHT);

        // One page of binding rows. The column is always as tall as a full page, so the page
        // buttons below don't jump around on a short last page.
        Column rows = panel.add(new Column(ROW_SPACING), SECTION_GAP);
        rows.setSize(PANEL_WIDTH, ROWS_PER_PAGE * ROW_HEIGHT + (ROWS_PER_PAGE - 1) * ROW_SPACING);
        for (int i = 0; i < this.bindings.size(); i++) {
            final int page = i / ROWS_PER_PAGE;
            BindingRow row = rows.add(new BindingRow(this.bindings.get(i)));
            row.setVisibleWhen(() -> this.currentPage == page);
        }

        // Page buttons: "< Prev" on the left edge, "Next >" on the right edge, the page number between.
        Group pager = panel.add(new Group(PANEL_WIDTH, PAGE_BUTTON_HEIGHT), SECTION_GAP);
        pager.add(new Button("< Prev", PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT, true).onClick(this::previousPage),
                Anchor.TOP_LEFT, 0, 0);
        pager.add(new Label(() -> "Page " + (this.currentPage + 1) + " / " + this.totalPages),
                Anchor.CENTER, 0, 0);
        pager.add(new Button("Next >", PAGE_BUTTON_WIDTH, PAGE_BUTTON_HEIGHT, true).onClick(this::nextPage),
                Anchor.TOP_RIGHT, 0, 0);

        // Reset All | Done
        float buttonWidth = (PANEL_WIDTH - UiStyle.BUTTON_GAP) / 2.0f;
        Row bottom = panel.add(new Row(UiStyle.BUTTON_GAP));
        this.resetAllButton = bottom.add(new Button(() -> this.confirmResetAll ? "Are you sure?" : "Reset All",
                buttonWidth, UiStyle.BUTTON_HEIGHT, true)
                .onClick(this::resetAll));
        bottom.add(new Button("Done", buttonWidth, UiStyle.BUTTON_HEIGHT, true).onClick(this::goBack));
    }

    private void previousPage() {
        this.confirmResetAll = false;
        this.currentPage = this.currentPage > 0 ? this.currentPage - 1 : this.totalPages - 1;
    }

    private void nextPage() {
        this.confirmResetAll = false;
        this.currentPage = this.currentPage < this.totalPages - 1 ? this.currentPage + 1 : 0;
    }

    private void resetAll() {
        if (!this.confirmResetAll) {
            this.confirmResetAll = true;
            return;
        }

        for (KeyBinding binding : this.bindings) {
            binding.resetToDefault();
        }
        KeyBinding.save();
        this.confirmResetAll = false;
    }

    private void goBack() {
        this.game.openScreen(new OptionsScreen(this.game));
    }

    private String rebindLabel(KeyBinding binding) {
        if (this.listeningFor == binding) {
            return this.pendingModifierDevice != null
                    ? KeyBinding.describeBinding(this.pendingModifierDevice, this.pendingModifierCode) + " + ..."
                    : "> Press <";
        }
        return binding.getKeyName();
    }

    // name, a button with the current key (click to rebind) and a "Reset" link
    private final class BindingRow extends Group {

        private final KeyBinding binding;
        private final Button rebindButton;
        private final Label resetLabel;

        BindingRow(KeyBinding binding) {
            super(PANEL_WIDTH, ROW_HEIGHT);
            this.binding = binding;

            Label name = new Label(binding.getDisplayName());
            add(name, Anchor.CENTER_LEFT, 10.0f, 0.0f);

            this.resetLabel = new Label("Reset")
                    .setColor(UiStyle.TEXT_DANGER)
                    .setHoverColor(UiStyle.TEXT_HOVER)
                    .onClick(() -> {
                        binding.resetToDefault();
                        KeyBinding.save();
                    });
            this.resetLabel.setSize(RESET_ZONE_WIDTH, ROW_HEIGHT);
            add(this.resetLabel, Anchor.CENTER_RIGHT, -ROW_INNER_GAP, 0.0f);

            this.rebindButton = new Button(() -> rebindLabel(binding), REBIND_BUTTON_WIDTH, REBIND_BUTTON_HEIGHT, true)
                    .onClick(() -> {
                        confirmResetAll = false;
                        listeningFor = binding;
                        pendingModifierDevice = null;
                    });
            add(this.rebindButton, Anchor.CENTER_RIGHT, -(RESET_ZONE_WIDTH + ROW_INNER_GAP * 2), 0.0f);
        }

        // sunken black slot like the world list's, hovering lifts it to dark grey
        @Override
        protected void drawSelf(Font font, int mouseX, int mouseY) {
            boolean resetHovered = this.resetLabel.isHovered(mouseX, mouseY);
            boolean rebindHovered = this.rebindButton.isHovered(mouseX, mouseY);
            boolean rowHovered = !resetHovered && !rebindHovered && contains(mouseX, mouseY);

            float shade = (rowHovered || resetHovered) ? 0.10f : 0.0f;
            Gui2D.fillRect(this.x, this.y, this.width, this.height, shade, shade, shade, 1.0f);
            Bevel.ring(this.x, this.y, this.width, this.height, GameFrame.UI_SCALE, false,
                    SLOT_SHADOW, SLOT_LIGHT, 1.0f, 1.0f, 1.0f);

            this.rebindButton.setSelected(listeningFor == this.binding);
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (this.listeningFor != null) {
            if (!this.listeningFor.isMouseBindable()) {
                // inventory, chat and the console read keys only: keep listening for a key instead of saving something that does nothing
                return;
            }
            if (this.pendingModifierDevice != null) {
                this.listeningFor.setBinding(this.pendingModifierDevice, this.pendingModifierCode,
                        KeyBinding.Device.MOUSE, button);
            } else {
                this.listeningFor.setBinding(KeyBinding.Device.MOUSE, button);
            }
            this.pendingModifierDevice = null;
            this.listeningFor = null;
            KeyBinding.save();
            return;
        }

        // Any click that isn't on Reset All cancels its "Are you sure?".
        if (!this.resetAllButton.contains(mouseX, mouseY)) {
            this.confirmResetAll = false;
        }

        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (this.listeningFor != null) {
            if (key == KeyMap.ESC) {
                this.pendingModifierDevice = null;
                this.listeningFor = null;
                return true;
            }

            if (isModifierKey(key) && this.pendingModifierDevice == null) {
                this.pendingModifierDevice = KeyBinding.Device.KEYBOARD;
                this.pendingModifierCode = key;
                return true;
            }

            if (this.pendingModifierDevice != null) {
                this.listeningFor.setBinding(this.pendingModifierDevice, this.pendingModifierCode,
                        KeyBinding.Device.KEYBOARD, key);
            } else {
                this.listeningFor.setKey(key);
            }

            this.pendingModifierDevice = null;
            this.listeningFor = null;
            KeyBinding.save();
            return true;
        }

        return super.keyPressed(key, character);
    }

    private static boolean isModifierKey(int key) {
        return key == KeyMap.LCONTROL || key == KeyMap.RCONTROL
                || key == KeyMap.LSHIFT || key == KeyMap.RSHIFT
                || key == KeyMap.LMENU || key == KeyMap.RMENU
                || key == KeyMap.LWIN || key == KeyMap.RWIN;
    }
}