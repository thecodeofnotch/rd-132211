package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.ChatLog;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.net.PlayerColors;
import com.nikohalfkino.rubydung.console.ChatText;
import com.nikohalfkino.rubydung.console.CommandCompleter;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.console.CommandSyntax;
import com.nikohalfkino.rubydung.interfaces.ChatActions;
import com.nikohalfkino.rubydung.interfaces.ChatImages;
import com.nikohalfkino.rubydung.interfaces.ChatRender;
import com.nikohalfkino.rubydung.interfaces.FrameMouthProvider;
import com.nikohalfkino.rubydung.interfaces.ChatInputSyntax;
import com.nikohalfkino.rubydung.interfaces.FramePanelScreen;
import com.nikohalfkino.rubydung.interfaces.GameFrame;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.SuggestionBox;
import com.nikohalfkino.rubydung.interfaces.widget.PanelSection;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.BevelBox;
import com.nikohalfkino.rubydung.interfaces.widget.TextField;
import com.nikohalfkino.rubydung.interfaces.widget.Tooltip;
import com.nikohalfkino.rubydung.interfaces.widget.UserList;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.nikohalfkino.rubydung.RubyDung.GAME_TITLE;

public class ChatScreen extends FramePanelScreen implements FrameMouthProvider {

    // scale of every string this screen draws, the one knob for text size
    private static final float CHAT_SCALE = 2.0F;
    private static final int CHAR_HEIGHT = (int) (8 * CHAT_SCALE);
    private static final int LINE_HEIGHT = CHAR_HEIGHT + 3;
    private static final int PAD = 6;
    private static final int TOPIC_HEIGHT = CHAR_HEIGHT + 7;
    // the row at the bottom of the conversation where "Name is typing..." shows
    private static final int TYPING_ROW_HEIGHT = LINE_HEIGHT;

    private static final int INPUT_Y_OFFSET = 24;
    private static final int MAX_INPUT = 8192;

    private static final boolean CLOSE_ON_SEND = false;

    private static final long OPEN_GRACE_NANOS = 150_000_000L;

    // between the input box's inside edge and its text
    private static final int INPUT_BOX_PADDING = 3;

    private static final int TEXT_COLOR = 0xE8E8E8;
    private static final int NOTICE_MARK_COLOR = 0x55CCFF;
    private static final int NOTICE_TEXT_COLOR = 0xAADDFF;

    private final ChatLog chat;
    private final TextField input = new TextField(MAX_INPUT);
    private final long openedAt = System.nanoTime();

    // sunken backdrop of the input line (the text field is drawn on top)
    private final BevelBox inputBox;

    // "Click to view" / "Click to copy" by the mouse, drawn last so it covers the conversation
    private final Tooltip tooltip = new Tooltip();

    // right-hand user list, fills the panel section
    private final UserList userList;

    private int scrollLines = 0;
    private int historyIndex = -1;

    // colours and completes ".commands"
    private final CommandSyntax syntax;
    private final CommandCompleter completer;

    public ChatScreen(RubyDung game) {
        super(game);
        this.chat = game.getChatLog();
        this.chat.setNick(Multiplayer.selfName());
        Keyboard.enableRepeatEvents(true);

        Console console = game.getConsole();
        this.syntax = new CommandSyntax(console::find, console::commands);
        this.input.setHighlighter(new ChatInputSyntax(this.syntax));
        this.completer = new CommandCompleter(this.input, this.syntax, ".");

        this.tooltip.setTint(getFrameTint()).setScale(CHAT_SCALE);

        this.inputBox = add(new BevelBox()
                .setTint(getFrameTint())
                .setFill(0.0F, 0.0F, 0.0F, 0.55F), Anchor.TOP_LEFT, 0, 0);
        this.inputBox.setVisibleWhen(this::isFrameOpen);

        this.userList = add(new UserList(
                () -> Multiplayer.playerNames(this.chat.getNick()),
                ChatScreen::nickColor,
                CHAT_SCALE).setSelf(this.chat::getNick), Anchor.TOP_LEFT, 0, 0);
        this.userList.setVisibleWhen(this::isFrameOpen);

        addCornerButton("Done", this.game::closeScreen);
    }

