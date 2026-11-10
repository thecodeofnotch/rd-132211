package com.nikohalfkino.rubydung.level.tiles.common;

/**
 * a tile that can lie along any axis, the data byte is the axis: 0 = Y (standing), 1 = Z, 2 = X
 *
 * textures and the axis -> orientation table are in the blockstate file (usually extending axis_column.toml), the geometry is rotated when the model is baked
 */
public class RotatableTile extends ModelTile {

    public RotatableTile(String name) {
        super(name);
    }

    public boolean isDirectional() {
        return true;
    }
}
