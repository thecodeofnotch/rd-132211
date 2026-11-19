package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.entity.ragdoll.RagdollRig;
import com.nikohalfkino.rubydung.interfaces.FramePanelScreen;
import com.nikohalfkino.rubydung.interfaces.GameFrame;
import com.nikohalfkino.rubydung.interfaces.widget.PanelSection;
import com.nikohalfkino.rubydung.interfaces.TileIconRenderer;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.SlotGrid;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.render.RagdollRenderer;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.level.tiles.special.CubeTile;
import com.nikohalfkino.rubydung.level.tiles.special.DiceTile;
import com.nikohalfkino.rubydung.level.tiles.special.TreeTile;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * while open, GameFrame shrinks the world view and slides the panel in (being a Screen, the camera gets no mouse input)
 *
 * three tabs, switched with the buttons in the bar under the world view:
 * "Tiles": "Palette" (ordinary tiles) and "Props" (billboarded tiles)
 * "Gadgets": "Tools" (empty for now) and "Gizmos" (the weighted cube, the dice in three colours and the chess pieces)
 * "Ragdolls": "Bodies" (every ragdoll model, see RagdollRig). a ragdoll is not a tile: choosing one tells PlayerInteraction to spawn it with the place button, choosing a tile again puts it away
 *
 * each section is a SlotGrid pinned to its panel section every frame. the panel shows the sections of the current tab only
 *
 * add another group with another addGrid(...) call in the constructor, another tab with another Tab constant
 */
public class InventoryScreen extends FramePanelScreen {

    private static final float LABEL_SCALE = 2.0F;
    private static final int LABEL_MARGIN = -2;
    private static final int LABEL_COLOR = 0xFFFFFF;

    // real pixels between the tab buttons
    private static final float TAB_GAP = 8.0F;

    // a ragdoll's slot icon: how tall (in icon sizes) and how far turned (degrees)
    private static final float ICON_RAGDOLL_HEIGHT = 1.7F;
    private static final float ICON_RAGDOLL_SPIN = 25.0F;

    // an empty grid (Tools) is this many slots wide and one row tall
    private static final int EMPTY_COLUMNS = 4;

    private enum Tab {
        TILES("Tiles"),
        GADGETS("Gadgets"),
        RAGDOLLS("Ragdolls");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    // one section of the panel
    private static final class Section {
        final String title;
        final int columns;
        final int rows;
        final SlotGrid<?> grid;

        Section(String title, int columns, int rows, SlotGrid<?> grid) {
            this.title = title;
            this.columns = columns;
            this.rows = rows;
            this.grid = grid;
        }
    }

    private final EnumMap<Tab, List<Section>> sections = new EnumMap<>(Tab.class);
    private final EnumMap<Tab, Button> tabButtons = new EnumMap<>(Tab.class);
    private Tab tab = Tab.TILES;

    // last mouse position seen by render(), so wheel scrolling hits the grid under the cursor
    private int lastMouseX = -1;
    private int lastMouseY = -1;

    public InventoryScreen(RubyDung game) {
        super(game);

        for (Tab t : Tab.values()) {
            this.sections.put(t, new ArrayList<>());
        }

        // Only the placeable tiles (Tile.getTiles() would also include AIR).
        List<Tile> tiles = new ArrayList<>();
        List<Tile> props = new ArrayList<>();
        List<Tile> gizmos = new ArrayList<>();
        for (Tile tile : Tile.getCycleOrder()) {
            if (isGizmo(tile)) {
                gizmos.add(tile);
            } else {
                (tile.isBillboard() ? props : tiles).add(tile);
            }
        }

        addGrid(Tab.TILES, "Palette", tiles);
        addGrid(Tab.TILES, "Props", props);

        // Nothing goes in Tools yet, it stays as an empty panel.
        addEmptyGrid(Tab.GADGETS, "Tools");
        addGrid(Tab.GADGETS, "Gizmos", gizmos);

        List<Integer> bodies = new ArrayList<>();
        for (int i = 0; i < RagdollRig.count(); i++) {
            bodies.add(i);   // the model's number (see RagdollRig)
        }
        addRagdollGrid(Tab.RAGDOLLS, "Bodies", bodies);

        for (Tab t : Tab.values()) {
            Button button = add(new Button(t.label, 0.0F, 0.0F, true).onClick(() -> setTab(t)),
                    Anchor.TOP_LEFT, 0, 0);
            this.tabButtons.put(t, button);
        }

        addCornerButton("Done", this.game::closeScreen);
        setTab(Tab.TILES);
    }

    /** the weighted cube, the dice and the chess pieces: things that are spawned as entities instead of being built into the level */
    private static boolean isGizmo(Tile tile) {
        return Tile.isGadget(tile);
    }

    private SlotGrid<Tile> newGrid(List<Tile> items) {
        return new SlotGrid<Tile>()
                .setItems(items)
                .selectedWhen(tile -> !this.game.getPlayerInteraction().isRagdollSelected() && tile == this.game.getSelectedTile())
                .onSelect(tile -> this.game.getPlayerInteraction().setSelectedTile(tile))
                .setIconDrawer((tile, cx, cy, size, w, h) ->
                        TileIconRenderer.draw(tile, this.game.getLevel(), cx, cy, size, w, h))
                .setOverlayDrawer(InventoryScreen::drawSizeLabel);
    }

