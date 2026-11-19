package com.nikohalfkino.rubydung.level.tiles.special;

import com.nikohalfkino.rubydung.entity.cube.Dice;
import com.nikohalfkino.rubydung.level.tiles.Tile;

/**
 * a see-through dice, the red one or the blue one (the white one is DiceTile). like it, it is not a block: placing it spawns a Dice of its colour (see BlockPlacer), the tile only stands for it in the inventory, the selector and the hand
 *
 * its six faces are the numbered textures of the dice, so the icon is the dice itself. the textures are semitransparent, hence setTransparent: the icon draws the faces behind the front ones too. which face has which number is the one CubeRenderer and Dice.upFaceValue use: +y 1, -y 6, -z 2, +z 5, -x 3, +x 4
 *
 * textures: textures/block/dice/dice1_red.png .. dice6_red.png, and _blue for the blue one
 */
public final class ColoredDiceTile extends Tile {

    private final Dice.Color color;

    public ColoredDiceTile(Dice.Color color) {
        super("dice_" + color.name().toLowerCase());
        this.color = color;
        setTransparent(true);
        setCastsShadow(false);
        setTexture(FACE_TOP, face(1));
        setTexture(FACE_BOTTOM, face(6));
        setTexture(FACE_NORTH, face(2));
        setTexture(FACE_SOUTH, face(5));
        setTexture(FACE_WEST, face(3));
        setTexture(FACE_EAST, face(4));
    }

    private String face(int number) {
        return "dice/dice" + number + this.color.textureSuffix;
    }

    public Dice.Color getColor() {
        return this.color;
    }
}
