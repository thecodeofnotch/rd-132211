package com.nikohalfkino.rubydung.interfaces;

import com.nikohalfkino.rubydung.interfaces.widget.PanelSection;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.Textures;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.nikohalfkino.rubydung.audio.SoundEffects;

import static org.lwjgl.opengl.GL11.*;

/**
 * in-world window frame: noise background with bevels, viewport on the left, slot grid on the right, all generated in code
 *
 * animated: setTarget sets the state, update() (once per frame) slides towards it. 0 = invisible, 1 = viewport shrunk to make room for the slot grid
 *
 * sizes are in "UI units" (UI_SCALE real pixels)
 */
public final class GameFrame {

    /** real pixels per UI unit */
    public static final int UI_SCALE = 2;

    // Layout, in UI units.
    private static final int MARGIN = 8;
    private static final int BOTTOM_BAR = 32;
    public static final int SLOT_SIZE = 16;
    public static final int GRID_COLUMNS = 5;
    public static final int GRID_ROWS = 5;
    private static final int FRAME_RING = 2;

    // around the whole window, in UI units
    private static final int OUTER_BEVEL = 2;

    public static final int SLOT_PX = SLOT_SIZE * UI_SCALE;

    // below this viewport width (px) the frame is skipped and the world fills the window
    private static final int MIN_VIEW_WIDTH = 160;
    private static final int MIN_VIEW_HEIGHT = 120;

    private static final float ANIM_SECONDS = 0.28F;

    // Palette (grey levels, 0..255). The original uses steps of 4.
    private static final int GRAY_STEP = 4;
    private static final int NOISE_MIN = 52;
    private static final int NOISE_MAX = 116;
    // grey levels darker than the background
    private static final int SLOT_DARKEN = 36;
    private static final int SLOT_MIN = 16;

    // Slot bevels sit clearly outside the slot interior's grey range (16..80), so they read as
    // crisp lines instead of blending into the noise.
    private static final int SLOT_SHADOW = 12;   // top/left edge inside a slot (darker than the interior)
    private static final int SLOT_LIGHT = 112;   // bottom/right edge inside a slot (lighter than the interior)

    // Outer bevel around the whole window (raised: lit top/left, shadowed bottom/right).
    // Swap the two values if you want it to look sunken instead.
    private static final int OUTER_LIGHT = 148;
    private static final int OUTER_SHADOW = 36;

    // Portrait box above the slot grid, in UI units.
    private static final int PORTRAIT_SIZE = 64;

    // in Visemes.Viseme order: idle, A, I, U, E, O (picked via FrameMouthProvider, idle without one)
    private static final String[] PORTRAIT_FRAMES = {
            "/assets/rubydung/textures/gui/portrait_idle.png",
            "/assets/rubydung/textures/gui/portrait_a.png",
            "/assets/rubydung/textures/gui/portrait_i.png",
            "/assets/rubydung/textures/gui/portrait_u.png",
            "/assets/rubydung/textures/gui/portrait_e.png",
            "/assets/rubydung/textures/gui/portrait_o.png"
    };

    // Text in the right-hand panel (username under the portrait, heading over the slots / buttons).
    private static final float TEXT_SCALE = 2.0F;
    // one line of heading / username text (glyphs are 8 * TEXT_SCALE = 16 tall)
    private static final int HEADING_HEIGHT = 18;
    // between stacked panel elements
    private static final int PANEL_GAP = 2 * UI_SCALE;
    private static final int NAME_COLOR = 0xFFFF00;
    private static final int TITLE_COLOR = 0xFFFFFF;

    // animation advances in this many discrete steps for a choppy retro feel
    private static final int STEPS = 10;

    // opacity of the dithered pixels (1 = solid black)
    private static final float DARKEN_ALPHA = 0.4F;

    // Noise texture.
    // big tiles repeat less on screen, so the tiling is harder to spot
    private static final int NOISE_SIZE = NoisePattern.TILE_SIZE;
    private static final long NOISE_SEED = 0x57A7AL;

    // textures of the pattern in use (set by ensureTextures)
    private int noiseTexture = 0;
    private int slotTexture = 0;

    // every pattern's textures, generated the first time it's drawn
    private final int[] noiseCache = new int[NoisePattern.values().length];
    private final int[] slotCache = new int[NoisePattern.values().length];

    private NoisePattern pattern = NoisePattern.STONE;

    private boolean open = false;
    private List<PanelSection> sections = Collections.singletonList(
            PanelSection.slots("", GRID_COLUMNS, GRID_ROWS));

    // where the portrait frame comes from
    // kept while closing so the panel slides out intact
    private FrameMouthProvider mouthSource;

