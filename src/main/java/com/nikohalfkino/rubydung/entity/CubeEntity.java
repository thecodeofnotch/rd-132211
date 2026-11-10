package com.nikohalfkino.rubydung.entity;

import java.util.List;

/**
 * a rigid cube (the "weighted cube"): position, velocity and an orientation that is only visual
 *
 * the collision hull is always the same axis-aligned box (HALF in every direction from the centre), however the cube is turned, so a spinning cube never snags on a corner. when it comes to rest the orientation eases to the nearest of the 24 axis-aligned ones, so a cube dropped on the floor ends up lying flat
 *
 * the same class also carries a chess piece (piece >= 0): a camera-facing sprite that does no physics, it only glides from one square to the next (see tickPiece). its position is the middle of its square at the feet, its hull is the hitbox of the piece (hullW wide and deep, hullH high), which can differ from the sprite that is drawn
 *
 * this class knows nothing about the level or OpenGL: the manager hands it the boxes to collide with, which is also what makes it testable on its own
 */
public final class CubeEntity {

    /** half the edge of the cube, in blocks: a full tile. the collision skin (SKIN) and the overlap tolerance keep a cube that fills a cell exactly from snagging on the walls of it */
    public static final double HALF = 0.5D;

    /** seconds per tick, the game runs at 60 */
    public static final double DT = 1.0D / 60.0D;

    private static final double GRAVITY = 25.0D;          // blocks/s^2, the same as the player's
    private static final double TERMINAL_SPEED = 60.0D;
    private static final double AIR_DRAG = 0.15D;         // per second
    private static final double GROUND_FRICTION = 7.0D;   // per second, exponential
    private static final double LIQUID_GRAVITY = 0.25D;   // share of gravity left in a liquid
    private static final double LIQUID_DRAG = 3.5D;       // per second
    private static final double BOUNCE = 0.28D;           // share of the speed kept when hitting a surface
    private static final double BOUNCE_MIN_SPEED = 3.0D;  // slower hits just stop
    private static final double STOP_SPEED = 0.05D;       // sliding slower than this on the ground stops
    private static final double SKIN = 1.0E-5D;           // gap left to what it touches
    private static final double OVERLAP_EPS = 1.0E-6D;

    private static final double HOLD_STIFFNESS = 30.0D;   // 1/s, how hard a held cube is pulled to its target
    private static final double HOLD_MAX_SPEED = 45.0D;

    private static final double SETTLE_RATE = 10.0D;      // 1/s, how fast a resting cube eases to an aligned orientation
    private static final double SPIN_DAMP_GROUND = 9.0D;  // 1/s
    private static final double SPIN_DAMP_AIR = 0.25D;    // 1/s

    // ---------------------------------------------------------------- chess pieces

    /** -1 for a plain cube, else the piece: bits 0-2 the type (ChessPieceTile.Type ordinal), bit 3 white, bit 4 has moved. see pieceCode */
    public int piece = -1;

    // ---------------------------------------------------------------- dice

    /**
     * a dice is a cube too (isPiece() stays false) and uses the negative piece codes, so the save file and the network need no new field: -1 plain cube, -2 dice that has not rolled, -3 .. -8 dice that shows 1 .. 6
     */
    public static final int DICE_IDLE = -2;

    public boolean isDice() {
        return this.piece <= DICE_IDLE;
    }

    /** the number the dice shows, 0 if it has not rolled (or this is no dice) */
    public int diceValue() {
        return this.piece < DICE_IDLE ? DICE_IDLE - this.piece : 0;
    }

    public static int diceCode(int value) {
        return value < 1 || value > 6 ? DICE_IDLE : DICE_IDLE - value;
    }

    // server / single player only, never sent: what is needed to notice that a throw has ended
    public boolean wasHeld;
    public boolean rollArmed;
    public int lastHolder = -1;
    public int restTicks;
    public int rollTicks;

