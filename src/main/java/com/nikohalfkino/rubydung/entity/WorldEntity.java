package com.nikohalfkino.rubydung.entity;

/**
 * an entity that lives in the CubeManager of a level: it is saved with the level, sent to the players and has an orientation (only a visual one for most). the cubes, dice and chess pieces are all of this kind
 *
 * this class has what the saving and the network need, so they can treat all of them the same way: the piece code (see pieceCode), the dirty check the server uses, and the replica state a client eases toward. the physics is in PhysCube, the gliding in MovablePiece
 *
 * it knows nothing about the level or OpenGL
 */
public abstract class WorldEntity extends Entity {

    /** blocks/s^2, the same as the player's */
    public static final double GRAVITY = 25.0D;

    // orientation (unit quaternion w, x, y, z) and the one of the last tick, to blend for drawing
    public double qw = 1.0D, qx, qy, qz;
    public double prevQw = 1.0D, prevQx, prevQy, prevQz;

    // ---------------------------------------------------------------- multiplayer

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
    private int sentCode = -1;

    private static final double REPLICA_EASE = 0.45D;       // share of the way to the server's state covered per tick
    private static final double REPLICA_SNAP_DISTANCE = 6.0D;
    private static final int REPLICA_EXTRAPOLATE_TICKS = 4; // ticks a state is carried on by its velocity while the next one is awaited

    protected WorldEntity(EntityType type, double x, double y, double z) {
        super(type);
        this.x = this.prevX = x;
        this.y = this.prevY = y;
        this.z = this.prevZ = z;
    }

    // ---------------------------------------------------------------- what it is

    /**
     * what the save file and the network know it by (one byte): -1 a plain cube, -2 a dice that has not rolled, -3 .. -8 a dice that shows 1 .. 6, 0 and up a chess piece (see ChessPiece.pieceCode). the class of an entity never changes, only what is in the code (the number on a dice, the "has moved" bit)
     */
    public abstract int pieceCode();

    /** takes what the server says the code is. it does not change the class, see CubeManager.create */
    public abstract void applyPieceCode(int code);

    public boolean isPiece() {
        return false;
    }

    public boolean isDice() {
        return false;
    }

    /** a person made of particles (see Ragdoll): what the sounds of a body that is heavy and soft are chosen by */
    public boolean isRagdoll() {
        return false;
    }

    // ---------------------------------------------------------------- multiplayer

    /** server: whether the entity changed since the players were last told (and notes that they now are). holder is who carries it, -1 nobody */
    public boolean takeDirty(int holder) {
        int code = pieceCode();
        boolean extra = extraStateDirty();
        boolean changed = extra || !this.sentOnce || holder != this.sentHolder || code != this.sentCode
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
            this.sentCode = code;
            extraStateSent();
        }
        return changed;
    }

    // ---------------------------------------------------------------- extra state
    // state beyond the position and the orientation (the pose of a ragdoll): the bytes after the usual entry on the wire and in the save, for the piece codes that EntityTypes.extraSizeOf gives a size to

    /** server: whether the extra state differs from what the players were last told. called by takeDirty, which then calls extraStateSent if the entry is sent */
    protected boolean extraStateDirty() {
        return false;
    }

    /** server: the extra state as it was just worked out by extraStateDirty went out */
    protected void extraStateSent() {
    }

    /** writes the extra state, exactly EntityTypes.extraSizeOf(pieceCode()) bytes */
    public void writeExtra(java.io.DataOutputStream out) throws java.io.IOException {
    }

    /** reads the extra state back. snap: it is the state to start from (a body just created or loaded), not one to ease toward */
    public void applyExtra(byte[] data, boolean snap) {
    }

    /** client: what the server says about this entity. snap = jump there instead of easing (a new one) */
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

    /** client: copies the entity's own state into the net state, so one let go of keeps going from where it was */
    public void syncNetToSelf() {
        setNetState(this.x, this.y, this.z, this.qw, this.qx, this.qy, this.qz, this.vx, this.vy, this.vz, false);
    }

    /** whether a replica carries the last state on by its velocity while the next is awaited (not one that somebody else carries: the server moves it) */
    protected boolean extrapolatesReplica() {
        return true;
    }

    /** client: one tick of an entity the server moves: no physics here, it eases toward the last state the server sent */
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
        if (extrapolatesReplica() && this.netAge < REPLICA_EXTRAPOLATE_TICKS) {
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

    // ---------------------------------------------------------------- orientation

    /** turns the entity by a rotation (axis need not be unit length) about a world axis, applied after its current orientation */
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

    /** blends the orientation toward target {w, x, y, z} by amount (0..1) */
    protected void easeToward(double[] target, double amount) {
        double dot = this.qw * target[0] + this.qx * target[1] + this.qy * target[2] + this.qz * target[3];
        double sign = dot < 0.0D ? -1.0D : 1.0D;
        setOrientation(
                this.qw + (sign * target[0] - this.qw) * amount,
                this.qx + (sign * target[1] - this.qx) * amount,
                this.qy + (sign * target[2] - this.qy) * amount,
                this.qz + (sign * target[3] - this.qz) * amount);
    }
}
