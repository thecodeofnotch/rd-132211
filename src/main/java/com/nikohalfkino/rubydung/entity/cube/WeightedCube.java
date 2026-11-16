package com.nikohalfkino.rubydung.entity.cube;

import com.nikohalfkino.rubydung.entity.EntityTypes;

/** the plain weighted cube: a PhysCube with nothing added. its piece code is -1 */
public final class WeightedCube extends PhysCube {

    public static final int CODE = -1;

    public WeightedCube(double x, double y, double z) {
        super(EntityTypes.WEIGHTED_CUBE, x, y, z);
    }

    @Override
    public int pieceCode() {
        return CODE;
    }

    @Override
    public void applyPieceCode(int code) {
        // nothing to remember: a plain cube is the same whatever the server says
    }
}
