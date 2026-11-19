package com.nikohalfkino.rubydung.entity.cube;

/**
 * what a ragdoll model is made of, as plain data (no OpenGL, so a server can load it): where the model lives, how big it is in the world, and the joints its limbs hang from
 *
 * the model is one mesh, there is no skeleton in it. the limbs are found by position instead: bone() gives how much of a vertex follows each limb (the rest of it follows the body), see RagdollPose for how the limbs move and RagdollRenderer for how the mesh is bent. all positions are in the model's own units, y up, the face toward +z, the feet at y = 0
 *
 * a new model is one more entry in MODELS. its index is what a ragdoll's piece code stores (see Ragdoll.codeFor), so never reorder them
 */
public final class RagdollRig {

    public static final int HEAD = 0;
    public static final int ARM_L = 1;
    public static final int ARM_R = 2;
    public static final int LEG_L = 3;
    public static final int LEG_R = 4;
    public static final int BONES = 5;

    /** the placeholder from HL2 */
    public static final RagdollRig CHEAPLE = new RagdollRig(
            "cheaple", "male_cheaple.obj", "male_cheaple_sheet.png",
            72.9D, 0.6D, 1.8D);

    private static final RagdollRig[] MODELS = {CHEAPLE};

    /** the folder under /assets/rubydung/models/ragdolls/ */
    public final String name;
    public final String objFile;
    public final String textureFile;

    /** how tall the model is in its own units, and the size of the box that is picked and collided with, in blocks */
    public final double modelHeight;
    public final double hullWidth;
    public final double hullHeight;

    /** blocks per model unit: the model is scaled so it is as tall as its hull */
    public final double scale;

    // per limb: where it hangs from, the way it points at rest, how much it hangs down (0..1), how far it may swing (radians), and for the head how much it lolls to the side that is down
    public final double[][] pivot = new double[BONES][];
    public final double[][] rest = new double[BONES][];
    public final double[] hang = new double[BONES];
    public final double[] maxAngle = new double[BONES];
    public final double[] droop = new double[BONES];

    private RagdollRig(String name, String objFile, String textureFile, double modelHeight, double hullWidth, double hullHeight) {
        this.name = name;
        this.objFile = objFile;
        this.textureFile = textureFile;
        this.modelHeight = modelHeight;
        this.hullWidth = hullWidth;
        this.hullHeight = hullHeight;
        this.scale = hullHeight / modelHeight;

        // the head turns about the neck and lolls toward whatever is down
        joint(HEAD, 0.0D, 61.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, Math.toRadians(55.0D), 1.4D);

        // the arms hang from the shoulders, they start out in the A pose of the mesh (out and down) and fall toward the floor
        double[] arm = normalize(17.5D, -22.5D, 0.0D);
        joint(ARM_L, 8.0D, 58.5D, 0.0D, arm[0], arm[1], arm[2], 0.55D, Math.toRadians(105.0D), 0.0D);
        joint(ARM_R, -8.0D, 58.5D, 0.0D, -arm[0], arm[1], arm[2], 0.55D, Math.toRadians(105.0D), 0.0D);

        // the legs from the hips, straight down
        joint(LEG_L, 3.5D, 31.0D, 0.0D, 0.0D, -1.0D, 0.0D, 0.9D, Math.toRadians(75.0D), 0.0D);
        joint(LEG_R, -3.5D, 31.0D, 0.0D, 0.0D, -1.0D, 0.0D, 0.9D, Math.toRadians(75.0D), 0.0D);
    }

    private void joint(int bone, double px, double py, double pz, double dx, double dy, double dz,
                       double hang, double maxAngle, double droop) {
        this.pivot[bone] = new double[]{px, py, pz};
        this.rest[bone] = new double[]{dx, dy, dz};
        this.hang[bone] = hang;
        this.maxAngle[bone] = maxAngle;
        this.droop[bone] = droop;
    }

    // ---------------------------------------------------------------- registry

    public static int count() {
        return MODELS.length;
    }

    /** the model with this index, the first one if there is none (a save from a newer game) */
    public static RagdollRig get(int index) {
        return MODELS[index < 0 || index >= MODELS.length ? 0 : index];
    }

    // ---------------------------------------------------------------- skinning

    /**
     * how much of a vertex at (x, y, z) follows the limb: 0 = stays with the body, 1 = moves with the limb. it eases in over a few units around the joint, so the mesh bends there instead of tearing. the head wins over the arms, so the weights of a vertex never add up to more than 1
     */
    public double bone(int bone, double x, double y, double z) {
        double ax = Math.abs(x);
        double head = smooth(59.0D, 63.0D, y) * (1.0D - smooth(7.0D, 9.0D, ax));
        switch (bone) {
            case HEAD:
                return head;
            case ARM_L:
            case ARM_R:
                if ((x >= 0.0D) != (bone == ARM_L)) {
                    return 0.0D;
                }
                return Math.min(smooth(6.5D, 10.5D, ax) * smooth(31.0D, 35.0D, y), 1.0D - head);
            default:
                if ((x >= 0.0D) != (bone == LEG_L)) {
                    return 0.0D;
                }
                return smooth(33.0D, 28.0D, y) * (1.0D - smooth(9.0D, 11.0D, ax));
        }
    }

    // 0 at e0, 1 at e1 (e0 may be the larger one), eased
    private static double smooth(double e0, double e1, double t) {
        double k = Math.max(0.0D, Math.min(1.0D, (t - e0) / (e1 - e0)));
        return k * k * (3.0D - 2.0D * k);
    }

    private static double[] normalize(double x, double y, double z) {
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }
}
