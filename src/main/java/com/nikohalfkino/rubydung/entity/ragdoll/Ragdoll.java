package com.nikohalfkino.rubydung.entity.ragdoll;

import com.nikohalfkino.rubydung.entity.EntityTypes;
import com.nikohalfkino.rubydung.entity.phys.ParticleEntity;

/**
 * a ragdoll: a person made of 16 points (RagdollRig) that falls, folds at its joints, lies in a heap, can be picked up by any limb, dragged, swung and thrown. drawn as a 3D model bent to the skeleton (RagdollSkin, RagdollRenderer)
 *
 * everything physical is ParticleEntity's and ParticleBody's: the stepping, the sleeping, the pose on the network and in the save file. what makes it a ragdoll is the skeleton, the way the model is bent to it and the piece code
 *
 * its piece code: CODE_BASE for the first model, one lower for each next one (see RagdollRig). it is the one placed from the place-ragdoll key, see CubeManager.spawnIn
 */
public final class Ragdoll extends ParticleEntity {

    /** the codes of a plain cube (-1) and the dice (-2 .. -8) come before it */
    public static final int CODE_BASE = -16;

    // a thrown person is heavier than a thrown cube
    private static final double THROW_FACTOR = 0.85D;

    /**
     * how much of gravity pushes a particle up while it is under the surface, as a share of gravity (ParticleEntity keeps LIQUID_GRAVITY = 0.25 of it pulling down, so anything above 0.25 rises). 0.38 is slightly buoyant: the net push is about an eighth of gravity, so a ragdoll that is let go of deep down drifts up slowly and then lies low in the water, mostly under, with the top of it out. a plain cube is 0, a dice 0.45
     */
    private static final double BUOYANCY = 0.38D;

    public final RagdollRig rig;
    private final int model;
    private final double[] pose = new double[RagdollRig.PARTICLES * 3];
    private final double[] frame = new double[9];

    /** a ragdoll of the model, standing, the middle of its particles at (x, y, z) */
    public Ragdoll(double x, double y, double z, int model) {
        super(EntityTypes.RAGDOLL, x, y, z, RagdollRig.get(model).createBody(x, y, z));
        this.model = Math.max(0, Math.min(model, RagdollRig.count() - 1));
        this.rig = RagdollRig.get(this.model);
        syncFromBody();
        updateOrientation();
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevQw = this.qw;
        this.prevQx = this.qx;
        this.prevQy = this.qy;
        this.prevQz = this.qz;
    }

    public int model() {
        return this.model;
    }

    public static int codeFor(int model) {
        return CODE_BASE - model;
    }

    /** how many models have a code of their own: the codes under them belong to the coloured dice (see Dice.Color) */
    public static final int MAX_MODELS = 16;

    public static boolean isRagdollCode(int code) {
        return code <= CODE_BASE && code > CODE_BASE - MAX_MODELS;
    }

    public static int modelOf(int code) {
        return CODE_BASE - code;
    }

    /** the height of the middle of a standing ragdoll of this model over its feet: where to put it so it stands on a floor */
    public static double centerHeightOf(int model) {
        return RagdollRig.get(model).centerHeight();
    }

    /** where the middle of a standing ragdoll of this model goes so its feet touch the floor (the spheres of the feet, not the ankles) */
    public static double restingCenterHeightOf(int model) {
        return RagdollRig.get(model).restingCenterHeight();
    }

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
    protected double throwFactor() {
        return THROW_FACTOR;
    }

    @Override
    protected double buoyancy() {
        return BUOYANCY;
    }

    /** the middle of the body turns as one piece, so a person held by the chest turns round like a cube would. an arm or a leg or the head is posed instead, the rest hangs from it */
    @Override
    protected boolean turnsWholeBody(int part) {
        return part == RagdollRig.PART_TORSO || part == RagdollRig.PART_SHOULDERS || part == RagdollRig.PART_HIPS;
    }

    @Override
    public boolean isRagdoll() {
        return true;
    }

    /** the orientation of the entity is the one of its torso: where its left, up and front point */
    @Override
    protected void updateOrientation() {
        this.body.copyPositions(this.pose);
        RagdollSkin.torsoFrame(this.pose, this.frame);
        double[] f = this.frame;   // row-major, the columns are the axes
        double trace = f[0] + f[4] + f[8];
        double w, x, y, z;
        if (trace > 0.0D) {
            double s = Math.sqrt(trace + 1.0D) * 2.0D;
            w = 0.25D * s;
            x = (f[7] - f[5]) / s;
            y = (f[2] - f[6]) / s;
            z = (f[3] - f[1]) / s;
        } else if (f[0] > f[4] && f[0] > f[8]) {
            double s = Math.sqrt(1.0D + f[0] - f[4] - f[8]) * 2.0D;
            w = (f[7] - f[5]) / s;
            x = 0.25D * s;
            y = (f[1] + f[3]) / s;
            z = (f[2] + f[6]) / s;
        } else if (f[4] > f[8]) {
            double s = Math.sqrt(1.0D + f[4] - f[0] - f[8]) * 2.0D;
            w = (f[2] - f[6]) / s;
            x = (f[1] + f[3]) / s;
            y = 0.25D * s;
            z = (f[5] + f[7]) / s;
        } else {
            double s = Math.sqrt(1.0D + f[8] - f[0] - f[4]) * 2.0D;
            w = (f[3] - f[1]) / s;
            x = (f[2] + f[6]) / s;
            y = (f[5] + f[7]) / s;
            z = 0.25D * s;
        }
        setOrientation(w, x, y, z);
    }

    /** turns the whole body about the vertical through its middle so its front looks along (dx, dz). it keeps its pose, and has no speed */
    public void faceToward(double dx, double dz) {
        if (dx * dx + dz * dz < 1.0E-9D) {
            return;
        }
        this.body.copyPositions(this.pose);
        RagdollSkin.torsoFrame(this.pose, this.frame);
        double currentYaw = Math.atan2(this.frame[2], this.frame[8]);   // where the front points: (x, z)
        double delta = Math.atan2(dx, dz) - currentYaw;
        double c = Math.cos(delta);
        double s = Math.sin(delta);

        double cx = this.x;
        double cz = this.z;
        for (int i = 0; i < this.body.count; i++) {
            double rx = this.body.x[i] - cx;
            double rz = this.body.z[i] - cz;
            this.body.place(i, cx + rx * c + rz * s, this.body.y[i], cz - rx * s + rz * c);
        }
        syncFromBody();
        updateOrientation();
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevQw = this.qw;
        this.prevQx = this.qx;
        this.prevQy = this.qy;
        this.prevQz = this.qz;
    }
}
