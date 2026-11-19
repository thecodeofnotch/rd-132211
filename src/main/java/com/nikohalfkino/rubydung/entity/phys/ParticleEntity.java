package com.nikohalfkino.rubydung.entity.phys;

import com.nikohalfkino.rubydung.entity.EntityType;
import com.nikohalfkino.rubydung.entity.cube.CollisionBox;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * an entity whose body is a ParticleBody: everything the manager, the hold, the server and the client need from a PhysEntity, done for a body made of points. a ragdoll is one, and so would be a rope or a chain: the subclass only says how the particles are laid out and what the body looks like
 *
 * what it does that a cube does not:
 *  - the hull is not fixed. it is the box around all the spheres, worked out again after every step, so a ragdoll that lies down has a low wide hull and one that is thrown has a long one. nothing about it is tied to a size
 *  - it is picked by part (a limb): raycastPart tests the segments of the body, beginGrab pins the two particles of the one that was picked and the rest hangs from it
 *  - what the others bump into, and what the walking player would, are small boxes along the segments and not the hull
 *  - it goes to sleep when it has lain still for a while, so a pile of them costs nothing, and wakes when anything touches it
 *  - a limb can be frozen in the air (freezeHeld): its particles stay where they are and the rest hangs from them. a held limb can be turned by hand about the point it is held by (rotateWorld)
 *  - the pose is state: it is sent to the clients and saved, as an extra after the usual state (see WorldEntity.writeExtra). the position and the velocity of the entity are the middle and the average of its particles
 */
public abstract class ParticleEntity extends PhysEntity {

    private static final double AIR_DRAG = 0.15D;          // per second
    private static final double LIQUID_GRAVITY = 0.25D;    // share of gravity left in a liquid
    private static final double LIQUID_DRAG = 3.5D;        // per second

    private static final double SLEEP_SPEED = 0.3D;        // blocks/s: the fastest particle is slower than this ...
    private static final int SLEEP_TICKS = 45;             // ... for this many ticks (and it lies on something): it sleeps
    private static final int PROBE_TICKS = 20;             // a sleeping body is stepped once this often to see that what it lies on is still there
    private static final double WAKE_SPEED = 0.9D;         // a probe step that moves it this fast wakes it
    private static final double WET_SHARE = 0.25D;         // it counts as being in a liquid when this share of its particles is

    private static final double PICK_BONUS = 0.08D;        // blocks a limb is easier to hit than it is thick

    // the player standing on a part: the part has to be this far under the soles at the most, and its middle has to be under the box of the player (not only its edge, a limb lying against the player's side does not carry them)
    private static final double STAND_DEPTH = 0.2D;
    private static final double STAND_INSET = 0.04D;
    private static final double PUSH_SHARE = 0.6D;         // how much of a push it takes: it is heavy
    // for this many ticks after the last turn by hand the body is still being slowed down (it settles instead of swinging on)
    private static final int SETTLE_TICKS = 10;
    private static final double SPIN_SHARE = 0.5D;         // how much of a thrown spin the limbs get

    private static final double REPLICA_EASE = 0.45D;
    private static final double REPLICA_SNAP_DISTANCE = 6.0D;

    private static final double POSE_SCALE = 2048.0D;      // the pose is sent as shorts of 1/2048 block, around the middle

    protected final ParticleBody body;

    // the hull
    private double hullMinX, hullMinY, hullMinZ, hullMaxX, hullMaxY, hullMaxZ;

    // sleep
    private boolean asleep;
    private int calm;
    private int probe;
    private final double[] sleepPose;

    // a replica: the pose the server last sent, as offsets from the middle it sent
    private final double[] netOffset;
    private boolean hasNetPose;

    // the server: what the players were last told
    private final short[] sentPose;
    private final short[] pendingPose;
    private boolean sentAsleep;
    private int sentFrozen;
    private int pendingFrozen;

    // the turns made to the held limb since they were last sent (a unit quaternion)
    private double turnW = 1.0D, turnX, turnY, turnZ;

    // the part that is held (-1: none) and how many ticks the body still has to settle from the last turn
    private int grabPart = -1;
    private int settleTicks;

    private final double[] scratch = new double[6];

    protected ParticleEntity(EntityType type, double x, double y, double z, ParticleBody body) {
        super(type, x, y, z);
        this.body = body;
        this.sleepPose = new double[body.count * 3];
        this.netOffset = new double[body.count * 3];
        this.sentPose = new short[body.count * 3];
        this.pendingPose = new short[body.count * 3];
    }

