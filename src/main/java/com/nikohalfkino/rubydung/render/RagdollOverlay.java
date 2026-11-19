package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.WorldEntity;
import com.nikohalfkino.rubydung.entity.phys.ParticleBody;
import com.nikohalfkino.rubydung.entity.phys.PhysEntity;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.player.hold.CubeHold;
import com.nikohalfkino.rubydung.entity.ragdoll.Ragdoll;
import com.nikohalfkino.rubydung.entity.ragdoll.RagdollRig;
import org.lwjgl.opengl.GL20;

import java.util.Map;
import java.util.WeakHashMap;

import static org.lwjgl.opengl.GL11.*;

/**
 * what the player needs to see while handling a ragdoll, as lines drawn over the world:
 *
 *  - a freeze: the particles that were just frozen flash ice-blue for a second and fade out (the same for every ragdoll, held by anyone or not: it is seen when the frozen set of a ragdoll changes). a part that has been frozen for a while is not marked
 *  - the part that is held: a small cross where the hand holds it
 *  - while turning (the place button held): two thin rings round the held point, one for each way the mouse turns it. the rings are coloured by the axis they turn about, R G B for X Y Z: green is the turn about the vertical (Y, mouse left / right), and the turn about your own right-hand side (mouse up / down) is red when that side points along X, blue when along Z, and the mix of the two in between (it is a horizontal axis that follows where you look). a ring lights up a little while it is being turned, and an arrow on it, with a short tail, shows which way the front of the thing is going
 *
 * each thing is drawn twice, faintly through everything and clearly where it is not behind anything, so it is never lost inside the model. call with the camera's matrices active, after the highlights
 *
 * it is drawn through an OverlayLayer: with the pixelation filter off the lines are still chunky (a small antialiased picture stretched over the view), with it on they already are
 */
public final class RagdollOverlay {

    private static final float[] ICE = {0.55F, 0.85F, 1.0F};
    // the axes: red X, green Y, blue Z (the blue is a little lighter than pure blue, which is too dark to see on the sky)
    private static final float[] AXIS_X = {1.0F, 0.2F, 0.2F};
    private static final float[] AXIS_Y = {0.2F, 1.0F, 0.2F};
    private static final float[] AXIS_Z = {0.3F, 0.5F, 1.0F};

    private static final double RING_RADIUS = 0.42D;
    private static final int RING_SEGMENTS = 48;

    // a turn this big (radians, see CubeHold.getSpinAboutUp) lights a ring up fully
    private static final double FULL_SPIN = 0.25D;
    private static final double ARROW_SPIN = 0.02D;
    private static final double ARROW_TAIL = 0.9D;   // radians of the ring behind the arrow head

    // a freeze is shown for this long (ms), fading out
    private static final long FLASH_MS = 1000L;

    /** what was last seen of a ragdoll: which particles were frozen, and when each one was frozen (ms, 0 = long ago) */
    private static final class Seen {
        int mask;
        final long[] since = new long[RagdollRig.PARTICLES];
    }

    // ragdolls that are gone are forgotten with them
    private final Map<Ragdoll, Seen> seen = new WeakHashMap<>();

    private final double[] pos = new double[RagdollRig.PARTICLES * 3];

    // the colour of the ring about the player's right-hand side, mixed from AXIS_X and AXIS_Z (reused)
    private final float[] rightColor = new float[3];