    /**
     * the number on the face that points up, from the orientation. the faces are the ones CubeRenderer draws: +y 1, -y 6, -z 2, +z 5, -x 3, +x 4 (opposite faces add up to 7)
     */
    public int upFaceValue() {
        double w = this.qw, x = this.qx, y = this.qy, z = this.qz;
        // the world y of the cube's own x, y and z axes
        double ux = 2 * (x * y + z * w);
        double uy = 1 - 2 * (x * x + z * z);
        double uz = 2 * (y * z - x * w);
        double ax = Math.abs(ux), ay = Math.abs(uy), az = Math.abs(uz);
        if (ay >= ax && ay >= az) {
            return uy > 0.0D ? 1 : 6;
        }
        if (az >= ax) {
            return uz > 0.0D ? 5 : 2;
        }
        return ux > 0.0D ? 4 : 3;
    }

    /** what kind of entity this is: what it may do (held, thrown, pushed...) is asked of the type, see EntityTypes. kept in step with piece by CubeManager.applyPieceCode */
    public EntityType type = EntityTypes.WEIGHTED_CUBE;

    /** size of the sprite drawn and of the hitbox, in blocks (a piece only) */
    public double spriteW, spriteH, hullW, hullH;

    /** gliding to another square */
    public boolean gliding;
    private double glideFromX, glideFromZ, glideToX, glideToZ;
    private int glideTick, glideTicks;

    public static final int PIECE_WHITE = 8;
    public static final int PIECE_MOVED = 16;

    /** identifies the cube between the server and the clients, set by CubeManager */
    public int id = -1;

    // centre
    public double x, y, z;
    public double prevX, prevY, prevZ;

    // blocks per second
    public double vx, vy, vz;

    // orientation (unit quaternion w, x, y, z) and the one of the last tick, to blend for drawing
    public double qw = 1.0D, qx, qy, qz;
    public double prevQw = 1.0D, prevQx, prevQy, prevQz;

    // angular velocity, radians per second about the world axes
    public double avx, avy, avz;

    public boolean onGround;
    public boolean inLiquid;

    /** carried by the player: pulled toward (targetX, targetY, targetZ), blocked by the world but not by the player */
    public boolean held;
    public double targetX, targetY, targetZ;
    /** the player's own velocity, added to the pull so the cube keeps up when walking */
    public double followVx, followVy, followVz;

    /**
     * the player is not an obstacle (and the cube is none for the player): set when it is let go of or thrown, until it has cleared the player. without it a cube dropped onto the player's feet would trap them
     */
    public boolean ignorePlayer;

    /** set by the manager: the cube went more than this far under the world */
    public boolean dead;

    // ---------------------------------------------------------------- multiplayer

    /** server: id of the connected player carrying it (see GameServer), -1 if none. a LAN host carrying it only sets held */
    public int heldBy = -1;

    /** client: somebody else carries it. it is not solid and cannot be picked up, the server moves it */
    public boolean remoteHeld;

    /** server: like ignorePlayer, for the other players' bodies */
    public boolean ignoreBodies;

    // client: the last state the server sent, the drawn one eases toward it (see stepReplica)
    public boolean hasNet;
    public double netX, netY, netZ;
    public double netQw = 1.0D, netQx, netQy, netQz;
    public double netVx, netVy, netVz;
    public int netAge;

    // server: what the players were last told (see takeDirty)
    private boolean sentOnce;
    private double sentX, sentY, sentZ, sentQw, sentQx, sentQy, sentQz;
    private int sentHolder;
    private int sentPiece = -1;

    private static final double REPLICA_EASE = 0.45D;       // share of the way to the server's state covered per tick
    private static final double REPLICA_SNAP_DISTANCE = 6.0D;
    private static final int REPLICA_EXTRAPOLATE_TICKS = 4; // ticks a state is carried on by its velocity while the next one is awaited

