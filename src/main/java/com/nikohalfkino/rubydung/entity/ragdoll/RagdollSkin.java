package com.nikohalfkino.rubydung.entity.ragdoll;

/**
 * bends a ragdoll's mesh to its skeleton: plain data and arithmetic, no OpenGL (a server could run it, the renderer does)
 *
 * the mesh has no bones of its own. a vertex follows the nearest bone of the skeleton, and near the border of two bones a share of both, so the mesh bends at a joint instead of tearing. the weights are worked out once per model, from the pose the model was made in (RagdollRig.rest). a bone is a part of the skeleton that moves as one piece: the head, the torso, and every segment of an arm and a leg
 *
 * how a bone moves: the torso gives a frame (left, up, front) from its particles. the other bones start from that frame, and turn from the way they pointed at rest to the way their two particles point now by the shortest turn. so a limb follows the torso's twist but not its own: the mesh has no way to know it
 */
public final class RagdollSkin {

    public static final int BONES = 10;

    private static final int B_TORSO = 1;

    // per bone: where it hangs from (the point the mesh turns about) and the particle at its far end, and the two particles the weights go by
    private static final int[] ORIGIN = {
            RagdollRig.NECK, RagdollRig.CHEST,
            RagdollRig.SH_L, RagdollRig.ELB_L, RagdollRig.SH_R, RagdollRig.ELB_R,
            RagdollRig.HIP_L, RagdollRig.KNEE_L, RagdollRig.HIP_R, RagdollRig.KNEE_R};
    private static final int[] END = {
            RagdollRig.HEAD, -1,
            RagdollRig.ELB_L, RagdollRig.HAND_L, RagdollRig.ELB_R, RagdollRig.HAND_R,
            RagdollRig.KNEE_L, RagdollRig.FOOT_L, RagdollRig.KNEE_R, RagdollRig.FOOT_R};
    private static final int[] WEIGHT_FROM = {
            RagdollRig.NECK, RagdollRig.NECK,
            RagdollRig.SH_L, RagdollRig.ELB_L, RagdollRig.SH_R, RagdollRig.ELB_R,
            RagdollRig.HIP_L, RagdollRig.KNEE_L, RagdollRig.HIP_R, RagdollRig.KNEE_R};
    private static final int[] WEIGHT_TO = {
            RagdollRig.HEAD, RagdollRig.PELVIS,
            RagdollRig.ELB_L, RagdollRig.HAND_L, RagdollRig.ELB_R, RagdollRig.HAND_R,
            RagdollRig.KNEE_L, RagdollRig.FOOT_L, RagdollRig.KNEE_R, RagdollRig.FOOT_R};

    // how far (in model units) a vertex is from a bone before it stops belonging to it: the weights go by distance over this
    private static final double[] REACH = {8.0D, 10.0D, 4.5D, 4.5D, 4.5D, 4.5D, 6.0D, 5.0D, 6.0D, 5.0D};

    // which side of the body a bone is on: +1 the left (x > 0), -1 the right, 0 the middle. a vertex well on the other side never follows it
    private static final int[] SIDE = {0, 0, 1, 1, -1, -1, 1, 1, -1, -1};
    private static final double SIDE_MARGIN = 1.5D;

    // two bones whose distances (over their reach) differ by less than this share the vertex
    private static final double BLEND = 0.3D;

    private final RagdollRig rig;
    private final int vertices;

    // the two bones a vertex follows, the share of the second one, and where the vertex is relative to its bones' origins (model units)
    private final int[] boneA;
    private final int[] boneB;
    private final float[] shareB;

    // the bone's axis at rest, in the model's space (unit vectors, the torso has none)
    private final double[][] restAxis = new double[BONES][3];

    // the bones of the pose being built: origin in the world (blocks) and the rotation (row-major 3x3)
    private final double[][] origin = new double[BONES][3];
    private final double[][] rotation = new double[BONES][9];
    private final double[] frame = new double[9];
    private final double[] swing = new double[9];