    // multiplier on the frame's greys so each screen can tint it
    private float tintR = 1.0F;
    private float tintG = 1.0F;
    private float tintB = 1.0F;

    private float openness = 0.0F;
    private long lastNanos = 0L;

    // one resolved block of the right-hand panel (see PanelSection), real pixels
    public static final class SectionLayout {
        public final String title;
        /** the box itself (inside the bevel) */
        public final int x, y, width, height;
        /** top of the heading text above the box */
        public final int titleY;
        public final boolean slots;
        /** slot grid size, 0 for plain panels. rows is what actually fits */
        public final int columns, rows;

        SectionLayout(String title, int x, int y, int width, int height, int titleY,
                      boolean slots, int columns, int rows) {
            this.title = title;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.titleY = titleY;
            this.slots = slots;
            this.columns = columns;
            this.rows = rows;
        }

        SectionLayout withX(int newX) {
            return new SectionLayout(this.title, newX, this.y, this.width, this.height, this.titleY,
                    this.slots, this.columns, this.rows);
        }

        public int capacity() {
            return this.columns * this.rows;
        }

        public int slotX(int slotIndex) {
            return this.x + (slotIndex % this.columns) * SLOT_PX;
        }

        public int slotY(int slotIndex) {
            return this.y + (slotIndex / this.columns) * SLOT_PX;
        }

        /** slot number (row by row) under the point, or -1 */
        public int slotIndexAt(int mouseX, int mouseY) {
            if (!this.slots || this.columns <= 0) {
                return -1;
            }
            int dx = mouseX - this.x;
            int dy = mouseY - this.y;
            if (dx < 0 || dy < 0 || dx >= this.columns * SLOT_PX || dy >= this.rows * SLOT_PX) {
                return -1;
            }
            return (dy / SLOT_PX) * this.columns + (dx / SLOT_PX);
        }

        public boolean contains(int mouseX, int mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }

    /** pixel rectangles, origin at the window's top-left */
    public static final class Layout {
        public final int viewX, viewY, viewWidth, viewHeight;
        /** the right-hand panel's column (portrait and sections are centred in it) */
        public final int panelX, panelWidth;
        /** the first section's box, for single-section screens */
        public final int gridX, gridY, gridWidth, gridHeight;
        public final int portraitX, portraitY, portraitSize;
        /** top of the username line and of the first section's heading */
        public final int nameY, titleY;
        /** every section top to bottom in declared order, never empty */
        public final List<SectionLayout> sections;
        /** 0..1 in animation steps, 1 once the transition is done */
        public final float openness;

        Layout(int viewX, int viewY, int viewWidth, int viewHeight,
               int panelX, int panelWidth, List<SectionLayout> sections,
               int portraitX, int portraitY, int portraitSize,
               int nameY, float openness) {
            this.viewX = viewX;
            this.viewY = viewY;
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.panelX = panelX;
            this.panelWidth = panelWidth;
            this.sections = sections;
            SectionLayout first = sections.get(0);
            this.gridX = first.x;
            this.gridY = first.y;
            this.gridWidth = first.width;
            this.gridHeight = first.height;
            this.titleY = first.titleY;
            this.portraitX = portraitX;
            this.portraitY = portraitY;
            this.portraitSize = portraitSize;
            this.nameY = nameY;
            this.openness = openness;
        }
    }

    /** opens or closes, panel contents are kept while closing so it slides out the way it came in */
    public void setTarget(boolean open, List<PanelSection> sections, int tint) {
        setTarget(open, sections, tint, NoisePattern.STONE);
    }

    /** same, with the noise pattern to draw with */
    public void setTarget(boolean open, List<PanelSection> sections, int tint, NoisePattern pattern) {
        setOpenState(open);
        if (open) {
            this.pattern = pattern == null ? NoisePattern.STONE : pattern;
            this.sections = (sections == null || sections.isEmpty())
                    ? Collections.singletonList(PanelSection.fill(""))
                    : new ArrayList<>(sections);
            this.tintR = Bevel.red(tint);
            this.tintG = Bevel.green(tint);
            this.tintB = Bevel.blue(tint);
            // No screen is attached to this form; the screen overload sets it again afterwards.
            this.mouthSource = null;
        }
    }

    /** uses the screen's sections and tint, this is the one GameRenderer should call */
    public void setTarget(boolean open, FrameScreen screen) {
        if (open && screen != null) {
            NoisePattern pattern = screen instanceof FramePatternProvider
                    ? ((FramePatternProvider) screen).getFramePattern()
                    : NoisePattern.STONE;
            setTarget(true, screen.getSections(), screen.getFrameTint(), pattern);
            this.mouthSource = screen instanceof FrameMouthProvider
                    ? (FrameMouthProvider) screen
                    : null;
        } else {
            setOpenState(false);
        }
    }

