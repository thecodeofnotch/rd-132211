package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.console.ChatLog;
import com.nikohalfkino.rubydung.console.ChatText;
import com.nikohalfkino.rubydung.render.Font;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** wrapping and drawing of chat text with its links and copyable parts, shared by the chat screen and the chat hud */
public final class ChatRender {

    public static final int LINK_COLOR = 0x6CB4FF;
    public static final int LINK_HOVER_COLOR = 0xB5DAFF;
    public static final int COPY_COLOR = 0xFFD866;
    public static final int COPY_HOVER_COLOR = 0xFFF0B0;

    // the chip behind a picture's label ("@ photo.png" in the chat screen, "[Image]" in the hud)
    public static final int IMAGE_BG = 0x1A2438;
    public static final int IMAGE_BG_HOVER = 0x283A5C;
    private static final float IMAGE_BG_ALPHA = 0.9F;

    /** in front of a picture's file name in the chat screen: "@ cat.png" */
    public static final String IMAGE_ICON = "@ ";

    /** what a link to a picture is shown as in the hud */
    public static final String IMAGE_TAG = "[Image]";

    /** one link / copyable part as it is shown (its position is in Shown.text, not in line.text) */
    public static final class Part {
        public final int start;
        public final int end;
        public final ChatText.Kind kind;
        public final String value;
        /** a link to a picture that is embedded under the line */
        public final boolean image;

        private final ChatLog.Line line;
        private final int index;

        private Part(int start, int end, ChatText.Kind kind, String value, boolean image,
                     ChatLog.Line line, int index) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.value = value;
            this.image = image;
            this.line = line;
            this.index = index;
        }

