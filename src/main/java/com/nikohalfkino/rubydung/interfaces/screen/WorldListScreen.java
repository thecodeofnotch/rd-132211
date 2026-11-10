package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Bevel;
import com.nikohalfkino.rubydung.interfaces.GameFrame;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.Group;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.Panel;
import com.nikohalfkino.rubydung.interfaces.widget.Row;
import com.nikohalfkino.rubydung.interfaces.widget.Shadow;
import com.nikohalfkino.rubydung.interfaces.widget.UiSounds;
import com.nikohalfkino.rubydung.interfaces.widget.Widget;
import com.nikohalfkino.rubydung.level.DoorRegistry;
import com.nikohalfkino.rubydung.level.tiles.special.DoorColor;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.render.Font;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class WorldListScreen extends MenuScreen {

    private static final float ROW_WIDTH = UiStyle.PANEL_WIDTH;
    // tall enough for a 28px badge inside the row's bevel
    private static final float ROW_HEIGHT = 36.0f;
    private static final float ROW_GAP = 4.0f;
    private static final int TOTAL_WORLD_SLOTS = 10;

    // panel bevel to its contents
    private static final float PANEL_PADDING = 20.0f;
    // heading to the first row
    private static final float HEADING_GAP = 14.0f;
    // last row to the buttons
    private static final float BUTTONS_GAP = 14.0f;

    // three buttons share a row's width so their outer edges line up with it
    private static final float BUTTON_WIDTH = 130.0f;
    private static final float BUTTON_ROW_GAP = (ROW_WIDTH - 3 * BUTTON_WIDTH) / 2.0f;

    // text inside a row, from its left / top edge
    private static final float TEXT_X = 10.0f;
    private static final float TEXT_Y = 10.0f;

    // opacity of both panels' black drop shadows (1 = solid black)
    private static final float SHADOW_ALPHA = 1.0f;

    // The "Info" tab: a second panel tucked behind the right edge of the main one.
    private static final float INFO_WIDTH = 232.0f;
    private static final float INFO_HEIGHT = 260.0f;
    // how far the tab slides in behind the main panel so it reads as attached
    private static final float INFO_OVERLAP = 14.0f;
    // tab start below the main panel's top edge
    private static final float INFO_TOP = 40.0f;
    private static final float INFO_PADDING = 22.0f;
    private static final int INFO_TEXT_WIDTH = (int) (INFO_WIDTH - 2 * INFO_PADDING);
    private static final float INFO_GAP = 4.0f;
    private static final float INFO_SECTION_GAP = 12.0f;
    private static final SimpleDateFormat INFO_DATE_FORMAT = new SimpleDateFormat("MMM d, HH:mm");

    // Info card: a square black slot that holds the four detail rows.
    private static final float INFO_CARD_SIZE = INFO_TEXT_WIDTH; // square
    private static final float INFO_CARD_PAD = 10.0f;
    private static final float INFO_CARD_LINE_GAP = 2.0f;
    private static final float INFO_CARD_SECTION_GAP = 6.0f;

    // between a row's name and its badges
    private static final float NAME_BADGE_GAP = 8.0f;

    // Door badges: 16x16 sprites whose art is a 14x14 square in the top-left corner (the right and
    // bottom two pixels are empty). Only the art is drawn (at the UI's 2x scale), so BADGE_SIZE is
    // exactly the area it occupies.
    private static final String BADGE_PATH = "/assets/rubydung/textures/gui/";
    private static final int BADGE_SPRITE_PIXELS = 16;
    private static final int BADGE_ART_PIXELS = 14;
    private static final float BADGE_SIZE = BADGE_ART_PIXELS * GameFrame.UI_SCALE;
    private static final float BADGE_GAP = 4.0f;
    private static final float BADGE_MARGIN = 4.0f;

    // Row bevel greys: the same sunken look as the inventory slots (dark top/left, light bottom/right).
    private static final int ROW_SHADOW = 12;
    private static final int ROW_LIGHT = 112;

    private final List<WorldRow> rows = new ArrayList<>();
    private final Button deleteButton;

    private List<WorldManager.WorldInfo> worlds = new ArrayList<>();
    private String pendingDelete = null;

    // world in the Info tab: the last one hovered (null = none yet)
    private WorldManager.WorldInfo infoWorld = null;
    private Set<DoorColor> infoDoors = EnumSet.noneOf(DoorColor.class);

    // while true, clicking a world asks to delete it instead of opening it
    private boolean deleteMode = false;

    public WorldListScreen(RubyDung game) {
        super(game);

        onEscape(() -> {
            if (this.deleteMode) {
                setDeleteMode(false);
            } else {
                game.openScreen(new TitleScreen(game));
            }
        });

        // A stage holds the Info tab and the main panel, drawn back to front: the main panel's
        // shadow, then the Info tab (which therefore covers that shadow), then the main panel,
        // which overlaps the tab's left edge. The stage is exactly as big as the main panel, so
        // the tab sticks out of its right side; shifting the stage left by half of that keeps the
        // pair centred in the window.
        Group stage = add(new Group(), Anchor.CENTER, -(INFO_WIDTH - INFO_OVERLAP) / 2.0f, 0);

        // The main panel's own shadow is switched off; the Shadow widget draws it in the back layer.
        Panel panel = new Panel(ROW_GAP);
        panel.setPadding(PANEL_PADDING);
        panel.setShadow(false);

        stage.add(new Shadow(panel, SHADOW_ALPHA));
        stage.add(buildInfoTab(), Anchor.TOP_RIGHT, INFO_WIDTH - INFO_OVERLAP, INFO_TOP);
        stage.add(panel, Anchor.TOP_LEFT, 0, 0);

        // Heading: swaps to a red one in delete mode (the hidden one takes no space).
        Label heading = panel.add(new Label("Select World"), HEADING_GAP);
        heading.setVisibleWhen(() -> !this.deleteMode);
        Label deleteHeading = panel.add(new Label("Select a World to Delete").setColor(UiStyle.TEXT_ERROR), HEADING_GAP);
        deleteHeading.setVisibleWhen(() -> this.deleteMode);

        for (int i = 0; i < TOTAL_WORLD_SLOTS; i++) {
            this.rows.add(panel.add(new WorldRow(i + 1)));
        }
        this.rows.get(TOTAL_WORLD_SLOTS - 1).setGapAfter(BUTTONS_GAP);

        Row buttons = panel.add(new Row(BUTTON_ROW_GAP));
        buttons.add(new Button("New World", BUTTON_WIDTH, UiStyle.BUTTON_HEIGHT, true)
                .onClick(() -> game.openScreen(new CreateWorldScreen(game))));

        this.deleteButton = buttons.add(new Button("Delete", BUTTON_WIDTH, UiStyle.BUTTON_HEIGHT, true)
                .onClick(() -> setDeleteMode(!this.deleteMode)));
        this.deleteButton.setEnabledWhen(() -> !this.worlds.isEmpty());

        buttons.add(new Button("Back", BUTTON_WIDTH, UiStyle.BUTTON_HEIGHT, true)
                .onClick(() -> game.openScreen(new TitleScreen(game))));
    }

    // details of the hovered world, or a hint when there isn't one yet
    private Panel buildInfoTab() {
        Panel info = new Panel(INFO_GAP);
        info.setSize(INFO_WIDTH, INFO_HEIGHT);
        info.setPadding(INFO_PADDING);
        info.setCrossAlign(0.5f);   // centres the "Info" heading
        info.setShadowAlpha(SHADOW_ALPHA);

        info.add(new Label("Info"), INFO_SECTION_GAP);

        Label hint = info.add(new Label("Hover over a world\nto see its details")
                .setMaxWidth(INFO_TEXT_WIDTH).setColor(UiStyle.TEXT_DIM));
        hint.setVisibleWhen(() -> this.infoWorld == null);

        InfoCard card = info.add(new InfoCard());
        card.setVisibleWhen(() -> this.infoWorld != null);

        return info;
    }

    // draws a badge's art (top-left 14x14 of its sprite) at (x, y). the regular door is door.png, other colours are named after it (blue.png, ...)
    private static void drawBadge(DoorColor color, float x, float y) {
        float art = BADGE_ART_PIXELS / (float) BADGE_SPRITE_PIXELS;
        Gui2D.drawTextureRegion(badgeTexture(color), x, y, BADGE_SIZE, BADGE_SIZE, 0.0f, 0.0f, art, art);
    }

    private static String badgeTexture(DoorColor color) {
        String name = color == DoorColor.REGULAR ? "door" : color.name().toLowerCase(Locale.ROOT);
        return BADGE_PATH + name + ".png";
    }

    @Override
    public void render(int mouseX, int mouseY) {
        updateInfo(mouseX, mouseY);
        super.render(mouseX, mouseY);
    }

    // points the Info tab at the hovered world. gaps between rows keep the last one (no flicker), moving onto a button clears it
    private void updateInfo(int mouseX, int mouseY) {
        Widget hit = this.root.pick(mouseX, mouseY);

        if (hit instanceof WorldRow) {
            setInfoWorld(((WorldRow) hit).world);
        } else if (hit instanceof Button) {
            setInfoWorld(null);
        }
    }

    private void setInfoWorld(WorldManager.WorldInfo world) {
        if (world == this.infoWorld) {
            return;
        }
        this.infoWorld = world;
        this.infoDoors = world == null ? EnumSet.noneOf(DoorColor.class) : DoorRegistry.colors(world.name);
    }

    @Override
    public void init(int width, int height) {
        super.init(width, height);
        refresh();
    }

    private void setDeleteMode(boolean deleteMode) {
        this.deleteMode = deleteMode;
        this.pendingDelete = null;
        this.deleteButton.setSelected(deleteMode);
    }

    private void refresh() {
        this.worlds = WorldManager.listWorlds();
        this.pendingDelete = null;
        setInfoWorld(null);

        for (int i = 0; i < this.rows.size(); i++) {
            this.rows.get(i).setWorld(i < this.worlds.size() ? this.worlds.get(i) : null);
        }

        // Nothing left to delete: leave delete mode.
        if (this.worlds.isEmpty() && this.deleteMode) {
            setDeleteMode(false);
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY) {
        // Any click that isn't on a world row cancels a pending "Are you sure?".
        if (!(this.root.pick(mouseX, mouseY) instanceof WorldRow)) {
            this.pendingDelete = null;
        }
        super.mouseClicked(mouseX, mouseY);
    }

    // one list slot: a saved world (click to play, or delete in delete mode) or an empty placeholder
    private final class WorldRow extends Widget {

        private final int slotNumber;
        private WorldManager.WorldInfo world;

        // cursor was over this slot last frame, so the hover sound plays once per entry
        private boolean wasHovered = false;

        // colours of the dimensional doors in this world, shown as badges on the right
        private Set<DoorColor> doors = EnumSet.noneOf(DoorColor.class);

        WorldRow(int slotNumber) {
            this.slotNumber = slotNumber;
            setSize(ROW_WIDTH, ROW_HEIGHT);
        }

        void setWorld(WorldManager.WorldInfo world) {
            this.world = world;
            this.doors = world == null
                    ? EnumSet.noneOf(DoorColor.class)
                    : DoorRegistry.colors(world.name);
        }

        @Override
        protected boolean isInteractive() {
            return this.world != null;
        }

        @Override
        public void onMouseClicked(int mouseX, int mouseY) {
            UiSounds.click();
            if (!deleteMode) {
                game.loadWorld(this.world.name);
            } else if (this.world.name.equals(pendingDelete)) {
                WorldManager.delete(this.world.name);
                refresh();
            } else {
                pendingDelete = this.world.name;
            }
        }

        @Override
        protected void draw(Font font, int mouseX, int mouseY) {
            boolean hoveredNow = this.world != null && contains(mouseX, mouseY);
            if (hoveredNow && !this.wasHovered) {
                UiSounds.hover();
            }
            this.wasHovered = hoveredNow;

            if (this.world == null) {
                drawEmpty(font);
            } else {
                drawWorld(font, mouseX, mouseY);
            }
        }

        // sunken slot shared by every row (dark inset with the inventory's bevel)
        private void drawSlot(float r, float g, float b, float fillAlpha, float bevelStrength) {
            float u = GameFrame.UI_SCALE;

            Gui2D.fillRect(this.x, this.y, ROW_WIDTH, ROW_HEIGHT, r, g, b, fillAlpha);
            Bevel.ring(this.x, this.y, ROW_WIDTH, ROW_HEIGHT, u, false, ROW_SHADOW, ROW_LIGHT,
                    bevelStrength, bevelStrength, bevelStrength);
        }

        private void drawWorld(Font font, int mouseX, int mouseY) {
            float u = GameFrame.UI_SCALE;

            if (this.world.name.equals(pendingDelete)) {
                Gui2D.fillRect(this.x, this.y, ROW_WIDTH, ROW_HEIGHT, 0.35f, 0.1f, 0.1f, 0.9f);
                Bevel.ring(this.x, this.y, ROW_WIDTH, ROW_HEIGHT, u, false, ROW_SHADOW, ROW_LIGHT, 1.0f, 0.6f, 0.6f);
                String prompt = "Are you sure? (Click again to confirm)";
                font.drawString(prompt, (int) (this.x + TEXT_X), (int) (this.y + TEXT_Y), 0xFFAAAA);
                return;
            }

            boolean hovered = contains(mouseX, mouseY);

            // Slots are solid black; hovering lifts them to a dark grey so there is still feedback.
            // In delete mode the slots are tinted red so it's obvious clicking won't open them.
            if (deleteMode) {
                drawSlot(0.30f, 0.04f, 0.04f, hovered ? 0.55f : 0.40f, 1.0f);
            } else {
                float shade = hovered ? 0.10f : 0.0f;
                drawSlot(shade, shade, shade, 1.0f, 1.0f);
            }

            int nameColor;
            if (deleteMode) {
                nameColor = hovered ? UiStyle.TEXT_ERROR : UiStyle.TEXT_DANGER;
            } else {
                nameColor = hovered ? UiStyle.TEXT_HOVER : UiStyle.TEXT;
            }
            // The name gets whatever width the badges leave, and ends in "..." if it doesn't fit.
            int nameSpace = (int) (ROW_WIDTH - TEXT_X - NAME_BADGE_GAP - badgesWidth());
            String name = fit(font, this.world.name, nameSpace);
            font.drawString(name, (int) (this.x + TEXT_X), (int) (this.y + TEXT_Y), nameColor);

            drawBadges();
        }

        // width the badges take from the row's right edge incl. margin (0 = no doors)
        private float badgesWidth() {
            int count = this.doors.size();
            return count == 0 ? 0.0f : count * BADGE_SIZE + (count - 1) * BADGE_GAP + BADGE_MARGIN;
        }

        // cuts the text short with "..." to fit maxWidth
        private String fit(Font font, String text, int maxWidth) {
            if (font.width(text) <= maxWidth) {
                return text;
            }
            String ellipsis = "...";
            String cut = text;
            while (!cut.isEmpty() && font.width(cut + ellipsis) > maxWidth) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + ellipsis;
        }

        // right-aligned and vertically centred in the row, always shown
        private void drawBadges() {
            if (this.doors.isEmpty()) {
                return;
            }

            float badgeX = this.x + ROW_WIDTH - badgesWidth();
            float badgeY = this.y + (ROW_HEIGHT - BADGE_SIZE) / 2.0f;

            for (DoorColor color : this.doors) {
                drawBadge(color, badgeX, badgeY);
                badgeX += BADGE_SIZE + BADGE_GAP;
            }
        }

        private void drawEmpty(Font font) {
            drawSlot(0.0f, 0.0f, 0.0f, 1.0f, 0.5f);
            String emptyLabel = "- Empty World Slot " + this.slotNumber + " -";
            int labelWidth = font.width(emptyLabel);
            font.drawString(emptyLabel, (int) (this.x + (ROW_WIDTH - labelWidth) / 2.0f), (int) (this.y + TEXT_Y), 0x777777);
        }
    }

    // square info card for the hovered world: World Type, Last Played, Doors and Size in a black bevelled slot
    private final class InfoCard extends Widget {

        InfoCard() {
            setSize(INFO_CARD_SIZE, INFO_CARD_SIZE);
        }

        @Override
        protected void draw(Font font, int mouseX, int mouseY) {
            float u = GameFrame.UI_SCALE;

            // Black fill + sunken bevel (same as world rows).
            Gui2D.fillRect(this.x, this.y, INFO_CARD_SIZE, INFO_CARD_SIZE, 0f, 0f, 0f, 0.72f);
            Bevel.ring(this.x, this.y, INFO_CARD_SIZE, INFO_CARD_SIZE, u, false,
                    ROW_SHADOW, ROW_LIGHT, 1f, 1f, 1f);

            float tx = this.x + INFO_CARD_PAD;
            float ty = this.y + INFO_CARD_PAD;
            float lh = UiStyle.LINE_HEIGHT;

            // world Type
            font.drawString("World Type", (int) tx, (int) ty, UiStyle.TEXT_DIM);
            ty += lh + INFO_CARD_LINE_GAP;
            String worldTypeStr = formatWorldType(infoWorld);
            font.drawString(worldTypeStr.isEmpty() ? "-" : worldTypeStr,
                    (int) tx, (int) ty,
                    worldTypeStr.isEmpty() ? UiStyle.TEXT_DISABLED : UiStyle.TEXT);
            ty += lh + INFO_CARD_SECTION_GAP;

            // last Played
            font.drawString("Last Played", (int) tx, (int) ty, UiStyle.TEXT_DIM);
            ty += lh + INFO_CARD_LINE_GAP;
            font.drawString(INFO_DATE_FORMAT.format(new Date(infoWorld.lastPlayed)),
                    (int) tx, (int) ty, UiStyle.TEXT);
            ty += lh + INFO_CARD_SECTION_GAP;

            // doors
            font.drawString("Doors", (int) tx, (int) ty, UiStyle.TEXT_DIM);
            ty += lh + INFO_CARD_LINE_GAP;
            if (infoDoors.isEmpty()) {
                font.drawString("None", (int) tx, (int) ty, UiStyle.TEXT_DISABLED);
            } else {
                float bx = tx;
                for (DoorColor color : infoDoors) {
                    drawBadge(color, bx, ty);
                    bx += BADGE_SIZE + BADGE_GAP;
                }
            }
            ty += BADGE_SIZE + INFO_CARD_SECTION_GAP;

            // size
            font.drawString("Size", (int) tx, (int) ty, UiStyle.TEXT_DIM);
            ty += lh + INFO_CARD_LINE_GAP;
            font.drawString(formatSize(infoWorld.sizeBytes), (int) tx, (int) ty, UiStyle.TEXT);
        }
    }

    // type + size + time mode as one string
    private static String formatWorldType(WorldManager.WorldInfo w) {
        List<String> parts = new ArrayList<>();

        if (w.worldType != null) {
            switch (w.worldType) {
                case NATURAL: parts.add("Natural"); break;
                case ISLAND:  parts.add("Island");  break;
                case ARCHIPELAGO: parts.add("Archipelago"); break;
                default:      parts.add("Flat");    break;
            }
        }

        if (w.worldSize != null) {
            switch (w.worldSize) {
                case TINY:    parts.add("Tiny");    break;
                case DEFAULT: parts.add("Default"); break;
                case LARGE:   parts.add("Large");   break;
                case INSANE:  parts.add("XL");      break;
                case XXL:     parts.add("XXL");     break;
                default:      parts.add(w.worldSize.name()); break;
            }
        }

        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L)             return bytes + " B";
        if (bytes < 1024L * 1024L)     return String.format("%.1f KB", bytes / 1024.0);
        return                                String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}