package com.nikohalfkino.rubydung.entity.ragdoll;

import com.nikohalfkino.rubydung.entity.phys.ParticleBody;

/**
 * what a ragdoll is made of, as plain data (no OpenGL, so a server can load it): the skeleton as points, the links between them, the joints that bend one way only, the parts that can be picked, and where the model lives
 *
 * the skeleton is the same for every model (the particles, the links and the parts are the numbers below), a model only says where the joints are in its own units (REST) and how big it is. the physics runs on the skeleton (ParticleBody), the mesh is bent to it afterwards (RagdollSkin), so a model with other proportions is one more entry in MODELS and one more table of joints
 *
 * all positions are in the model's own units, y up, the face toward +z, the feet at y = 0, the left side of the person at +x. a model's index is what a ragdoll's piece code stores (see Ragdoll.codeFor), so never reorder them
 */
public final class RagdollRig {

    // ---------------------------------------------------------------- the skeleton

    public static final int HEAD = 0;
    public static final int NECK = 1;
    public static final int PELVIS = 2;
    public static final int SH_L = 3;
    public static final int SH_R = 4;
    public static final int HIP_L = 5;
    public static final int HIP_R = 6;
    public static final int ELB_L = 7;
    public static final int HAND_L = 8;
    public static final int ELB_R = 9;
    public static final int HAND_R = 10;
    public static final int KNEE_L = 11;
    public static final int FOOT_L = 12;
    public static final int KNEE_R = 13;
    public static final int FOOT_R = 14;
    public static final int CHEST = 15;
    public static final int PARTICLES = 16;

    /** the parts a player can pick up one at a time (the segments of the body, in this order) */
    public static final int PART_HEAD = 0;
    public static final int PART_TORSO = 1;
    public static final int PART_UARM_L = 2;
    public static final int PART_FARM_L = 3;
    public static final int PART_UARM_R = 4;
    public static final int PART_FARM_R = 5;
    public static final int PART_THIGH_L = 6;
    public static final int PART_SHIN_L = 7;
    public static final int PART_THIGH_R = 8;
    public static final int PART_SHIN_R = 9;
    public static final int PART_SHOULDERS = 10;
    public static final int PART_HIPS = 11;
    public static final int PARTS = 12;

    // the particles' sizes in blocks (a sphere that collides) and how heavy they are (1 / mass: the lighter ones are moved more by a link)
    private static final double[] RADIUS = {
            0.14D, 0.08D, 0.12D, 0.09D, 0.09D, 0.11D, 0.11D, 0.06D,
            0.06D, 0.06D, 0.06D, 0.08D, 0.08D, 0.08D, 0.08D, 0.15D};
    private static final double[] INV_MASS = {
            1.6D, 1.0D, 2.0D, 1.2D, 1.2D, 1.4D, 1.4D, 0.8D,
            0.7D, 0.8D, 0.7D, 1.0D, 0.8D, 1.0D, 0.8D, 2.0D};

    // the parts: the two particles of each and how thick it is (blocks). what a ray is tested against, what the others bump into
    private static final int[][] PART_ENDS = {
            {HEAD, NECK}, {NECK, PELVIS},
            {SH_L, ELB_L}, {ELB_L, HAND_L}, {SH_R, ELB_R}, {ELB_R, HAND_R},
            {HIP_L, KNEE_L}, {KNEE_L, FOOT_L}, {HIP_R, KNEE_R}, {KNEE_R, FOOT_R},
            {SH_L, SH_R}, {HIP_L, HIP_R}};
    private static final double[] PART_RADIUS = {
            0.15D, 0.17D, 0.07D, 0.07D, 0.07D, 0.07D, 0.10D, 0.08D, 0.10D, 0.08D, 0.10D, 0.12D};

    // the bones: pairs whose distance never changes. the torso is a rigid frame of them
    private static final int[][] BONES = {
            {HEAD, NECK},
            {NECK, SH_L}, {NECK, SH_R}, {SH_L, SH_R}, {NECK, CHEST}, {CHEST, PELVIS},
            {CHEST, SH_L}, {CHEST, SH_R}, {CHEST, HIP_L}, {CHEST, HIP_R},
            {PELVIS, HIP_L}, {PELVIS, HIP_R}, {HIP_L, HIP_R},
            {SH_L, HIP_L}, {SH_R, HIP_R}, {SH_L, HIP_R}, {SH_R, HIP_L},
            {NECK, PELVIS}, {NECK, HIP_L}, {NECK, HIP_R}, {PELVIS, SH_L}, {PELVIS, SH_R},
            {SH_L, ELB_L}, {ELB_L, HAND_L}, {SH_R, ELB_R}, {ELB_R, HAND_R},
            {HIP_L, KNEE_L}, {KNEE_L, FOOT_L}, {HIP_R, KNEE_R}, {KNEE_R, FOOT_R}};

