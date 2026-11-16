package com.nikohalfkino.rubydung.entity;

/**
 * anything that is in the world and has a body: the root of every entity class. what it has in common is here: its type, an id, where it is and how fast it moves, and a hull (an axis-aligned box) that can be overlapped and picked with a ray
 *
 * the subclasses decide what x, y, z means and what the hull is: a cube's position is its centre, a chess piece's the middle of its feet. see WorldEntity for the ones that live in the CubeManager (cubes, dice, pieces)
 */
public abstract class Entity {

    /** seconds per tick, the game runs at 60 */
    public static final double DT = 1.0D / 60.0D;

    /** overlaps shallower than this do not count as overlapping, so things that touch exactly stay apart */
    protected static final double OVERLAP_EPS = 1.0E-6D;

    /** what kind of entity this is: what it may do (held, thrown, pushed...) is asked of the type, see EntityTypes */
    public final EntityType type;

    /** identifies the entity between the server and the clients, set by CubeManager. -1 = none */
    public int id = -1;

    public double x, y, z;
    public double prevX, prevY, prevZ;

    /** blocks per second */
    public double vx, vy, vz;

    public boolean onGround;
    public boolean inLiquid;

    /** set when the entity went more than the manager allows under the world: it is removed at the end of the tick */
    public boolean dead;

    protected Entity(EntityType type) {
        this.type = type;
    }

    // ---------------------------------------------------------------- hull

    public abstract double minX();

    public abstract double maxX();

    public abstract double minY();

    public abstract double maxY();

    public abstract double minZ();

    public abstract double maxZ();

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
}