    public void render(OverlayLayer layer, boolean pixelationOn, CubeManager manager, CubeHold hold, Player player,
                       float partialTicks, double eyeX, double eyeY, double eyeZ) {
        PhysEntity held = hold.getHeld();
        Ragdoll heldDoll = held instanceof Ragdoll ? (Ragdoll) held : null;

        // which particles were frozen just now (a ragdoll seen for the first time is not flashed: it was frozen before)
        long now = System.currentTimeMillis();
        boolean flashing = false;
        for (WorldEntity entity : manager.all()) {
            if (entity instanceof Ragdoll && noteFreezes((Ragdoll) entity, now)) {
                flashing = true;
            }
        }
        if (heldDoll == null && !flashing) {
            return;
        }

        // with the pixelation filter off, everything below goes into the layer's small buffer
        boolean inLayer = layer.begin(pixelationOn);

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        glPushAttrib(GL_ENABLE_BIT | GL_LINE_BIT | GL_CURRENT_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        try {
            glDisable(GL_TEXTURE_2D);
            glDisable(GL_FOG);
            glDisable(GL_ALPHA_TEST);
            glDisable(GL_CULL_FACE);
            glEnable(GL_BLEND);
            layer.blend();
            glDepthMask(false);
            glEnable(GL_LINE_SMOOTH);
            glHint(GL_LINE_SMOOTH_HINT, GL_NICEST);

            for (int pass = 0; pass < 2; pass++) {
                float strength;
                if (pass == 0) {
                    glDisable(GL_DEPTH_TEST);
                    strength = 0.2F;
                } else {
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(GL_LEQUAL);
                    strength = 1.0F;
                }

                if (flashing) {
                    for (WorldEntity entity : manager.all()) {
                        if (entity instanceof Ragdoll) {
                            drawFrozen((Ragdoll) entity, partialTicks, strength, now);
                        }
                    }
                }
                if (heldDoll != null) {
                    drawHeld(heldDoll, partialTicks, strength);
                    if (hold.isRotating()) {
                        drawTurnRings(heldDoll, hold, player, partialTicks, strength, eyeX, eyeY, eyeZ);
                    }
                }
            }
        } finally {
            glPopAttrib();
            GL20.glUseProgram(program);
            if (inLayer) {
                layer.end();
            }
        }
    }

    // ---------------------------------------------------------------- frozen

    // notes what froze since the last frame, true if any particle of this ragdoll is still within its flash
    private boolean noteFreezes(Ragdoll doll, long now) {
        int mask = doll.body().frozenMask();
        Seen s = this.seen.get(doll);
        if (s == null) {
            s = new Seen();
            s.mask = mask;
            this.seen.put(doll, s);
            return false;
        }
        int fresh = mask & ~s.mask;
        s.mask = mask;
        boolean any = false;
        for (int i = 0; i < RagdollRig.PARTICLES; i++) {
            if ((fresh & (1 << i)) != 0) {
                s.since[i] = now;
            }
            if ((mask & (1 << i)) != 0 && now - s.since[i] < FLASH_MS) {
                any = true;
            }
        }
        return any;
    }

    // how bright the flash of particle i is: 1 at the moment it froze, down to 0 after FLASH_MS (0 when it is not frozen, or did long ago)
    private float flash(Seen s, int i, long now) {
        if ((s.mask & (1 << i)) == 0) {
            return 0.0F;
        }
        float t = (now - s.since[i]) / (float) FLASH_MS;
        return t >= 1.0F ? 0.0F : (1.0F - t) * (1.0F - t);
    }

    private void drawFrozen(Ragdoll doll, float partialTicks, float strength, long now) {
        Seen s = this.seen.get(doll);
        if (s == null || s.mask == 0) {
            return;
        }
        ParticleBody b = doll.body();
        boolean any = false;
        for (int i = 0; i < b.count && !any; i++) {
            any = flash(s, i, now) > 0.0F;
        }
        if (!any) {
            return;
        }
        doll.renderPositions(partialTicks, this.pos);

        // the parts that were frozen at both ends
        glLineWidth(2.5F);
        glBegin(GL_LINES);
        for (int seg = 0; seg < b.segmentCount(); seg++) {
            int a = b.segmentA(seg);
            int e = b.segmentB(seg);
            float f = Math.min(flash(s, a, now), flash(s, e, now));
            if (f > 0.0F) {
                glColor4f(ICE[0], ICE[1], ICE[2], 0.7F * f * strength);
                line(a, e);
            }
        }
        glEnd();

        // the points: a small diamond that opens up as it fades
        for (int i = 0; i < b.count; i++) {
            float f = flash(s, i, now);
            if (f <= 0.0F) {
                continue;
            }
            double size = 0.04D + b.radius[i] * 0.3D + (1.0D - f) * 0.06D;
            glColor4f(ICE[0], ICE[1], ICE[2], 0.3F * f * strength);
            diamondFill(this.pos[i * 3], this.pos[i * 3 + 1], this.pos[i * 3 + 2], size);
            glLineWidth(1.5F);
            glColor4f(0.85F, 0.97F, 1.0F, 0.9F * f * strength);
            diamondEdges(this.pos[i * 3], this.pos[i * 3 + 1], this.pos[i * 3 + 2], size);
        }
    }

    // ---------------------------------------------------------------- held

    // the point the part is held by
    private void drawHeld(Ragdoll doll, float partialTicks, float strength) {
        ParticleBody b = doll.body();
        if (b.grabbedA() < 0) {
            return;
        }
        doll.renderPositions(partialTicks, this.pos);

        double[] h = holdPoint(b);
        glLineWidth(1.0F);
        glColor4f(1.0F, 1.0F, 1.0F, 0.45F * strength);
        double c = 0.03D;
        glBegin(GL_LINES);
        glVertex3d(h[0] - c, h[1], h[2]);
        glVertex3d(h[0] + c, h[1], h[2]);
        glVertex3d(h[0], h[1] - c, h[2]);
        glVertex3d(h[0], h[1] + c, h[2]);
        glVertex3d(h[0], h[1], h[2] - c);
        glVertex3d(h[0], h[1], h[2] + c);
        glEnd();
    }

    // where the part is held, from the particles as they are drawn (pos holds them)
    private double[] holdPoint(ParticleBody b) {
        int a = b.grabbedA();
        int e = b.grabbedB();
        double f = b.grabFraction();
        return new double[]{
                this.pos[a * 3] * (1.0D - f) + this.pos[e * 3] * f,
                this.pos[a * 3 + 1] * (1.0D - f) + this.pos[e * 3 + 1] * f,
                this.pos[a * 3 + 2] * (1.0D - f) + this.pos[e * 3 + 2] * f};
    }

    // ---------------------------------------------------------------- turning

    private void drawTurnRings(Ragdoll doll, CubeHold hold, Player player, float partialTicks, float strength,
                               double eyeX, double eyeY, double eyeZ) {
        ParticleBody b = doll.body();
        if (b.grabbedA() < 0) {
            return;
        }
        doll.renderPositions(partialTicks, this.pos);
        double[] c = holdPoint(b);

        // the axes CubeHold.rotate turns about: the world's up (Y, green), and the player's right-hand side, a horizontal axis
        // that is X (red) when it points along x and Z (blue) when along z: its colour is the mix of the two by how much of each it has
        double yaw = Math.toRadians(player.yRotation);
        double rx = Math.cos(yaw);
        double rz = Math.sin(yaw);
        double wx = Math.abs(rx);
        double wz = Math.abs(rz);
        double peak = Math.max(wx, wz);
        wx /= peak;   // the stronger of the two is full colour, so the ring is as bright at 45 degrees as along an axis
        wz /= peak;
        for (int i = 0; i < 3; i++) {
            this.rightColor[i] = (float) Math.min(1.0D, AXIS_X[i] * wx + AXIS_Z[i] * wz);
        }

        ring(c, 0.0D, 1.0D, 0.0D, hold.getSpinAboutUp(), AXIS_Y, strength, eyeX, eyeY, eyeZ);
        ring(c, rx, 0.0D, rz, hold.getSpinAboutRight(), this.rightColor, strength, eyeX, eyeY, eyeZ);
    }

    /**
     * a ring of radius RING_RADIUS about the axis (unit) through c. spin is the recent turn about that axis (radians, right-handed: positive is counter-clockwise looking down the axis toward the viewer). when it is not 0 the ring is brighter and an arrow on the side nearest the eye points the way the turn goes
     */
    private void ring(double[] c, double ax, double ay, double az, double spin, float[] col, float strength,
                      double eyeX, double eyeY, double eyeZ) {
        double activity = Math.min(1.0D, Math.abs(spin) / FULL_SPIN);

        // two unit vectors in the plane of the ring
        double hx = Math.abs(ay) < 0.9D ? 0.0D : 1.0D;
        double hy = Math.abs(ay) < 0.9D ? 1.0D : 0.0D;
        double ux = -az * hy;   // axis x helper, the helper having no z
        double uy = az * hx;
        double uz = ax * hy - ay * hx;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        double vx = ay * uz - az * uy;
        double vy = az * ux - ax * uz;
        double vz = ax * uy - ay * ux;

        glLineWidth(1.0F + 1.0F * (float) activity);
        glColor4f(col[0], col[1], col[2], (0.14F + 0.5F * (float) activity) * strength);
        glBegin(GL_LINE_LOOP);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            double t = Math.PI * 2.0D * i / RING_SEGMENTS;
            double cs = Math.cos(t) * RING_RADIUS;
            double sn = Math.sin(t) * RING_RADIUS;
            glVertex3d(c[0] + ux * cs + vx * sn, c[1] + uy * cs + vy * sn, c[2] + uz * cs + vz * sn);
        }
        glEnd();

        if (Math.abs(spin) < ARROW_SPIN) {
            return;
        }

        // the point of the ring nearest the eye: r0 (the direction from the middle toward the eye, with the axis part taken off)
        double dx = eyeX - c[0];
        double dy = eyeY - c[1];
        double dz = eyeZ - c[2];
        double along = dx * ax + dy * ay + dz * az;
        double rx = dx - ax * along;
        double ry = dy - ay * along;
        double rz = dz - az * along;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rl < 1.0E-6D) {
            rx = ux;
            ry = uy;
            rz = uz;
        } else {
            rx /= rl;
            ry /= rl;
            rz /= rl;
        }
        // the way a positive turn goes there: axis x r0
        double tx = ay * rz - az * ry;
        double ty = az * rx - ax * rz;
        double tz = ax * ry - ay * rx;
        double dir = spin > 0.0D ? 1.0D : -1.0D;
        tx *= dir;
        ty *= dir;
        tz *= dir;

