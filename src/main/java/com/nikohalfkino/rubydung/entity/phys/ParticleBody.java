package com.nikohalfkino.rubydung.entity.phys;

import com.nikohalfkino.rubydung.entity.Entity;
import com.nikohalfkino.rubydung.entity.cube.CollisionBox;

import java.util.ArrayList;
import java.util.List;

/**
 * a body made of points (a ragdoll, and later a rope, a chain, a banner): the part of it that is not about what it is. no level, no OpenGL, no entity: it is handed the boxes to collide with, so it can be tested on its own
 *
 * what it has:
 *  - particles: a position, the position one step ago (that is the velocity), a radius (a sphere that collides) and an inverse mass
 *  - links: a distance that has to stay between a minimum and a maximum. a bone is a link whose two are equal, a joint that bends only so far is a link between the two particles on either side of it
 *  - segments: two particles and a radius. they are the thickness of the body between its points: what a ray is tested against (picking) and what the others bump into. the particles' own spheres are what collides with the world
 *  - solvers: extra constraints a body adds (a knee that only bends one way), run in every iteration
 *  - one grab: two particles (a limb) are pinned and dragged toward a target, the rest hangs from them
 *
 * it is position based: every step moves the points by their velocity and gravity, then the links, the solvers and the collisions are satisfied a few times over, and what the points moved is their new velocity. stable whatever is thrown at it, which a rigid-body ragdoll in a world of boxes is not
 */
public final class ParticleBody {

    /** an extra constraint, called once per iteration after the links */
    public interface Solver {
        void solve(ParticleBody body);
    }

    /** tells the body whether a point is inside a liquid (the world is not the body's business) */
    public interface LiquidProbe {
        boolean wet(double x, double y, double z);
    }

    private static final double DT = Entity.DT;

    // a body with a frozen or held part is solved harder, so it does not stretch
    private static final int ANCHORED_EXTRA_ITERATIONS = 8;

    // a limb can not be pulled further from a frozen part than the body is long (a share of it, so the chain is never pulled taut)
    private static final double REACH_SLACK = 0.97D;

    // a link that is stretched past this many times its longest length is put right after the collisions, whatever is in the way
    private static final double STRETCH_GUARD = 1.12D;

    // while a part is frozen the rest hangs from it: it is slowed down so it settles instead of swinging for ever (1/s)
    private static final double ANCHOR_DRAG = 5.0D;

    // while the player turns a held body by hand it is "settling": the free particles lose speed fast, so the turn is not left over as swinging. drag per second on the way in, and the share of the speed a particle keeps after the constraints have been solved (that is where a turn becomes speed: every link that is put right moves a particle)
    private static final double SETTLE_DRAG = 14.0D;
    private static final double SETTLE_KEEP = 0.35D;

    /** the pull of a carried point toward its target (1/s) and the fastest it is moved (blocks/s), the same as a carried cube */
    public static final double HOLD_STIFFNESS = 30.0D;
    public static final double HOLD_MAX_SPEED = 45.0D;

    // obstacles within this of a particle (on top of its radius) are looked at during a step: a held limb moves up to HOLD_MAX_SPEED * DT
    private static final double NEAR_MARGIN = 0.9D;

    public final int count;

    /** where each particle is, where it was one step ago, and where it was at the start of the last tick (to draw between ticks) */
    public final double[] x, y, z;
    public final double[] px, py, pz;
    public final double[] lx, ly, lz;

    public final double[] radius;
    public final double[] invMass;

    // ---------------------------------------------------------------- tuning

    public int iterations = 8;

    /** what the body does in a liquid: the share of gravity that is left, how much of gravity pushes it up (see ParticleEntity), the drag per second. the probe says which particles are in one */
    public LiquidProbe liquidProbe;
    public double liquidGravity = 0.25D;
    public double buoyancy = 0.0D;
    public double liquidDrag = 3.5D;

    /** the fastest a particle goes (blocks/s). under half a block a tick, so it can not get through a block */
    public double maxSpeed = 28.0D;

    /** share of the sliding speed a particle loses per tick while it touches something */
    public double friction = 0.12D;

    /** share of the speed kept when a particle hits something faster than restitutionMinSpeed */
    public double restitution = 0.12D;
    public double restitutionMinSpeed = 4.0D;

    // ---------------------------------------------------------------- what happened in the last step

    public boolean grounded;
    /** how many particles were in a liquid at the start of the last step */
    public int wetCount;
    /** the held limb could not follow the hand last step because the body is held back by a frozen part */
    public boolean reachLimited;
    /** the hardest hit on the world or another body (blocks/s), and on a player's body with the box that was hit */
    public double bumpSpeed;
    public double hitSpeed;
    public CollisionBox hitBody;

    // ---------------------------------------------------------------- structure

    private final int[] linkA, linkB;
    private final double[] linkMin, linkMax;
    private int links;

    private final int[] segA, segB;
    private final double[] segRadius;
    private int segments;

    private final List<Solver> solvers = new ArrayList<>();

    // ---------------------------------------------------------------- grab