    // joints that bend only so far: {a, b, min, max as shares of the rest distance} for the head, and {a, b, min distance in model units} for the rest
    private static final double[][] HEAD_LIMITS = {
            {HEAD, SH_L, 0.85D, 1.30D}, {HEAD, SH_R, 0.85D, 1.30D}, {HEAD, CHEST, 0.86D, 1.0D}};
    private static final double[][] KEEP_APART = {
            {ELB_L, CHEST, 6.0D}, {ELB_R, CHEST, 6.0D}, {HAND_L, HIP_L, 5.0D}, {HAND_R, HIP_R, 5.0D},
            {KNEE_L, KNEE_R, 7.0D}, {FOOT_L, FOOT_R, 7.0D}, {KNEE_L, CHEST, 13.0D}, {KNEE_R, CHEST, 13.0D}};

    private static final int MAX_LINKS = 64;

    /** how tall every ragdoll is, in blocks (the height of the player, PlayerPhysics.HEIGHT) */
    public static final double HEIGHT = 1.8D;

    // ---------------------------------------------------------------- the models

    /** the placeholder from HL2 */
    public static final RagdollRig CHEAPLE = new RagdollRig(
            "cheaple", "male_cheaple.obj", "male_cheaple_sheet.png", 72.9D,
            new double[][]{
                    {0.0D, 66.5D, 0.5D},     // head
                    {0.0D, 59.0D, 0.0D},     // neck
                    {0.0D, 34.0D, 0.0D},     // pelvis
                    {7.5D, 57.5D, -1.0D},    // shoulder L
                    {-7.5D, 57.5D, -1.0D},   // shoulder R
                    {3.6D, 32.0D, 0.0D},     // hip L
                    {-3.6D, 32.0D, 0.0D},    // hip R
                    {15.5D, 48.0D, -2.5D},   // elbow L (a little behind the line, it bends forward)
                    {23.5D, 38.5D, -1.5D},   // hand L
                    {-15.5D, 48.0D, -2.5D},  // elbow R
                    {-23.5D, 38.5D, -1.5D},  // hand R
                    {3.8D, 17.0D, 1.5D},     // knee L (a little ahead of the line, it bends back: stands, but gives way)
                    {3.8D, 3.5D, 0.5D},      // ankle L
                    {-3.8D, 17.0D, 1.5D},    // knee R
                    {-3.8D, 3.5D, 0.5D},     // ankle R
                    {0.0D, 49.0D, 0.0D}});   // chest

    private static final RagdollRig[] MODELS = {CHEAPLE};

    /** the folder under /assets/rubydung/models/ragdolls/ */
    public final String name;
    public final String objFile;
    public final String textureFile;

    /** how tall the model is in its own units */
    public final double modelHeight;

    /** blocks per model unit: the model is scaled so it is as tall as a person */
    public final double scale;

    /** where each joint is in the model's units (PARTICLES x 3), the pose the mesh was made in */
    public final double[][] rest;

    private RagdollRig(String name, String objFile, String textureFile, double modelHeight, double[][] rest) {
        if (rest.length != PARTICLES) {
            throw new IllegalArgumentException("a ragdoll model has " + PARTICLES + " joints");
        }
        this.name = name;
        this.objFile = objFile;
        this.textureFile = textureFile;
        this.modelHeight = modelHeight;
        this.scale = HEIGHT / modelHeight;
        this.rest = rest;
    }

    // ---------------------------------------------------------------- registry

    public static int count() {
        return MODELS.length;
    }

    /** the model with this index, the first one if there is none (a save from a newer game) */
    public static RagdollRig get(int index) {
        return MODELS[index < 0 || index >= MODELS.length ? 0 : index];
    }

    // ---------------------------------------------------------------- the body

    /** the height of the middle of the particles over the feet, standing: where a ragdoll is put so it stands on the floor */
    public double centerHeight() {
        double sum = 0.0D;
        for (int i = 0; i < PARTICLES; i++) {
            sum += this.rest[i][1];
        }
        return sum / PARTICLES * this.scale;
    }

    /**
     * the height of the middle of the particles over the floor when the lowest sphere (the feet) just touches it: what a standing ragdoll is put at. centerHeight() goes by the joints, and the feet are spheres that reach below them
     */
    public double restingCenterHeight() {
        double lowest = Double.MAX_VALUE;
        for (int i = 0; i < PARTICLES; i++) {
            lowest = Math.min(lowest, this.rest[i][1] * this.scale - RADIUS[i]);
        }
        return centerHeight() - lowest;
    }

