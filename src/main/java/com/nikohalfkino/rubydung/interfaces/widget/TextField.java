package com.nikohalfkino.rubydung.interfaces.widget;

import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.Sys;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

public class TextField {

    /** colours the text as it's drawn, e.g. for syntax highlighting */
    public interface Highlighter {

        /** one RGB colour per character of text, -1 = the normal colour (may be shorter than text, the rest is normal) */
        int[] colors(String text, int cursor);
    }

    private Highlighter highlighter;

    private final StringBuilder text = new StringBuilder();
    private int maxLength = Integer.MAX_VALUE;

    private int cursorPosition = 0;
    private int selectionAnchor = 0;
    private int scrollPosition = 0;
    private boolean isPressed = false;
    private boolean masked = false;

    public TextField() {
    }

    public TextField(int maxLength) {
        this.maxLength = maxLength;
    }

    public void mouseClicked(int mouseX, int mouseY, int fieldX, int fieldY, Font font, String prefix) {
        mouseClicked(mouseX, mouseY, fieldX, fieldY, Integer.MAX_VALUE, font, prefix);
    }

    public void mouseClicked(int mouseX, int mouseY, int fieldX, int fieldY,
                             int availableWidth, Font font, String prefix) {
        ensureCursorVisible(font, availableWidth, prefix);

        int charIndex = getCharIndexAt(mouseX, fieldX, availableWidth, font, prefix);
        this.cursorPosition = charIndex;
        this.selectionAnchor = charIndex;
        this.isPressed = true;
    }

    public void updateMouse(int mouseX, int mouseY, int fieldX, int fieldY, Font font, String prefix) {
        updateMouse(mouseX, mouseY, fieldX, fieldY, Integer.MAX_VALUE, font, prefix);
    }

    public void updateMouse(int mouseX, int mouseY, int fieldX, int fieldY,
                            int availableWidth, Font font, String prefix) {
        if (!this.isPressed) {
            return;
        }

        if (Mouse.isButtonDown(0)) {
            this.cursorPosition = getCharIndexAt(mouseX, fieldX, availableWidth, font, prefix);
            ensureCursorVisible(font, availableWidth, prefix);
        } else {
            this.isPressed = false;
        }
    }

    private int getCharIndexAt(int mouseX, int fieldX, int availableWidth, Font font, String prefix) {
        int prefixWidth = font.width(prefix);
        int textX = fieldX + prefixWidth;

        if (mouseX <= textX) {
            return this.scrollPosition;
        }

        int visibleEnd = getVisibleEnd(font, availableWidth, prefix);
        int currentWidth = textX;

        for (int i = this.scrollPosition; i < visibleEnd; i++) {
            String sub = shown().substring(this.scrollPosition, i + 1);
            int nextWidth = textX + font.width(sub);

            if (mouseX < (currentWidth + nextWidth) / 2) {
                return i;
            }

            currentWidth = nextWidth;
        }

        return visibleEnd;
    }

    private void ensureCursorVisible(Font font, int availableWidth, String prefix) {
        int prefixWidth = font.width(prefix);
        int textWidth = Math.max(0, availableWidth - prefixWidth);

        this.scrollPosition = Math.max(0, Math.min(this.scrollPosition, this.text.length()));

        while (this.scrollPosition < this.cursorPosition) {
            String visible = shown().substring(this.scrollPosition, this.cursorPosition);

            if (font.width(visible) <= textWidth) {
                break;
            }

            this.scrollPosition++;
        }

        while (this.scrollPosition > this.cursorPosition) {
            this.scrollPosition--;
        }
    }

    private int getVisibleEnd(Font font, int availableWidth, String prefix) {
        int textWidth = Math.max(0, availableWidth - font.width(prefix));
        int end = this.scrollPosition;

        while (end < this.text.length()) {
            String visible = shown().substring(this.scrollPosition, end + 1);

            if (font.width(visible) > textWidth) {
                break;
            }

            end++;
        }

        return end;
    }

    /** plays the keypress sound when the text changed (cursor moves stay silent) */
    public boolean keyTyped(int key, char character) {
        String before = this.text.toString();
        boolean handled = handleKey(key, character);
        if (!before.contentEquals(this.text)) {
            SoundEffects.playKey("key_press", key);
        }
        return handled;
    }

