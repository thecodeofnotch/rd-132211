package com.nikohalfkino.rubydung.entity.cube;

import com.nikohalfkino.rubydung.entity.EntityType;
import com.nikohalfkino.rubydung.entity.phys.PhysEntity;

import java.util.List;

/**
 * a rigid cube: position, velocity and an orientation that is only visual. what the weighted cube and the dice have in common, they only differ in how they take a knock (see hitSurface), how they roll to rest (see settle) and what they do with their piece code
 *
 * the collision hull is always the same axis-aligned box (HALF in every direction from the centre), however the cube is turned, so a spinning cube never snags on a corner. when it comes to rest the orientation eases to the nearest of the 24 axis-aligned ones, so a cube dropped on the floor ends up lying flat
 *
 * this class knows nothing about the level or OpenGL: the manager hands it the boxes to collide with, which is also what makes it testable on its own
 */
public abstract class PhysCube extends PhysEntity {

    /** half the edge of the cube, in blocks: a full tile. the collision skin (SKIN) and the overlap tolerance keep a cube that fills a cell exactly from snagging on the walls of it */
    public static final double HALF = 0.5D;

    private static final double TERMINAL_SPEED = 60.0D;
    private static final double AIR_DRAG = 0.15D;         // per second
    private static final double GROUND_FRICTION = 7.0D;   // per second, exponential
    private static final double LIQUID_GRAVITY = 0.25D;   // share of gravity left in a liquid
    private static final double LIQUID_DRAG = 3.5D;       // per second
    private static final double LIQUID_SPIN_DAMP = 4.0D;  // 1/s, what a floating cube loses of its spin
    private static final double FLOAT_SETTLE_SPEED = 1.0D; // blocks/s, a floating cube slower than this eases to an aligned orientation
    private static final double BOUNCE = 0.28D;           // share of the speed kept when hitting a surface
    private static final double BOUNCE_MIN_SPEED = 5.0D;  // slower hits just stop (a fall of 1 block lands at about 7)
    private static final double KNOCK_PER_SPEED = 0.25D;  // rad/s of spin per block/s of impact over BOUNCE_MIN_SPEED
    private static final double KNOCK_MAX = 3.0D;         // rad/s
    private static final double STOP_SPEED = 0.05D;       // sliding slower than this on the ground stops
    private static final double SKIN = 1.0E-5D;           // gap left to what it touches

    private static final double HOLD_STIFFNESS = 30.0D;   // 1/s, how hard a held cube is pulled to its target
    private static final double HOLD_MAX_SPEED = 45.0D;

    // a carried cube shoves what is in its way: the other cube is set moving at this share of the carried one's speed, at most SHOVE_MAX_SPEED
    private static final double SHOVE_FACTOR = 0.9D;
    private static final double SHOVE_MAX_SPEED = 14.0D;

    protected static final double SETTLE_RATE = 10.0D;      // 1/s, how fast a resting cube eases to an aligned orientation
    protected static final double SPIN_DAMP_GROUND = 9.0D;  // 1/s
    protected static final double SPIN_DAMP_AIR = 0.25D;    // 1/s

    // ---------------------------------------------------------------- state
    // what it has in common with the other bodies (spin, what it hit, who carries it) is in PhysEntity

    /**
     * set by pushHorizontal, cleared at the end of the tick: something (a body) is still shoving it this tick. a cube that is shoved into a wall is pinned, not thrown at it: no bounce, no kick of spin, no bump sound
     */
    private boolean pushedThisTick;

    protected PhysCube(EntityType type, double x, double y, double z) {
        super(type, x, y, z);
    }

    // ---------------------------------------------------------------- hull

    @Override
    public double minX() { return this.x - HALF; }

    @Override
    public double maxX() { return this.x + HALF; }

    @Override
    public double minY() { return this.y - HALF; }

    @Override
    public double maxY() { return this.y + HALF; }

    @Override
    public double minZ() { return this.z - HALF; }

    @Override
    public double maxZ() { return this.z + HALF; }

    // ---------------------------------------------------------------- how the kinds differ

    /** per second, how fast a cube sliding on the ground loses speed */
    protected double groundFriction() {
        return GROUND_FRICTION;
    }

