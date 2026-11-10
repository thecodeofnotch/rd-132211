package com.nikohalfkino.rubydung.level.tiles.common;

import com.nikohalfkino.rubydung.player.HitResult;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * half-height block with a "half" property (bottom / top / both), see blockstates/NAME.toml
 *
 * placed against any face: the half comes from where the player pointed (above/below the midpoint for side faces, forced top/bottom for the top/bottom faces)
 *
 * clicking the exposed face of a half slab of the same type (top of a bottom slab, bottom of a top slab), or placing the complementary half from a side into a cell with a single slab, upgrades it to "both"
 *
 * breaking a double slab only removes the half the player aimed at
 */
public class SlabTile extends ModelTile {

    // half the tile icon shows (null = state 0)
    private String iconHalf = null;

    public SlabTile(String name) {
        super(name);
    }

    /** "bottom", "top" or "both" */
    public SlabTile setIconHalf(String half) {
        this.iconHalf = half;
        return this;
    }

    /** data byte of the bottom or top half */
    public int halfData(boolean top) {
        return dataOf("half", top ? "top" : "bottom");
    }

    /** a slab is the half-block shape itself, it never clips into another slab's free half */
    @Override
    public boolean canClipToHalf() {
        return false;
    }

    @Override
    public int getIconData() {
        return this.iconHalf == null ? 0 : dataOf("half", this.iconHalf);
    }

    @Override
    public int getPlacementData(int face) {
        return getPlacementData(face, Double.NaN, Double.NaN, Double.NaN);
    }

    /**
     * against the underside of a block -> top slab
     *
     * against the top face -> bottom slab
     *
     * against a side face -> whichever half the player pointed at (fractionY >= 0.5 = top)
     */
    @Override
    public int getPlacementData(int face, double fractionX, double fractionY, double fractionZ) {
        boolean top;
        if (face == FACE_BOTTOM) {
            top = true;
        } else if (face == FACE_TOP || Double.isNaN(fractionY)) {
            top = false;
        } else {
            top = fractionY >= 0.5;
        }
        return dataOf("half", top ? "top" : "bottom");
    }

    // merge on placement

    /**
     * in-place upgrade (the hit cell IS the slab cell): clicking the exposed face of a half slab (top of a bottom slab, bottom of a top slab) upgrades it to "both" regardless of fractionY, the intent is unambiguous
     *
     * adjacent-cell merge: the complementary half placed into a cell that already holds one slab half, fractionY / face say which half was requested, then complementarity is checked
     */
    @Override
    public boolean tryMergePlacement(Level level, int x, int y, int z,
                                     int face, double fractionX, double fractionY, double fractionZ) {
        if (level.getTile(x, y, z) != this.id) {
            return false;
        }

        int existingData = level.getData(x, y, z);
        int bothData     = dataOf("half", "both");
        int bottomData   = dataOf("half", "bottom");
        int topData      = dataOf("half", "top");

        // Already a double slab — nothing to merge.
        if (existingData == bothData) {
            return false;
        }

        // Every merge fills the cell: not possible while another tile is sharing the free half.
        if (!level.canSetHost(x, y, z, this, bothData)) {
            return false;
        }

        // In-place upgrade: player clicked the exposed face of the half slab directly.
        if (existingData == bottomData && face == FACE_TOP) {
            level.setTile(x, y, z, this.id, bothData);
            return true;
        }
        if (existingData == topData && face == FACE_BOTTOM) {
            level.setTile(x, y, z, this.id, bothData);
            return true;
        }

        // Adjacent merge: the half requested (from fractionY / face) is complementary.
        int newData = getPlacementData(face, fractionX, fractionY, fractionZ);
        boolean complementary =
                (existingData == bottomData && newData == topData) ||
                        (existingData == topData    && newData == bottomData);

        if (complementary) {
            level.setTile(x, y, z, this.id, bothData);
            return true;
        }

        return false;
    }

    /**
     * full-hit entry point, order matters: first the clicked cell itself (aiming at the exposed face of a half slab completes it into a double slab), otherwise the cell next to the clicked face (side-click merge)
     */
    @Override
    public boolean tryMergePlacement(Level level, HitResult hit,
                                     double fractionX, double fractionY, double fractionZ) {
        // Only the exposed half-face upgrades in place, exactly like vanilla's canBeReplaced:
        //   top face of a BOTTOM slab, underside of a TOP slab.
        // Any other click on the slab (top of a top slab, underside of a bottom slab, a side) is a
        // click on the outside of the cell and places NEXT to it, so it must not fill this cell.
        if ((hit.face == FACE_TOP || hit.face == FACE_BOTTOM)
                && hit.overlayId < 0          // the cell's own slab was clicked, not a tile sharing the cell
                && level.getTile(hit.cellX, hit.cellY, hit.cellZ) == this.id) {
            int existing = level.getData(hit.cellX, hit.cellY, hit.cellZ);
            int bothData = dataOf("half", "both");
            boolean exposed = (existing == dataOf("half", "bottom") && hit.face == FACE_TOP)
                    || (existing == dataOf("half", "top") && hit.face == FACE_BOTTOM);
            if (exposed && level.canSetHost(hit.cellX, hit.cellY, hit.cellZ, this, bothData)) {
                level.setTile(hit.cellX, hit.cellY, hit.cellZ, this.id, bothData);
                return true;
            }
        }
        return super.tryMergePlacement(level, hit, fractionX, fractionY, fractionZ);
    }

    // partial breaking

    /** a double slab loses only the aimed half, a single slab is removed entirely (returns -1) */
    @Override
    public int getBreakResult(Level level, int x, int y, int z, AABB hitBox) {
        int data     = level.getData(x, y, z);
        int bothData = dataOf("half", "both");

        if (data != bothData) {
            return -1; // single slab — remove normally
        }

        // The bottom pick-box has maxY == y+0.5; the top has minY == y+0.5.
        if (hitBox != null) {
            if (hitBox.maxY <= y + 0.5 + 1.0E-4) {
                return dataOf("half", "top");    // bottom struck → keep top
            } else {
                return dataOf("half", "bottom"); // top struck → keep bottom
            }
        }

        return -1; // no box info — remove both
    }

    // pick boxes

    /** a double slab has two independent half-height pick boxes so each half can be aimed at and broken */
    @Override
    public List<AABB> getPickBoxes(Level level, int x, int y, int z) {
        if (level.getData(x, y, z) == dataOf("half", "both")) {
            List<AABB> boxes = new ArrayList<>();
            boxes.add(new AABB(x, y,       z, x + 1, y + 0.5, z + 1)); // bottom half
            boxes.add(new AABB(x, y + 0.5, z, x + 1, y + 1,   z + 1)); // top half
            return boxes;
        }
        return super.getPickBoxes(level, x, y, z);
    }
}