    private boolean handleKey(int key, char character) {
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
                || Keyboard.isKeyDown(Keyboard.KEY_LMETA)
                || Keyboard.isKeyDown(Keyboard.KEY_RMETA);

        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);

        if (ctrl) {
            if (key == Keyboard.KEY_A || character == 'a' || character == 'A') {
                selectAll();
                return true;
            }

            if (key == Keyboard.KEY_C) {
                copySelection();
                return true;
            }

            if (key == Keyboard.KEY_X) {
                if (!this.masked) {
                    copySelection();
                    deleteSelection();
                }
                return true;
            }

            if (key == Keyboard.KEY_V) {
                paste();
                return true;
            }
        }

        if (key == Keyboard.KEY_LEFT) {
            if (!shift && hasSelection()) {
                moveTo(Math.min(this.cursorPosition, this.selectionAnchor), false);
            } else {
                moveTo(this.cursorPosition - 1, shift);
            }
            return true;
        }

        if (key == Keyboard.KEY_RIGHT) {
            if (!shift && hasSelection()) {
                moveTo(Math.max(this.cursorPosition, this.selectionAnchor), false);
            } else {
                moveTo(this.cursorPosition + 1, shift);
            }
            return true;
        }

        if (key == Keyboard.KEY_HOME) {
            moveTo(0, shift);
            return true;
        }

        if (key == Keyboard.KEY_END) {
            moveTo(this.text.length(), shift);
            return true;
        }

        if (key == KeyMap.BACKSPACE) {
            if (hasSelection()) {
                deleteSelection();
            } else if (this.cursorPosition > 0) {
                this.text.deleteCharAt(this.cursorPosition - 1);
                this.cursorPosition--;
                this.selectionAnchor = this.cursorPosition;
            }
            return true;
        }

        if (key == Keyboard.KEY_DELETE) {
            if (hasSelection()) {
                deleteSelection();
            } else if (this.cursorPosition < this.text.length()) {
                this.text.deleteCharAt(this.cursorPosition);
            }
            return true;
        }

        if (character >= 32 && character != 127) {
            if (hasSelection()) {
                deleteSelection();
            }

            if (this.text.length() < this.maxLength) {
                this.text.insert(this.cursorPosition, character);
                this.cursorPosition++;
                this.selectionAnchor = this.cursorPosition;
            }
            return true;
        }