    /** the bytes of the extra state of a body of this many particles */
    public static int extraBytes(int particles) {
        return 1 + 4 + particles * 6;   // flags, the mask of the frozen particles, the pose
    }

    /** the manager says which points of the world are liquid (the particles are tested one by one, so a body can lie half in the water) */
    public final void setLiquidProbe(ParticleBody.LiquidProbe probe) {
        this.body.liquidProbe = probe;
    }

    // ---------------------------------------------------------------- hull

    @Override
    public double minX() { return this.hullMinX; }

    @Override
    public double maxX() { return this.hullMaxX; }

    @Override
    public double minY() { return this.hullMinY; }

    @Override
    public double maxY() { return this.hullMaxY; }

    @Override
    public double minZ() { return this.hullMinZ; }

    @Override
    public double maxZ() { return this.hullMaxZ; }

    /** the middle of the entity and its hull follow the particles. call after the particles were moved */
    protected final void syncFromBody() {
        this.body.center(this.scratch);
        this.x = this.scratch[0];
        this.y = this.scratch[1];
        this.z = this.scratch[2];
        this.body.bounds(this.scratch);
        this.hullMinX = this.scratch[0];
        this.hullMinY = this.scratch[1];
        this.hullMinZ = this.scratch[2];
        this.hullMaxX = this.scratch[3];
        this.hullMaxY = this.scratch[4];
        this.hullMaxZ = this.scratch[5];
    }

    /** the subclass keeps its orientation (facing) up to date from the pose: called after every step */
    protected void updateOrientation() {
    }

    /** how much of gravity pushes it up in a liquid, as a share of gravity. 0 sinks slowly */
    protected double buoyancy() {
        return 0.0D;
    }

    /** a throw is multiplied by this: a body is heavier than a cube */
    protected double throwFactor() {
        return 1.0D;
    }

    public final boolean isAsleep() {
        return this.asleep;
    }

    /** something happened to it: it may not lie still any more */
    public void wake() {
        this.asleep = false;
        this.calm = 0;
    }

    /** the particles as x y z ... between the last tick and this one, for drawing (3 * count) */
    public void renderPositions(float partial, double[] out) {
        for (int i = 0; i < this.body.count; i++) {
            out[i * 3] = this.body.lx[i] + (this.body.x[i] - this.body.lx[i]) * partial;
            out[i * 3 + 1] = this.body.ly[i] + (this.body.y[i] - this.body.ly[i]) * partial;
            out[i * 3 + 2] = this.body.lz[i] + (this.body.z[i] - this.body.lz[i]) * partial;
        }
    }

    public final ParticleBody body() {
        return this.body;
    }

    // ---------------------------------------------------------------- the tick

    @Override
    public void tick(List<CollisionBox> obstacles) {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevQw = this.qw;
        this.prevQx = this.qx;
        this.prevQy = this.qy;
        this.prevQz = this.qz;
        this.hitBody = null;
        this.hitSpeed = 0.0D;
        this.bumpSpeed = 0.0D;
        if (this.hitCooldown > 0) {
            this.hitCooldown--;
        }
        if (this.bumpCooldown > 0) {
            this.bumpCooldown--;
        }
        this.body.snapshotLast();

        if (this.held) {
            wake();
            this.body.setGrabTarget(this.targetX, this.targetY, this.targetZ, this.followVx, this.followVy, this.followVz);
        } else if (this.body.isGrabbing()) {
            this.body.releaseGrab();
        }

        boolean probing = false;
        if (this.asleep) {
            if (touchedByBody(obstacles)) {
                wake();
            } else if (--this.probe > 0) {
                return;
            } else {
                this.probe = PROBE_TICKS;
                probing = true;
                this.body.copyPositions(this.sleepPose);
            }
        }

        // the body does the liquid itself, particle by particle: the ones under the surface sink slower or rise, the ones above it fall as usual
        this.body.liquidGravity = LIQUID_GRAVITY;
        this.body.buoyancy = buoyancy();
        this.body.liquidDrag = LIQUID_DRAG;
        this.body.setSettling(this.settleTicks > 0 && this.held);
        if (this.settleTicks > 0) {
            this.settleTicks--;
        }
        this.body.step(obstacles, this, GRAVITY, AIR_DRAG);
        this.inLiquid = this.body.wetCount >= Math.max(1.0D, this.body.count * WET_SHARE);

        if (probing) {
            if (this.body.fastest() > WAKE_SPEED || (!this.body.grounded && !this.body.hasFrozen())) {
                wake();   // what it lay on is gone (one step of gravity is too slow to see, so: it no longer touches anything)
            } else {
                this.body.restorePositions(this.sleepPose);   // laid back exactly where it was: no drift
                return;
            }
        }

        this.onGround = this.body.grounded;
        this.bumpSpeed = this.body.bumpSpeed;
        this.hitSpeed = this.body.hitSpeed;
        this.hitBody = this.body.hitBody;

        syncFromBody();
        this.vx = (this.x - this.prevX) / DT;
        this.vy = (this.y - this.prevY) / DT;
        this.vz = (this.z - this.prevZ) / DT;
        updateOrientation();
        this.avx = this.avy = this.avz = 0.0D;

        // it lies on something, or hangs from a frozen part: either way, once it is still it sleeps (a floating body never does, it keeps bobbing)
        boolean supported = this.onGround || this.body.hasFrozen();
        if (!this.held && supported && this.body.wetCount == 0 && this.body.fastest() < SLEEP_SPEED) {
            if (++this.calm >= SLEEP_TICKS) {
                fallAsleep();
            }
        } else {
            this.calm = 0;
        }
    }