    /** positions: x, y, z of every vertex of the mesh in the model's own units */
    public RagdollSkin(RagdollRig rig, float[] positions) {
        this.rig = rig;
        this.vertices = positions.length / 3;
        this.boneA = new int[this.vertices];
        this.boneB = new int[this.vertices];
        this.shareB = new float[this.vertices];

        for (int b = 0; b < BONES; b++) {
            if (END[b] < 0) {
                continue;
            }
            double[] from = rig.rest[ORIGIN[b]];
            double[] to = rig.rest[END[b]];
            double dx = to[0] - from[0];
            double dy = to[1] - from[1];
            double dz = to[2] - from[2];
            double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
            this.restAxis[b][0] = dx / n;
            this.restAxis[b][1] = dy / n;
            this.restAxis[b][2] = dz / n;
        }

        double[] p = new double[3];
        for (int i = 0; i < this.vertices; i++) {
            p[0] = positions[i * 3];
            p[1] = positions[i * 3 + 1];
            p[2] = positions[i * 3 + 2];

            int best = -1, second = -1;
            double bestScore = Double.MAX_VALUE, secondScore = Double.MAX_VALUE;
            for (int b = 0; b < BONES; b++) {
                if (SIDE[b] != 0 && SIDE[b] * p[0] < -SIDE_MARGIN) {
                    continue;
                }
                double score = distanceToSegment(p, rig.rest[WEIGHT_FROM[b]], rig.rest[WEIGHT_TO[b]]) / REACH[b];
                if (score < bestScore) {
                    second = best;
                    secondScore = bestScore;
                    best = b;
                    bestScore = score;
                } else if (score < secondScore) {
                    second = b;
                    secondScore = score;
                }
            }

            this.boneA[i] = best;
            this.boneB[i] = second < 0 ? best : second;
            double gap = secondScore - bestScore;
            if (second < 0 || gap >= BLEND) {
                this.shareB[i] = 0.0F;
            } else {
                double t = gap / BLEND;
                this.shareB[i] = (float) (0.5D * (1.0D - t * t * (3.0D - 2.0D * t)));
            }
        }
    }

    public int vertexCount() {
        return this.vertices;
    }

    /** the bone each vertex follows most (one per vertex): for drawing a single limb */
    public int[] primaryBones() {
        return this.boneA;
    }

    /** the bone of the mesh that a pickable part of the rig (RagdollRig.PART_*) is made of. the shoulders and the hips are the torso's */
    public static int boneForPart(int part) {
        switch (part) {
            case RagdollRig.PART_HEAD: return 0;
            case RagdollRig.PART_UARM_L: return 2;
            case RagdollRig.PART_FARM_L: return 3;
            case RagdollRig.PART_UARM_R: return 4;
            case RagdollRig.PART_FARM_R: return 5;
            case RagdollRig.PART_THIGH_L: return 6;
            case RagdollRig.PART_SHIN_L: return 7;
            case RagdollRig.PART_THIGH_R: return 8;
            case RagdollRig.PART_SHIN_R: return 9;
            default: return B_TORSO;
        }
    }

    // ---------------------------------------------------------------- the pose

    /**
     * the mesh as it is bent to a pose. particles: x, y, z of every particle in the world (RagdollRig.PARTICLES * 3, as ParticleEntity.renderPositions gives them). the vertices go to out (3 per vertex) in blocks, relative to (ox, oy, oz)
     */
    public void pose(float[] restPositions, double[] particles, double ox, double oy, double oz, float[] out) {
        buildBones(particles);
        blendVertices(restPositions, ox, oy, oz, out);
    }

    // ---------------------------------------------------------------- the bones, for skinning on the graphics card