        /** the same part of the same line, even when it comes from another frame's Shown */
        public boolean same(Part other) {
            return other != null && this.line == other.line && this.index == other.index;
        }
    }

    /** a line's text the way it is drawn: picture links shortened, with its parts moved to match */
    public static final class Shown {
        public final String text;
        public final List<Part> parts;

        private Shown(String text, List<Part> parts) {
            this.text = text;
            this.parts = parts;
        }
    }

    /** told about every clickable part that gets drawn (once per row it covers) */
    public interface HitSink {
        void hit(int x, int y, int width, int height, Part part);
    }

    private ChatRender() {
    }

    /**
     * line.text with every link to one of the line's pictures swapped for a short label: the file name,
     * or "[Image]" when tag is set (the hud has no room for the picture). the rest is unchanged
     */
    public static Shown show(ChatLog.Line line, boolean tag) {
        String text = line.text;
        StringBuilder out = new StringBuilder(text.length());
        List<Part> parts = new ArrayList<>();
        int taken = 0;
        int index = 0;
        // with embeds off no picture is shown under the line, so a picture link stays a plain link with its full address
        boolean embeds = com.nikohalfkino.rubydung.net.Multiplayer.embedsAllowed();

        for (ChatText.Span span : line.spans) {
            if (span.start < taken || span.end > text.length()) {
                continue;
            }

            out.append(text, taken, span.start);

            boolean image = embeds && span.kind == ChatText.Kind.LINK && isImage(line, span.value);
            String label = image
                    ? (tag ? IMAGE_TAG : IMAGE_ICON + fileName(span.value))
                    : text.substring(span.start, span.end);

            int start = out.length();
            out.append(label);
            parts.add(new Part(start, out.length(), span.kind, span.value, image, line, index++));

            taken = span.end;
        }

        out.append(text, taken, text.length());

        return new Shown(out.toString(), parts);
    }

    private static boolean isImage(ChatLog.Line line, String url) {
        for (String image : line.images) {
            if (image.equals(url)) {
                return true;
            }
        }
        return false;
    }

    /** "https://x.org/a/cat%20photo.png?size=big" -> "cat photo.png" */
    static String fileName(String url) {
        String path = url;

        int cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }

        String name = path.substring(path.lastIndexOf('/') + 1);

        try {
            name = URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            // not valid escapes: keep it as it is
        }

        name = name.trim();

        if (name.isEmpty()) {
            try {
                String host = new URI(url).getHost();
                name = host == null ? "" : host;
            } catch (Exception ignored) {
                // falls through to the generic name
            }
        }

        return name.isEmpty() ? "image" : name;
    }

    public static int width(Font font, String text, float scale) {
        return (int) (font.getWidth(text) * scale);
    }

    /**
     * splits text into pieces that fit: [start, end) pairs. the first piece may be at most firstWidth wide
     * and the others restWidth. breaks fall on spaces (the space is dropped), a word wider than a line is cut
     */
    public static List<int[]> wrap(Font font, String text, int firstWidth, int restWidth, float scale) {
        List<int[]> out = new ArrayList<>();

        int pieceStart = 0;
        int pieceEnd = 0;
        int limit = Math.max(1, firstWidth);
        int wordStart = 0;

        while (true) {
            int wordEnd = text.indexOf(' ', wordStart);
            if (wordEnd < 0) {
                wordEnd = text.length();
            }

            boolean empty = pieceEnd == pieceStart;
            int candidateStart = empty ? wordStart : pieceStart;

            if (width(font, text.substring(candidateStart, wordEnd), scale) <= limit) {
                pieceStart = candidateStart;
                pieceEnd = wordEnd;
            } else {
                if (!empty) {
                    out.add(new int[]{pieceStart, pieceEnd});
                    limit = Math.max(1, restWidth);
                }

                pieceStart = wordStart;
                pieceEnd = wordStart;

                for (int c = wordStart; c < wordEnd; c++) {
                    if (pieceEnd > pieceStart
                            && width(font, text.substring(pieceStart, c + 1), scale) > limit) {
                        out.add(new int[]{pieceStart, pieceEnd});
                        pieceStart = c;
                        limit = Math.max(1, restWidth);
                    }
                    pieceEnd = c + 1;
                }
            }

            if (wordEnd >= text.length()) {
                break;
            }
            wordStart = wordEnd + 1;
        }

        out.add(new int[]{pieceStart, pieceEnd});

        return out;
    }

    /**
     * draws shown.text[start, end) with its links and copyable parts coloured and underlined, and
     * picture labels on a background. hovered is the part under the mouse: all of it is drawn lighter,
     * on every row it wraps over. hovered and sink may be null
     */
    public static void drawPiece(Font font, Shown shown, int start, int end, int x, int y,
                                 int baseColor, float scale, Part hovered, HitSink sink) {
        String text = shown.text;
        int charHeight = (int) (8 * scale);
        // as thick as a pixel of the text
        int underline = Math.max(1, Math.round(scale));
        int pos = start;

        for (Part part : shown.parts) {
            if (part.end <= start) {
                continue;
            }
            if (part.start >= end) {
                break;
            }

            int a = Math.max(part.start, start);
            int b = Math.min(part.end, end);

            if (a > pos) {
                drawRun(font, text.substring(pos, a), x + width(font, text.substring(start, pos), scale), y,
                        baseColor, scale);
            }

            int runX = x + width(font, text.substring(start, a), scale);
            int runWidth = width(font, text.substring(a, b), scale);
            boolean hover = part.same(hovered);

            int color = part.kind == ChatText.Kind.LINK
                    ? (hover ? LINK_HOVER_COLOR : LINK_COLOR)
                    : (hover ? COPY_HOVER_COLOR : COPY_COLOR);

            if (part.image) {
                int bg = hover ? IMAGE_BG_HOVER : IMAGE_BG;
                Gui2D.fillRect(runX, y, runWidth, charHeight + 3,
                        ((bg >> 16) & 0xFF) / 255.0F, ((bg >> 8) & 0xFF) / 255.0F, (bg & 0xFF) / 255.0F,
                        IMAGE_BG_ALPHA);
            }

            drawRun(font, text.substring(a, b), runX, y, color, scale);

            float r = ((color >> 16) & 0xFF) / 255.0F;
            float g = ((color >> 8) & 0xFF) / 255.0F;
            float bl = (color & 0xFF) / 255.0F;
            Gui2D.fillRect(runX, y + charHeight, runWidth, underline, r, g, bl, 1.0F);

            if (sink != null) {
                sink.hit(runX, y, runWidth, charHeight + 3, part);
            }

            pos = b;
        }

        if (pos < end) {
            drawRun(font, text.substring(pos, end), x + width(font, text.substring(start, pos), scale), y,
                    baseColor, scale);
        }
    }

    private static void drawRun(Font font, String run, int x, int y, int color, float scale) {
        if (!run.isEmpty()) {
            font.drawStringScaled(run, x, y, color, scale);
        }
    }
}
