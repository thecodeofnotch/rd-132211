package com.nikohalfkino.rubydung.entity.phys;

/**
 * what a ray found on a body that can be picked by part (a limb of a ragdoll): which part and where along it. filled by PhysEntity.raycastPart, read by CubeHold when the part is picked up
 *
 * a body of one part (a cube) answers part 0, frac 0.5
 */
public final class GrabPick {

    /** the part that was hit, 0 .. PhysEntity.partCount() - 1 */
    public int part;

    /** how far along the part (0..1) from its first end to its second one. 0.5 for a part without ends */
    public double frac = 0.5D;

    /** where the ray touched the part */
    public double hitX, hitY, hitZ;

    public void set(int part, double frac, double hitX, double hitY, double hitZ) {
        this.part = part;
        this.frac = frac;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
    }

    public void copyFrom(GrabPick other) {
        set(other.part, other.frac, other.hitX, other.hitY, other.hitZ);
    }

    public void clear() {
        set(0, 0.5D, 0.0D, 0.0D, 0.0D);
    }
}
