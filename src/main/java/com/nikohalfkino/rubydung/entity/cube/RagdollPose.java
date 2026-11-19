package com.nikohalfkino.rubydung.entity.cube;

/**
 * how loose the limbs of a ragdoll are. every limb is a direction in the model's own space (where it points from its joint) that is pulled by a spring toward where it would hang, with damping, so it swings and settles
 *
 * "where it hangs" is worked out from the felt gravity: gravity minus the acceleration of the body. standing still that is straight down, in free fall it is nothing (the limbs float), when the body is flung the limbs trail behind it. the same numbers on every machine come from the body's own movement, so nothing about the pose is saved or sent
 *
 * the limbs are only a look: the hull and the physics are the body's
 */
public final class RagdollPose {

    private static final double GRAVITY = 25.0D;          // blocks/s^2, WorldEntity.GRAVITY
    private static final double STIFFNESS = 48.0D;        // 1/s^2, how hard a limb is pulled to where it hangs
    private static final double DAMPING = 5.0D;           // 1/s, below 2 * sqrt(STIFFNESS) so it swings a few times
    private static final double MAX_FELT = 3.0D;          // the felt gravity counts for at most this many g
    private static final double MAX_ACCEL = 400.0D;       // blocks/s^2, a throw or a hit is a spike

    private static final double DT = 1.0D / 60.0D;

    private final RagdollRig rig;

    /** where each limb points now and one tick ago (model space, unit vectors) */
    public final double[][] dir = new double[RagdollRig.BONES][3];
    public final double[][] prevDir = new double[RagdollRig.BONES][3];
    private final double[][] vel = new double[RagdollRig.BONES][3];

    private final double[] lastVel = new double[3];
    private boolean primed;

    public RagdollPose(RagdollRig rig) {
        this.rig = rig;
        for (int b = 0; b < RagdollRig.BONES; b++) {
            System.arraycopy(rig.rest[b], 0, this.dir[b], 0, 3);
            System.arraycopy(rig.rest[b], 0, this.prevDir[b], 0, 3);
        }
    }

    /** one game tick, after the body has moved (its position and orientation are the new ones, prevX.. the old ones) */
    public void step(Ragdoll body) {
        double vx = (body.x - body.prevX) / DT;
        double vy = (body.y - body.prevY) / DT;
        double vz = (body.z - body.prevZ) / DT;
        double ax = 0.0D, ay = 0.0D, az = 0.0D;
        if (this.primed) {
            ax = clamp((vx - this.lastVel[0]) / DT, MAX_ACCEL);
            ay = clamp((vy - this.lastVel[1]) / DT, MAX_ACCEL);
            az = clamp((vz - this.lastVel[2]) / DT, MAX_ACCEL);
        }
        this.lastVel[0] = vx;
        this.lastVel[1] = vy;
        this.lastVel[2] = vz;
        this.primed = true;

        // gravity as the body feels it, turned into the model's space
        double[] felt = body.toLocal(-ax, -GRAVITY - ay, -az);
        double magnitude = Math.sqrt(felt[0] * felt[0] + felt[1] * felt[1] + felt[2] * felt[2]);
        double pull = Math.min(1.0D, magnitude / GRAVITY);
        double g = Math.min(MAX_FELT, magnitude / GRAVITY);
        double[] down = magnitude > 1.0E-6D
                ? new double[]{felt[0] / magnitude, felt[1] / magnitude, felt[2] / magnitude}
                : new double[]{0.0D, -1.0D, 0.0D};

        for (int b = 0; b < RagdollRig.BONES; b++) {
            double[] d = this.dir[b];
            double[] v = this.vel[b];
            System.arraycopy(d, 0, this.prevDir[b], 0, 3);

            double[] rest = this.rig.rest[b];
            double[] target = target(b, rest, down, pull);

            // a stronger felt gravity (the body is flung) pulls harder, so the limbs do not lag far behind
            double k = STIFFNESS * (0.6D + 0.4D * Math.min(g, 2.0D));
            for (int i = 0; i < 3; i++) {
                v[i] += (k * (target[i] - d[i]) - DAMPING * v[i]) * DT;
                d[i] += v[i] * DT;
            }
            normalize(d);
            limit(d, v, rest, this.rig.maxAngle[b]);
        }
    }

    // where the limb would hang
    private double[] target(int bone, double[] rest, double[] down, double pull) {
        double[] t = new double[3];
        if (this.rig.droop[bone] > 0.0D) {
            // the head: stays on top of the neck and lolls to the side that is down
            double along = down[0] * rest[0] + down[1] * rest[1] + down[2] * rest[2];
            for (int i = 0; i < 3; i++) {
                t[i] = rest[i] + this.rig.droop[bone] * pull * (down[i] - rest[i] * along);
            }
        } else {
            double h = this.rig.hang[bone] * pull;
            for (int i = 0; i < 3; i++) {
                t[i] = rest[i] * (1.0D - h) + down[i] * h;
            }
        }
        double n = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        if (n < 1.0E-6D) {
            return rest;
        }
        t[0] /= n;
        t[1] /= n;
        t[2] /= n;
        return t;
    }

    // keeps the limb inside a cone around its rest direction (a joint only bends so far), taking the speed that pushes it out
    private static void limit(double[] d, double[] v, double[] rest, double maxAngle) {
        double dot = d[0] * rest[0] + d[1] * rest[1] + d[2] * rest[2];
        if (dot >= Math.cos(maxAngle)) {
            return;
        }
        double[] side = {d[0] - rest[0] * dot, d[1] - rest[1] * dot, d[2] - rest[2] * dot};
        double n = Math.sqrt(side[0] * side[0] + side[1] * side[1] + side[2] * side[2]);
        if (n < 1.0E-9D) {
            return;
        }
        double c = Math.cos(maxAngle);
        double s = Math.sin(maxAngle);
        for (int i = 0; i < 3; i++) {
            d[i] = rest[i] * c + side[i] / n * s;
        }
        double out = v[0] * side[0] / n + v[1] * side[1] / n + v[2] * side[2] / n;
        if (out > 0.0D) {
            for (int i = 0; i < 3; i++) {
                v[i] -= side[i] / n * out;
            }
        }
    }

    private static void normalize(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n < 1.0E-9D) {
            return;
        }
        v[0] /= n;
        v[1] /= n;
        v[2] /= n;
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }
}