    /**
     * a free cube hit a surface (floor, wall, ceiling) along axis at this speed (signed, the way it was going). it sets the speed along the axis: bounce or stop. bounce is false while it is only being slid along something
     */
    protected void hitSurface(int axis, double speed, boolean bounce) {
        if (bounce && Math.abs(speed) > BOUNCE_MIN_SPEED) {
            // a hard hit gives the cube a knock of spin and bounces it a little. the knock grows with how far over the
            // limit the hit was, so a drop from a short height only wobbles it and a long fall tumbles it
            setAxisSpeed(axis, -speed * BOUNCE);
            if (axis == 1) {
                double knock = Math.min(KNOCK_MAX, (Math.abs(speed) - BOUNCE_MIN_SPEED) * KNOCK_PER_SPEED);
                this.avx += (hash(this.x) - 0.5D) * 2.0D * knock;
                this.avz += (hash(this.z) - 0.5D) * 2.0D * knock;
            }
        } else {
            setAxisSpeed(axis, 0.0D);
        }
    }

    /**
     * how much of gravity pushes it up while it is in a liquid, as a share of gravity. 0 (the default) is a cube that sinks, slowly: gravity is only LIQUID_GRAVITY of itself there. above LIQUID_GRAVITY it floats: it rises until its centre leaves the liquid and bobs at the surface
     */
    protected double buoyancy() {
        return 0.0D;
    }

    /** it is in a liquid and buoyant enough not to sink: it rides at the surface and does not come to rest on anything */
    @Override
    public boolean isFloating() {
        return this.inLiquid && buoyancy() > LIQUID_GRAVITY;
    }

    /** the cube lies on something (or has just left it): it eases to the nearest aligned orientation. a dice rolls on instead */
    protected void settle() {
        double damp = Math.exp(-SPIN_DAMP_GROUND * DT);
        this.avx *= damp;
        this.avy *= damp;
        this.avz *= damp;
        easeToward(nearestAligned(), 1.0D - Math.exp(-SETTLE_RATE * DT));
    }

    // ---------------------------------------------------------------- physics

    /**
     * one tick. obstacles are everything it may hit (world boxes, the other cubes, the player unless ignored), already collected for the region it can reach this tick, see reach()
     */
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