    /**
     * only the bones of a pose, for a vertex shader that bends the mesh itself (the vertices are not touched). particles as in pose(). per bone (BONES of them):
     *  - rotation: 9 floats, row-major 3x3 (bone b is at rotation[b * 9 ...])
     *  - origin: 3 floats, where the bone hangs from in the world, in blocks relative to (ox, oy, oz)
     * a vertex then goes to  origin + rotation * (restPosition - restOrigin(b)) * scale(), blended between its two bones (boneA, boneB, shareB) like pose() does
     */
    public void bones(double[] particles, double ox, double oy, double oz, float[] rotationOut, float[] originOut) {
        buildBones(particles);
        for (int b = 0; b < BONES; b++) {
            for (int i = 0; i < 9; i++) {
                rotationOut[b * 9 + i] = (float) this.rotation[b][i];
            }
            originOut[b * 3] = (float) (this.origin[b][0] - ox);
            originOut[b * 3 + 1] = (float) (this.origin[b][1] - oy);
            originOut[b * 3 + 2] = (float) (this.origin[b][2] - oz);
        }
    }

    /** the point of the rest pose that bone b turns about (model units) */
    public double[] restOrigin(int bone) {
        return this.rig.rest[ORIGIN[bone]];
    }

    /** blocks per model unit */
    public double scale() {
        return this.rig.scale;
    }

    /** the second bone each vertex follows (the same as boneA for a vertex that follows one) */
    public int[] secondaryBones() {
        return this.boneB;
    }

    /** the share of the second bone in each vertex, 0 to 0.5 */
    public float[] shares() {
        return this.shareB;
    }

    // works out this.rotation and this.origin of the bones for the particles
    private void buildBones(double[] particles) {
        torsoFrame(particles, this.frame);

        for (int b = 0; b < BONES; b++) {
            int o = ORIGIN[b];
            this.origin[b][0] = particles[o * 3];
            this.origin[b][1] = particles[o * 3 + 1];
            this.origin[b][2] = particles[o * 3 + 2];

            double[] r = this.rotation[b];
            if (b == B_TORSO) {
                System.arraycopy(this.frame, 0, r, 0, 9);
                continue;
            }
            // where the bone points at rest once it has been turned with the torso, and where it points now
            double[] a = this.restAxis[b];
            double ax = this.frame[0] * a[0] + this.frame[1] * a[1] + this.frame[2] * a[2];
            double ay = this.frame[3] * a[0] + this.frame[4] * a[1] + this.frame[5] * a[2];
            double az = this.frame[6] * a[0] + this.frame[7] * a[1] + this.frame[8] * a[2];
            int e = END[b];
            double bx = particles[e * 3] - particles[o * 3];
            double by = particles[e * 3 + 1] - particles[o * 3 + 1];
            double bz = particles[e * 3 + 2] - particles[o * 3 + 2];
            double n = Math.sqrt(bx * bx + by * by + bz * bz);
            if (n < 1.0E-9D) {
                System.arraycopy(this.frame, 0, r, 0, 9);
                continue;
            }
            turn(ax, ay, az, bx / n, by / n, bz / n, this.swing);
            multiply(this.swing, this.frame, r);
        }
    }

    // bends the vertices with the bones buildBones() made
    private void blendVertices(float[] restPositions, double ox, double oy, double oz, float[] out) {
        double scale = this.rig.scale;
        for (int i = 0; i < this.vertices; i++) {
            double wb = this.shareB[i];
            float x = restPositions[i * 3], y = restPositions[i * 3 + 1], z = restPositions[i * 3 + 2];
            double px = 0.0D, py = 0.0D, pz = 0.0D;
            for (int k = 0; k < 2; k++) {
                int b = k == 0 ? this.boneA[i] : this.boneB[i];
                double w = k == 0 ? 1.0D - wb : wb;
                if (w <= 0.0D) {
                    continue;
                }
                double[] rest = this.rig.rest[ORIGIN[b]];
                double lx = x - rest[0];
                double ly = y - rest[1];
                double lz = z - rest[2];
                double[] r = this.rotation[b];
                double[] o = this.origin[b];
                px += w * (o[0] + (r[0] * lx + r[1] * ly + r[2] * lz) * scale);
                py += w * (o[1] + (r[3] * lx + r[4] * ly + r[5] * lz) * scale);
                pz += w * (o[2] + (r[6] * lx + r[7] * ly + r[8] * lz) * scale);
            }
            out[i * 3] = (float) (px - ox);
            out[i * 3 + 1] = (float) (py - oy);
            out[i * 3 + 2] = (float) (pz - oz);
        }
    }