    @Override
    protected void placeWidgets() {
        place(this.inputBox, this.layout.viewX, boxY(), this.layout.viewWidth, boxHeight());
        placeInSection(this.userList, 0);
    }

    @Override
    public void onClose() {
        Multiplayer.setTyping(false);
        Keyboard.enableRepeatEvents(false);
    }

    public void scroll(int lines) {
        this.scrollLines = Math.max(0, this.scrollLines + lines);
    }

    /** 0 = idle, 1..5 = A I U E O */
    @Override
    public int getMouthFrame() {
        return this.game.getSpeaker().getViseme().ordinal();
    }

    @Override
    public boolean showsSlots() {
        return false;
    }

    @Override
    public String getPanelTitle() {
        return "Chat";
    }

    @Override
    public int getFrameTint() {
        return 0xA8FFC0;
    }

    /** one plain panel under the portrait that fills down to the bottom (the user list) */
    @Override
    public List<PanelSection> getSections() {
        return Collections.singletonList(PanelSection.fill(getPanelTitle()));
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (key == KeyBinding.CHAT.getKey()
                && System.nanoTime() - this.openedAt < OPEN_GRACE_NANOS) {
            return true;
        }

        if (key == KeyMap.ESC) {
            this.game.closeScreen();
            return true;
        }

        if (key == KeyMap.ENTER || key == KeyMap.NUMPAD_ENTER) {
            submit();
            return true;
        }

        if (key == KeyMap.TAB) {
            boolean back = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                    || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            if (ChatInputSyntax.isCommand(this.input.getText())) {
                this.completer.tab(back ? -1 : 1);
            }
            return true;
        }

        if (key == KeyMap.UP) {
            navigateHistory(1);
            return true;
        }

        if (key == KeyMap.DOWN) {
            navigateHistory(-1);
            return true;
        }

        if (key == Keyboard.KEY_PRIOR) {
            scroll(Math.max(1, visibleLineCapacity() / 2));
            return true;
        }

        if (key == Keyboard.KEY_NEXT) {
            scroll(-Math.max(1, visibleLineCapacity() / 2));
            return true;
        }

        // Let TextField handle input without restricting it to the visible width.
        this.input.keyTyped(key, character);
        return true;
    }

    private void navigateHistory(int direction) {
        List<String> history = this.chat.getHistory();

        if (history.isEmpty()) {
            return;
        }

        this.historyIndex = Math.max(
                -1,
                Math.min(history.size() - 1, this.historyIndex + direction)
        );

        if (this.historyIndex >= 0) {
            this.input.setText(history.get(history.size() - 1 - this.historyIndex));
        } else {
            this.input.clear();
        }
    }

    private void submit() {
        this.completer.reset();

        String text = this.input.getText().trim();

        this.input.clear();
        // stopped typing before the message goes out, so the others' "..." is gone when it arrives
        Multiplayer.setTyping(false);
        this.historyIndex = -1;
        this.scrollLines = 0;

        if (text.isEmpty()) {
            return;
        }

        this.chat.remember(text);

        if (ChatInputSyntax.isCommand(text)) {
            runCommand(text.substring(1).trim());
        } else {
            if (!Multiplayer.sendChat(text)) {
                // alone or hosting: shown here (a joined player's chat comes back from the server)
                this.chat.say(ChatInputSyntax.displayed(text));
            }
            // the voice (and the portrait's mouth) is this player's own, whether or not a server carried the message.
            // this used to be skipped when joined, so a joined player never spoke
            this.game.getSpeaker().speak(text);
        }

        if (CLOSE_ON_SEND) {
            this.game.closeScreen();
        }
    }

    private void runCommand(String line) {
        if (line.isEmpty()) {
            this.chat.notice("Type .help for a list of commands.");
            return;
        }

        if (line.split("\\s+")[0].equalsIgnoreCase("clear")) {
            this.chat.clear();
            return;
        }

        // in multiplayer most commands are the server's to run (and to refuse: they have tiers). a joined player's own
        // game only runs the ones that touch nothing but their own screen, see Multiplayer.runCommand
        List<String> serverAnswer = Multiplayer.runCommand(line);
        if (serverAnswer != null) {
            for (String output : serverAnswer) {
                this.chat.notice(output);
            }
            return;
        }

        for (String output : this.game.getConsole().execute(line)) {
            this.chat.notice(output);
        }
    }