    private void fallAsleep() {
        this.asleep = true;
        this.calm = 0;
        this.probe = PROBE_TICKS;
        this.body.freeze();
        this.vx = this.vy = this.vz = 0.0D;
        this.body.copyPositions(this.sleepPose);
    }

    // somebody's body (the player's) is against it: it is stepped, so it can be pushed
    private boolean touchedByBody(List<CollisionBox> obstacles) {
        double g = 0.08D;
        for (int i = 0; i < obstacles.size(); i++) {
            CollisionBox b = obstacles.get(i);
            if (b.body && overlaps(b.minX - g, b.minY - g, b.minZ - g, b.maxX + g, b.maxY + g, b.maxZ + g)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double reach() {
        return Math.max(1.0D, this.body.fastest() * DT * 1.5D + 0.5D) + (this.held ? 0.8D : 0.0D);
    }

    // ---------------------------------------------------------------- the others move it

    @Override
    public void pushHorizontal(int axis, double speed) {
        wake();
        double along = this.body.meanVelocity(axis);
        if (Math.abs(speed) > Math.abs(along) || speed * along < 0.0D) {
            this.body.shiftVelocity(axis, (speed - along) * PUSH_SHARE);
        }
    }

    @Override
    public double axisSpeed(int axis) {
        return this.body.meanVelocity(axis);
    }

    @Override
    public void setAxisSpeed(int axis, double value) {
        wake();
        this.body.shiftVelocity(axis, value - this.body.meanVelocity(axis));
    }

    @Override
    public double carryBy(List<CollisionBox> obstacles, double dx, double dy, double dz) {
        if (this.body.hasFrozen()) {
            return 0.0D;   // a frozen part stays where it was put: nothing carries the body away from it
        }
        wake();
        this.body.translate(dx, dy, dz);
        syncFromBody();
        return dy;
    }

    @Override
    public void carryBy(List<CollisionBox> obstacles, double dx, double dz) {
        carryBy(obstacles, dx, 0.0D, dz);
    }

    @Override
    public void lowerBy(double amount) {
        if (this.body.hasFrozen()) {
            return;
        }
        this.body.translate(0.0D, -amount, 0.0D);
        syncFromBody();
    }

    @Override
    public boolean pushedByWalking() {
        return false;   // the player's box is an obstacle in its tick: the limbs are pushed out of it
    }

    @Override
    public boolean dragsOnFloor() {
        return false;
    }

    /** the held limb is turned about the point it is held by. a body that is not held has no turn to give */
    @Override
    public void rotateWorld(double axisX, double axisY, double axisZ, double radians) {
        double len = Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
        if (!this.body.isGrabbing() || len < 1.0E-9D || radians == 0.0D) {
            return;
        }
        double s = Math.sin(radians * 0.5D) / len;
        double w = Math.cos(radians * 0.5D);
        double x = axisX * s;
        double y = axisY * s;
        double z = axisZ * s;
        this.body.rotateGrab(w, x, y, z, turnsWholeBody());
        this.settleTicks = SETTLE_TICKS;
        syncFromBody();

        // r * pending
        double nw = w * this.turnW - x * this.turnX - y * this.turnY - z * this.turnZ;
        double nx = w * this.turnX + x * this.turnW + y * this.turnZ - z * this.turnY;
        double ny = w * this.turnY - x * this.turnZ + y * this.turnW + z * this.turnX;
        double nz = w * this.turnZ + x * this.turnY - y * this.turnX + z * this.turnW;
        double n = Math.sqrt(nw * nw + nx * nx + ny * ny + nz * nz);
        this.turnW = nw / n;
        this.turnX = nx / n;
        this.turnY = ny / n;
        this.turnZ = nz / n;
    }

    /** turning the part that is held turns the whole body with it (a ragdoll's torso) and not only that part (a ragdoll's arm, which is posed). what is frozen never turns, so with a frozen part only the held part is turned */
    protected boolean turnsWholeBody(int part) {
        return false;
    }

    private boolean turnsWholeBody() {
        return this.grabPart >= 0 && turnsWholeBody(this.grabPart) && !this.body.hasFrozen();
    }

    /** the part that is held, -1 when none */
    public final int grabbedPart() {
        return this.body.isGrabbing() ? this.grabPart : -1;
    }

    @Override
    public boolean turnsWithCamera() {
        return false;   // a limb that turned with the camera would spin about as the player looks around
    }

    @Override
    public boolean takeGrabTurn(double[] out) {
        out[0] = this.turnW;
        out[1] = this.turnX;
        out[2] = this.turnY;
        out[3] = this.turnZ;
        boolean any = this.turnW < 0.9999999D;
        this.turnW = 1.0D;
        this.turnX = this.turnY = this.turnZ = 0.0D;
        return any;
    }

    @Override
    public void turnGrab(double qw, double qx, double qy, double qz) {
        double n = Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
        if (n < 1.0E-9D || !this.body.isGrabbing()) {
            return;
        }
        this.body.rotateGrab(qw / n, qx / n, qy / n, qz / n, turnsWholeBody());
        this.settleTicks = SETTLE_TICKS;
        syncFromBody();
    }

    // ---------------------------------------------------------------- freezing

    @Override
    public boolean freezeHeld() {
        if (!this.body.isGrabbing()) {
            return false;
        }
        this.body.freezeGrab();   // stops the whole body, not only the frozen part
        this.body.releaseGrab();
        this.turnW = 1.0D;
        this.turnX = this.turnY = this.turnZ = 0.0D;
        wake();
        this.vx = this.vy = this.vz = 0.0D;
        this.avx = this.avy = this.avz = 0.0D;
        return true;
    }

    @Override
    public void unfreezeAll() {
        this.body.thawAll();
        wake();
    }

    @Override
    public boolean hasFrozenParts() {
        return this.body.hasFrozen();
    }

    /** it is put to sleep where it stands, as it is: nothing moves it until something touches it, picks it up or takes what it stands on away. for a body that is put down in a pose it would not keep (a ragdoll standing) */
    public final void putToSleep() {
        fallAsleep();
    }

    @Override
    public void confine(double limitX, double limitZ) {
        this.body.confineXZ(limitX, limitZ);
        syncFromBody();
    }

    // ---------------------------------------------------------------- the boxes it presents

    @Override
    public void addSolidBoxes(AABB region, List<AABB> out) {
        limbBoxes(region.minX - 1.0D, region.minY - 1.0D, region.minZ - 1.0D,
                region.maxX + 1.0D, region.maxY + 1.0D, region.maxZ + 1.0D, out, null);
    }

    @Override
    public void addObstacles(AABB region, List<CollisionBox> out) {
        limbBoxes(region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ, null, out);
    }

    // small boxes along every segment, the size of its thickness. only the ones inside the region
    private void limbBoxes(double loX, double loY, double loZ, double hiX, double hiY, double hiZ,
                           List<AABB> aabbs, List<CollisionBox> boxes) {
        if (!overlaps(loX, loY, loZ, hiX, hiY, hiZ)) {
            return;
        }
        ParticleBody b = this.body;
        for (int s = 0; s < b.segmentCount(); s++) {
            int a = b.segmentA(s);
            int e = b.segmentB(s);
            double r = b.segmentRadius(s);
            double dx = b.x[e] - b.x[a];
            double dy = b.y[e] - b.y[a];
            double dz = b.z[e] - b.z[a];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int n = Math.max(1, (int) Math.ceil(len / (r * 1.5D)));
            for (int k = 0; k <= n; k++) {
                double t = (double) k / n;
                double cx = b.x[a] + dx * t;
                double cy = b.y[a] + dy * t;
                double cz = b.z[a] + dz * t;
                if (cx + r <= loX || cx - r >= hiX || cy + r <= loY || cy - r >= hiY || cz + r <= loZ || cz - r >= hiZ) {
                    continue;
                }
                if (aabbs != null) {
                    aabbs.add(new AABB(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r));
                }
                if (boxes != null) {
                    boxes.add(new CollisionBox(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r, this));
                }
            }
        }
    }

    @Override
    public boolean isStoodOnBy(AABB feet) {
        // the soles are on a part when the middle of one of the spheres is under the box of the player (a little inside of its edges) and the top of it is just under the soles. a limb that only lies against the player's side, or that touches the floor next to them, does not carry them
        for (int i = 0; i < this.body.count; i++) {
            double r = this.body.radius[i];
            double top = this.body.y[i] + r;
            if (this.body.x[i] > feet.minX + STAND_INSET && this.body.x[i] < feet.maxX - STAND_INSET
                    && this.body.z[i] > feet.minZ + STAND_INSET && this.body.z[i] < feet.maxZ - STAND_INSET
                    && feet.minY < top && feet.minY > top - STAND_DEPTH) {
                return true;
            }
        }
        return false;
    }

    /** the held limb is held back by a frozen part: the hand is further away than the body reaches, which is not a reason to let go */
    @Override
    public boolean holdStrained() {
        return this.body.reachLimited;
    }

    // ---------------------------------------------------------------- parts

    @Override
    public int partCount() {
        return this.body.segmentCount();
    }

    @Override
    public double raycastPart(double ox, double oy, double oz, double dx, double dy, double dz,
                              double maxDistance, GrabPick pick) {
        // the hull first: most bodies are nowhere near the ray (the hull test of Entity, not the one of this class)
        if (super.raycast(ox, oy, oz, dx, dy, dz, maxDistance) < 0.0D) {
            return -1.0D;
        }
        return this.body.raycastSegments(ox, oy, oz, dx, dy, dz, maxDistance, PICK_BONUS, pick);
    }

    @Override
    public double raycast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance) {
        return raycastPart(ox, oy, oz, dx, dy, dz, maxDistance, null);
    }

    @Override
    public void beginGrab(int part, double frac) {
        wake();
        int s = Math.max(0, Math.min(part, this.body.segmentCount() - 1));
        this.grabPart = s;
        this.settleTicks = 0;
        this.body.grab(this.body.segmentA(s), this.body.segmentB(s), Math.max(0.0D, Math.min(1.0D, frac)));
        this.body.grabPoint(this.scratch);
        this.targetX = this.scratch[0];
        this.targetY = this.scratch[1];
        this.targetZ = this.scratch[2];
        this.followVx = this.followVy = this.followVz = 0.0D;
    }

    @Override
    public double grabX() {
        if (!this.body.isGrabbing()) {
            return this.x;
        }
        this.body.grabPoint(this.scratch);
        return this.scratch[0];
    }

    @Override
    public double grabY() {
        if (!this.body.isGrabbing()) {
            return this.y;
        }
        this.body.grabPoint(this.scratch);
        return this.scratch[1];
    }

    @Override
    public double grabZ() {
        if (!this.body.isGrabbing()) {
            return this.z;
        }
        this.body.grabPoint(this.scratch);
        return this.scratch[2];
    }

    // ---------------------------------------------------------------- letting go

    @Override
    public void dropMotion(double maxSpeed) {
        this.body.releaseGrab();
        this.body.clampSpeed(maxSpeed);
        this.vx = this.body.meanVelocity(0);
        this.vy = this.body.meanVelocity(1);
        this.vz = this.body.meanVelocity(2);
    }

    @Override
    public void throwWith(double vx, double vy, double vz, double avx, double avy, double avz) {
        wake();
        this.body.releaseGrab();
        double f = throwFactor();
        this.body.launch(vx * f, vy * f, vz * f, avx, avy, avz, SPIN_SHARE);
        this.vx = vx * f;
        this.vy = vy * f;
        this.vz = vz * f;
    }

    // ---------------------------------------------------------------- multiplayer: the pose

    @Override
    public void writeExtra(DataOutputStream out) throws IOException {
        out.writeByte(this.asleep ? 1 : 0);
        out.writeInt(this.body.frozenMask());
        for (int i = 0; i < this.body.count; i++) {
            out.writeShort(quantize(this.body.x[i] - this.x));
            out.writeShort(quantize(this.body.y[i] - this.y));
            out.writeShort(quantize(this.body.z[i] - this.z));
        }
    }

    @Override
    public void applyExtra(byte[] data, boolean snap) {
        if (data == null || data.length < extraBytes(this.body.count)) {
            return;
        }
        this.body.setFrozenMask(((data[1] & 0xFF) << 24) | ((data[2] & 0xFF) << 16) | ((data[3] & 0xFF) << 8) | (data[4] & 0xFF));
        int p = 5;
        for (int i = 0; i < this.body.count * 3; i++) {
            int hi = data[p++];
            int lo = data[p++] & 0xFF;
            this.netOffset[i] = (short) ((hi << 8) | lo) / POSE_SCALE;
        }
        this.hasNetPose = true;

        if (snap) {
            for (int i = 0; i < this.body.count; i++) {
                this.body.place(i, this.x + this.netOffset[i * 3], this.y + this.netOffset[i * 3 + 1],
                        this.z + this.netOffset[i * 3 + 2]);
            }
            this.asleep = (data[0] & 1) != 0;
            this.probe = PROBE_TICKS;
            syncFromBody();
            updateOrientation();
            this.prevX = this.x;
            this.prevY = this.y;
            this.prevZ = this.z;
        }
    }

    @Override
    protected boolean extraStateDirty() {
        this.pendingFrozen = this.body.frozenMask();
        boolean changed = this.asleep != this.sentAsleep || this.pendingFrozen != this.sentFrozen;
        for (int i = 0; i < this.body.count; i++) {
            short sx = quantize(this.body.x[i] - this.x);
            short sy = quantize(this.body.y[i] - this.y);
            short sz = quantize(this.body.z[i] - this.z);
            this.pendingPose[i * 3] = sx;
            this.pendingPose[i * 3 + 1] = sy;
            this.pendingPose[i * 3 + 2] = sz;
            if (sx != this.sentPose[i * 3] || sy != this.sentPose[i * 3 + 1] || sz != this.sentPose[i * 3 + 2]) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    protected void extraStateSent() {
        System.arraycopy(this.pendingPose, 0, this.sentPose, 0, this.sentPose.length);
        this.sentAsleep = this.asleep;
        this.sentFrozen = this.pendingFrozen;
    }

    @Override
    public void syncNetToSelf() {
        super.syncNetToSelf();
        for (int i = 0; i < this.body.count; i++) {
            this.netOffset[i * 3] = this.body.x[i] - this.x;
            this.netOffset[i * 3 + 1] = this.body.y[i] - this.y;
            this.netOffset[i * 3 + 2] = this.body.z[i] - this.z;
        }
        this.hasNetPose = true;
    }

    /** client: no physics, every particle eases toward the pose the server sent (around the middle it sent, carried on by its velocity for a few ticks) */
    @Override
    public void stepReplica() {
        this.body.snapshotLast();
        super.stepReplica();
        if (!this.hasNet || !this.hasNetPose) {
            return;
        }
        for (int i = 0; i < this.body.count; i++) {
            double tx = this.netX + this.netOffset[i * 3];
            double ty = this.netY + this.netOffset[i * 3 + 1];
            double tz = this.netZ + this.netOffset[i * 3 + 2];
            double dx = tx - this.body.x[i];
            double dy = ty - this.body.y[i];
            double dz = tz - this.body.z[i];
            if (dx * dx + dy * dy + dz * dz > REPLICA_SNAP_DISTANCE * REPLICA_SNAP_DISTANCE) {
                this.body.x[i] = tx;
                this.body.y[i] = ty;
                this.body.z[i] = tz;
            } else {
                this.body.x[i] += dx * REPLICA_EASE;
                this.body.y[i] += dy * REPLICA_EASE;
                this.body.z[i] += dz * REPLICA_EASE;
            }
        }
        this.body.freeze();
        syncFromBody();
        updateOrientation();
    }

    private static short quantize(double v) {
        long q = Math.round(v * POSE_SCALE);
        if (q > 32767L) {
            q = 32767L;
        } else if (q < -32767L) {
            q = -32767L;
        }
        return (short) q;
    }
}