    private int grabA = -1;
    private int grabB = -1;
    private double grabFrac;
    private double grabTx, grabTy, grabTz;
    private double grabVx, grabVy, grabVz;

    // ---------------------------------------------------------------- scratch

    // particles that stay where they are (a limb frozen in the air): pinned like a grabbed one, but nothing moves them
    private final boolean[] frozen;
    private final boolean[] wet;

    // the player is turning the held body: see SETTLE_DRAG
    private boolean settling;

    // the longest the straight line between two particles can be, going along the links (what the body is long between them). worked out when first needed
    private double[][] reach;

    private final boolean[] touched;
    private final double[] normalX, normalY, normalZ;
    private final int[][] near;
    private final int[] nearCount;

    public ParticleBody(int count, int maxLinks, int maxSegments) {
        this.count = count;
        this.x = new double[count];
        this.y = new double[count];
        this.z = new double[count];
        this.px = new double[count];
        this.py = new double[count];
        this.pz = new double[count];
        this.lx = new double[count];
        this.ly = new double[count];
        this.lz = new double[count];
        this.radius = new double[count];
        this.invMass = new double[count];
        this.linkA = new int[maxLinks];
        this.linkB = new int[maxLinks];
        this.linkMin = new double[maxLinks];
        this.linkMax = new double[maxLinks];
        this.segA = new int[maxSegments];
        this.segB = new int[maxSegments];
        this.segRadius = new double[maxSegments];
        this.frozen = new boolean[count];
        this.wet = new boolean[count];
        this.touched = new boolean[count];
        this.normalX = new double[count];
        this.normalY = new double[count];
        this.normalZ = new double[count];
        this.near = new int[count][];
        this.nearCount = new int[count];
    }

    // ---------------------------------------------------------------- building

    public void setParticle(int i, double x, double y, double z, double radius, double invMass) {
        place(i, x, y, z);
        this.radius[i] = radius;
        this.invMass[i] = invMass;
    }

    /** puts a particle somewhere without any speed */
    public void place(int i, double x, double y, double z) {
        this.x[i] = this.px[i] = this.lx[i] = x;
        this.y[i] = this.py[i] = this.ly[i] = y;
        this.z[i] = this.pz[i] = this.lz[i] = z;
    }

    /** the two particles stay between min and max apart. min == max is a bone */
    public void addLink(int a, int b, double min, double max) {
        this.linkA[this.links] = a;
        this.linkB[this.links] = b;
        this.linkMin[this.links] = min;
        this.linkMax[this.links] = max;
        this.links++;
        this.reach = null;
    }

    /** a thick line between two particles, for picking and for what the others bump into. the number is what a part is called from outside */
    public int addSegment(int a, int b, double radius) {
        this.segA[this.segments] = a;
        this.segB[this.segments] = b;
        this.segRadius[this.segments] = radius;
        return this.segments++;
    }

    public void addSolver(Solver solver) {
        this.solvers.add(solver);
    }

    public int segmentCount() {
        return this.segments;
    }

    public int segmentA(int segment) {
        return this.segA[segment];
    }

    public int segmentB(int segment) {
        return this.segB[segment];
    }

    public double segmentRadius(int segment) {
        return this.segRadius[segment];
    }

    // ---------------------------------------------------------------- one step

    /**
     * one step of 1/60 s. obstacles are what the particles may hit, self is the entity that owns the body (its own boxes are skipped). gravity in blocks/s^2, drag per second
     */
    public void step(List<CollisionBox> obstacles, PhysEntity self, double gravity, double drag) {
        this.bumpSpeed = 0.0D;
        this.hitSpeed = 0.0D;
        this.hitBody = null;
        this.grounded = false;
        for (int i = 0; i < this.count; i++) {
            this.touched[i] = false;
        }

        this.reachLimited = false;
        boolean anchored = hasFrozen();
        probeLiquid();
        integrate(gravity, drag, anchored);
        moveGrab();
        gatherNear(obstacles, self);

        int rounds = this.iterations + (anchored || isGrabbing() ? ANCHORED_EXTRA_ITERATIONS : 0);
        for (int it = 0; it < rounds; it++) {
            solveLinks();
            for (int s = 0; s < this.solvers.size(); s++) {
                this.solvers.get(s).solve(this);
            }
            collide(obstacles);
        }
        guardStretch();
        if (this.settling) {
            damp(SETTLE_KEEP);
        }
        settleContacts();
    }

    // every free particle keeps this share of its speed
    private void damp(double keep) {
        for (int i = 0; i < this.count; i++) {
            if (isPinned(i)) {
                continue;
            }
            this.px[i] = this.x[i] - (this.x[i] - this.px[i]) * keep;
            this.py[i] = this.y[i] - (this.y[i] - this.py[i]) * keep;
            this.pz[i] = this.z[i] - (this.z[i] - this.pz[i]) * keep;
        }
    }

    /** the held body is being turned by hand: it is slowed down hard until this is called with false */
    public void setSettling(boolean settling) {
        this.settling = settling;
    }

