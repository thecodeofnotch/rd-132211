package com.nikohalfkino.rubydung.entity.cube;

import com.nikohalfkino.rubydung.entity.phys.PhysEntity;

/** one axis-aligned obstacle for a PhysEntity; owner is the body it belongs to (a cube, or a limb of a ragdoll), null for the world and the players */
public final class CollisionBox {
    public double minX, minY, minZ, maxX, maxY, maxZ;
    public PhysEntity owner;
    /** a player's body (not the world or a cube): hitting it is heard. bodyId is the player's id, -1 = the player of this machine */
    public boolean body;
    public int bodyId = -1;

    public CollisionBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, PhysEntity owner) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.owner = owner;
    }

    public double min(int axis) {
        return axis == 0 ? this.minX : axis == 1 ? this.minY : this.minZ;
    }

    public double max(int axis) {
        return axis == 0 ? this.maxX : axis == 1 ? this.maxY : this.maxZ;
    }
}