        // the tail: the ring behind the head, thick
        glLineWidth(2.5F);
        glColor4f(col[0], col[1], col[2], (0.35F + 0.4F * (float) activity) * strength);
        int steps = 12;
        glBegin(GL_LINE_STRIP);
        for (int i = 0; i <= steps; i++) {
            double back = -ARROW_TAIL * (1.0D - (double) i / steps);   // from the end of the tail to the head
            double cs = Math.cos(back);
            double sn = Math.sin(back);
            // the point at an angle `back` from r0, along the way of the turn (tx,ty,tz is already signed)
            glVertex3d(c[0] + (rx * cs + tx * sn) * RING_RADIUS,
                    c[1] + (ry * cs + ty * sn) * RING_RADIUS,
                    c[2] + (rz * cs + tz * sn) * RING_RADIUS);
        }
        glEnd();

        // the head: four barbs back from the tip, out and in and to both sides along the axis
        double px = c[0] + rx * RING_RADIUS;
        double py = c[1] + ry * RING_RADIUS;
        double pz = c[2] + rz * RING_RADIUS;
        double back = 0.1D;
        double side = 0.055D;
        glLineWidth(2.0F);
        glBegin(GL_LINES);
        OverlayLayer.arrowBarbs(px, py, pz, tx, ty, tz, rx, ry, rz, ax, ay, az, back, side);
        glEnd();
    }

    // ---------------------------------------------------------------- primitives

    // inside glBegin(GL_LINES): a line between two particles as they are drawn
    private void line(int a, int b) {
        glVertex3d(this.pos[a * 3], this.pos[a * 3 + 1], this.pos[a * 3 + 2]);
        glVertex3d(this.pos[b * 3], this.pos[b * 3 + 1], this.pos[b * 3 + 2]);
    }

    private static void diamondEdges(double x, double y, double z, double s) {
        glBegin(GL_LINES);
        double[][] v = {{s, 0, 0}, {-s, 0, 0}, {0, s, 0}, {0, -s, 0}, {0, 0, s}, {0, 0, -s}};
        // every pair of vertices that are not opposite ones
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                if (i / 2 == j / 2) {
                    continue;
                }
                glVertex3d(x + v[i][0], y + v[i][1], z + v[i][2]);
                glVertex3d(x + v[j][0], y + v[j][1], z + v[j][2]);
            }
        }
        glEnd();
    }

    private static void diamondFill(double x, double y, double z, double s) {
        glBegin(GL_TRIANGLES);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    glVertex3d(x + sx * s, y, z);
                    glVertex3d(x, y + sy * s, z);
                    glVertex3d(x, y, z + sz * s);
                }
            }
        }
        glEnd();
    }
}