    // which particles are in a liquid right now
    private void probeLiquid() {
        this.wetCount = 0;
        for (int i = 0; i < this.count; i++) {
            boolean in = this.liquidProbe != null && this.liquidProbe.wet(this.x[i], this.y[i], this.z[i]);
            this.wet[i] = in;
            if (in) {
                this.wetCount++;
            }
        }
    }

    // every free particle goes on by the way it was going, and falls. one in a liquid falls slower (or is pushed up, see buoyancy) and is slowed down by it
    private void integrate(double gravity, double drag, boolean anchored) {
        double airDamp = Math.exp(-drag * DT);
        double liquidDamp = Math.exp(-this.liquidDrag * DT);
        double anchorDamp = (anchored ? Math.exp(-ANCHOR_DRAG * DT) : 1.0D) * (this.settling ? Math.exp(-SETTLE_DRAG * DT) : 1.0D);
        double fallDry = gravity * DT * DT;
        double fallWet = gravity * (this.liquidGravity - this.buoyancy) * DT * DT;   // negative = pushed up
        double cap = this.maxSpeed * DT;
        for (int i = 0; i < this.count; i++) {
            if (isPinned(i)) {
                // moved by the grab, see moveGrab: its velocity is that movement
                this.px[i] = this.x[i];
                this.py[i] = this.y[i];
                this.pz[i] = this.z[i];
                continue;
            }
            double damp = (this.wet[i] ? liquidDamp : airDamp) * anchorDamp;
            double fall = this.wet[i] ? fallWet : fallDry;
            double vx = (this.x[i] - this.px[i]) * damp;
            double vy = (this.y[i] - this.py[i]) * damp;
            double vz = (this.z[i] - this.pz[i]) * damp;
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (speed > cap) {
                double k = cap / speed;
                vx *= k;
                vy *= k;
                vz *= k;
            }
            this.px[i] = this.x[i];
            this.py[i] = this.y[i];
            this.pz[i] = this.z[i];
            this.x[i] += vx;
            this.y[i] += vy - fall;
            this.z[i] += vz;
        }
    }

    // the grabbed limb goes toward where the player holds it, the way a carried cube does: a share of the way each tick, at most so fast
    private void moveGrab() {
        if (this.grabA < 0) {
            return;
        }
        double f = this.grabFrac;
        double gx = this.x[this.grabA] * (1.0D - f) + this.x[this.grabB] * f;
        double gy = this.y[this.grabA] * (1.0D - f) + this.y[this.grabB] * f;
        double gz = this.z[this.grabA] * (1.0D - f) + this.z[this.grabB] * f;
        double mx = (this.grabTx - gx) * HOLD_STIFFNESS * DT + this.grabVx * DT;
        double my = (this.grabTy - gy) * HOLD_STIFFNESS * DT + this.grabVy * DT;
        double mz = (this.grabTz - gz) * HOLD_STIFFNESS * DT + this.grabVz * DT;
        double len = Math.sqrt(mx * mx + my * my + mz * mz);
        double cap = HOLD_MAX_SPEED * DT;
        if (len > cap) {
            double k = cap / len;
            mx *= k;
            my *= k;
            mz *= k;
        }

        // with a part frozen, the hand can not take the grabbed limb further from it than the body is long
        this.reachLimited = false;
        if (hasFrozen()) {
            double[] m = {mx, my, mz};
            limitByAnchors(m);
            mx = m[0];
            my = m[1];
            mz = m[2];
        }
        this.x[this.grabA] += mx;
        this.y[this.grabA] += my;
        this.z[this.grabA] += mz;
        if (this.grabB != this.grabA) {
            this.x[this.grabB] += mx;
            this.y[this.grabB] += my;
            this.z[this.grabB] += mz;
        }
    }

