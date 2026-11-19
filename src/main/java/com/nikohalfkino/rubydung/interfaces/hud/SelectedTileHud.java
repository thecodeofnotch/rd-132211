package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.TileIconRenderer;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Widget;
import com.nikohalfkino.rubydung.level.tiles.special.ColoredDiceTile;
import com.nikohalfkino.rubydung.level.tiles.special.DiceTile;
import com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.special.TreeTile;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.RagdollRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SelectedTileHud extends Widget {

    private static final float SCALE = 2.0F;
    private static final float LINE_STEP = 12 * SCALE;

    private static final float TEXT_ICON_GAP = 6.0F;

    private static final float ROW_HEIGHT = 48.0F;

    private static final float SPACING = 46.0F;

    private static final float CURRENT_ICON = 32.0F;
    private static final float SIDE_ICON = 24.0F;

    private static final float SIDE_DARKEN = 0.5F;

    private static final float ANIM_SECONDS = 0.28F;
    private static final int STEPS = 10;

    private static final float MAX_FRAME_SECONDS = 0.1F;

    // tiles shown under one shared name with their own name as the "Type:" line (like trees and doors), anything else shows its own name
    private static final Map<Tile, String> GROUP_NAMES = new HashMap<>();
    private static final Map<Tile, String> GROUP_TYPES = new HashMap<>();

    // goes through Tile on purpose: Tile and TileRegistry initialise each other, and if TileRegistry is touched first
    // Tile.AIR and the other Tile.* aliases end up null
    private static Tile t(String name) {
        Tile tile = Tile.byName(name);
        if (tile == null) {
            throw new IllegalStateException("SelectedTileHud: no tile named '" + name + "'");
        }
        return tile;
    }

    private static void group(String groupName, Object... tilesAndTypes) {
        for (int i = 0; i < tilesAndTypes.length; i += 2) {
            Tile tile = (Tile) tilesAndTypes[i];
            GROUP_NAMES.put(tile, groupName);
            GROUP_TYPES.put(tile, (String) tilesAndTypes[i + 1]);
        }
    }

    static {
        group("Wood",
                t("log"), "Log",
                t("planks"), "Planks",
                t("wood_slab"), "Slab",
                t("wood"), "Smooth");
        group("Metal",
                t("metal"), "Block",
                t("metal_girder"), "Girder",
                t("metal_grate"), "Grate",
                t("metal_bars"), "Bars");
        group("Marble",
                t("marble"), "Block",
                t("pillar_top"), "Pillar Top",
                t("pillar"), "Pillar",
                t("pillar_bottom"), "Pillar Bottom",
                t("column"), "Column");
        group("Bricks",
                t("bricks"), "Small",
                t("bricks_large"), "Large",
                t("bricks_slab"), "Small Slab",
                t("bricks_large_slab"), "Large Slab");
        group("Stone Bricks",
                t("stone_bricks"), "Small",
                t("stone_bricks_large"), "Large",
                t("stone_bricks_slab"), "Small Slab",
                t("stone_bricks_large_slab"), "Large Slab");
        group("Glass",
                t("glass"), "Plain",
                t("framed_glass"), "Framed");
        group("Lamp",
                t("lamp"), "Block",
                t("lamppost"), "Post");
    }

    private static final float FAST_WINDOW_SECONDS = 0.20F;
    private static final float TICK_DISTANCE = 0.35F;
    private static final float TICK_SECONDS = 0.12F;
    private static final float FAST_SETTLE_SPEEDUP = 3.0F;

    private final RubyDung game;

    private Tile shown;

    private float offset = 0.0F;

    private float tick = 0.0F;

    private boolean settling = false;

    private long lastNanos = 0L;
    private long lastChangeNanos = 0L;

    public SelectedTileHud(RubyDung game) {
        this.game = game;
        setAnchor(Anchor.BOTTOM_LEFT, EDGE_MARGIN, -EDGE_MARGIN);
    }

    private static String tileName(Tile tile) {
        if (tile instanceof TreeTile) return "Tree";
        if (tile instanceof DimensionalDoorTile) return "Dimensional Door";
        if (tile instanceof DiceTile || tile instanceof ColoredDiceTile) return "Dice";
        String group = GROUP_NAMES.get(tile);
        if (group != null) return group;
        return formatTileName(tile.name);
    }

    private static String typeValue(Tile tile) {
        if (tile instanceof TreeTile) {
            return ((TreeTile) tile).getType().displayName;
        }
        if (tile instanceof DimensionalDoorTile) {
            return ((DimensionalDoorTile) tile).getColor().getDisplayName();
        }
        if (tile instanceof DiceTile) {
            return "White";
        }
        if (tile instanceof ColoredDiceTile) {
            return ((ColoredDiceTile) tile).getColor().displayName;
        }
        return GROUP_TYPES.get(tile);
    }

    private static int textLines(Tile tile) {
        return typeValue(tile) == null ? 1 : 2;
    }

    // a ragdoll picked in the inventory is selected instead of the tile (model 0, the one the place button spawns)
    private static final String RAGDOLL_NAME = "Ragdoll";
    private static final float RAGDOLL_HEIGHT = ROW_HEIGHT;
    private static final float RAGDOLL_SPIN_DEGREES_PER_SECOND = 40.0F;

    private static boolean ragdollSelected(RubyDung game) {
        return game.getPlayerInteraction() != null && game.getPlayerInteraction().isRagdollSelected();
    }

    @Override
    protected float naturalWidth(Font font) {
        if (ragdollSelected(this.game)) {
            return Math.max(font.getWidth("Selected: " + RAGDOLL_NAME) * SCALE, SPACING * 3);
        }
        Tile tile = this.game.getSelectedTile();
        float widest = font.getWidth("Selected: " + tileName(tile)) * SCALE;
        String type = typeValue(tile);
        if (type != null) {
            widest = Math.max(widest, font.getWidth("Type: " + type) * SCALE);
        }
        return Math.max(widest, SPACING * 3);
    }

    @Override
    protected float naturalHeight(Font font) {
        return heightFor(this.game);
    }

    /** heightFor(tile), or one text line plus the icon row while a ragdoll is selected. the chat hud asks this one */
    public static float heightFor(RubyDung game) {
        if (ragdollSelected(game)) {
            return LINE_STEP + TEXT_ICON_GAP + ROW_HEIGHT;
        }
        return heightFor(game.getSelectedTile());
    }

    /**
     * how tall this hud is while the given tile is selected: one text line, or two when the tile has a "Type:" line,
     * then the icon row. other widgets stacked above it (the chat hud) ask this instead of guessing, so the two can
     * never overlap whichever tile is selected
     */
    public static float heightFor(Tile tile) {
        return textLines(tile) * LINE_STEP + TEXT_ICON_GAP + ROW_HEIGHT;
    }

    /** the gap this hud keeps from the window's left and bottom edges */
    public static final float EDGE_MARGIN = 10.0F;

    @Override
    protected void draw(Font font, int mouseX, int mouseY) {
        Tile tile = this.game.getSelectedTile();
        List<Tile> order = cycleOrder(tile);

        advance(tile, order);

        int left = (int) this.x;
        int top = (int) this.y;

        if (ragdollSelected(this.game)) {
            String prefix = "Selected: ";
            font.drawStringScaled(prefix, left, top, 0xFFFFFF, SCALE);
            font.drawStringScaled(RAGDOLL_NAME, left + (int) (font.getWidth(prefix) * SCALE), top, 0xFFFF00, SCALE);

            float middle = top + LINE_STEP + TEXT_ICON_GAP + ROW_HEIGHT / 2.0F;
            float spin = System.nanoTime() / 1.0e9F * RAGDOLL_SPIN_DEGREES_PER_SECOND;
            RagdollRenderer.drawIcon(0, left + SPACING * 1.5F, middle, RAGDOLL_HEIGHT,
                    (int) getRootWidth(), (int) getRootHeight(), spin % 360.0F, 1.0F);
            return;
        }

        drawText(font, tile, left, top);

        float rowMiddle = top + textLines(tile) * LINE_STEP + TEXT_ICON_GAP + ROW_HEIGHT / 2.0F;
        float centerX = left + SPACING * 1.5F;

        drawRow(tile, order, centerX, rowMiddle);
    }

    private void advance(Tile tile, List<Tile> order) {
        long now = System.nanoTime();
        float dt = this.lastNanos == 0L ? 0.0F : Math.min(MAX_FRAME_SECONDS, (now - this.lastNanos) / 1.0e9F);
        this.lastNanos = now;

        if (this.shown == null) {
            this.shown = tile;
        } else if (tile != this.shown) {
            int from = order.indexOf(this.shown);
            int to = order.indexOf(tile);
            int size = order.size();

            if (from >= 0 && to >= 0 && size > 1) {
                int forward = Math.floorMod(to - from, size);
                int steps = forward <= size / 2 ? forward : forward - size;

                boolean fast = this.lastChangeNanos != 0L
                        && (now - this.lastChangeNanos) / 1.0e9F < FAST_WINDOW_SECONDS;
                if (fast) {
                    this.tick = steps > 0 ? TICK_DISTANCE : -TICK_DISTANCE;
                    this.settling = true;
                } else {
                    this.offset = Math.max(-1.0F, Math.min(1.0F, steps));
                    this.settling = false;
                }
            } else {
                this.offset = 0.0F;
            }
            this.shown = tile;
            this.lastChangeNanos = now;
        }

        float step = dt / ANIM_SECONDS * (this.settling ? FAST_SETTLE_SPEEDUP : 1.0F);
        if (this.offset > 0.0F) {
            this.offset = Math.max(0.0F, this.offset - step);
        } else if (this.offset < 0.0F) {
            this.offset = Math.min(0.0F, this.offset + step);
        }

        float tickStep = dt / TICK_SECONDS * TICK_DISTANCE;
        if (this.tick > 0.0F) {
            this.tick = Math.max(0.0F, this.tick - tickStep);
        } else if (this.tick < 0.0F) {
            this.tick = Math.min(0.0F, this.tick + tickStep);
        }

        if (this.offset == 0.0F && this.tick == 0.0F) {
            this.settling = false;
        }
    }

    private float steppedOffset() {
        float total = this.offset + this.tick;
        float magnitude = (float) Math.ceil(Math.abs(total) * STEPS - 1.0e-4F) / STEPS;
        return Math.copySign(magnitude, total);
    }

    private void drawRow(Tile tile, List<Tile> order, float centerX, float middleY) {
        int index = order.indexOf(tile);
        int count = order.size();
        if (index < 0) {
            return;
        }

        float shift = steppedOffset();

        List<Integer> slots = new ArrayList<>();
        for (int k = -2; k <= 2; k++) {
            if (k == 0 || Math.abs(k) < count) {
                slots.add(k);
            }
        }
        slots.sort((a, b) -> Float.compare(Math.abs(b + shift), Math.abs(a + shift)));

        for (int k : slots) {
            float distance = k + shift;
            float away = Math.abs(distance);
            if (away >= 2.0F) {
                continue;
            }

            float size;
            float darken;
            if (away <= 1.0F) {
                size = lerp(CURRENT_ICON, SIDE_ICON, away);
                darken = SIDE_DARKEN * away;
            } else {
                size = SIDE_ICON * (2.0F - away);
                darken = SIDE_DARKEN;
            }
            if (size < 2.0F) {
                continue;
            }

            Tile icon = order.get(Math.floorMod(index + k, count));
            TileIconRenderer.draw(icon, this.game.getLevel(),
                    centerX + distance * SPACING, middleY, size,
                    (int) getRootWidth(), (int) getRootHeight(), darken);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void drawText(Font font, Tile tile, int left, int top) {
        String prefix = "Selected: ";
        font.drawStringScaled(prefix, left, top, 0xFFFFFF, SCALE);
        int prefixWidth = (int) (font.getWidth(prefix) * SCALE);
        font.drawStringScaled(tileName(tile), left + prefixWidth, top, 0xFFFF00, SCALE);

        String type = typeValue(tile);
        if (type != null) {
            String typePrefix = "Type: ";
            int secondLineY = top + (int) LINE_STEP;
            font.drawStringScaled(typePrefix, left, secondLineY, 0xFFFFFF, SCALE);
            int typePrefixWidth = (int) (font.getWidth(typePrefix) * SCALE);
            font.drawStringScaled(type, left + typePrefixWidth, secondLineY, 0xFFFF00, SCALE);
        }
    }

    // the tiles the wheel steps through from this one: the gadgets or the rest, never both (see Tile.getCycleGroup)
    private static List<Tile> cycleOrder(Tile selected) {
        return new ArrayList<>(Tile.getCycleGroup(selected));
    }

    private static String formatTileName(String rawName) {
        if (rawName == null || rawName.isEmpty()) {
            return "";
        }

        String[] words = rawName.split("_");
        StringBuilder formattedName = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (!word.isEmpty()) {
                formattedName.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase());

                if (i < words.length - 1) {
                    formattedName.append(" ");
                }
            }
        }

        return formattedName.toString();
    }
}