    // plays the menu sound only when the state actually changes
    private void setOpenState(boolean open) {
        if (open != this.open) {
            SoundEffects.play(open ? "ui/menu_open" : "ui/menu_close");
        }
        this.open = open;
    }

    /** single-section form, prefer the list overload */
    public void setTarget(boolean open, boolean showSlots, String title, int tint) {
        setTarget(open, Collections.singletonList(showSlots
                ? PanelSection.slots(title, GRID_COLUMNS, GRID_ROWS)
                : PanelSection.fill(title)), tint);
    }

    // openness as the player sees it, quantised to STEPS (0 only when fully closed)
    private float stepped() {
        return (float) Math.ceil(this.openness * STEPS - 1.0e-4F) / STEPS;
    }

    /** dithers the world view dark (checkerboard, sparser while opening). call with the world viewport active, before the HUD */
    public void renderDarken(int viewWidth, int viewHeight) {
        int step = Math.round(stepped() * STEPS);
        if (step <= 0) {
            return;
        }

        // Gui2D.begin() enables alpha blending, so DARKEN_ALPHA works as the pixels' opacity.
        // Sparser pattern for the first steps of the opening animation.
        Gui2D.begin(viewWidth, viewHeight);
        Dither.fill(0, 0, viewWidth, viewHeight, DARKEN_ALPHA, step <= STEPS / 2);
        Gui2D.end();
    }

    public boolean isFullyOpen() {
        return this.openness >= 1.0F;
    }

    /** call once per rendered frame */
    public void update() {
        long now = System.nanoTime();
        float dt = this.lastNanos == 0L ? 0.0F : (now - this.lastNanos) / 1.0e9F;
        this.lastNanos = now;

        float step = dt / ANIM_SECONDS;
        if (this.open) {
            this.openness = Math.min(1.0F, this.openness + step);
        } else {
            this.openness = Math.max(0.0F, this.openness - step);
        }
    }

    /** where the viewport and slot grid are now (interpolated mid-animation), null if closed or the window is too small */
    public Layout layout(int windowWidth, int windowHeight) {
        if (this.openness <= 0.0F) {
            return null;
        }

        Layout target = targetLayout(windowWidth, windowHeight);
        if (target == null) {
            return null;
        }

        float t = stepped();
        int hiddenPanelX = windowWidth + FRAME_RING * UI_SCALE + 2 * UI_SCALE;

        int panelX = lerp(hiddenPanelX, target.panelX, t);
        int dx = panelX - target.panelX;

        List<SectionLayout> shifted = new ArrayList<>();
        for (SectionLayout section : target.sections) {
            shifted.add(section.withX(section.x + dx));
        }

        return new Layout(
                lerp(0, target.viewX, t),
                lerp(0, target.viewY, t),
                lerp(windowWidth, target.viewWidth, t),
                lerp(windowHeight, target.viewHeight, t),
                panelX,
                target.panelWidth,
                shifted,
                panelX + (target.panelWidth - target.portraitSize) / 2,
                target.portraitY,
                target.portraitSize,
                target.nameY,
                t);
    }

    private Layout targetLayout(int windowWidth, int windowHeight) {
        int u = UI_SCALE;

        int margin = MARGIN * u;
        int ring = FRAME_RING * u;

        int panelWidth = GRID_COLUMNS * SLOT_SIZE * u;

        // gap | frame ring | panel | frame ring | right margin
        int reserved = margin + ring + panelWidth + ring + margin;

        int viewX = margin;
        int viewY = margin;
        int viewWidth = windowWidth - reserved - viewX;
        int viewHeight = windowHeight - viewY - BOTTOM_BAR * u;

        if (viewWidth < MIN_VIEW_WIDTH || viewHeight < MIN_VIEW_HEIGHT) {
            return null;
        }

        int panelX = viewX + viewWidth + margin + ring;

        // Portrait at the top of the right-hand panel, centred over it.
        int portraitSize = PORTRAIT_SIZE * u;
        int portraitX = panelX + (panelWidth - portraitSize) / 2;
        int portraitY = viewY;

        // Username under the portrait; the sections start below it.
        int nameY = portraitY + portraitSize + ring + PANEL_GAP;
        int top = nameY + HEADING_HEIGHT + PANEL_GAP;
        int bottom = viewY + viewHeight;

        return new Layout(viewX, viewY, viewWidth, viewHeight, panelX, panelWidth,
                layoutSections(panelX, panelWidth, top, bottom),
                portraitX, portraitY, portraitSize, nameY, 1.0F);
    }

