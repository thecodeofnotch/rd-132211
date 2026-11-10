package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.ChatLog;
import com.nikohalfkino.rubydung.interfaces.ChatRender;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.screen.ChatScreen;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Widget;
import com.nikohalfkino.rubydung.render.Font;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * recent chat in the bottom-left corner so you can follow it without opening the chat screen
 *
 * a line shows for HOLD_SECONDS after it arrives and then disappears. nothing is drawn while the
 * chat screen is open (it shows the conversation itself), and what arrives in the meantime
 * (your own messages, command output) doesn't pop up again when it closes
 */
public class ChatHud extends Widget {

    private static final float SCALE = 2.0F;
    private static final int CHAR_HEIGHT = (int) (8 * SCALE);
    private static final int LINE_HEIGHT = CHAR_HEIGHT + 3;
    private static final int PAD = 4;
    private static final int MARGIN = 10;

    // gap between the top of SelectedTileHud (which owns the very bottom-left corner) and the bottom of the chat
    private static final int GAP_ABOVE_SELECTED_TILE = 6;

    private static final int MAX_VISUAL_LINES = 8;
    private static final float MAX_WIDTH_FRACTION = 0.45F;
    private static final int MIN_WIDTH = 240;

    private static final float HOLD_SECONDS = 10.0F;
    private static final long HOLD_NANOS = (long) (HOLD_SECONDS * 1_000_000_000L);

    private static final float BACKDROP_ALPHA = 0.45F;

    // arrival time marker for lines that must never show (already there at start, arrived while hidden)
    private static final long EXPIRED = Long.MIN_VALUE;

    private final RubyDung game;

    // when each line was first seen; identity, so two identical messages are two entries
    private final Map<ChatLog.Line, Long> arrived = new IdentityHashMap<>();
    private boolean primed = false;

    private static final class VisualLine {
        final ChatLog.Line source;
        /** whether the mark ("*** " or "<nick> ") is drawn in front of this piece */
        final boolean marked;
        /** the text this row is cut from: source.text with a link to a picture shown as "[Image]" */
        final ChatRender.Shown shown;
        /** the piece of shown.text on this row */
        final int start;
        final int end;

        VisualLine(ChatLog.Line source, ChatRender.Shown shown, boolean marked, int start, int end) {
            this.source = source;
            this.shown = shown;
            this.marked = marked;
            this.start = start;
            this.end = end;
        }

        String text() {
            return this.shown.text.substring(this.start, this.end);
        }
    }

    // rebuilt every frame by measure(), drawn by draw(), oldest at the top
    private final List<VisualLine> lines = new ArrayList<>();

    public ChatHud(RubyDung game) {
        this.game = game;
        setAnchor(Anchor.BOTTOM_LEFT, MARGIN, -aboveSelectedTile());
    }

    /**
     * how far up from the window's bottom edge the chat has to sit. SelectedTileHud is 78px tall, or 102px when the
     * selected tile has a "Type:" line (trees, doors, wood...), so this is worked out from the real height every frame
     * and not a fixed number, which is what used to make the two overlap
     */
    private float aboveSelectedTile() {
        float selectedHeight = SelectedTileHud.heightFor(this.game.getSelectedTile());
        return SelectedTileHud.EDGE_MARGIN + selectedHeight + GAP_ABOVE_SELECTED_TILE;
    }

    private boolean isSuppressed() {
        return !this.game.hasWorld()
                || this.game.getCurrentScreen() instanceof ChatScreen;
    }

