package com.nikohalfkino.rubydung.level.tiles.common;

import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.render.Textures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * full block whose texture is a big sheet laid over the WORLD, not over each block: with a 64x48 texture (4 x 3 cells) every block shows the cell for its position, so the picture runs across neighbouring blocks and repeats every 4 blocks sideways and every 3 up
 *
 * side faces: the sheet runs along the wall (left to right as seen from outside) and up the Y axis, row 0 is the top, y = 0 is the bottom row. top / bottom faces: columns follow X, rows follow Z (north = top of the sheet)
 *
 * any size works if its sides are whole multiples of the cell size (see the slice notes in Textures). it's all decided at draw time from the coordinates, so nothing is stored in the world
 */
public class WrappedTile extends Tile {

    private final int columns;
    private final int rows;
    private final String[] slicePaths;
    private final int[] slots;

    public WrappedTile(String name) {
        this(name, name);
    }

    public WrappedTile(String name, String textureName) {
        super(name, textureName); // the faces start on the sheet's first slice (also the tile icon)

        String path = Tile.texturePath(textureName);
        int at = path.indexOf('@');
        String sheet = at < 0 ? path : path.substring(0, at);

        int[] grid = Textures.sliceGrid(sheet);
        this.columns = grid[0];
        this.rows = grid[1];
        this.slicePaths = new String[this.columns * this.rows];
        this.slots = new int[this.slicePaths.length];
        for (int row = 0; row < this.rows; row++) {
            for (int col = 0; col < this.columns; col++) {
                this.slicePaths[row * this.columns + col] =
                        this.slicePaths.length == 1 ? sheet : Textures.sliceName(sheet, col, row);
            }
        }
    }

    @Override
    public Collection<String> getExtraTextures() {
        List<String> paths = new ArrayList<>();
        for (String path : this.slicePaths) {
            paths.add(path);
        }
        return paths;
    }

    @Override
    public void onAtlasBuilt(Map<String, Integer> slotsByPath) {
        for (int i = 0; i < this.slicePaths.length; i++) {
            Integer slot = slotsByPath.get(this.slicePaths[i]);
            this.slots[i] = slot == null ? 0 : slot;
        }
    }

    @Override
    protected int getFaceAtlasSlot(Level level, int x, int y, int z, int face, int direction) {
        int col;
        int row;
        switch (face) {
            case FACE_TOP:
            case FACE_BOTTOM:
                col = Math.floorMod(x, this.columns);
                row = Math.floorMod(z, this.rows);
                break;
            default:
                // Seen from outside, "right" is -x on the north face, +x south, +z west, -z east.
                int along = face == FACE_NORTH ? -x : face == FACE_SOUTH ? x : face == FACE_WEST ? z : -z;
                col = Math.floorMod(along, this.columns);
                row = this.rows - 1 - Math.floorMod(y, this.rows);
                break;
        }
        return this.slots[row * this.columns + col];
    }
}