    private void addGrid(Tab target, String title, List<Tile> items) {
        if (items.isEmpty()) {
            return;
        }

        // The smallest square that holds every item, at most the full-size grid (then it scrolls).
        int side = Math.min(GameFrame.GRID_ROWS, (int) Math.ceil(Math.sqrt(items.size())));

        this.sections.get(target).add(new Section(title, side, side, add(newGrid(items), Anchor.TOP_LEFT, 0, 0)));
    }

    // a ragdoll is no Tile, so its grid holds the numbers of the models. spawning always uses the first model for now (CubeManager.spawnIn takes a kind, not a model)
    private void addRagdollGrid(Tab target, String title, List<Integer> items) {
        if (items.isEmpty()) {
            return;
        }
        int side = Math.min(GameFrame.GRID_ROWS, (int) Math.ceil(Math.sqrt(items.size())));

        SlotGrid<Integer> grid = new SlotGrid<Integer>()
                .setItems(items)
                .selectedWhen(model -> this.game.getPlayerInteraction().isRagdollSelected())
                .onSelect(model -> this.game.getPlayerInteraction().setRagdollSelected(true))
                // the real model, a little turned so it is not flat on. the icon size is the cube scale, a person is taller than a cube
                .setIconDrawer((model, cx, cy, size, w, h) ->
                        RagdollRenderer.drawIcon(model, cx, cy, size * ICON_RAGDOLL_HEIGHT, w, h, ICON_RAGDOLL_SPIN, 1.0F));
        this.sections.get(target).add(new Section(title, side, side, add(grid, Anchor.TOP_LEFT, 0, 0)));
    }

    private void addEmptyGrid(Tab target, String title) {
        this.sections.get(target).add(new Section(title, EMPTY_COLUMNS, 1,
                add(newGrid(new ArrayList<>()), Anchor.TOP_LEFT, 0, 0)));
    }

    private List<Section> currentSections() {
        return this.sections.get(this.tab);
    }

    private void setTab(Tab newTab) {
        this.tab = newTab;
        for (Tab t : Tab.values()) {
            this.tabButtons.get(t).setSelected(t == newTab);
            // Hidden now, placeWidgets shows the current tab's grids again on the next frame.
            for (Section group : this.sections.get(t)) {
                group.grid.setVisible(t == newTab);
            }
        }
    }

    /** scrolls the grid under the mouse (the first one if none), positive = down */
    public void scroll(int rows) {
        List<Section> current = currentSections();
        for (Section group : current) {
            if (group.grid.contains(this.lastMouseX, this.lastMouseY)) {
                group.grid.scroll(rows);
                return;
            }
        }
        if (!current.isEmpty()) {
            current.get(0).grid.scroll(rows);
        }
    }

    @Override
    public boolean showsSlots() {
        return true;
    }

    @Override
    public String getPanelTitle() {
        return this.tab.label;
    }

    @Override
    public int getFrameTint() {
        return 0xFFFFFF;
    }

    /** the current tab's sections top to bottom, each a box just big enough for its tiles (shrinks if the window is short) */
    @Override
    public List<PanelSection> getSections() {
        List<PanelSection> sections = new ArrayList<>();
        for (Section group : currentSections()) {
            sections.add(PanelSection.slots(group.title, group.columns, group.rows));
        }
        return sections;
    }

    @Override
    protected void placeWidgets() {
        // Tab buttons side by side under the world view, from its left edge.
        float width = (this.layout.viewWidth - TAB_GAP * (Tab.values().length - 1)) / Tab.values().length;
        width = Math.min(width, this.layout.panelWidth);
        float x = this.layout.viewX;
        for (Tab t : Tab.values()) {
            placeInBottomBar(this.tabButtons.get(t), x, width);
            x += width + TAB_GAP;
        }

        for (Tab t : Tab.values()) {
            List<Section> list = this.sections.get(t);
            for (int i = 0; i < list.size(); i++) {
                if (t == this.tab) {
                    placeInSection(list.get(i).grid, i);
                } else {
                    list.get(i).grid.setVisible(false);
                }
            }
        }
    }

    @Override
    public boolean keyPressed(int key, char character) {
        if (key == KeyBinding.INVENTORY.getKey() || key == Keyboard.KEY_ESCAPE) {
            this.game.closeScreen();
            return true;
        }
        return false;
    }

    @Override
    public void render(int mouseX, int mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        super.render(mouseX, mouseY);
    }

    // S / M / L in the slot's top-right corner, for tiles that have one
    private static void drawSizeLabel(Font font, Tile tile, int slotX, int slotY, int slotSize) {
        String label = sizeLabel(tile);
        if (label == null) {
            return;
        }

        int labelWidth = (int) (font.getWidth(label) * LABEL_SCALE);
        int x = slotX + slotSize - labelWidth - LABEL_MARGIN;
        int y = slotY + LABEL_MARGIN;
        font.drawStringScaled(label, x, y, LABEL_COLOR, LABEL_SCALE);
    }

    // "S", "M" or "L" for multi-size tiles, null otherwise. add other multi-size types here
    private static String sizeLabel(Tile tile) {
        if (tile instanceof TreeTile) {
            TreeTile.TreeType type = ((TreeTile) tile).getType();
            if (type == TreeTile.TreeType.SAPLING) {
                return "S";
            }
            if (type == TreeTile.TreeType.MEDIUM) {
                return "M";
            }
            if (type == TreeTile.TreeType.LARGE) {
                return "L";
            }
        }
        return null;
    }
}