    /** one axis-aligned obstacle; owner is the cube it belongs to, null for the world and the player */
    public static final class Box {
        public double minX, minY, minZ, maxX, maxY, maxZ;
        public CubeEntity owner;

        public Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, CubeEntity owner) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.owner = owner;
        }

        double min(int axis) {
            return axis == 0 ? this.minX : axis == 1 ? this.minY : this.minZ;
        }

        double max(int axis) {
            return axis == 0 ? this.maxX : axis == 1 ? this.maxY : this.maxZ;
        }
    }

    public CubeEntity(double x, double y, double z) {
        this.x = this.prevX = x;
        this.y = this.prevY = y;
        this.z = this.prevZ = z;
    }

    // ---------------------------------------------------------------- multiplayer

    /** server: whether the cube changed since the players were last told (and notes that they now are). holder is who carries it, -1 nobody */
    public boolean takeDirty(int holder) {
        boolean changed = !this.sentOnce || holder != this.sentHolder || this.piece != this.sentPiece
                || Math.abs(this.x - this.sentX) > 1.0E-4D || Math.abs(this.y - this.sentY) > 1.0E-4D
                || Math.abs(this.z - this.sentZ) > 1.0E-4D
                || Math.abs(this.qw - this.sentQw) > 1.0E-4D || Math.abs(this.qx - this.sentQx) > 1.0E-4D
                || Math.abs(this.qy - this.sentQy) > 1.0E-4D || Math.abs(this.qz - this.sentQz) > 1.0E-4D;
        if (changed) {
            this.sentOnce = true;
            this.sentX = this.x;
            this.sentY = this.y;
            this.sentZ = this.z;
            this.sentQw = this.qw;
            this.sentQx = this.qx;
            this.sentQy = this.qy;
            this.sentQz = this.qz;
            this.sentHolder = holder;
            this.sentPiece = this.piece;
        }
        return changed;
    }

    /** client: what the server says about this cube. snap = jump there instead of easing (a new cube) */
    public void setNetState(double x, double y, double z, double qw, double qx, double qy, double qz,
                            double vx, double vy, double vz, boolean snap) {
        this.hasNet = true;
        this.netX = x;
        this.netY = y;
        this.netZ = z;
        double n = Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
        if (n < 1.0E-9D) {
            qw = 1.0D;
            qx = qy = qz = 0.0D;
            n = 1.0D;
        }
        this.netQw = qw / n;
        this.netQx = qx / n;
        this.netQy = qy / n;
        this.netQz = qz / n;
        this.netVx = vx;
        this.netVy = vy;
        this.netVz = vz;
        this.netAge = 0;

        if (snap) {
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.z = this.prevZ = z;
            setOrientation(this.netQw, this.netQx, this.netQy, this.netQz);
            this.prevQw = this.qw;
            this.prevQx = this.qx;
            this.prevQy = this.qy;
            this.prevQz = this.qz;
        }
    }

    /** client: copies the cube's own state into the net state, so a cube let go of keeps going from where it was */
    public void syncNetToSelf() {
        setNetState(this.x, this.y, this.z, this.qw, this.qx, this.qy, this.qz, this.vx, this.vy, this.vz, false);
    }

    /** client: one tick of a cube the server moves: no physics here, it eases toward the last state the server sent */
    public void stepReplica() {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevQw = this.qw;
        this.prevQx = this.qx;
        this.prevQy = this.qy;
        this.prevQz = this.qz;
        if (!this.hasNet) {
            return;
        }

        // a state is carried on by its velocity for a few ticks until the next arrives (not downwards: the floor is not known to be there)
        if (!this.remoteHeld && this.netAge < REPLICA_EXTRAPOLATE_TICKS) {
            this.netX += this.netVx * DT;
            this.netZ += this.netVz * DT;
            if (this.netVy > 0.0D) {
                this.netY += this.netVy * DT;
                this.netVy -= GRAVITY * DT;
            }
        }
        this.netAge++;

        double dx = this.netX - this.x;
        double dy = this.netY - this.y;
        double dz = this.netZ - this.z;
        if (dx * dx + dy * dy + dz * dz > REPLICA_SNAP_DISTANCE * REPLICA_SNAP_DISTANCE) {
            this.x = this.netX;
            this.y = this.netY;
            this.z = this.netZ;
        } else {
            this.x += dx * REPLICA_EASE;
            this.y += dy * REPLICA_EASE;
            this.z += dz * REPLICA_EASE;
        }
        easeToward(new double[]{this.netQw, this.netQx, this.netQy, this.netQz}, REPLICA_EASE);
        this.vx = this.netVx;
        this.vy = this.netVy;
        this.vz = this.netVz;
    }

    // ---------------------------------------------------------------- queries

    // a cube: x, y, z is the centre. a piece: the middle of the feet, the hull stands on it
    public double minX() { return this.x - halfWidth(); }
    public double maxX() { return this.x + halfWidth(); }
    public double minY() { return this.piece >= 0 ? this.y : this.y - HALF; }
    public double maxY() { return this.piece >= 0 ? this.y + this.hullH : this.y + HALF; }
    public double minZ() { return this.z - halfWidth(); }
    public double maxZ() { return this.z + halfWidth(); }

    private double halfWidth() {
        return this.piece >= 0 ? this.hullW * 0.5D : HALF;
    }

    public double speed() {
        return Math.sqrt(this.vx * this.vx + this.vy * this.vy + this.vz * this.vz);
    }

    /** whether the hull overlaps a box (touching does not count) */
    public boolean overlaps(double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
        return minX() < bMaxX - OVERLAP_EPS && maxX() > bMinX + OVERLAP_EPS
                && minY() < bMaxY - OVERLAP_EPS && maxY() > bMinY + OVERLAP_EPS
                && minZ() < bMaxZ - OVERLAP_EPS && maxZ() > bMinZ + OVERLAP_EPS;
    }

    /**
     * distance along a ray (unit direction) at which it enters the hull, or -1 if it misses or the hull is farther than maxDistance. 0 when the origin is inside
     */
    public double raycast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance) {
        double near = 0.0D;
        double far = maxDistance;
        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] lo = {minX(), minY(), minZ()};
        double[] hi = {maxX(), maxY(), maxZ()};

        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1.0E-12D) {
                if (o[a] < lo[a] || o[a] > hi[a]) {
                    return -1.0D;
                }
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a];
            double t2 = (hi[a] - o[a]) / d[a];
            if (t1 > t2) {
                double t = t1;
                t1 = t2;
                t2 = t;
            }
            near = Math.max(near, t1);
            far = Math.min(far, t2);
            if (near > far) {
                return -1.0D;
            }
        }
        return near;
    }

    // ---------------------------------------------------------------- chess pieces

    public boolean isPiece() {
        return this.piece >= 0;
    }

    public static int pieceCode(int typeOrdinal, boolean white, boolean moved) {
        return typeOrdinal | (white ? PIECE_WHITE : 0) | (moved ? PIECE_MOVED : 0);
    }

    public int pieceTypeOrdinal() {
        return this.piece & 7;
    }

    public boolean pieceWhite() {
        return (this.piece & PIECE_WHITE) != 0;
    }

    public boolean pieceMoved() {
        return (this.piece & PIECE_MOVED) != 0;
    }

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
        this.piece |= PIECE_MOVED;
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

    // ---------------------------------------------------------------- orientation

    /** turns the cube by a rotation (axis need not be unit length) about a world axis, applied after its current orientation */
    public void rotateWorld(double axisX, double axisY, double axisZ, double radians) {
        double len = Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
        if (len < 1.0E-9D || radians == 0.0D) {
            return;
        }
        double s = Math.sin(radians * 0.5D) / len;
        double rw = Math.cos(radians * 0.5D);
        double rx = axisX * s;
        double ry = axisY * s;
        double rz = axisZ * s;

        // r * q
        double nw = rw * this.qw - rx * this.qx - ry * this.qy - rz * this.qz;
        double nx = rw * this.qx + rx * this.qw + ry * this.qz - rz * this.qy;
        double ny = rw * this.qy - rx * this.qz + ry * this.qw + rz * this.qx;
        double nz = rw * this.qz + rx * this.qy - ry * this.qx + rz * this.qw;
        setOrientation(nw, nx, ny, nz);
    }

    public void setOrientation(double w, double x, double y, double z) {
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        if (n < 1.0E-12D) {
            w = 1.0D;
            x = y = z = 0.0D;
            n = 1.0D;
        }
        this.qw = w / n;
        this.qx = x / n;
        this.qy = y / n;
        this.qz = z / n;
    }

    /** the orientation as a column-major 4x4 matrix (OpenGL order) blended between the last two ticks, written into out[0..15] */
    public void writeMatrix(float partial, float[] out) {
        double w = this.prevQw + (this.qw - this.prevQw) * partial;
        double x = this.prevQx + (this.qx - this.prevQx) * partial;
        double y = this.prevQy + (this.qy - this.prevQy) * partial;
        double z = this.prevQz + (this.qz - this.prevQz) * partial;
        // a nlerp: the two are a tick apart, so the difference to a slerp is invisible
        if (this.prevQw * this.qw + this.prevQx * this.qx + this.prevQy * this.qy + this.prevQz * this.qz < 0.0D) {
            w = this.prevQw * -(1.0D - partial) + this.qw * partial;
            x = this.prevQx * -(1.0D - partial) + this.qx * partial;
            y = this.prevQy * -(1.0D - partial) + this.qy * partial;
            z = this.prevQz * -(1.0D - partial) + this.qz * partial;
        }
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        if (n < 1.0E-12D) {
            w = 1.0D;
            x = y = z = 0.0D;
            n = 1.0D;
        }
        w /= n;
        x /= n;
        y /= n;
        z /= n;

        out[0] = (float) (1 - 2 * (y * y + z * z));
        out[1] = (float) (2 * (x * y + z * w));
        out[2] = (float) (2 * (x * z - y * w));
        out[3] = 0.0F;

        out[4] = (float) (2 * (x * y - z * w));
        out[5] = (float) (1 - 2 * (x * x + z * z));
        out[6] = (float) (2 * (y * z + x * w));
        out[7] = 0.0F;

        out[8] = (float) (2 * (x * z + y * w));
        out[9] = (float) (2 * (y * z - x * w));
        out[10] = (float) (1 - 2 * (x * x + y * y));
        out[11] = 0.0F;

        out[12] = 0.0F;
        out[13] = 0.0F;
        out[14] = 0.0F;
        out[15] = 1.0F;
    }

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

    private void easeToward(double[] target, double amount) {
        double dot = this.qw * target[0] + this.qx * target[1] + this.qy * target[2] + this.qz * target[3];
        double sign = dot < 0.0D ? -1.0D : 1.0D;
        setOrientation(
                this.qw + (sign * target[0] - this.qw) * amount,
                this.qx + (sign * target[1] - this.qx) * amount,
                this.qy + (sign * target[2] - this.qy) * amount,
                this.qz + (sign * target[3] - this.qz) * amount);
    }

    // ---------------------------------------------------------------- physics

    /**
     * one tick. obstacles are everything it may hit (world boxes, the other cubes, the player unless ignored), already collected for the region it can reach this tick, see reach()
     */
    public void tick(List<Box> obstacles) {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevQw = this.qw;
        this.prevQx = this.qx;
        this.prevQy = this.qy;
        this.prevQz = this.qz;

        if (this.held) {
            tickHeld(obstacles);
        } else {
            tickFree(obstacles);
        }
    }

    /** how far (per axis, in blocks) the cube can move in one tick at most, for collecting the obstacles */
    public double reach() {
        return Math.max(1.0D, speed() * DT * 1.5D + 0.5D);
    }

    private void tickHeld(List<Box> obstacles) {
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

    private void tickFree(List<Box> obstacles) {
        double gravity = GRAVITY;
        if (this.inLiquid) {
            gravity *= LIQUID_GRAVITY;
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
            double friction = Math.exp(-GROUND_FRICTION * DT);
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
            double damp = Math.exp(-SPIN_DAMP_GROUND * DT);
            this.avx *= damp;
            this.avy *= damp;
            this.avz *= damp;
            easeToward(nearestAligned(), 1.0D - Math.exp(-SETTLE_RATE * DT));
        } else {
            double damp = Math.exp(-SPIN_DAMP_AIR * DT);
            this.avx *= damp;
            this.avy *= damp;
            this.avz *= damp;
        }
    }

    // moves the centre by (dx, dy, dz), one axis at a time (y first so landing is found before sliding) and stops at obstacles
    private void moveWithCollision(List<Box> obstacles, double dx, double dy, double dz, boolean bounce) {
        moveAxis(obstacles, 1, dy, bounce);
        moveAxis(obstacles, 0, dx, bounce);
        moveAxis(obstacles, 2, dz, bounce);
    }

    private void moveAxis(List<Box> obstacles, int axis, double delta, boolean bounce) {
        if (delta == 0.0D) {
            return;
        }

        double lo = axis == 0 ? minX() : axis == 1 ? minY() : minZ();
        double hi = axis == 0 ? maxX() : axis == 1 ? maxY() : maxZ();
        double allowed = delta;
        Box blocker = null;

        for (Box box : obstacles) {
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

        double speed = axis == 0 ? this.vx : axis == 1 ? this.vy : this.vz;
        if (axis == 1 && delta < 0.0D) {
            this.onGround = true;
        }

        if (!this.held) {
            // hitting another cube sideways gives it a push and costs this one some speed
            if (blocker.owner != null && axis != 1 && !blocker.owner.held) {
                CubeEntity other = blocker.owner;
                double along = axis == 0 ? other.vx : other.vz;
                double push = speed * 0.6D;
                if (Math.abs(push) > Math.abs(along) && push * speed > 0.0D) {
                    if (axis == 0) {
                        other.vx = push;
                    } else {
                        other.vz = push;
                    }
                }
                speed *= 0.4D;
                setAxisSpeed(axis, speed);
            } else if (bounce && Math.abs(speed) > BOUNCE_MIN_SPEED) {
                // a hard hit gives the cube a knock of spin and bounces it a little
                setAxisSpeed(axis, -speed * BOUNCE);
                if (axis == 1) {
                    this.avx += (hash(this.x) - 0.5D) * 4.0D;
                    this.avz += (hash(this.z) - 0.5D) * 4.0D;
                }
            } else {
                setAxisSpeed(axis, 0.0D);
            }
        } else {
            setAxisSpeed(axis, 0.0D);
        }
    }

    private void setAxisSpeed(int axis, double value) {
        if (axis == 0) {
            this.vx = value;
        } else if (axis == 1) {
            this.vy = value;
        } else {
            this.vz = value;
        }
    }

    private boolean overlapsOnOtherAxes(Box box, int axis) {
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

    /** pushes the cube along one horizontal axis (0 = x, 2 = z) up to the given speed, used when the player walks into it */
    public void pushHorizontal(int axis, double speed) {
        if (axis == 0) {
            if (Math.abs(speed) > Math.abs(this.vx) || speed * this.vx < 0.0D) {
                this.vx = speed;
            }
        } else if (Math.abs(speed) > Math.abs(this.vz) || speed * this.vz < 0.0D) {
            this.vz = speed;
        }
    }
}
