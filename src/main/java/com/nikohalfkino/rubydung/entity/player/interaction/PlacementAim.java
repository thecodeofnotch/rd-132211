package com.nikohalfkino.rubydung.entity.player.interaction;

/** where in the cell being placed into the player pointed (fractions 0..1, NaN = unknown) */
final class PlacementAim {

    double fx = Double.NaN;
    double fy = Double.NaN;
    double fz = Double.NaN;

    void aim(HitResult hit, int x, int y, int z) {
        if (hit.hasPoint) {
            this.fx = hit.hitX - x;
            this.fy = hit.hitY - y;
            this.fz = hit.hitZ - z;
        } else {
            clear();
        }
    }

    void clear() {
        this.fx = Double.NaN;
        this.fy = Double.NaN;
        this.fz = Double.NaN;
    }
}