    /** conversation and input text, drawn after the widgets so it's on top of the input box */
    @Override
    protected void renderForeground(int mouseX, int mouseY) {
        if (!isFrameOpen()) {
            return;
        }

        Font font = this.game.getFont();

        Gui2D.begin(this.game.getWidth(), this.game.getHeight());

        updateTyping();
        drawConversation(font, mouseX, mouseY);
        drawTypingIndicator(font);
        drawInputLine(font, mouseX, mouseY);
        drawSuggestions(font);
        drawLinkTooltip(font, mouseX, mouseY);

        Gui2D.end();
    }

    private int visibleLineCapacity() {
        if (this.layout == null) {
            return 1;
        }

        return Math.max(
                1,
                (this.layout.viewHeight - TOPIC_HEIGHT - PAD * 2 - TYPING_ROW_HEIGHT) / LINE_HEIGHT
        );
    }

    private static final int IMAGE_MAX_HEIGHT = 240;
    private static final int IMAGE_MAX_WIDTH = 480;
    private static final int NOTE_COLOR = 0x8899AA;
    // a picture's drop shadow is offset by one pixel of the text and drawn under it
    private static final int IMAGE_SHADOW = (int) CHAT_SCALE;
    private static final float IMAGE_SHADOW_ALPHA = 0.8F;
    private static final long COPIED_NANOS = 1_200_000_000L;

    private enum Row { TEXT, IMAGE, NOTE }

    private static final class VisualLine {
        final ChatLog.Line source;
        final Row row;

        // TEXT: whether the mark ("*** " or "<nick> ") goes in front, and which part of source.text this is
        final boolean marked;
        final int start;
        final int end;
        // the text the offsets point into (source.text with picture links shortened)
        final ChatRender.Shown shown;

        // IMAGE: one row-high slice of the picture, NOTE: placeholder text
        final String url;
        final ChatImages.Entry image;
        final int slice;
        final int slices;
        final int imageWidth;
        final int imageHeight;
        final String note;

        private VisualLine(ChatLog.Line source, Row row, boolean marked, int start, int end,
                           ChatRender.Shown shown, String url,
                           ChatImages.Entry image, int slice, int slices, int imageWidth, int imageHeight,
                           String note) {
            this.source = source;
            this.row = row;
            this.marked = marked;
            this.start = start;
            this.end = end;
            this.shown = shown;
            this.url = url;
            this.image = image;
            this.slice = slice;
            this.slices = slices;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.note = note;
        }

        static VisualLine text(ChatLog.Line source, ChatRender.Shown shown, boolean marked, int start, int end) {
            return new VisualLine(source, Row.TEXT, marked, start, end, shown, null, null, 0, 0, 0, 0, null);
        }

        static VisualLine slice(ChatLog.Line source, String url, ChatImages.Entry image, int slice, int slices,
                                int width, int height) {
            return new VisualLine(source, Row.IMAGE, false, 0, 0, null, url, image, slice, slices, width, height, null);
        }

        static VisualLine note(ChatLog.Line source, String url, String note) {
            return new VisualLine(source, Row.NOTE, false, 0, 0, null, url, null, 0, 0, 0, 0, note);
        }
    }

    /** a clickable part drawn this frame */
    private static final class Hit {
        final int x;
        final int y;
        final int width;
        final int height;
        final ChatText.Kind kind;
        final String value;
        // the text part it belongs to, null for a picture
        final ChatRender.Part part;

        Hit(int x, int y, int width, int height, ChatText.Kind kind, String value, ChatRender.Part part) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.kind = kind;
            this.value = value;
            this.part = part;
        }

        boolean contains(int mouseX, int mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }

    // rebuilt by drawConversation every frame
    private final List<Hit> hits = new ArrayList<>();

    // the copyable part that was just clicked, shown as "Copied!" for a moment
    private String copiedValue = null;
    private long copiedAt = 0L;