    // stacks the sections between top (under the username) and bottom (viewport's bottom edge)
    // slot sections get their rows (fewer if space runs out, min one), "fill" sections share the rest, with no fill section the stack sits on the bottom edge
    private List<SectionLayout> layoutSections(int panelX, int panelWidth, int top, int bottom) {
        int u = UI_SCALE;
        int ring = FRAME_RING * u;
        int gap = PANEL_GAP;
        int slot = SLOT_PX;

        int headerHeight = HEADING_HEIGHT + gap + ring;     // heading + gap + top frame ring
        int betweenHeight = ring + gap;                     // bottom frame ring + gap before next heading

        int n = this.sections.size();
        int overhead = n * headerHeight + (n - 1) * betweenHeight;
        int available = bottom - top - overhead;

        int[] rows = new int[n];
        int slotsTotal = 0;
        int fills = 0;

        for (int i = 0; i < n; i++) {
            PanelSection section = this.sections.get(i);
            if (section.slots) {
                rows[i] = Math.max(1, section.rows);
                slotsTotal += rows[i] * slot;
            } else {
                fills++;
            }
        }

        // Out of space: take rows away from the tallest grid, one at a time.
        while (slotsTotal > available) {
            int tallest = -1;
            for (int i = 0; i < n; i++) {
                if (this.sections.get(i).slots && rows[i] > 1 && (tallest < 0 || rows[i] > rows[tallest])) {
                    tallest = i;
                }
            }
            if (tallest < 0) {
                break;
            }
            rows[tallest]--;
            slotsTotal -= slot;
        }

        int fillTotal = fills == 0 ? 0 : Math.max(fills * slot, available - slotsTotal);
        int fillEach = fills == 0 ? 0 : (fillTotal / fills) / u * u;
        int fillLeft = fillTotal - fillEach * fills;

        int[] heights = new int[n];
        int total = overhead;
        int fillsSeen = 0;

        for (int i = 0; i < n; i++) {
            if (this.sections.get(i).slots) {
                heights[i] = rows[i] * slot;
            } else {
                fillsSeen++;
                heights[i] = fillEach + (fillsSeen == fills ? fillLeft : 0);
            }
            total += heights[i];
        }

        int y = Math.max(top, bottom - total) + headerHeight;
        List<SectionLayout> result = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            PanelSection section = this.sections.get(i);

            int columns = section.slots ? Math.min(Math.max(1, section.columns), GRID_COLUMNS) : 0;
            if (section.slots && section.columns == section.rows) {
                // A square grid stays square when it has to shrink.
                columns = Math.min(columns, rows[i]);
            }
            int w = section.slots ? columns * slot : panelWidth;
            int x = panelX + (panelWidth - w) / 2;

            result.add(new SectionLayout(section.title, x, y, w, heights[i],
                    y - ring - gap - HEADING_HEIGHT,
                    section.slots, columns, section.slots ? rows[i] : 0));

            y += heights[i] + betweenHeight + headerHeight;
        }

        return result;
    }

    public void render(Layout layout, int windowWidth, int windowHeight, Font font) {
        ensureTextures();

        boolean textureWasEnabled = glIsEnabled(GL_TEXTURE_2D);

        Gui2D.begin(windowWidth, windowHeight);

        drawTexturedRect(this.noiseTexture, 0, 0, windowWidth, windowHeight);

        int u = UI_SCALE;

        // Bevel around the entire window.
        ring(0, 0, windowWidth, windowHeight, OUTER_BEVEL * u, false, OUTER_LIGHT, OUTER_SHADOW);

        sunkenFrame(layout.viewX, layout.viewY, layout.viewWidth, layout.viewHeight, u);

        if (layout.gridX < windowWidth) {
            sunkenFrame(layout.portraitX, layout.portraitY, layout.portraitSize, layout.portraitSize, u);
            // Same darkened noise as the slots behind the portrait (shows through transparent pixels).
            drawTexturedRect(this.slotTexture, layout.portraitX, layout.portraitY,
                    layout.portraitSize, layout.portraitSize);

            // The portrait frame the screen asks for (mouth shape); idle when it has no opinion.
            int frame = this.mouthSource == null ? 0 : this.mouthSource.getMouthFrame();
            if (frame < 0 || frame >= PORTRAIT_FRAMES.length) {
                frame = 0;
            }
            Gui2D.drawTexture(PORTRAIT_FRAMES[frame], layout.portraitX, layout.portraitY,
                    layout.portraitSize, layout.portraitSize);
            glDisable(GL_TEXTURE_2D);

            for (SectionLayout section : layout.sections) {
                sunkenFrame(section.x, section.y, section.width, section.height, u);
                if (section.slots) {
                    drawSlots(section, u);
                } else {
                    // Plain panel with the same darkened noise as the slots (chat, pause menu, ...).
                    drawTexturedRect(this.slotTexture, section.x, section.y, section.width, section.height);
                }
            }
        }

        if (font != null && layout.gridX < windowWidth) {
            float centerX = layout.panelX + layout.panelWidth / 2.0F;
            drawCentered(font, Multiplayer.selfName(), centerX, layout.nameY, NAME_COLOR);
            for (SectionLayout section : layout.sections) {
                drawCentered(font, section.title, centerX, section.titleY, TITLE_COLOR);
            }
        }

        Gui2D.end();

        if (textureWasEnabled) {
            glEnable(GL_TEXTURE_2D);
        } else {
            glDisable(GL_TEXTURE_2D);
        }
    }