        if (this.held) {
            tickHeld(obstacles);
        } else {
            tickFree(obstacles);
        }
        this.pushedThisTick = false;
    }

    /** how far (per axis, in blocks) the cube can move in one tick at most, for collecting the obstacles */
    @Override
    public double reach() {
        return Math.max(1.0D, speed() * DT * 1.5D + 0.5D);
    }

    private void tickHeld(List<CollisionBox> obstacles) {
        double px = this.targetX - this.x;
        double py = this.targetY - this.y;
        double pz = this.targetZ - this.z;

        this.vx = px * HOLD_STIFFNESS + this.followVx;
        this.vy = py * HOLD_STIFFNESS + this.followVy;
        this.vz = pz * HOLD_STIFFNESS + this.followVz;

        double speed = speed();
        if (speed > HOLD_MAX_SPEED) {
            double k = HOLD_MAX_SPEED / speed;
            this.vx *= k;
            this.vy *= k;
            this.vz *= k;
        }

        this.onGround = false;
        this.inLiquid = false;
        moveWithCollision(obstacles, this.vx * DT, this.vy * DT, this.vz * DT, false);

        // a held cube is steady: no spin of its own, the holder turns it
        this.avx = this.avy = this.avz = 0.0D;
    }

    private void tickFree(List<CollisionBox> obstacles) {
        double gravity = GRAVITY;
        if (this.inLiquid) {
            gravity *= LIQUID_GRAVITY - buoyancy();   // negative = pushed up
            double drag = Math.exp(-LIQUID_DRAG * DT);
            this.vx *= drag;
            this.vy *= drag;
            this.vz *= drag;
        }

        this.vy -= gravity * DT;
        if (this.vy < -TERMINAL_SPEED) {
            this.vy = -TERMINAL_SPEED;
        }

        double air = Math.exp(-AIR_DRAG * DT);
        this.vx *= air;
        this.vz *= air;

        if (this.onGround) {
            double friction = Math.exp(-groundFriction() * DT);
            this.vx *= friction;
            this.vz *= friction;
            if (this.vx * this.vx + this.vz * this.vz < STOP_SPEED * STOP_SPEED) {
                this.vx = 0.0D;
                this.vz = 0.0D;
            }
        }

        boolean wasOnGround = this.onGround;
        this.onGround = false;
        moveWithCollision(obstacles, this.vx * DT, this.vy * DT, this.vz * DT, true);

        // orientation: spin while it flies, settle flat once it rests
        double spin = Math.sqrt(this.avx * this.avx + this.avy * this.avy + this.avz * this.avz);
        if (spin > 1.0E-6D) {
            rotateWorld(this.avx, this.avy, this.avz, spin * DT);
        }

        if (this.onGround || wasOnGround && this.vy == 0.0D) {
            settle();
        } else if (isFloating()) {
            // on the water it does not tumble for ever: the spin dies away and it lies flat once it has slowed down
            double damp = Math.exp(-LIQUID_SPIN_DAMP * DT);
            this.avx *= damp;
            this.avy *= damp;
            this.avz *= damp;
            if (speed() < FLOAT_SETTLE_SPEED) {
                settle();
            }
        } else {
            double damp = Math.exp(-SPIN_DAMP_AIR * DT);
            this.avx *= damp;
            this.avy *= damp;
            this.avz *= damp;
        }
    }

    /** moves the cube along the ground by (dx, dz) with collision, without touching its speed on purpose: what a cube standing on a carried one is taken along by */
    @Override
    public void carryBy(List<CollisionBox> obstacles, double dx, double dz) {
        moveWithCollision(obstacles, dx, 0.0D, dz, false);
    }

    /** a player walks into it: it is set moving along one horizontal axis (0 = x, 2 = z) up to the given speed */
    @Override
    public void pushHorizontal(int axis, double speed) {
        this.pushedThisTick = true;
        if (axis == 0) {
            if (Math.abs(speed) > Math.abs(this.vx) || speed * this.vx < 0.0D) {
                this.vx = speed;
            }
        } else if (Math.abs(speed) > Math.abs(this.vz) || speed * this.vz < 0.0D) {
            this.vz = speed;
        }
    }

    @Override
    public void markPushed() {
        this.pushedThisTick = true;
    }

    // moves the centre by (dx, dy, dz), one axis at a time (y first so landing is found before sliding) and stops at obstacles
    private void moveWithCollision(List<CollisionBox> obstacles, double dx, double dy, double dz, boolean bounce) {
        moveAxis(obstacles, 1, dy, bounce);
        moveAxis(obstacles, 0, dx, bounce);
        moveAxis(obstacles, 2, dz, bounce);
    }

    private void moveAxis(List<CollisionBox> obstacles, int axis, double delta, boolean bounce) {
        if (delta == 0.0D) {
            return;
        }

        double lo = axis == 0 ? minX() : axis == 1 ? minY() : minZ();
        double hi = axis == 0 ? maxX() : axis == 1 ? maxY() : maxZ();
        double allowed = delta;
        CollisionBox blocker = null;

        for (CollisionBox box : obstacles) {
            if (box.owner == this || !overlapsOnOtherAxes(box, axis)) {
                continue;
            }
            if (delta > 0.0D && box.min(axis) >= hi - OVERLAP_EPS) {
                double room = box.min(axis) - hi - SKIN;
                if (room < allowed) {
                    allowed = Math.max(room, 0.0D);
                    blocker = box;
                }
            } else if (delta < 0.0D && box.max(axis) <= lo + OVERLAP_EPS) {
                double room = box.max(axis) - lo + SKIN;
                if (room > allowed) {
                    allowed = Math.min(room, 0.0D);
                    blocker = box;
                }
            }
        }

        if (axis == 0) {
            this.x += allowed;
        } else if (axis == 1) {
            this.y += allowed;
        } else {
            this.z += allowed;
        }

        if (blocker == null) {
            return;
        }

        double speed = axisSpeed(axis);

        // being shoved into something (a wall, another cube) by a body is pressure, not an impact: it just stops there.
        // no bounce, no random kick of spin (which is what made a pushed dice roll on the spot), no sound
        if (this.pushedThisTick && axis != 1 && !this.held && !blocker.body
                && (blocker.owner == null || !blocker.owner.held)) {
            if (blocker.owner != null) {
                shoveNeighbour(blocker.owner, axis, speed);
            }
            setAxisSpeed(axis, 0.0D);
            return;
        }

        if (blocker.body && !this.held && Math.abs(speed) > this.hitSpeed) {
            this.hitBody = blocker;
            this.hitSpeed = Math.abs(speed);
        } else if (!blocker.body && !this.held && Math.abs(speed) > this.bumpSpeed) {
            this.bumpSpeed = Math.abs(speed);
        }
        if (axis == 1 && delta < 0.0D) {
            this.onGround = true;
        }

        // moving up into a cube that lies on this one lifts it: the stack goes up together, it is not a hit
        if (axis == 1 && delta > 0.0D && !this.held && blocker.owner != null && !blocker.owner.held && speed > 0.0D) {
            PhysEntity above = blocker.owner;
            if (speed > above.vy) {
                above.vy = speed;
                above.onGround = false;
            }
            if (this.bumpSpeed == speed) {
                this.bumpSpeed = 0.0D;   // lifting is quiet
            }
            return;   // keeps its own upward speed, it follows the one it lifts (it was blocked for this tick only)
        }

        if (!this.held) {
            // hitting another cube sideways gives it a push and costs this one some speed
            if (blocker.owner != null && axis != 1 && !blocker.owner.held) {
                PhysEntity other = blocker.owner;
                double along = other.axisSpeed(axis);
                double push = speed * 0.6D;
                if (Math.abs(push) > Math.abs(along) && push * speed > 0.0D) {
                    other.setAxisSpeed(axis, push);
                }
                speed *= 0.4D;
                setAxisSpeed(axis, speed);
            } else {
                hitSurface(axis, speed, bounce);
            }
        } else {
            // a carried cube shoves the cubes in its way (sideways, and up: it can lift one that lies on it), it is stopped by them. a body made of parts (a ragdoll) is not shoved: the cube is blocked by it, and the ragdoll only feels what it really touches
            if (blocker.owner != null && !blocker.owner.held && blocker.owner.partCount() <= 1) {
                shove(blocker.owner, axis, speed);
            }
            setAxisSpeed(axis, 0.0D);
        }
    }

    // a shoved cube pushes the one in front of it along at its own speed, without the impact of a hit
    private void shoveNeighbour(PhysEntity other, int axis, double speed) {
        double along = other.axisSpeed(axis);
        if (Math.abs(speed) > Math.abs(along) || speed * along < 0.0D) {
            other.setAxisSpeed(axis, speed);
        }
        other.markPushed();
    }

    /** moves the cube by (dx, dy, dz) with collision, without touching its speed. returns how far it really went along y */
    @Override
    public double carryBy(List<CollisionBox> obstacles, double dx, double dy, double dz) {
        double before = this.y;
        moveWithCollision(obstacles, dx, dy, dz, false);
        return this.y - before;
    }

    /** puts the cube back down by amount (it was carried up further than the stack on it could go) */
    @Override
    public void lowerBy(double amount) {
        this.y -= amount;
    }

    private void shove(PhysEntity other, int axis, double speed) {
        if (axis == 1 && speed < 0.0D) {
            return;   // pressing down on a cube: it is held up by whatever it lies on
        }
        double push = Math.max(-SHOVE_MAX_SPEED, Math.min(SHOVE_MAX_SPEED, speed * SHOVE_FACTOR));
        double along = other.axisSpeed(axis);
        if (Math.abs(push) > Math.abs(along) || push * along < 0.0D) {
            other.setAxisSpeed(axis, push);
        }
        if (axis == 1) {
            other.onGround = false;
        }
    }

    @Override
    public double axisSpeed(int axis) {
        return axis == 0 ? this.vx : axis == 1 ? this.vy : this.vz;
    }

    @Override
    public void setAxisSpeed(int axis, double value) {
        if (axis == 0) {
            this.vx = value;
        } else if (axis == 1) {
            this.vy = value;
        } else {
            this.vz = value;
        }
    }

    private boolean overlapsOnOtherAxes(CollisionBox box, int axis) {
        if (axis != 0 && !(minX() < box.maxX - OVERLAP_EPS && maxX() > box.minX + OVERLAP_EPS)) {
            return false;
        }
        if (axis != 1 && !(minY() < box.maxY - OVERLAP_EPS && maxY() > box.minY + OVERLAP_EPS)) {
            return false;
        }
        return axis == 2 || (minZ() < box.maxZ - OVERLAP_EPS && maxZ() > box.minZ + OVERLAP_EPS);
    }

    // cheap deterministic pseudo-random in 0..1, so spin from a bounce doesn't need a Random
    private static double hash(double v) {
        double s = Math.sin(v * 12.9898D + 78.233D) * 43758.5453D;
        return s - Math.floor(s);
    }

    // ---------------------------------------------------------------- orientation

    /** the nearest of the 24 axis-aligned orientations to the current one, as {w, x, y, z} */
    public double[] nearestAligned() {
        // columns of the rotation matrix = where the cube's own x, y, z axes point
        double w = this.qw, x = this.qx, y = this.qy, z = this.qz;
        double[][] col = {
                {1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w)},
                {2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w)},
                {2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y)}
        };

        // the column that points most clearly along one world axis is snapped first
        int first = 0;
        double best = -1.0D;
        for (int c = 0; c < 3; c++) {
            double m = Math.max(Math.abs(col[c][0]), Math.max(Math.abs(col[c][1]), Math.abs(col[c][2])));
            if (m > best) {
                best = m;
                first = c;
            }
        }

        double[][] snapped = new double[3][];
        snapped[first] = snapAxis(col[first], -1);
        int usedAxis = axisOf(snapped[first]);

        int second = (first + 1) % 3;
        int third = (first + 2) % 3;
        if (maxAbsExcluding(col[third], usedAxis) > maxAbsExcluding(col[second], usedAxis)) {
            int t = second;
            second = third;
            third = t;
        }
        snapped[second] = snapAxis(col[second], usedAxis);

        // the third is whatever keeps it a rotation (right-handed): (first, second, third) cyclic => third = first x second
        double[] cross = cross(snapped[first], snapped[second]);
        boolean cyclic = (second == (first + 1) % 3);
        snapped[third] = cyclic ? cross : new double[]{-cross[0], -cross[1], -cross[2]};

        return fromColumns(snapped);
    }

    private static double[] snapAxis(double[] v, int excludedAxis) {
        int axis = -1;
        double best = -1.0D;
        for (int a = 0; a < 3; a++) {
            if (a != excludedAxis && Math.abs(v[a]) > best) {
                best = Math.abs(v[a]);
                axis = a;
            }
        }
        double[] r = new double[3];
        r[axis] = v[axis] < 0 ? -1.0D : 1.0D;
        return r;
    }

    private static double maxAbsExcluding(double[] v, int excludedAxis) {
        double m = 0.0D;
        for (int a = 0; a < 3; a++) {
            if (a != excludedAxis) {
                m = Math.max(m, Math.abs(v[a]));
            }
        }
        return m;
    }

    private static int axisOf(double[] unitAxisVector) {
        for (int a = 0; a < 3; a++) {
            if (unitAxisVector[a] != 0.0D) {
                return a;
            }
        }
        return 0;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    // quaternion {w, x, y, z} from a rotation matrix given as its three columns
    private static double[] fromColumns(double[][] c) {
        double m00 = c[0][0], m10 = c[0][1], m20 = c[0][2];
        double m01 = c[1][0], m11 = c[1][1], m21 = c[1][2];
        double m02 = c[2][0], m12 = c[2][1], m22 = c[2][2];
        double trace = m00 + m11 + m22;
        double w, x, y, z;
        if (trace > 0.0D) {
            double s = Math.sqrt(trace + 1.0D) * 2.0D;
            w = 0.25D * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0D + m00 - m11 - m22) * 2.0D;
            w = (m21 - m12) / s;
            x = 0.25D * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0D + m11 - m00 - m22) * 2.0D;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25D * s;
            z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0D + m22 - m00 - m11) * 2.0D;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25D * s;
        }
        return new double[]{w, x, y, z};
    }
}