package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Widget;

/**
 * base for screens shown inside the GameFrame
 *
 * every frame, before layout, placeWidgets() pins widgets to the frame's rectangles so they follow the animation and window resizes
 *
 * nothing draws or clicks until the frame has provided a layout
 */
public abstract class FramePanelScreen extends Screen implements FrameScreen {

    // real pixels, width is the panel's
    private static final float CORNER_BUTTON_HEIGHT = 28.0F;

    // bottom of the world view to the middle of the bar under it (the chat input's centre line)
    private static final float BOTTOM_BAR_CENTER = 32.0F;

    protected final RubyDung game;

    private Button cornerButton;

    // set by GameRenderer every frame (animated layout)
    protected GameFrame.Layout layout;

    protected FramePanelScreen(RubyDung game) {
        super(game.getFont());
        this.game = game;
    }

    @Override
    public void setLayout(GameFrame.Layout layout) {
        this.layout = layout;
    }

    protected boolean isFrameOpen() {
        return this.layout != null && this.layout.openness >= 1.0F;
    }

    /** bottom-right button in the bar under the world view, as wide as the right panel. call once in the constructor ("Done" / "Quit") */
    protected final Button addCornerButton(String label, Runnable onClick) {
        this.cornerButton = add(new Button(label, 0.0F, CORNER_BUTTON_HEIGHT, true)
                .onClick(onClick), Anchor.TOP_LEFT, 0, 0);
        return this.cornerButton;
    }

    private void placeCornerButton() {
        if (this.cornerButton == null) {
            return;
        }

        // As wide as the panel above it, flush with its left and right edges.
        placeInBottomBar(this.cornerButton, this.layout.panelX, this.layout.panelWidth);
    }

    /** a button in the bar under the world view, the same height and centre line as the corner button. x is in real pixels */
    protected final void placeInBottomBar(Widget widget, float x, float width) {
        float y = this.layout.viewY + this.layout.viewHeight + BOTTOM_BAR_CENTER - CORNER_BUTTON_HEIGHT / 2.0F;
        place(widget, x, y, width, CORNER_BUTTON_HEIGHT);
    }

    /** called every frame while a layout exists, before the widgets are measured */
    protected abstract void placeWidgets();

    /** hides the widget if there is no such section */
    protected final void placeInSection(Widget widget, int index) {
        if (this.layout == null || index < 0 || index >= this.layout.sections.size()) {
            widget.setVisible(false);
            return;
        }

        GameFrame.SectionLayout section = this.layout.sections.get(index);
        widget.setVisible(true);
        place(widget, section.x, section.y, section.width, section.height);
    }

    /** measures the widget here so it's centred correctly on the first frame */
    protected final void placeCentered(Widget widget, float x, float y, float width, float height) {
        widget.measure(this.font);
        widget.setAnchor(Anchor.TOP_LEFT,
                x + (width - widget.getWidth()) / 2.0F,
                y + (height - widget.getHeight()) / 2.0F);
    }

    /** hides the widget if there is no such section */
    protected final void placeCenteredInSection(Widget widget, int index) {
        if (this.layout == null || index < 0 || index >= this.layout.sections.size()) {
            widget.setVisible(false);
            return;
        }

        GameFrame.SectionLayout section = this.layout.sections.get(index);
        widget.setVisible(true);
        placeCentered(widget, section.x, section.y, section.width, section.height);
    }

    protected final void place(Widget widget, float x, float y, float width, float height) {
        widget.setAnchor(Anchor.TOP_LEFT, x, y);
        widget.setSize(width, height);
    }

    @Override
    protected void layoutRoot() {
        if (this.layout != null) {
            placeCornerButton();
            placeWidgets();
        }
        this.root.layoutRoot(this.font, 0, 0, this.game.getWidth(), this.game.getHeight());
    }

    @Override
    public void render(int mouseX, int mouseY) {
        if (this.layout == null) {
            return;
        }
        super.render(mouseX, mouseY);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        if (this.layout == null) {
            return;
        }
        super.mouseClicked(mouseX, mouseY);
    }
}