package com.nikohalfkino.rubydung.level.tiles.special;

import com.nikohalfkino.rubydung.level.tiles.Tile;

/**
 * the dice in the tile selector. like the weighted cube (see CubeTile) it is never stored in the level: placing it spawns a dice entity (CubeManager.spawnIn(..., dice = true)), so the tile exists only for the inventory, the hotbar and the hand, which draw it like any other block from its textures
 *
 * one texture per face, textures/block/dice/dice1.png .. dice6.png, on the same faces the entity shows (see CubeEntity.upFaceValue): top 1, bottom 6, north 2, south 5, west 3, east 4 (opposite faces add up to 7)
 */
public class DiceTile extends Tile {

    public static final String NAME = "dice";

    // textures/block/dice/dice<n>.png
    private static final String TEXTURE_PREFIX = "dice/dice";

    public DiceTile() {
        super(NAME, TEXTURE_PREFIX + 1);
        setTexture(FACE_TOP, TEXTURE_PREFIX + 1);
        setTexture(FACE_BOTTOM, TEXTURE_PREFIX + 6);
        setTexture(FACE_NORTH, TEXTURE_PREFIX + 2);
        setTexture(FACE_SOUTH, TEXTURE_PREFIX + 5);
        setTexture(FACE_WEST, TEXTURE_PREFIX + 3);
        setTexture(FACE_EAST, TEXTURE_PREFIX + 4);
    }
}