    private static void drawCentered(Font font, String text, float centerX, int y, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int width = (int) (font.getWidth(text) * TEXT_SCALE);
        font.drawStringScaled(text, (int) (centerX - width / 2.0F), y, color, TEXT_SCALE);
    }

    private void drawTexturedRect(int texture, float x, float y, float w, float h) {
        glEnable(GL_TEXTURE_2D);
        Textures.bind(texture);
        glColor4f(this.tintR, this.tintG, this.tintB, 1.0F);

        float period = NOISE_SIZE * UI_SCALE;
        float u0 = x / period;
        float u1 = (x + w) / period;
        float v0 = y / period;
        float v1 = (y + h) / period;

        glBegin(GL_QUADS);
        glTexCoord2f(u0, v0);
        glVertex2f(x, y);
        glTexCoord2f(u1, v0);
        glVertex2f(x + w, y);
        glTexCoord2f(u1, v1);
        glVertex2f(x + w, y + h);
        glTexCoord2f(u0, v1);
        glVertex2f(x, y + h);
        glEnd();

        glDisable(GL_TEXTURE_2D);
    }

    private void sunkenFrame(int x, int y, int w, int h, int u) {
        Bevel.sunkenFrame(x, y, w, h, this.tintR, this.tintG, this.tintB);
    }

    private void drawSlots(SectionLayout section, int u) {
        int slot = SLOT_SIZE * u;

        for (int row = 0; row < section.rows; row++) {
            for (int column = 0; column < section.columns; column++) {
                int x = section.x + column * slot;
                int y = section.y + row * slot;

                drawTexturedRect(this.slotTexture, x, y, slot, slot);
                ring(x, y, slot, slot, u, false, SLOT_SHADOW, SLOT_LIGHT);
            }
        }
    }

    private void ring(float x, float y, float w, float h, float t, boolean outside,
                      int topLeftGray, int bottomRightGray) {
        Bevel.ring(x, y, w, h, t, outside, topLeftGray, bottomRightGray,
                this.tintR, this.tintG, this.tintB);
    }

    // snaps to whole UI units so animation steps stay on the pixel grid
    private static int lerp(int a, int b, float t) {
        int value = Math.round(a + (b - a) * t);
        return Math.round(value / (float) UI_SCALE) * UI_SCALE;
    }

    // procedural noise textures (the patterns live in NoisePattern)

    private void ensureTextures() {
        int i = this.pattern.ordinal();

        if (this.noiseCache[i] == 0) {
            float[] noise = this.pattern.generate(NOISE_SIZE, NOISE_SEED);

            this.noiseCache[i] = uploadTexture(noise, 0, NOISE_MIN);
            this.slotCache[i] = uploadTexture(noise, -SLOT_DARKEN, SLOT_MIN);
        }

        this.noiseTexture = this.noiseCache[i];
        this.slotTexture = this.slotCache[i];
    }

    private static int uploadTexture(float[] noise, int offset, int minGray) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(NOISE_SIZE * NOISE_SIZE * 4);

        for (float value : noise) {
            int gray = Math.round(value * 255.0F / GRAY_STEP) * GRAY_STEP;
            gray = Math.max(NOISE_MIN, Math.min(NOISE_MAX, gray)) + offset;
            gray = Math.max(minGray, gray);

            byte b = (byte) gray;
            buffer.put(b).put(b).put(b).put((byte) 255);
        }
        buffer.flip();

        int id = glGenTextures();

        Textures.bind(id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, NOISE_SIZE, NOISE_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

        return id;
    }
}