    /** the rows one line takes, top to bottom: its text, then the pictures linked in it */
    private List<VisualLine> layoutLine(Font font, ChatLog.Line line, int textWidth) {
        List<VisualLine> rows = new ArrayList<>();

        int prefixWidth = textWidth(font, markOf(line));
        boolean everyLine = marksEveryLine(line);

        // a link to a picture shows as its file name, the picture itself follows under the text
        ChatRender.Shown shown = ChatRender.show(line, false);

        List<int[]> pieces = ChatRender.wrap(font, shown.text, textWidth - prefixWidth,
                everyLine ? textWidth - prefixWidth : textWidth, CHAT_SCALE);

        for (int p = 0; p < pieces.size(); p++) {
            rows.add(VisualLine.text(line, shown, p == 0 || everyLine, pieces.get(p)[0], pieces.get(p)[1]));
        }

        // pictures line up with the text, so they get what is left of the width
        int maxWidth = Math.min(IMAGE_MAX_WIDTH, textWidth - prefixWidth);

        // pictures from links are only downloaded where the server allows it (it shows our address to their host)
        boolean showPictures = Multiplayer.embedsAllowed();

        for (String url : line.images) {
            if (!showPictures) {
                break;
            }
            ChatImages.Entry image = ChatImages.get(url);

            if (!image.isReady() || maxWidth < LINE_HEIGHT) {
                rows.add(VisualLine.note(line, url, image.isFailed() ? "[image unavailable]" : "[loading image...]"));
                continue;
            }

            double scale = Math.min(1.0, Math.min(maxWidth / (double) image.width(),
                    IMAGE_MAX_HEIGHT / (double) image.height()));
            int w = Math.max(1, (int) Math.round(image.width() * scale));
            int h = Math.max(1, (int) Math.round(image.height() * scale));
            int slices = (h + LINE_HEIGHT - 1) / LINE_HEIGHT;

            for (int i = 0; i < slices; i++) {
                rows.add(VisualLine.slice(line, url, image, i, slices, w, h));
            }
        }

        return rows;
    }

    private void drawConversation(Font font, int mouseX, int mouseY) {
        // last frame's hits say what the mouse is over: the whole link lights up, not just its row
        Hit over = hitAt(mouseX, mouseY);
        ChatRender.Part hovered = over == null ? null : over.part;

        this.hits.clear();

        int x = this.layout.viewX;
        int y = this.layout.viewY;
        int w = this.layout.viewWidth;
        int h = this.layout.viewHeight;

        Gui2D.fillRect(x, y, w, h, 0.0F, 0.0F, 0.0F, 0.55F);

        Gui2D.fillRect(
                x, y, w, TOPIC_HEIGHT,
                0.10F, 0.15F, 0.45F, 0.9F
        );

        String serverTopic = Multiplayer.chatTopic();
        String topic = "Topic for " + this.chat.getChannel()
                + ": " + (serverTopic.isEmpty() ? "Welcome to " + GAME_TITLE : serverTopic)
                + (this.scrollLines > 0 ? "  [scrollback]" : "");

        drawText(
                font,
                fit(font, topic, w - PAD * 2),
                x + PAD,
                y + 4,
                0xFFFFFF
        );

        int capacity = visibleLineCapacity();
        int textWidth = w - PAD * 2;

        List<ChatLog.Line> all = this.chat.getLines();
        List<VisualLine> visual = new ArrayList<>();

        int needed = capacity + this.scrollLines;

        // newest first, and the rows of one line bottom to top, so index 0 is the bottom row
        for (int i = all.size() - 1; i >= 0 && visual.size() < needed; i--) {
            List<VisualLine> rows = layoutLine(font, all.get(i), textWidth);

            for (int r = rows.size() - 1; r >= 0; r--) {
                visual.add(rows.get(r));
            }
        }

        this.scrollLines = Math.min(
                this.scrollLines,
                Math.max(0, visual.size() - capacity)
        );

        // the bottom row of the panel is the typing indicator's, so the lines stop above it
        int baseY = y + h - PAD - LINE_HEIGHT - TYPING_ROW_HEIGHT;
        int end = Math.min(visual.size(), this.scrollLines + capacity);

        // shadows first: a slice's shadow reaches into the row below, which would cover that slice
        for (int i = this.scrollLines; i < end; i++) {
            VisualLine v = visual.get(i);

            if (v.row == Row.IMAGE) {
                int lineY = baseY - (i - this.scrollLines) * LINE_HEIGHT;
                drawImageShadow(v, x + PAD + textWidth(font, markOf(v.source)), lineY);
            }
        }

        for (int i = this.scrollLines; i < end; i++) {
            VisualLine v = visual.get(i);

            int lineY = baseY - (i - this.scrollLines) * LINE_HEIGHT;
            int lineX = x + PAD;

            switch (v.row) {
                case TEXT:
                    drawTextRow(font, v, lineX, lineY, hovered);
                    break;
                case IMAGE:
                    drawImageRow(v, lineX + textWidth(font, markOf(v.source)), lineY);
                    break;
                default:
                    drawText(font, v.note, lineX + textWidth(font, markOf(v.source)), lineY, NOTE_COLOR);
                    break;
            }
        }
    }

