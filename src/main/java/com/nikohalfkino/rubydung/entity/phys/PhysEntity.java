package com.nikohalfkino.rubydung.entity.phys;

import com.nikohalfkino.rubydung.audio.BodySounds;
import com.nikohalfkino.rubydung.entity.EntityType;
import com.nikohalfkino.rubydung.entity.WorldEntity;
import com.nikohalfkino.rubydung.entity.cube.CollisionBox;

import java.util.List;

/**
 * a world entity with physics that the player can carry, throw and push, and that the manager steps once per tick. this is what the cube, the dice and the ragdoll have in common, and what the manager, the hold, the server and the client talk to: none of them needs to know how the body is built
 *
 * it holds the state that has nothing to do with the shape: who carries it (held, heldBy, remoteHeld, where the carried point should go), the flags that keep a let-go body from trapping the player, what it hit during its last tick (for the sounds) and the splash sound
 *
 * how the shape works is up to the subclass, through a small contract:
 *  - the hull (minX .. maxZ of Entity) is whatever the body says it is right now. a cube's is fixed, a ragdoll's follows its limbs. nothing may assume it stays the same size
 *  - parts: a body may be made of parts that are picked one at a time (partCount, raycastPart, beginGrab). a cube has one
 *  - the boxes it presents to the others (addObstacles) and to the walking player (addSolidBoxes). by default one box, the hull
 *  - the motion the others may ask for (pushHorizontal, setAxisSpeed, carryBy, lowerBy, throwWith, dropMotion)
 *
 * the defaults are the ones of a single rigid box, see PhysCube. a body made of particles starts from ParticleEntity instead
 */
public abstract class PhysEntity extends WorldEntity {

    // ---------------------------------------------------------------- state

    // angular velocity, radians per second about the world axes (a body that has no use for it ignores it)
    public double avx, avy, avz;

    /** what it hit during its last tick: a player's body (null = none) and how hard (blocks/s). set by tick(), read by CubeManager */
    public CollisionBox hitBody;
    public double hitSpeed;
    /** how hard it hit the world (a wall, the floor) or another body during its last tick (blocks/s), 0 = nothing. set by tick(), read by CubeManager */
    public double bumpSpeed;
    /** ticks until a bump is heard again */
    public int bumpCooldown;

    /** ticks until it may hit somebody audibly again (it rests against them, it should not rattle) */
    public int hitCooldown;

    /** the splash when it goes into water (see BodySounds, CubeManager.tick) */
    public final BodySounds sounds = new BodySounds();

    /** carried by the player: the grabbed point is pulled toward (targetX, targetY, targetZ), blocked by the world but not by the player */
    public boolean held;
    public double targetX, targetY, targetZ;
    /** the player's own velocity, added to the pull so the body keeps up when walking */
    public double followVx, followVy, followVz;

    /**
     * the player is not an obstacle (and the body is none for the player): set when it is let go of or thrown, until it has cleared the player. without it a body dropped onto the player's feet would trap them
     */
    public boolean ignorePlayer;

    // ---------------------------------------------------------------- multiplayer

    /** server: id of the connected player carrying it (see GameServer), -1 if none. a LAN host carrying it only sets held */
    public int heldBy = -1;

    /** client: somebody else carries it. it is not solid and cannot be picked up, the server moves it */
    public boolean remoteHeld;

    /** server: like ignorePlayer, for the other players' bodies */
    public boolean ignoreBodies;

    protected PhysEntity(EntityType type, double x, double y, double z) {
        super(type, x, y, z);
    }

    @Override
    protected boolean extrapolatesReplica() {
        return !this.remoteHeld;
    }

    // ---------------------------------------------------------------- the physics contract

    /**
     * one tick. obstacles are everything it may hit (world boxes, the other bodies, the player unless ignored), already collected for the region it can reach this tick, see reach()
     */
    public abstract void tick(List<CollisionBox> obstacles);

    /** how far (per axis, in blocks) the body can move in one tick at most, for collecting the obstacles */
    public abstract double reach();

    /** a player walks into it: it is set moving along one horizontal axis (0 = x, 2 = z) up to the given speed */
    public abstract void pushHorizontal(int axis, double speed);

    /** the speed of the body as a whole along an axis (0 = x, 1 = y, 2 = z) */
    public abstract double axisSpeed(int axis);

    /** sets the speed of the body as a whole along an axis */
    public abstract void setAxisSpeed(int axis, double value);

    /** moves the body by (dx, dy, dz), without touching its speed on purpose. returns how far it really went along y (what something standing on a carried body is taken along by) */
    public abstract double carryBy(List<CollisionBox> obstacles, double dx, double dy, double dz);

    /** the same along the ground only */
    public abstract void carryBy(List<CollisionBox> obstacles, double dx, double dz);

    /** puts the body back down by amount (it was carried up further than the stack on it could go) */
    public abstract void lowerBy(double amount);

    /** something shoves it (a body pushed into it) this tick: it is pressure and not an impact. only a body that tells the two apart needs this */
    public void markPushed() {
    }

    /** the carried part can not follow the hand because something holds the body back on purpose (a frozen part): the holder must not let go because of the distance */
    public boolean holdStrained() {
        return false;
    }

    /** it is in a liquid and buoyant enough not to sink */
    public boolean isFloating() {
        return false;
    }

    // ---------------------------------------------------------------- what the manager asks of the shape