    /** a body with this skeleton, in the pose of the model, standing, its middle at (cx, cy, cz) */
    public ParticleBody createBody(double cx, double cy, double cz) {
        ParticleBody body = new ParticleBody(PARTICLES, MAX_LINKS, PARTS);

        double mx = 0.0D, my = 0.0D, mz = 0.0D;
        for (int i = 0; i < PARTICLES; i++) {
            mx += this.rest[i][0];
            my += this.rest[i][1];
            mz += this.rest[i][2];
        }
        mx /= PARTICLES;
        my /= PARTICLES;
        mz /= PARTICLES;
        for (int i = 0; i < PARTICLES; i++) {
            body.setParticle(i,
                    cx + (this.rest[i][0] - mx) * this.scale,
                    cy + (this.rest[i][1] - my) * this.scale,
                    cz + (this.rest[i][2] - mz) * this.scale,
                    RADIUS[i], INV_MASS[i]);
        }

        for (int[] bone : BONES) {
            double d = restDistance(body, bone[0], bone[1]);
            body.addLink(bone[0], bone[1], d, d);
        }
        for (double[] limit : HEAD_LIMITS) {
            int a = (int) limit[0];
            int b = (int) limit[1];
            double d = restDistance(body, a, b);
            body.addLink(a, b, d * limit[2], d * limit[3]);
        }
        for (double[] apart : KEEP_APART) {
            body.addLink((int) apart[0], (int) apart[1], apart[2] * this.scale, 1.0E6D);
        }

        for (int p = 0; p < PARTS; p++) {
            body.addSegment(PART_ENDS[p][0], PART_ENDS[p][1], PART_RADIUS[p]);
        }

        // knees bend back and elbows bend forward, never the other way
        body.addSolver(b -> hinge(b, HIP_L, KNEE_L, FOOT_L, 1.0D));
        body.addSolver(b -> hinge(b, HIP_R, KNEE_R, FOOT_R, 1.0D));
        body.addSolver(b -> hinge(b, SH_L, ELB_L, HAND_L, -1.0D));
        body.addSolver(b -> hinge(b, SH_R, ELB_R, HAND_R, -1.0D));
        return body;
    }

    private static double restDistance(ParticleBody body, int a, int b) {
        double dx = body.x[b] - body.x[a];
        double dy = body.y[b] - body.y[a];
        double dz = body.z[b] - body.z[a];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * a hinge: the middle particle (a knee, an elbow) has to stay on one side of the line between the two on either side of it, seen from the way the body faces. sign +1 = in front of the line (a knee), -1 = behind it (an elbow). the one that is too far over is moved back, and the two ends the other way
     */
    private static void hinge(ParticleBody b, int root, int mid, int tip, double sign) {
        // the way the body faces: left x up of the torso
        double lx = b.x[SH_L] - b.x[SH_R];
        double ly = b.y[SH_L] - b.y[SH_R];
        double lz = b.z[SH_L] - b.z[SH_R];
        double ux = b.x[NECK] - b.x[PELVIS];
        double uy = b.y[NECK] - b.y[PELVIS];
        double uz = b.z[NECK] - b.z[PELVIS];
        double fx = ly * uz - lz * uy;
        double fy = lz * ux - lx * uz;
        double fz = lx * uy - ly * ux;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1.0E-9D) {
            return;
        }
        fx /= fl;
        fy /= fl;
        fz /= fl;

        double cx = (b.x[root] + b.x[tip]) * 0.5D;
        double cy = (b.y[root] + b.y[tip]) * 0.5D;
        double cz = (b.z[root] + b.z[tip]) * 0.5D;
        double side = (b.x[mid] - cx) * fx + (b.y[mid] - cy) * fy + (b.z[mid] - cz) * fz;
        double over = -sign * side;   // above 0: on the wrong side
        if (over <= 0.0D) {
            return;
        }

        double move = over * sign;
        if (!b.isPinned(mid)) {
            b.x[mid] += fx * move * 0.6D;
            b.y[mid] += fy * move * 0.6D;
            b.z[mid] += fz * move * 0.6D;
        }
        if (!b.isPinned(root)) {
            b.x[root] -= fx * move * 0.3D;
            b.y[root] -= fy * move * 0.3D;
            b.z[root] -= fz * move * 0.3D;
        }
        if (!b.isPinned(tip)) {
            b.x[tip] -= fx * move * 0.3D;
            b.y[tip] -= fy * move * 0.3D;
            b.z[tip] -= fz * move * 0.3D;
        }
    }
}
