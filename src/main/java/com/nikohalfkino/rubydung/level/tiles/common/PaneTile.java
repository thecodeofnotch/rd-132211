package com.nikohalfkino.rubydung.level.tiles.common;

import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.SubTile;
import com.nikohalfkino.rubydung.render.Tessellator;

/**
 * thin, flat, see-through blocks that stand in the middle of a cell and extend vertically: metal bars, framed glass, ...
 *
 * to add one: a 16x48 texture (top / middle / bottom 16x16 slices), a blockstate with parent = "pane" and [textures] top / middle / bottom = "my_pane@0,N", and register(new PaneTile("my_pane")) in TileRegistry
 *
 * shape (flat plane, 2 px collision), facing and stacking live here and in blockstates/pane.toml. facing north/south = a plane running east-west, east/west = north-south
 *
 * only the facing is stored in the world, the "part" is worked out when drawing from the panes of the same plane above, so nothing needs updating when neighbours change: 1 pane = top, 2 = top + middle, 3+ = top, middle ... middle, bottom
 *
 * panes may cross slabs (see canIntersect)
 */
public class PaneTile extends FacingTile {

    private static final String[] PARTS = {"top", "middle", "bottom"};
    private static final int TOP = 0, MIDDLE = 1, BOTTOM = 2;

    // data byte of every (facing, part) combination, for the model state to draw with
    private final int[][] stateData = new int[NAMES.length][PARTS.length];

    public PaneTile(String name) {
        super(name);
        setTransparent(true);   // see-through: lets light and neighbours' faces through
        for (int facing = 0; facing < NAMES.length; facing++) {
            for (int part = 0; part < PARTS.length; part++) {
                this.stateData[facing][part] = dataOf("facing", NAMES[facing], "part", PARTS[part]);
            }
        }
    }

    // against a side face the pane faces away from that wall (as any FacingTile), a top/bottom click says nothing about direction so the pointer's spot in the cell decides
    // near the east/west edge = north-south, near the north/south edge = east-west, middle = east-west (default). a call with the look direction overrides all this (see FacingTile)
    @Override
    public int getPlacementData(int face, double fractionX, double fractionY, double fractionZ) {
        if ((face == FACE_TOP || face == FACE_BOTTOM)
                && !Double.isNaN(fractionX) && !Double.isNaN(fractionZ)) {
            double fromEastWestEdge = Math.abs(fractionX - 0.5);
            double fromNorthSouthEdge = Math.abs(fractionZ - 0.5);
            return stateData[fromEastWestEdge > fromNorthSouthEdge ? EAST : NORTH][TOP];
        }
        return super.getPlacementData(face, fractionX, fractionY, fractionZ);
    }

    /** slabs pass through panes (and panes through slabs) */
    @Override
    public boolean canIntersect(Tile other) {
        return other instanceof SlabTile;
    }

    @Override
    public void render(Tessellator tessellator, Level level, int layer, int x, int y, int z,
                       int totalAtlasSlots, int data) {
        int facing = facingOf(data);

        int part;
        if (!connects(level, x, y + 1, z, facing)) {
            part = TOP;                                   // nothing above: top (a lone pane too)
        } else if (!connects(level, x, y - 1, z, facing)) {
            // The bottom of a stack: "bottom" from height 3 on, "middle" at height 2.
            part = connects(level, x, y + 2, z, facing) ? BOTTOM : MIDDLE;
        } else {
            part = MIDDLE;
        }

        super.render(tessellator, level, layer, x, y, z, totalAtlasSlots, this.stateData[facing][part]);
    }

    // whether the cell holds this pane in the same plane (as its tile or as a tile sharing the cell)
    private boolean connects(Level level, int x, int y, int z, int facing) {
        int plane = facing % 2;
        if (level.getTile(x, y, z) == this.id && facingOf(level.getData(x, y, z)) % 2 == plane) {
            return true;
        }
        if (level.getSubTiles().has(x, y, z)) {
            for (SubTile sub : level.getSubTiles().at(x, y, z)) {
                if (sub.tileId == this.id && facingOf(SubTile.baseData(sub.data)) % 2 == plane) {
                    return true;
                }
            }
        }
        return false;
    }
}
