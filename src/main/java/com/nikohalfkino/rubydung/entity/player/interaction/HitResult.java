package com.nikohalfkino.rubydung.entity.player.interaction;

import com.nikohalfkino.rubydung.entity.phys.AABB;

public class HitResult {

    public int x;
    public int y;
    public int z;

    public int type;
    public int face;

    /**
     * the cell the ray actually entered. equal to x/y/z for ordinary tiles, for a multi-tile object x/y/z is the origin (so breaking or highlighting acts on the whole object)
     *
     * and this is the cell that was hit (so placing against the face lands right)
     */
    public int cellX;
    public int cellY;
    public int cellZ;

    /** exact point the ray struck, world coordinates. only set (hasPoint) when the picker produced the hit, a plain cell-stepping hit leaves these at 0 */
    public boolean hasPoint;
    public double hitX;
    public double hitY;
    public double hitZ;

    /** part of the tile that was struck, world coordinates: the whole cell for a cube, the slab itself, one step of a staircase. null when unknown (the overlay falls back to the tile's outline box) */
    public AABB hitBox;

    /** the ray struck the liquid in this cell, not a block (only when the player holds that liquid, see LiquidRaycaster). x/y/z are the cell, breaking it clears the liquid */
    public boolean liquid;

    /** which SubTile of the cell was struck (SubTile.id), -1 for the cell's normal tile. x/y/z are the cell either way */
    public int overlayId = -1;

    /** records the exact hit point and the box struck, returns this */
    public HitResult withPoint(double hitX, double hitY, double hitZ, AABB hitBox) {
        this.hasPoint = true;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
        this.hitBox = hitBox;
        return this;
    }

    public HitResult(int x, int y, int z, int type, int face) {
        this(x, y, z, type, face, x, y, z);
    }

    public HitResult(int x, int y, int z, int type, int face, int cellX, int cellY, int cellZ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.face = face;
        this.cellX = cellX;
        this.cellY = cellY;
        this.cellZ = cellZ;
    }
}