    // the move m of the grabbed particles is shortened so that none ends up further from a frozen particle than the links between them allow. only the part of the move that goes away from the frozen one is taken off, so the limb still slides around it
    private void limitByAnchors(double[] m) {
        ensureReach();
        for (int pass = 0; pass < 3; pass++) {
            for (int f = 0; f < this.count; f++) {
                if (!this.frozen[f]) {
                    continue;
                }
                for (int side = 0; side < 2; side++) {
                    int g = side == 0 ? this.grabA : this.grabB;
                    if (g == f) {
                        continue;
                    }
                    double longest = this.reach[g][f] * REACH_SLACK;
                    double dx = this.x[g] + m[0] - this.x[f];
                    double dy = this.y[g] + m[1] - this.y[f];
                    double dz = this.z[g] + m[2] - this.z[f];
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > longest && d > 1.0E-9D) {
                        double excess = d - longest;
                        m[0] -= dx / d * excess;
                        m[1] -= dy / d * excess;
                        m[2] -= dz / d * excess;
                        this.reachLimited = true;
                    }
                }
            }
        }
    }

    // how far apart two particles can possibly be: the shortest way between them along the links, each link at its longest (links that never pull, like the ones that keep the arms off the body, are not counted)
    private void ensureReach() {
        if (this.reach != null) {
            return;
        }
        double[][] r = new double[this.count][this.count];
        for (int i = 0; i < this.count; i++) {
            for (int j = 0; j < this.count; j++) {
                r[i][j] = i == j ? 0.0D : Double.MAX_VALUE / 4.0D;
            }
        }
        for (int k = 0; k < this.links; k++) {
            if (this.linkMax[k] > 1.0E5D) {
                continue;
            }
            int a = this.linkA[k];
            int b = this.linkB[k];
            r[a][b] = Math.min(r[a][b], this.linkMax[k]);
            r[b][a] = Math.min(r[b][a], this.linkMax[k]);
        }
        for (int m = 0; m < this.count; m++) {
            for (int i = 0; i < this.count; i++) {
                for (int j = 0; j < this.count; j++) {
                    double via = r[i][m] + r[m][j];
                    if (via < r[i][j]) {
                        r[i][j] = via;
                    }
                }
            }
        }
        this.reach = r;
    }

    // how much of a link's correction goes to particle i. a frozen one never moves. the grabbed ones are carried by the hand, except that the two of one grab (the ends of the held limb) keep their distance to each other: if the world pushes one of them, the other gives way
    private double linkWeight(int i, int other) {
        if (this.frozen[i]) {
            return 0.0D;
        }
        if (i == this.grabA || i == this.grabB) {
            boolean pair = other == this.grabA || other == this.grabB;
            return pair && !this.frozen[other] ? this.invMass[i] : 0.0D;
        }
        return this.invMass[i];
    }

    // a last look at the links after the collisions: one that is stretched far past its longest (squeezed between a frozen part and a wall, say) is pulled back, even if that puts a particle into something. better a limb in the wall than a body that stretches for ever
    private void guardStretch() {
        for (int k = 0; k < this.links; k++) {
            double hi = this.linkMax[k];
            if (hi > 1.0E5D) {
                continue;
            }
            int a = this.linkA[k];
            int b = this.linkB[k];
            double dx = this.x[b] - this.x[a];
            double dy = this.y[b] - this.y[a];
            double dz = this.z[b] - this.z[a];
            double d2 = dx * dx + dy * dy + dz * dz;
            double limit = hi * STRETCH_GUARD;
            if (d2 <= limit * limit) {
                continue;
            }
            double d = Math.sqrt(d2);
            double wa = linkWeight(a, b);
            double wb = linkWeight(b, a);
            // when neither end may move (a frozen part and the held one), the free end of the chain has to take it: the guard leaves that to the reach limit
            double ws = wa + wb;
            if (ws <= 0.0D) {
                continue;
            }
            double c = (d - hi) / d / ws;
            this.x[a] += dx * c * wa;
            this.y[a] += dy * c * wa;
            this.z[a] += dz * c * wa;
            this.x[b] -= dx * c * wb;
            this.y[b] -= dy * c * wb;
            this.z[b] -= dz * c * wb;
        }
    }

    private void solveLinks() {
        for (int k = 0; k < this.links; k++) {
            int a = this.linkA[k];
            int b = this.linkB[k];
            double dx = this.x[b] - this.x[a];
            double dy = this.y[b] - this.y[a];
            double dz = this.z[b] - this.z[a];
            double d2 = dx * dx + dy * dy + dz * dz;
            double lo = this.linkMin[k];
            double hi = this.linkMax[k];
            if (d2 > lo * lo && d2 < hi * hi) {
                continue;
            }
            double d = Math.sqrt(d2);
            if (d < 1.0E-9D) {
                continue;
            }
            double wa = linkWeight(a, b);
            double wb = linkWeight(b, a);
            double ws = wa + wb;
            if (ws <= 0.0D) {
                continue;
            }
            double target = d < lo ? lo : hi;
            double c = (d - target) / d / ws;   // above 0: too far apart
            this.x[a] += dx * c * wa;
            this.y[a] += dy * c * wa;
            this.z[a] += dz * c * wa;
            this.x[b] -= dx * c * wb;
            this.y[b] -= dy * c * wb;
            this.z[b] -= dz * c * wb;
        }
    }

    // for every particle the boxes it can reach during this step, so the iterations do not look at all of them
    private void gatherNear(List<CollisionBox> obstacles, PhysEntity self) {
        int total = obstacles.size();
        for (int i = 0; i < this.count; i++) {
            if (this.near[i] == null || this.near[i].length < total) {
                this.near[i] = new int[Math.max(16, total)];
            }
            int[] list = this.near[i];
            int n = 0;
            double m = this.radius[i] + NEAR_MARGIN;
            double loX = this.x[i] - m, hiX = this.x[i] + m;
            double loY = this.y[i] - m, hiY = this.y[i] + m;
            double loZ = this.z[i] - m, hiZ = this.z[i] + m;
            for (int k = 0; k < total; k++) {
                CollisionBox b = obstacles.get(k);
                if (b.owner == self && self != null) {
                    continue;
                }
                if (b.maxX < loX || b.minX > hiX || b.maxY < loY || b.minY > hiY || b.maxZ < loZ || b.minZ > hiZ) {
                    continue;
                }
                list[n++] = k;
            }
            this.nearCount[i] = n;
        }
    }

    private void collide(List<CollisionBox> obstacles) {
        for (int i = 0; i < this.count; i++) {
            if (this.frozen[i]) {
                continue;   // it stays where it was put, whatever is in it
            }
            int[] list = this.near[i];
            int n = this.nearCount[i];
            double r = this.radius[i];
            for (int k = 0; k < n; k++) {
                pushOut(i, r, obstacles.get(list[k]));
            }
        }
    }

    // pushes the sphere of a particle out of a box, by the shortest way
    private void pushOut(int i, double r, CollisionBox b) {
        double cx = this.x[i];
        double cy = this.y[i];
        double cz = this.z[i];
        double qx = cx < b.minX ? b.minX : (cx > b.maxX ? b.maxX : cx);
        double qy = cy < b.minY ? b.minY : (cy > b.maxY ? b.maxY : cy);
        double qz = cz < b.minZ ? b.minZ : (cz > b.maxZ ? b.maxZ : cz);
        double dx = cx - qx;
        double dy = cy - qy;
        double dz = cz - qz;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 >= r * r) {
            return;
        }

        double nx, ny, nz, depth;
        if (d2 > 1.0E-12D) {
            double d = Math.sqrt(d2);
            nx = dx / d;
            ny = dy / d;
            nz = dz / d;
            depth = r - d;
        } else {
            // the centre is inside the box: out by the nearest face
            double best = cx - b.minX;
            nx = -1.0D;
            ny = 0.0D;
            nz = 0.0D;
            double e = b.maxX - cx;
            if (e < best) { best = e; nx = 1.0D; ny = 0.0D; nz = 0.0D; }
            e = cy - b.minY;
            if (e < best) { best = e; nx = 0.0D; ny = -1.0D; nz = 0.0D; }
            e = b.maxY - cy;
            if (e < best) { best = e; nx = 0.0D; ny = 1.0D; nz = 0.0D; }
            e = cz - b.minZ;
            if (e < best) { best = e; nx = 0.0D; ny = 0.0D; nz = -1.0D; }
            e = b.maxZ - cz;
            if (e < best) { best = e; nx = 0.0D; ny = 0.0D; nz = 1.0D; }
            depth = best + r;
        }

        // how fast it was going into the box (blocks/s), before it is pushed out
        double inward = -((cx - this.px[i]) * nx + (cy - this.py[i]) * ny + (cz - this.pz[i]) * nz) / DT;
        if (inward > 0.0D) {
            if (b.body) {
                if (inward > this.hitSpeed) {
                    this.hitSpeed = inward;
                    this.hitBody = b;
                }
            } else if (inward > this.bumpSpeed) {
                this.bumpSpeed = inward;
            }
        }

        this.x[i] = cx + nx * depth;
        this.y[i] = cy + ny * depth;
        this.z[i] = cz + nz * depth;
        this.touched[i] = true;
        this.normalX[i] = nx;
        this.normalY[i] = ny;
        this.normalZ[i] = nz;
        if (ny > 0.6D) {
            this.grounded = true;
        }
    }

    // a particle that touched something loses sliding speed, and bounces a little if it came in hard. done once per step, not per iteration
    private void settleContacts() {
        for (int i = 0; i < this.count; i++) {
            if (!this.touched[i] || isPinned(i)) {
                continue;
            }
            double nx = this.normalX[i];
            double ny = this.normalY[i];
            double nz = this.normalZ[i];
            double vx = this.x[i] - this.px[i];
            double vy = this.y[i] - this.py[i];
            double vz = this.z[i] - this.pz[i];
            double vn = vx * nx + vy * ny + vz * nz;
            double tx = vx - vn * nx;
            double ty = vy - vn * ny;
            double tz = vz - vn * nz;

            double keep = 1.0D - this.friction;
            double out = vn;
            if (vn < 0.0D) {
                out = (-vn / DT) > this.restitutionMinSpeed ? -vn * this.restitution : 0.0D;
            }
            this.px[i] = this.x[i] - (tx * keep + nx * out);
            this.py[i] = this.y[i] - (ty * keep + ny * out);
            this.pz[i] = this.z[i] - (tz * keep + nz * out);
        }
    }

    // ---------------------------------------------------------------- grab

    /** pins the two particles and drags the point between them (frac 0 = a, 1 = b) toward the target, see setGrabTarget */
    public void grab(int a, int b, double frac) {
        this.frozen[a] = false;   // taken in the hand: it is not frozen any more
        this.frozen[b] = false;
        this.grabA = a;
        this.grabB = b;
        this.grabFrac = frac;
        double[] p = new double[3];
        grabPoint(p);
        this.grabTx = p[0];
        this.grabTy = p[1];
        this.grabTz = p[2];
        this.grabVx = this.grabVy = this.grabVz = 0.0D;
    }

    public void releaseGrab() {
        this.grabA = -1;
        this.grabB = -1;
    }

    public boolean isGrabbing() {
        return this.grabA >= 0;
    }

    /** the particles of the grabbed limb, -1 when nothing is held */
    public int grabbedA() {
        return this.grabA;
    }

    public int grabbedB() {
        return this.grabB;
    }

    /** how far along the grabbed limb the hand holds it (0 = grabbedA, 1 = grabbedB) */
    public double grabFraction() {
        return this.grabFrac;
    }

    public boolean isFrozen(int i) {
        return this.frozen[i];
    }

    public boolean isPinned(int i) {
        return i == this.grabA || i == this.grabB || this.frozen[i];
    }

    // ---------------------------------------------------------------- frozen particles

    /** the two particles that are held stay exactly where they are from now on, and the rest of the body hangs from them. the grab itself is not let go of, see releaseGrab */
    public void freezeGrab() {
        if (this.grabA < 0) {
            return;
        }
        this.frozen[this.grabA] = true;
        this.frozen[this.grabB] = true;
        // everything stops where it is: the body was being swung about by the hand, and what it carried would go on swinging from the frozen part
        freeze();
    }

    /** every frozen particle is free again */
    public void thawAll() {
        java.util.Arrays.fill(this.frozen, false);
    }

    public boolean hasFrozen() {
        for (int i = 0; i < this.count; i++) {
            if (this.frozen[i]) {
                return true;
            }
        }
        return false;
    }

    /** which particles are frozen, bit i = particle i (at most 32 particles) */
    public int frozenMask() {
        int mask = 0;
        for (int i = 0; i < this.count && i < 32; i++) {
            if (this.frozen[i]) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    public void setFrozenMask(int mask) {
        for (int i = 0; i < this.count; i++) {
            this.frozen[i] = i < 32 && (mask & (1 << i)) != 0;
        }
    }

    /**
     * turns the grabbed limb about the point it is held by, by the rotation (w, x, y, z, a unit quaternion) in the world's axes: the two pinned particles go round it, the rest of the body follows through the links. nothing happens when nothing is grabbed
     */
    public void rotateGrab(double qw, double qx, double qy, double qz) {
        rotateGrab(qw, qx, qy, qz, false);
    }

    /**
     * the same, and with whole = true every particle that is not frozen goes round the point, so the body turns as one piece and keeps its pose. that is what holding the torso should do: the two particles of the torso lie on its axis, so turning only them does nothing to a twist about the spine and the rest of the body is whipped round by the links instead
     */
    public void rotateGrab(double qw, double qx, double qy, double qz, boolean whole) {
        if (this.grabA < 0) {
            return;
        }
        double[] g = new double[3];
        grabPoint(g);
        if (whole) {
            for (int i = 0; i < this.count; i++) {
                if (this.frozen[i]) {
                    continue;
                }
                // the speed and the place it is drawn from turn with it, so neither adds a swing of its own
                rotateArrays(this.x, this.y, this.z, i, g, qw, qx, qy, qz);
                rotateArrays(this.px, this.py, this.pz, i, g, qw, qx, qy, qz);
                rotateArrays(this.lx, this.ly, this.lz, i, g, qw, qx, qy, qz);
            }
            return;
        }
        rotateAbout(this.grabA, g, qw, qx, qy, qz);
        if (this.grabB != this.grabA) {
            rotateAbout(this.grabB, g, qw, qx, qy, qz);
        }
    }

    private static void rotateArrays(double[] ax, double[] ay, double[] az, int i, double[] c,
                                     double qw, double qx, double qy, double qz) {
        double rx = ax[i] - c[0];
        double ry = ay[i] - c[1];
        double rz = az[i] - c[2];
        // v' = v + 2w (q x v) + 2 q x (q x v)
        double tx = 2.0D * (qy * rz - qz * ry);
        double ty = 2.0D * (qz * rx - qx * rz);
        double tz = 2.0D * (qx * ry - qy * rx);
        ax[i] = c[0] + rx + qw * tx + (qy * tz - qz * ty);
        ay[i] = c[1] + ry + qw * ty + (qz * tx - qx * tz);
        az[i] = c[2] + rz + qw * tz + (qx * ty - qy * tx);
    }

    private void rotateAbout(int i, double[] c, double qw, double qx, double qy, double qz) {
        double rx = this.x[i] - c[0];
        double ry = this.y[i] - c[1];
        double rz = this.z[i] - c[2];
        // v' = v + 2w (q x v) + 2 q x (q x v)
        double tx = 2.0D * (qy * rz - qz * ry);
        double ty = 2.0D * (qz * rx - qx * rz);
        double tz = 2.0D * (qx * ry - qy * rx);
        double nx = rx + qw * tx + (qy * tz - qz * ty);
        double ny = ry + qw * ty + (qz * tx - qx * tz);
        double nz = rz + qw * tz + (qx * ty - qy * tx);
        this.x[i] = c[0] + nx;
        this.y[i] = c[1] + ny;
        this.z[i] = c[2] + nz;
    }

    /** where the grabbed point should go, and the player's own velocity that is added to the pull */
    public void setGrabTarget(double tx, double ty, double tz, double vx, double vy, double vz) {
        this.grabTx = tx;
        this.grabTy = ty;
        this.grabTz = tz;
        this.grabVx = vx;
        this.grabVy = vy;
        this.grabVz = vz;
    }

    /** where the grabbed point is now (out[0..2]) */
    public void grabPoint(double[] out) {
        double f = this.grabFrac;
        out[0] = this.x[this.grabA] * (1.0D - f) + this.x[this.grabB] * f;
        out[1] = this.y[this.grabA] * (1.0D - f) + this.y[this.grabB] * f;
        out[2] = this.z[this.grabA] * (1.0D - f) + this.z[this.grabB] * f;
    }

    // ---------------------------------------------------------------- motion of the whole

    /** copies the positions to where they are drawn from: call at the start of a tick */
    public void snapshotLast() {
        System.arraycopy(this.x, 0, this.lx, 0, this.count);
        System.arraycopy(this.y, 0, this.ly, 0, this.count);
        System.arraycopy(this.z, 0, this.lz, 0, this.count);
    }

    /** moves everything, speed included */
    public void translate(double dx, double dy, double dz) {
        for (int i = 0; i < this.count; i++) {
            this.x[i] += dx;
            this.y[i] += dy;
            this.z[i] += dz;
            this.px[i] += dx;
            this.py[i] += dy;
            this.pz[i] += dz;
            this.lx[i] += dx;
            this.ly[i] += dy;
            this.lz[i] += dz;
        }
    }

    /** the average velocity along an axis (blocks/s) */
    public double meanVelocity(int axis) {
        double[] now = axis == 0 ? this.x : axis == 1 ? this.y : this.z;
        double[] before = axis == 0 ? this.px : axis == 1 ? this.py : this.pz;
        double sum = 0.0D;
        for (int i = 0; i < this.count; i++) {
            sum += now[i] - before[i];
        }
        return sum / this.count / DT;
    }

    /** adds delta (blocks/s) to the velocity of every free particle along an axis */
    public void shiftVelocity(int axis, double delta) {
        double[] before = axis == 0 ? this.px : axis == 1 ? this.py : this.pz;
        for (int i = 0; i < this.count; i++) {
            if (!isPinned(i)) {
                before[i] -= delta * DT;
            }
        }
    }

    /** sets every particle going: velocity v plus the spin (rad/s) about the middle, spinShare of it */
    public void launch(double vx, double vy, double vz, double avx, double avy, double avz, double spinShare) {
        double[] c = new double[3];
        center(c);
        for (int i = 0; i < this.count; i++) {
            double rx = this.x[i] - c[0];
            double ry = this.y[i] - c[1];
            double rz = this.z[i] - c[2];
            double sx = (avy * rz - avz * ry) * spinShare;
            double sy = (avz * rx - avx * rz) * spinShare;
            double sz = (avx * ry - avy * rx) * spinShare;
            this.px[i] = this.x[i] - (vx + sx) * DT;
            this.py[i] = this.y[i] - (vy + sy) * DT;
            this.pz[i] = this.z[i] - (vz + sz) * DT;
        }
    }

    /** no particle goes faster than max (blocks/s) */
    public void clampSpeed(double max) {
        double cap = max * DT;
        for (int i = 0; i < this.count; i++) {
            double vx = this.x[i] - this.px[i];
            double vy = this.y[i] - this.py[i];
            double vz = this.z[i] - this.pz[i];
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (speed > cap) {
                double k = cap / speed;
                this.px[i] = this.x[i] - vx * k;
                this.py[i] = this.y[i] - vy * k;
                this.pz[i] = this.z[i] - vz * k;
            }
        }
    }

    /** stops everything where it is */
    public void freeze() {
        System.arraycopy(this.x, 0, this.px, 0, this.count);
        System.arraycopy(this.y, 0, this.py, 0, this.count);
        System.arraycopy(this.z, 0, this.pz, 0, this.count);
    }

    /** the speed of the fastest particle (blocks/s) */
    public double fastest() {
        double best = 0.0D;
        for (int i = 0; i < this.count; i++) {
            double vx = this.x[i] - this.px[i];
            double vy = this.y[i] - this.py[i];
            double vz = this.z[i] - this.pz[i];
            best = Math.max(best, vx * vx + vy * vy + vz * vz);
        }
        return Math.sqrt(best) / DT;
    }

    public double largestRadius() {
        double best = 0.0D;
        for (int i = 0; i < this.count; i++) {
            best = Math.max(best, this.radius[i]);
        }
        return best;
    }

    /** the middle of the particles (out[0..2]) */
    public void center(double[] out) {
        double sx = 0.0D, sy = 0.0D, sz = 0.0D;
        for (int i = 0; i < this.count; i++) {
            sx += this.x[i];
            sy += this.y[i];
            sz += this.z[i];
        }
        out[0] = sx / this.count;
        out[1] = sy / this.count;
        out[2] = sz / this.count;
    }

    /** the box around all the spheres: out = minX, minY, minZ, maxX, maxY, maxZ */
    public void bounds(double[] out) {
        double loX = Double.MAX_VALUE, loY = Double.MAX_VALUE, loZ = Double.MAX_VALUE;
        double hiX = -Double.MAX_VALUE, hiY = -Double.MAX_VALUE, hiZ = -Double.MAX_VALUE;
        for (int i = 0; i < this.count; i++) {
            double r = this.radius[i];
            loX = Math.min(loX, this.x[i] - r);
            loY = Math.min(loY, this.y[i] - r);
            loZ = Math.min(loZ, this.z[i] - r);
            hiX = Math.max(hiX, this.x[i] + r);
            hiY = Math.max(hiY, this.y[i] + r);
            hiZ = Math.max(hiZ, this.z[i] + r);
        }
        out[0] = loX;
        out[1] = loY;
        out[2] = loZ;
        out[3] = hiX;
        out[4] = hiY;
        out[5] = hiZ;
    }

    /** keeps every sphere inside 0 .. limitX and 0 .. limitZ */
    public void confineXZ(double limitX, double limitZ) {
        for (int i = 0; i < this.count; i++) {
            double r = this.radius[i];
            if (this.x[i] < r) { this.px[i] += r - this.x[i]; this.x[i] = r; }
            if (this.x[i] > limitX - r) { this.px[i] += limitX - r - this.x[i]; this.x[i] = limitX - r; }
            if (this.z[i] < r) { this.pz[i] += r - this.z[i]; this.z[i] = r; }
            if (this.z[i] > limitZ - r) { this.pz[i] += limitZ - r - this.z[i]; this.z[i] = limitZ - r; }
        }
    }

    // ---------------------------------------------------------------- positions as numbers

    /** the positions as x0 y0 z0 x1 y1 z1 ... into out (3 * count) */
    public void copyPositions(double[] out) {
        for (int i = 0; i < this.count; i++) {
            out[i * 3] = this.x[i];
            out[i * 3 + 1] = this.y[i];
            out[i * 3 + 2] = this.z[i];
        }
    }

    /** puts every particle at the positions of copyPositions, without speed */
    public void restorePositions(double[] in) {
        for (int i = 0; i < this.count; i++) {
            place(i, in[i * 3], in[i * 3 + 1], in[i * 3 + 2]);
        }
    }

    // ---------------------------------------------------------------- picking

    /**
     * the nearest segment a ray (unit direction) passes within its radius + bonus of: the distance along the ray at which it enters, or -1. pick gets the segment and how far along it the ray comes closest
     */
    public double raycastSegments(double ox, double oy, double oz, double dx, double dy, double dz,
                                  double maxDistance, double bonus, GrabPick pick) {
        double best = -1.0D;
        for (int s = 0; s < this.segments; s++) {
            int a = this.segA[s];
            int b = this.segB[s];
            double ex = this.x[b] - this.x[a];
            double ey = this.y[b] - this.y[a];
            double ez = this.z[b] - this.z[a];
            double wx = ox - this.x[a];
            double wy = oy - this.y[a];
            double wz = oz - this.z[a];

            double bd = dx * ex + dy * ey + dz * ez;    // d . e
            double c = ex * ex + ey * ey + ez * ez;     // e . e
            double dd = dx * wx + dy * wy + dz * wz;    // d . w
            double ee = ex * wx + ey * wy + ez * wz;    // e . w

            // closest point of the segment to the ray: the point of the segment at s, and the ray at t = s * bd - dd
            double sParam = 0.0D;
            double denom = c - bd * bd;
            if (c > 1.0E-12D) {
                sParam = denom > 1.0E-9D ? (ee - bd * dd) / denom : 0.0D;
                sParam = Math.max(0.0D, Math.min(1.0D, sParam));
            }
            double t = sParam * bd - dd;
            if (t < 0.0D) {
                // the ray starts past the closest point: the closest point of the segment to the ray's start
                t = 0.0D;
                sParam = c > 1.0E-12D ? Math.max(0.0D, Math.min(1.0D, ee / c)) : 0.0D;
            }

            double qx = this.x[a] + ex * sParam;
            double qy = this.y[a] + ey * sParam;
            double qz = this.z[a] + ez * sParam;
            double rx = ox + dx * t - qx;
            double ry = oy + dy * t - qy;
            double rz = oz + dz * t - qz;
            double miss2 = rx * rx + ry * ry + rz * rz;
            double reach = this.segRadius[s] + bonus;
            if (miss2 > reach * reach) {
                continue;
            }

            double hit = Math.max(0.0D, t - Math.sqrt(reach * reach - miss2));
            if (hit > maxDistance || (best >= 0.0D && hit >= best)) {
                continue;
            }
            best = hit;
            if (pick != null) {
                pick.set(s, sParam, ox + dx * hit, oy + dy * hit, oz + dz * hit);
            }
        }
        return best;
    }
}