        return false;
    }

    private void moveTo(int position, boolean extendSelection) {
        this.cursorPosition = Math.max(0, Math.min(this.text.length(), position));

        if (!extendSelection) {
            this.selectionAnchor = this.cursorPosition;
        }
    }

    private void copySelection() {
        if (this.masked || !hasSelection()) {
            return;
        }

        int start = Math.min(this.cursorPosition, this.selectionAnchor);
        int end = Math.max(this.cursorPosition, this.selectionAnchor);

        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(this.text.substring(start, end)), null);
        } catch (Throwable ignored) {
            // Clipboard unavailable.
        }
    }

    private void paste() {
        String clip = Sys.getClipboard();

        if (clip == null) {
            return;
        }

        StringBuilder clean = new StringBuilder();

        for (int i = 0; i < clip.length(); i++) {
            char c = clip.charAt(i);

            if (c >= 32 && c != 127) {
                clean.append(c);
            }
        }

        if (clean.length() == 0) {
            return;
        }

        if (hasSelection()) {
            deleteSelection();
        }

        int room = this.maxLength - this.text.length();

        if (room <= 0) {
            return;
        }

        if (clean.length() > room) {
            clean.setLength(room);
        }

        this.text.insert(this.cursorPosition, clean);
        this.cursorPosition += clean.length();
        this.selectionAnchor = this.cursorPosition;
    }

    /** on: the text is drawn as asterisks and can't be copied or cut (a password) */
    public TextField setMasked(boolean masked) {
        this.masked = masked;
        return this;
    }

    public boolean isMasked() {
        return this.masked;
    }

    /** what is drawn: the text, or one '*' per character when masked */
    public String getDisplayText() {
        return shown();
    }

    private String shown() {
        if (!this.masked) {
            return this.text.toString();
        }
        char[] stars = new char[this.text.length()];
        java.util.Arrays.fill(stars, '*');
        return new String(stars);
    }

    /** null = plain text */
    public TextField setHighlighter(Highlighter highlighter) {
        this.highlighter = highlighter;
        return this;
    }

    public boolean hasSelection() {
        return this.cursorPosition != this.selectionAnchor;
    }

    public void selectAll() {
        this.selectionAnchor = 0;
        this.cursorPosition = this.text.length();
    }

    public void deleteSelection() {
        int start = Math.min(this.cursorPosition, this.selectionAnchor);
        int end = Math.max(this.cursorPosition, this.selectionAnchor);

        this.text.delete(start, end);
        this.cursorPosition = start;
        this.selectionAnchor = start;
    }

    public int getCursorPosition() {
        return this.cursorPosition;
    }

    public void setCursorPosition(int position) {
        moveTo(position, false);
    }

    public String getText() {
        return this.text.toString();
    }

    public void setText(String value) {
        this.text.setLength(0);

        if (value.length() > this.maxLength) {
            value = value.substring(0, this.maxLength);
        }

        this.text.append(value);
        this.cursorPosition = this.text.length();
        this.selectionAnchor = this.cursorPosition;
        this.scrollPosition = 0;
    }

    public void clear() {
        this.text.setLength(0);
        this.cursorPosition = 0;
        this.selectionAnchor = 0;
        this.scrollPosition = 0;
    }

    public boolean isEmpty() {
        return this.text.length() == 0;
    }

    public void render(Font font, int x, int y, int color, String prefix) {
        render(font, x, y, color, prefix, Integer.MAX_VALUE);
    }

    public void render(Font font, int x, int y, int color, String prefix, int availableWidth) {
        ensureCursorVisible(font, availableWidth, prefix);

        int visibleEnd = getVisibleEnd(font, availableWidth, prefix);
        String visibleText = shown().substring(this.scrollPosition, visibleEnd);

        int visibleSelectionStart = Math.max(
                Math.min(this.cursorPosition, this.selectionAnchor),
                this.scrollPosition
        );

        int visibleSelectionEnd = Math.min(
                Math.max(this.cursorPosition, this.selectionAnchor),
                visibleEnd
        );

        if (visibleSelectionStart < visibleSelectionEnd) {
            String beforeSelection = shown().substring(this.scrollPosition, visibleSelectionStart);
            String selected = shown().substring(visibleSelectionStart, visibleSelectionEnd);

            int selectXOffset = font.width(prefix + beforeSelection);
            int selectWidth = font.width(selected);

            Gui2D.fillRect(
                    x + selectXOffset,
                    y - 1,
                    selectWidth,
                    18,
                    0.2f, 0.4f, 0.8f, 0.6f
            );
        }

        drawVisible(font, x, y, color, prefix, visibleText);

        boolean showCursor = (System.currentTimeMillis() / 400L) % 2 == 0;

        if (showCursor && !hasSelection()) {
            int cursorX = x + font.width(
                    prefix + shown().substring(this.scrollPosition, this.cursorPosition)
            );

            font.drawString("_", cursorX, y, color);
        }
    }

    // the prefix in the normal colour, then the visible text in runs of one colour each
    private void drawVisible(Font font, int x, int y, int color, String prefix, String visibleText) {
        int[] colors = null;

        if (this.highlighter != null) {
            try {
                colors = this.highlighter.colors(this.text.toString(), this.cursorPosition);
            } catch (RuntimeException e) {
                colors = null;   // a broken highlighter must not break typing
            }
        }

        if (colors == null) {
            font.drawString(prefix + visibleText, x, y, color);
            return;
        }

        font.drawString(prefix, x, y, color);

        int runStart = 0;

        for (int i = 1; i <= visibleText.length(); i++) {
            if (i < visibleText.length()
                    && colorAt(colors, this.scrollPosition + i, color)
                    == colorAt(colors, this.scrollPosition + runStart, color)) {
                continue;
            }

            if (i > runStart) {
                font.drawString(
                        visibleText.substring(runStart, i),
                        x + font.width(prefix + visibleText.substring(0, runStart)),
                        y,
                        colorAt(colors, this.scrollPosition + runStart, color)
                );
            }

            runStart = i;
        }
    }

    private static int colorAt(int[] colors, int index, int normal) {
        return index >= 0 && index < colors.length && colors[index] >= 0 ? colors[index] : normal;
    }
}