    private void drawTextRow(Font font, VisualLine v, int lineX, int lineY, ChatRender.Part hovered) {
        int textX = lineX;

        if (v.marked) {
            String mark = markOf(v.source);
            drawText(font, mark, lineX, lineY, markColorOf(v.source));
            textX += textWidth(font, mark);
        }

        ChatRender.drawPiece(font, v.shown, v.start, v.end, textX, lineY, textColorOf(v.source),
                CHAT_SCALE, hovered,
                (hx, hy, hw, hh, part) -> this.hits.add(new Hit(hx, hy, hw, hh, part.kind, part.value, part)));
    }

    /** the black copy of one slice, down and to the right of it like the text's shadow */
    private void drawImageShadow(VisualLine v, int imageX, int lineY) {
        int top = v.slice * LINE_HEIGHT;
        int sliceHeight = Math.min(LINE_HEIGHT, v.imageHeight - top);

        Gui2D.fillRect(imageX + IMAGE_SHADOW, lineY + IMAGE_SHADOW, v.imageWidth, sliceHeight,
                0.0F, 0.0F, 0.0F, IMAGE_SHADOW_ALPHA);
    }

    private void drawImageRow(VisualLine v, int imageX, int lineY) {
        int top = v.slice * LINE_HEIGHT;
        int sliceHeight = Math.min(LINE_HEIGHT, v.imageHeight - top);

        ChatImages.drawSlice(v.image, imageX, lineY, v.imageWidth, sliceHeight,
                top / (float) v.imageHeight, (top + sliceHeight) / (float) v.imageHeight);

        // clicking the picture opens it
        this.hits.add(new Hit(imageX, lineY, v.imageWidth, sliceHeight, ChatText.Kind.LINK, v.url, null));
    }

    private Hit hitAt(int mouseX, int mouseY) {
        for (Hit hit : this.hits) {
            if (hit.contains(mouseX, mouseY)) {
                return hit;
            }
        }
        return null;
    }

    private void activate(Hit hit) {
        if (hit.kind == ChatText.Kind.LINK) {
            if (!Multiplayer.linksAllowed()) {
                this.chat.notice("This server does not allow opening links");
                return;
            }
            ChatActions.openLink(hit.value);
        } else if (ChatActions.copy(hit.value)) {
            this.copiedValue = hit.value;
            this.copiedAt = System.nanoTime();
        }
    }

    /** "Click to view" by the mouse over a link or picture, "Click to copy" over a copyable part */
    private void drawLinkTooltip(Font font, int mouseX, int mouseY) {
        Hit hit = hitAt(mouseX, mouseY);

        if (hit == null) {
            this.tooltip.hide();
        } else if (hit.kind == ChatText.Kind.COPY) {
            boolean justCopied = hit.value.equals(this.copiedValue)
                    && System.nanoTime() - this.copiedAt < COPIED_NANOS;

            this.tooltip.show(justCopied ? "Copied!" : "Click to copy", justCopied ? 0x77FF77 : 0xFFFFFF);
        } else {
            this.tooltip.show("Click to view", 0xFFFFFF);
        }

        this.tooltip.setScreenSize(this.game.getWidth(), this.game.getHeight());
        this.tooltip.render(font, mouseX, mouseY);
    }

    private int inputX() {
        return this.layout.viewX + PAD;
    }

    private int inputY() {
        return this.layout.viewY + this.layout.viewHeight + INPUT_Y_OFFSET;
    }

    // top of the input box, as wide as the world view above it
    private int boxY() {
        return inputY() - INPUT_BOX_PADDING;
    }

