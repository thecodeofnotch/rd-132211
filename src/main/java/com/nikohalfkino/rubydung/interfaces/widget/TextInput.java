package com.nikohalfkino.rubydung.interfaces.widget;

import com.nikohalfkino.rubydung.interfaces.Bevel;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyMap;

import java.util.function.Supplier;

/**
 * single-line text box: translucent dark background, a fixed prefix ("Name: ") and the typed text, blinking cursor and mouse selection while focused. wraps TextField, which does the editing
 *
 * focus is handled by the Screen: a click focuses, Tab moves between boxes, the focused box gets the keyboard first
 */
public class TextInput extends Widget {

    private static final float PADDING = 8.0f;

    private final TextField field;
    private String prefix;

    private boolean commitOnKeys = false;
    private Runnable onCommit;

    private Font lastFont;

    // greyed suggestion shown while the box is empty and not focused (clicking the box hides it)
    private Supplier<String> placeholder;

    public TextInput(String prefix, float width, float height, int maxLength) {
        this.prefix = prefix;
        this.field = new TextField(maxLength);
        setSize(width, height);
    }

    public String getText() {
        return this.field.getText();
    }

    public void setText(String text) {
        this.field.setText(text);
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    /** on: typed characters show as asterisks (passwords) */
    public TextInput setMasked(boolean masked) {
        this.field.setMasked(masked);
        return this;
    }

    /** greyed text after the prefix while the box is empty and not focused, re-read every frame (null / "" = nothing) */
    public TextInput setPlaceholder(Supplier<String> placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    public TextField getField() {
        return this.field;
    }

    /** on: Enter and Escape finish editing (drop focus, run onCommit). off (default): they go to the screen, e.g. Enter = "Create" */
    public TextInput setCommitOnKeys(boolean commitOnKeys) {
        this.commitOnKeys = commitOnKeys;
        return this;
    }

    /** runs whenever the box loses focus, after a click elsewhere or Enter/Escape if commit-on-keys is on */
    public TextInput onCommit(Runnable onCommit) {
        this.onCommit = onCommit;
        return this;
    }

    @Override
    public void measure(Font font) {
        this.lastFont = font;
        super.measure(font);
    }

    private int textX() {
        return (int) (this.x + PADDING);
    }

    private int textY() {
        return (int) (this.y + PADDING);
    }

    @Override
    protected boolean isInteractive() {
        return true;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public void onMouseClicked(int mouseX, int mouseY) {
        if (this.lastFont != null) {
            this.field.mouseClicked(mouseX, mouseY, textX(), textY(), this.lastFont, this.prefix);
        }
    }

    @Override
    protected void onBlur() {
        if (this.onCommit != null) {
            this.onCommit.run();
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        boolean enter = key == KeyMap.ENTER || key == KeyMap.NUMPAD_ENTER;

        if (key == KeyMap.ESC || enter) {
            if (this.commitOnKeys) {
                requestBlur();
                return true;
            }
            return false;
        }
        if (key == KeyMap.TAB) {
            return false;
        }

        this.field.keyTyped(key, character);
        return true;
    }

    @Override
    protected void draw(Font font, int mouseX, int mouseY) {
        Gui2D.begin((int) getRootWidth(), (int) getRootHeight());
        Gui2D.fillRect(this.x, this.y, this.width, this.height, 0.0f, 0.0f, 0.0f, 0.45f);
        Bevel.sunkenFrame(this.x, this.y, this.width, this.height, 0xFFFFFF);
        Gui2D.end();

        int textX = textX();
        int textY = textY();

        if (isFocused()) {
            this.field.updateMouse(mouseX, mouseY, textX, textY, font, this.prefix);
            this.field.render(font, textX, textY, UiStyle.TEXT, this.prefix);
        } else {
            String hint = this.placeholder == null ? null : this.placeholder.get();
            if (hint != null && !hint.isEmpty() && this.field.getText().isEmpty()) {
                font.drawString(this.prefix, textX, textY, UiStyle.TEXT);
                font.drawString(hint, textX + (int) font.width(this.prefix), textY, UiStyle.TEXT_DISABLED);
            } else {
                font.drawString(this.prefix + this.field.getDisplayText(), textX, textY, UiStyle.TEXT);
            }
        }
    }
}