    @Override
    public void measure(Font font) {
        this.lines.clear();
        this.width = 0.0F;
        this.height = 0.0F;

        // the offset is applied after measure() in the arrange pass, so this is what places the chat this frame
        setOffset(MARGIN, -aboveSelectedTile());

        List<ChatLog.Line> all = this.game.getChatLog().getLines();
        boolean suppressed = isSuppressed();

        track(all, suppressed);

        if (suppressed) {
            return;
        }

        int maxWidth = Math.max(MIN_WIDTH, (int) (getRootWidth() * MAX_WIDTH_FRACTION));
        int textWidth = maxWidth - PAD * 2;
        long now = System.nanoTime();

        // newest first so the cap drops the oldest, then flipped into reading order
        List<VisualLine> newestFirst = new ArrayList<>();

        for (int i = all.size() - 1; i >= 0 && newestFirst.size() < MAX_VISUAL_LINES; i--) {
            ChatLog.Line line = all.get(i);
            Long at = this.arrived.get(line);

            if (at == null || at == EXPIRED || now - at > HOLD_NANOS) {
                // lines arrive in order, so everything older than an expired one is expired too
                break;
            }

            int prefixWidth = textWidth(font, ChatScreen.markOf(line));
            int restWidth = ChatScreen.marksEveryLine(line) ? textWidth - prefixWidth : textWidth;
            // the pictures themselves are only shown in the chat screen, here a link to one reads "[Image]"
            ChatRender.Shown shown = ChatRender.show(line, true);
            List<int[]> pieces = ChatRender.wrap(font, shown.text, textWidth - prefixWidth, restWidth, SCALE);

            for (int p = pieces.size() - 1; p >= 0 && newestFirst.size() < MAX_VISUAL_LINES; p--) {
                int[] piece = pieces.get(p);
                newestFirst.add(new VisualLine(line, shown, p == 0 || ChatScreen.marksEveryLine(line), piece[0], piece[1]));
            }
        }

        if (newestFirst.isEmpty()) {
            return;
        }

        Collections.reverse(newestFirst);
        this.lines.addAll(newestFirst);

        float widest = 0.0F;

        for (VisualLine v : this.lines) {
            int w = textWidth(font, v.text());

            if (v.marked) {
                w += textWidth(font, ChatScreen.markOf(v.source));
            }

            widest = Math.max(widest, w);
        }

        this.width = widest + PAD * 2;
        this.height = this.lines.size() * LINE_HEIGHT;
    }

    // stamps lines the first time they're seen and forgets lines that have left the log
    private void track(List<ChatLog.Line> all, boolean suppressed) {
        long now = System.nanoTime();

        for (ChatLog.Line line : all) {
            if (!this.arrived.containsKey(line)) {
                // first pass: whatever is already in the log is history, not news
                this.arrived.put(line, this.primed && !suppressed ? now : EXPIRED);
            }
        }

        this.primed = true;

        if (this.arrived.size() != all.size()) {
            Map<ChatLog.Line, Boolean> live = new IdentityHashMap<>();

            for (ChatLog.Line line : all) {
                live.put(line, Boolean.TRUE);
            }

            this.arrived.keySet().removeIf(line -> !live.containsKey(line));
        }
    }

    @Override
    protected void draw(Font font, int mouseX, int mouseY) {
        if (this.lines.isEmpty()) {
            return;
        }

        Gui2D.begin((int) getRootWidth(), (int) getRootHeight());

        int x = (int) this.x;
        int y = (int) this.y;

        Gui2D.fillRect(x, y, this.width, this.height, 0.0F, 0.0F, 0.0F, BACKDROP_ALPHA);

        for (VisualLine v : this.lines) {
            int lineX = x + PAD;
            int lineY = y + (LINE_HEIGHT - CHAR_HEIGHT) / 2;

            if (v.marked) {
                String mark = ChatScreen.markOf(v.source);

                font.drawStringScaled(mark, lineX, lineY, ChatScreen.markColorOf(v.source), SCALE);
                lineX += textWidth(font, mark);
            }

            // links and copyable parts are coloured here too, but can only be clicked in the chat screen
            ChatRender.drawPiece(font, v.shown, v.start, v.end, lineX, lineY,
                    ChatScreen.textColorOf(v.source), SCALE, null, null);

            y += LINE_HEIGHT;
        }

        Gui2D.end();
    }

    private static int textWidth(Font font, String text) {
        return (int) (font.getWidth(text) * SCALE);
    }
}