    /** whether the manager pushes it with the walking player's box (PhysCube). a body that takes the player's box as an obstacle in its own tick does not want this too */
    public boolean pushedByWalking() {
        return true;
    }

    /** whether the camera turning turns the carried body with it (a cube keeps the same face toward the player, a limb does not) */
    public boolean turnsWithCamera() {
        return true;
    }

    /**
     * a body that is turned by hand about the grabbed point (not as a whole): the turn made since the last call as a unit quaternion w, x, y, z in out[0..3], which a client sends to the server so it can make the same turn. false (out untouched) if the body has no such turn
     */
    public boolean takeGrabTurn(double[] out) {
        return false;
    }

    /** the same turn made on the machine that steps the body: a quaternion about the world's axes, about the grabbed point */
    public void turnGrab(double qw, double qx, double qy, double qz) {
    }

    /**
     * freezes the part that is carried where it is in the air and lets go of it: it stays there, the rest hangs from it. false if the body can not be frozen (nothing is changed then). the caller does what it does for any let-go
     */
    public boolean freezeHeld() {
        return false;
    }

    /** every frozen part is free again */
    public void unfreezeAll() {
    }

    /** whether any part is frozen */
    public boolean hasFrozenParts() {
        return false;
    }

    /** whether a body sliding on the floor makes the drag sound */
    public boolean dragsOnFloor() {
        return true;
    }

    /** adds the boxes the player can bump into near region (the player's own collision, see CubeManager.addCollisionBoxes) */
    public void addSolidBoxes(AABB region, List<AABB> out) {
        if (overlaps(region.minX - 1.0D, region.minY - 1.0D, region.minZ - 1.0D,
                region.maxX + 1.0D, region.maxY + 1.0D, region.maxZ + 1.0D)) {
            out.add(new AABB(minX(), minY(), minZ(), maxX(), maxY(), maxZ()));
        }
    }

    /** adds the boxes the other bodies bump into when they move through region. owner of each is this body */
    public void addObstacles(AABB region, List<CollisionBox> out) {
        if (overlaps(region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ)) {
            out.add(new CollisionBox(minX(), minY(), minZ(), maxX(), maxY(), maxZ(), this));
        }
    }

    /** keeps the body inside the level, which reaches from 0 to limitX and from 0 to limitZ (the manager takes care of falling out at the bottom) */
    public void confine(double limitX, double limitZ) {
        double hx = (maxX() - minX()) * 0.5D;
        double hz = (maxZ() - minZ()) * 0.5D;
        if (this.x < hx) { this.x = hx; this.vx = Math.max(this.vx, 0.0D); }
        if (this.x > limitX - hx) { this.x = limitX - hx; this.vx = Math.min(this.vx, 0.0D); }
        if (this.z < hz) { this.z = hz; this.vz = Math.max(this.vz, 0.0D); }
        if (this.z > limitZ - hz) { this.z = limitZ - hz; this.vz = Math.min(this.vz, 0.0D); }
    }

    // ---------------------------------------------------------------- parts and grabbing

    /** how many parts can be picked one at a time. 1 = the body is picked as a whole */
    public int partCount() {
        return 1;
    }

    /**
     * distance along a ray (unit direction) at which it hits a part, or -1 if it misses or is farther than maxDistance. pick (if not null) gets the part and where along it. the default is the hull as one part
     */
    public double raycastPart(double ox, double oy, double oz, double dx, double dy, double dz,
                              double maxDistance, GrabPick pick) {
        double d = raycast(ox, oy, oz, dx, dy, dz, maxDistance);
        if (d >= 0.0D && pick != null) {
            pick.set(0, 0.5D, ox + dx * d, oy + dy * d, oz + dz * d);
        }
        return d;
    }

    /**
     * the player took hold of a part, after held is set. frac says where along the part (see GrabPick). from now on targetX/Y/Z is where the grabbed point should go. a rigid body is held by its middle whichever part it was, so it does nothing
     */
    public void beginGrab(int part, double frac) {
    }

    /** where the grabbed point is now: the middle of a rigid body */
    public double grabX() {
        return this.x;
    }

    public double grabY() {
        return this.y;
    }

    public double grabZ() {
        return this.z;
    }

    /** whether a body (the player's box) stands on top of it, so that carrying it would lift the player */
    public boolean isStoodOnBy(AABB body) {
        return body.minX < maxX() && body.maxX > minX()
                && body.minZ < maxZ() && body.maxZ > minZ()
                && body.minY < maxY() && body.minY > maxY() - 0.3D;
    }

    // ---------------------------------------------------------------- letting go

    /**
     * it was let go of (not thrown): it keeps the speed it really had, at most maxSpeed. what a held body has as its velocity is the pull toward where the player looks, which is not how fast it is going, so this goes by how far it moved in the last tick
     */
    public void dropMotion(double maxSpeed) {
        this.vx = (this.x - this.prevX) / DT;
        this.vy = (this.y - this.prevY) / DT;
        this.vz = (this.z - this.prevZ) / DT;
        double speed = speed();
        if (speed > maxSpeed) {
            double k = maxSpeed / speed;
            this.vx *= k;
            this.vy *= k;
            this.vz *= k;
        }
    }

    /** it was thrown: speed in blocks/s, spin in radians/s about the world axes */
    public void throwWith(double vx, double vy, double vz, double avx, double avy, double avz) {
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.avx = avx;
        this.avy = avy;
        this.avz = avz;
    }
}
