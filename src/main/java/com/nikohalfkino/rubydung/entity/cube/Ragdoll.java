package com.nikohalfkino.rubydung.entity.cube;

import com.nikohalfkino.rubydung.entity.EntityTypes;

import java.util.List;

/**
 * a ragdoll: a body the player can place, pick up, carry, turn, throw and push like a weighted cube, drawn as a 3D model (see RagdollRig for the models, RagdollRenderer for the drawing)
 *
 * it is one rigid body, the same as a cube, with a hull that is the size of a person instead of a block. position, orientation, holding, throwing, saving and the network are all PhysCube's and WorldEntity's. what makes it a ragdoll is that its limbs hang loose and swing with how the body moves (RagdollPose): that is only a look, computed from the movement by every machine on its own
 *
 * where a cube comes to rest on any of its faces, a ragdoll rights itself: the hull is an upright box, so a ragdoll that lands comes back to standing, facing the way it was turned
 *
 * its piece code: CODE_BASE for the first model, one lower for each next one (see RagdollRig). a ragdoll is the one that is placed from the place-ragdoll key, see CubeManager.spawnIn
 */
public final class Ragdoll extends PhysCube {

    /** the codes of a plain cube (-1) and the dice (-2 .. -8) come before it */
    public static final int CODE_BASE = -16;

    private static final double UPRIGHT_RATE = 5.0D;   // 1/s, how fast a ragdoll on the ground comes back to standing

    public final RagdollRig rig;
    public final RagdollPose pose;
    private final int model;

    public Ragdoll(double x, double y, double z, int model) {
        super(EntityTypes.RAGDOLL, x, y, z);
        this.model = Math.max(0, Math.min(model, RagdollRig.count() - 1));
        this.rig = RagdollRig.get(this.model);
        this.pose = new RagdollPose(this.rig);
    }

    public int model() {
        return this.model;
    }

    public static int codeFor(int model) {
        return CODE_BASE - model;
    }

    public static boolean isRagdollCode(int code) {
        return code <= CODE_BASE;
    }

    public static int modelOf(int code) {
        return CODE_BASE - code;
    }

    /** the height of the centre of a ragdoll of this model over its feet */
    public static double halfHeightOf(int model) {
        return RagdollRig.get(model).hullHeight * 0.5D;
    }

    // ---------------------------------------------------------------- hull

    @Override
    public double minX() { return this.x - this.rig.hullWidth * 0.5D; }

    @Override
    public double maxX() { return this.x + this.rig.hullWidth * 0.5D; }

    @Override
    public double minY() { return this.y - this.rig.hullHeight * 0.5D; }

    @Override
    public double maxY() { return this.y + this.rig.hullHeight * 0.5D; }

    @Override
    public double minZ() { return this.z - this.rig.hullWidth * 0.5D; }

    @Override
    public double maxZ() { return this.z + this.rig.hullWidth * 0.5D; }

    // ---------------------------------------------------------------- what makes it one

    @Override
    public int pieceCode() {
        return codeFor(this.model);
    }

    @Override
    public void applyPieceCode(int code) {
        // the model is part of the class, it never changes
    }

    @Override
    protected double groundFriction() {
        return 9.0D;   // a body does not slide as far as a cube
    }

    @Override
    protected void settle() {
        double damp = Math.exp(-SPIN_DAMP_GROUND * DT);
        this.avx *= damp;
        this.avy *= damp;
        this.avz *= damp;
        easeToward(uprightTarget(), 1.0D - Math.exp(-UPRIGHT_RATE * DT));
    }

    @Override
    public void tick(List<CollisionBox> obstacles) {
        super.tick(obstacles);
        this.pose.step(this);
    }

    @Override
    public void stepReplica() {
        super.stepReplica();
        this.pose.step(this);
    }

    // ---------------------------------------------------------------- orientation

    /** turns it (about the vertical) so its front looks along (dx, dz), standing */
    public void faceToward(double dx, double dz) {
        if (dx * dx + dz * dz < 1.0E-9D) {
            return;
        }
        double yaw = Math.atan2(dx, dz);
        setOrientation(Math.cos(yaw * 0.5D), 0.0D, Math.sin(yaw * 0.5D), 0.0D);
        this.prevQw = this.qw;
        this.prevQx = this.qx;
        this.prevQy = this.qy;
        this.prevQz = this.qz;
    }

    // standing, with the front where it points now (a ragdoll that lies on its face or back has the way its head points)
    private double[] uprightTarget() {
        double w = this.qw, x = this.qx, y = this.qy, z = this.qz;
        double hx = 2 * (x * z + y * w);          // where the front (+z) points
        double hz = 1 - 2 * (x * x + y * y);
        if (hx * hx + hz * hz < 0.09D) {
            hx = 2 * (x * y - z * w);              // where the head (+y) points
            hz = 2 * (y * z + x * w);
        }
        double yaw = Math.atan2(hx, hz);
        return new double[]{Math.cos(yaw * 0.5D), 0.0D, Math.sin(yaw * 0.5D), 0.0D};
    }

    /** a vector of the world turned into the model's own space (the opposite of what the orientation does) */
    public double[] toLocal(double vx, double vy, double vz) {
        double w = this.qw, x = this.qx, y = this.qy, z = this.qz;
        return new double[]{
                (1 - 2 * (y * y + z * z)) * vx + 2 * (x * y + z * w) * vy + 2 * (x * z - y * w) * vz,
                2 * (x * y - z * w) * vx + (1 - 2 * (x * x + z * z)) * vy + 2 * (y * z + x * w) * vz,
                2 * (x * z + y * w) * vx + 2 * (y * z - x * w) * vy + (1 - 2 * (x * x + y * y)) * vz
        };
    }
}
