package com.nikohalfkino.rubydung.entity.piece;

import com.nikohalfkino.rubydung.entity.EntityType;
import com.nikohalfkino.rubydung.entity.WorldEntity;

/**
 * a piece that stands on a square of the level and glides from one square to the next: a camera-facing sprite that does no physics. its position is the middle of its square at the feet, its hull is the hitbox of the piece (hullW wide and deep, hullH high), which can differ from the sprite that is drawn. the sizes come from its EntityType
 *
 * nothing pushes it or collides with it. what a piece is (a chess piece) and what its code means is in the subclass
 */
public abstract class MovablePiece extends WorldEntity {

    /** size of the sprite drawn and of the hitbox, in blocks */
    public final double spriteW, spriteH, hullW, hullH;

    /** gliding to another square */
    public boolean gliding;
    private double glideFromX, glideFromZ, glideToX, glideToZ;
    private int glideTick, glideTicks;

    protected MovablePiece(EntityType type, double x, double y, double z) {
        super(type, x, y, z);
        this.spriteW = type.getSpriteWidth();
        this.spriteH = type.getSpriteHeight();
        this.hullW = type.getHullWidth();
        this.hullH = type.getHullHeight();
    }

    @Override
    public boolean isPiece() {
        return true;
    }

    // ---------------------------------------------------------------- hull: x, z the middle of the feet, the hull stands on y

    @Override
    public double minX() { return this.x - this.hullW * 0.5D; }

    @Override
    public double maxX() { return this.x + this.hullW * 0.5D; }

    @Override
    public double minY() { return this.y; }

    @Override
    public double maxY() { return this.y + this.hullH; }

    @Override
    public double minZ() { return this.z - this.hullW * 0.5D; }

    @Override
    public double maxZ() { return this.z + this.hullW * 0.5D; }

    // ---------------------------------------------------------------- the square

    /** the square the piece stands on, or is gliding to (the one it will occupy), as block cell coordinates */
    public int pieceCellX() {
        return (int) Math.floor(this.hasNet ? this.netX : this.gliding ? this.glideToX : this.x);
    }

    public int pieceCellZ() {
        return (int) Math.floor(this.hasNet ? this.netZ : this.gliding ? this.glideToZ : this.z);
    }

    /** the cell of the floor level (the feet may hang a little below it on a lowered tile) */
    public int pieceCellY() {
        return (int) Math.ceil((this.hasNet ? this.netY : this.y) - 1.0E-4D);
    }

    // ---------------------------------------------------------------- gliding

    /** called when it sets off to another square (a chess piece notes that it has moved) */
    protected void onGlideStart() {
    }

    /** sets off to the middle of another square, a longer way takes longer. the piece is already there as far as pieceCellX / Z are concerned */
    public void glideTo(double toX, double toZ) {
        this.glideFromX = this.x;
        this.glideFromZ = this.z;
        this.glideToX = toX;
        this.glideToZ = toZ;
        double distance = Math.sqrt((toX - this.x) * (toX - this.x) + (toZ - this.z) * (toZ - this.z));
        this.glideTick = 0;
        this.glideTicks = (int) Math.max(1, Math.min(36, 10 + distance * 4.0D));
        this.gliding = distance > 1.0E-6D;
        onGlideStart();
    }

    /** one tick of a piece the server (or this machine) runs: no physics, the glide eases in and out, and the speed is kept for the clients to carry the motion on between two updates */
    public void tickPiece() {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;

        if (!this.gliding) {
            this.vx = this.vz = 0.0D;
            return;
        }

        this.glideTick++;
        double t = Math.min(1.0D, this.glideTick / (double) this.glideTicks);
        double e = t * t * (3.0D - 2.0D * t);
        double rate = 6.0D * t * (1.0D - t) / (this.glideTicks * DT);   // d(e)/d(seconds)
        this.x = this.glideFromX + (this.glideToX - this.glideFromX) * e;
        this.z = this.glideFromZ + (this.glideToZ - this.glideFromZ) * e;
        this.vx = (this.glideToX - this.glideFromX) * rate;
        this.vz = (this.glideToZ - this.glideFromZ) * rate;

        if (this.glideTick >= this.glideTicks) {
            this.gliding = false;
            this.x = this.glideToX;
            this.z = this.glideToZ;
            this.vx = this.vz = 0.0D;
        }
    }
}