    /**
     * the way the torso faces as a rotation (row-major 3x3, out[0..8]): its columns are where the model's left (+x), up (+y) and front (+z) point in the world. from the shoulders and the neck and the pelvis
     */
    public static void torsoFrame(double[] particles, double[] out) {
        double lx = particles[RagdollRig.SH_L * 3] - particles[RagdollRig.SH_R * 3];
        double ly = particles[RagdollRig.SH_L * 3 + 1] - particles[RagdollRig.SH_R * 3 + 1];
        double lz = particles[RagdollRig.SH_L * 3 + 2] - particles[RagdollRig.SH_R * 3 + 2];
        double ux = particles[RagdollRig.NECK * 3] - particles[RagdollRig.PELVIS * 3];
        double uy = particles[RagdollRig.NECK * 3 + 1] - particles[RagdollRig.PELVIS * 3 + 1];
        double uz = particles[RagdollRig.NECK * 3 + 2] - particles[RagdollRig.PELVIS * 3 + 2];

        double ln = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (ln < 1.0E-9D) {
            lx = 1.0D;
            ly = lz = 0.0D;
        } else {
            lx /= ln;
            ly /= ln;
            lz /= ln;
        }

        // front = left x up, then up = front x left, so the three stay at right angles
        double fx = ly * uz - lz * uy;
        double fy = lz * ux - lx * uz;
        double fz = lx * uy - ly * ux;
        double fn = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fn < 1.0E-9D) {
            fx = 0.0D;
            fy = 0.0D;
            fz = 1.0D;
        } else {
            fx /= fn;
            fy /= fn;
            fz /= fn;
        }
        double upx = fy * lz - fz * ly;
        double upy = fz * lx - fx * lz;
        double upz = fx * ly - fy * lx;

        out[0] = lx;  out[1] = upx;  out[2] = fx;
        out[3] = ly;  out[4] = upy;  out[5] = fy;
        out[6] = lz;  out[7] = upz;  out[8] = fz;
    }

    // ---------------------------------------------------------------- arithmetic

    private static double distanceToSegment(double[] p, double[] a, double[] b) {
        double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
        double apx = p[0] - a[0], apy = p[1] - a[1], apz = p[2] - a[2];
        double c = abx * abx + aby * aby + abz * abz;
        double t = c < 1.0E-12D ? 0.0D : Math.max(0.0D, Math.min(1.0D, (apx * abx + apy * aby + apz * abz) / c));
        double dx = apx - abx * t;
        double dy = apy - aby * t;
        double dz = apz - abz * t;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // the rotation (row-major 3x3) that takes the unit vector a to the unit vector b by the shortest turn
    private static void turn(double ax, double ay, double az, double bx, double by, double bz, double[] out) {
        double kx = ay * bz - az * by;
        double ky = az * bx - ax * bz;
        double kz = ax * by - ay * bx;
        double c = ax * bx + ay * by + az * bz;
        if (c < -0.9999D) {
            c = -0.9999D;   // a limb folded right back on itself: any turn will do, the links keep it from happening
        }
        double f = 1.0D / (1.0D + c);
        out[0] = c + f * kx * kx;
        out[1] = -kz + f * kx * ky;
        out[2] = ky + f * kx * kz;
        out[3] = kz + f * ky * kx;
        out[4] = c + f * ky * ky;
        out[5] = -kx + f * ky * kz;
        out[6] = -ky + f * kz * kx;
        out[7] = kx + f * kz * ky;
        out[8] = c + f * kz * kz;
    }

    // out = a * b, row-major 3x3
    private static void multiply(double[] a, double[] b, double[] out) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
    }
}