    private int boxHeight() {
        return CHAR_HEIGHT + INPUT_BOX_PADDING * 2;
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        if (!isFrameOpen()) {
            return;
        }

        int x = inputX();
        int y = inputY();

        Hit hit = hitAt(mouseX, mouseY);
        if (hit != null) {
            activate(hit);
            return;
        }

        boolean onInput = mouseX >= this.layout.viewX
                && mouseX < this.layout.viewX + this.layout.viewWidth
                && mouseY >= boxY()
                && mouseY < boxY() + boxHeight();

        if (onInput) {
            this.input.mouseClicked(
                    mouseX,
                    mouseY,
                    x,
                    y,
                    inputWidth(),
                    this.game.getFont(),
                    prompt()
            );
            return;
        }

        // Anything else: the widgets (the Done button).
        super.mouseClicked(mouseX, mouseY);
    }

    /** tells the others whether a chat message (not a command) is being typed, every frame: Multiplayer only sends changes and refreshes */
    private void updateTyping() {
        String text = this.input.getText();
        Multiplayer.setTyping(!text.trim().isEmpty() && !ChatInputSyntax.isCommand(text));
    }

    /** "Name is typing..." in the bottom row of the conversation (part of the chat area, not over the input line) */
    private void drawTypingIndicator(Font font) {
        List<String> names = Multiplayer.typingNames();
        if (names.isEmpty()) {
            return;
        }
        String text;
        if (names.size() == 1) {
            text = names.get(0) + " is typing...";
        } else if (names.size() == 2) {
            text = names.get(0) + " and " + names.get(1) + " are typing...";
        } else {
            text = "Several people are typing...";
        }
        drawText(font, fit(font, text, this.layout.viewWidth - PAD * 2), this.layout.viewX + PAD,
                this.layout.viewY + this.layout.viewHeight - PAD - LINE_HEIGHT, NOTE_COLOR);
    }

    private void drawInputLine(Font font, int mouseX, int mouseY) {
        int x     = inputX();
        int y     = inputY();
        int width = inputWidth();

        this.input.updateMouse(mouseX, mouseY, x, y, width, font, prompt());
        this.input.render(font, x, y, 0xFFFFFF, prompt(), width);
    }

    /** matches for the word at the cursor in a box above the input line, plus the command's usage */
    private void drawSuggestions(Font font) {
        if (!ChatInputSyntax.isCommand(this.input.getText())) {
            return;
        }

        CommandCompleter.Popup popup = this.completer.popup();
        if (popup == null) {
            return;
        }

        // just above the input box's own bevel
        int edge = boxY() - 2 * GameFrame.UI_SCALE - 2;

        SuggestionBox.draw(font, popup, this.layout.viewX, edge, this.layout.viewWidth,
                true, getFrameTint(), CHAT_SCALE);
    }

    private String prompt() {
        return "> ";
    }

    private int inputWidth() {
        return this.layout.viewWidth - PAD * 2;
    }

    /** "<nick> " for messages, "*** " for notices (also used by ChatHud) */
    public static String markOf(ChatLog.Line line) {
        return line.kind == ChatLog.Kind.MESSAGE
                ? "<" + line.nick + "> "
                : "*** ";
    }

    /** notices carry their "*** " on every wrapped line, so the whole text sits to the right of it */
    public static boolean marksEveryLine(ChatLog.Line line) {
        return line.kind == ChatLog.Kind.NOTICE;
    }

    public static int textColorOf(ChatLog.Line line) {
        return line.kind == ChatLog.Kind.MESSAGE
                ? TEXT_COLOR
                : NOTICE_TEXT_COLOR;
    }

    public static int markColorOf(ChatLog.Line line) {
        return line.kind == ChatLog.Kind.MESSAGE
                ? nickColor(line.nick)
                : NOTICE_MARK_COLOR;
    }

    public static int nickColor(String nick) {
        return PlayerColors.of(nick);
    }

    private static int textWidth(Font font, String text) {
        return (int) (font.getWidth(text) * CHAT_SCALE);
    }

    private void drawText(Font font, String text, int x, int y, int color) {
        if (!text.isEmpty()) {
            font.drawStringScaled(text, x, y, color, CHAT_SCALE);
        }
    }

    private static String fit(Font font, String text, int maxWidth) {
        int end = text.length();

        while (end > 0 && textWidth(font, text.substring(0, end)) > maxWidth) {
            end--;
        }

        return text.substring(0, end);
    }
}
