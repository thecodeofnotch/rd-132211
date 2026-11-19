package com.nikohalfkino.rubydung.level.tiles.special;

import com.nikohalfkino.rubydung.level.tiles.Tile;

/**
 * the weighted cube in the tile selector. it is never stored in the level: placing it spawns a CubeEntity (see BlockPlacer.placeCube), so the tile exists only for the inventory, the hotbar and the hand, which draw it like any other block from its texture
 */
public class CubeTile extends Tile {

    public static final String NAME = "weighted_cube";

    private static final String TEXTURE = "weighted_cube";

    public CubeTile() {
        super(NAME, TEXTURE);
    }
}
