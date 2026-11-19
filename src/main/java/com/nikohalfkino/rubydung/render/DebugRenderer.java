package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.player.PlayerPhysics;

import static org.lwjgl.opengl.GL11.*;

/**
 * Debug overlays drawn in world space, after the world and with the camera's matrices still active:
 * the chunk borders around the camera and the player's movement vectors.
 *
 * the vectors are drawn like the ragdoll overlay: antialiased lines with an arrow head, faint through everything and clear where nothing is in front, and pixelated (through an OverlayLayer) when the pixelation filter is off. the chunk borders are plain lines: they lie exactly on block faces and rely on a depth trick the small buffer's depth can not keep
 */
public class DebugRenderer {

    public static final int DEFAULT_CHUNK_RANGE = 2;
    public static final float DEFAULT_VECTOR_SECONDS = 0.35F;

    // The chunk border lines lie exactly on block faces, so they are drawn a hair closer than the terrain to not z-fight with it.
    private static final double LINE_DEPTH_RANGE = 0.99997;

    // the arrow head on the tip of a vector: the same size as the arrows of the ragdoll overlay (RagdollOverlay)
    private static final double HEAD_BACK = 0.1;
    private static final double HEAD_SIDE = 0.055;

    // how strongly the vectors show through everything (the clear pass, where nothing is in front, is 1)
    private static final float THROUGH_STRENGTH = 0.25F;

    private boolean chunkBorders = false;
    private boolean vectors = false;

    // chunks drawn on each side of the camera's chunk
    private int chunkRange = DEFAULT_CHUNK_RANGE;

    // a vector of 1 block per second is drawn this many blocks long
    private float vectorSeconds = DEFAULT_VECTOR_SECONDS;

    public boolean isChunkBorders() {
        return this.chunkBorders;
    }

    public void setChunkBorders(boolean chunkBorders) {
        this.chunkBorders = chunkBorders;
    }

    public boolean isVectors() {
        return this.vectors;
    }

    public void setVectors(boolean vectors) {
        this.vectors = vectors;
    }

    public int getChunkRange() {
        return this.chunkRange;
    }

    public void setChunkRange(int chunkRange) {
        this.chunkRange = Math.max(1, Math.min(8, chunkRange));
    }

    public float getVectorSeconds() {
        return this.vectorSeconds;
    }

    public void setVectorSeconds(float vectorSeconds) {
        this.vectorSeconds = Math.max(0.01F, Math.min(5.0F, vectorSeconds));
    }

    public boolean isActive() {
        return this.chunkBorders || this.vectors;
    }

    public void render(Level level, Player player, double camX, double camY, double camZ, float partialTicks) {
        if (!this.chunkBorders || level == null) {
            return;
        }

        glPushAttrib(GL_ENABLE_BIT | GL_LINE_BIT | GL_CURRENT_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        try {
            glDisable(GL_TEXTURE_2D);
            glDisable(GL_FOG);
            glDisable(GL_ALPHA_TEST);
            glDisable(GL_CULL_FACE);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);

            if (this.chunkBorders) {
                drawChunkBorders(level, camX, camY, camZ);
            }
        } finally {
            glDepthRange(0.0, 1.0);
            glPopAttrib();
        }
    }

    /** the movement vectors, through the layer (see the class comment). call after render(), with the camera's matrices active */
    public void renderVectors(OverlayLayer layer, boolean pixelationOn, Player player,
                              double camX, double camY, double camZ, float partialTicks) {
        if (!this.vectors || player == null) {
            return;
        }

        boolean inLayer = layer.begin(pixelationOn);
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
                    strength = THROUGH_STRENGTH;
                } else {
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(GL_LEQUAL);
                    strength = 1.0F;
                }
                drawVectors(player, camX, camY, camZ, partialTicks, strength);
            }
        } finally {
            glPopAttrib();
            if (inLayer) {
                layer.end();
            }
        }
    }

    // ---- chunk borders ----

    private void drawChunkBorders(Level level, double camX, double camY, double camZ) {
        int size = Chunk.SIZE;

        int[] xs = borders(level.width, camX, this.chunkRange);
        int[] ys = borders(level.depth, camY, this.chunkRange);
        int[] zs = borders(level.height, camZ, this.chunkRange);
        if (xs.length < 2 || ys.length < 2 || zs.length < 2) {
            return;
        }

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthRange(0.0, LINE_DEPTH_RANGE);

        // the grid: one long line per pair of border coordinates
        glLineWidth(1.5F);
        glColor4f(0.35F, 0.55F, 1.0F, 0.6F);
        glBegin(GL_LINES);
        for (int y : ys) {
            for (int z : zs) {
                line(xs[0], y, z, xs[xs.length - 1], y, z);
            }
        }
        for (int x : xs) {
            for (int z : zs) {
                line(x, ys[0], z, x, ys[ys.length - 1], z);
            }
        }
        for (int x : xs) {
            for (int y : ys) {
                line(x, y, zs[0], x, y, zs[zs.length - 1]);
            }
        }
        glEnd();

        // the chunk the camera is in, when it is inside the level
        int cx = (int) Math.floor(camX / size);
        int cy = (int) Math.floor(camY / size);
        int cz = (int) Math.floor(camZ / size);
        if (camX >= 0 && camX < level.width && camY >= 0 && camY < level.depth && camZ >= 0 && camZ < level.height) {
            glLineWidth(3.0F);
            glColor4f(1.0F, 0.9F, 0.2F, 1.0F);
            box(cx * size, cy * size, cz * size,
                    Math.min(level.width, (cx + 1) * size),
                    Math.min(level.depth, (cy + 1) * size),
                    Math.min(level.height, (cz + 1) * size));
        }

        // the edge of the world
        glLineWidth(3.0F);
        glColor4f(1.0F, 0.25F, 0.25F, 1.0F);
        box(0, 0, 0, level.width, level.depth, level.height);
    }

    /**
     * the chunk border coordinates along one axis, from `range` chunks before the camera's chunk to `range` chunks after it,
     * clamped to the level (the last border of a level that isn't a multiple of the chunk size is the level's edge)
     */
    private static int[] borders(int extent, double camera, int range) {
        if (extent <= 0) {
            return new int[0];
        }

        int chunks = (extent + Chunk.SIZE - 1) / Chunk.SIZE;
        int chunk = (int) Math.floor(camera / Chunk.SIZE);
        chunk = Math.max(0, Math.min(chunks - 1, chunk));

        int first = Math.max(0, chunk - range);
        int last = Math.min(chunks, chunk + range + 1);

        int[] result = new int[last - first + 1];
        for (int i = 0; i < result.length; i++) {
            result[i] = Math.min(extent, (first + i) * Chunk.SIZE);
        }
        return result;
    }

    // ---- movement vectors ----

    private void drawVectors(Player player, double camX, double camY, double camZ, float partialTicks, float strength) {
        PlayerPhysics physics = player.getPhysics();

        // the player's eye position, blended between the last two ticks like the camera does
        double px = player.prevX + (player.x - player.prevX) * partialTicks;
        double py = player.prevY + (player.y - player.prevY) * partialTicks;
        double pz = player.prevZ + (player.z - player.prevZ) * partialTicks;

        double feetY = py - Player.EYE_HEIGHT;
        double centerY = feetY + PlayerPhysics.HEIGHT * 0.5;

        // the hull, skipped when the camera is inside it (first person)
        double half = PlayerPhysics.HALF_WIDTH;
        boolean cameraInside = Math.abs(camX - px) < half + 0.05
                && Math.abs(camZ - pz) < half + 0.05
                && camY > feetY - 0.05 && camY < feetY + PlayerPhysics.HEIGHT + 0.05;
        if (!cameraInside) {
            glLineWidth(2.0F);
            if (physics.isOnGround()) {
                glColor4f(1.0F, 0.6F, 0.1F, strength);
            } else {
                glColor4f(1.0F, 1.0F, 1.0F, 0.8F * strength);
            }
            box(px - half, feetY, pz - half, px + half, feetY + PlayerPhysics.HEIGHT, pz + half);
        }

        vector(px, centerY, pz, physics.getWishVelocity(), 0.2F, 1.0F, 0.3F, strength);   // green: what the inputs ask for
        vector(px, centerY, pz, physics.velocity, 0.2F, 0.9F, 1.0F, strength);            // cyan: the velocity the physics holds
        vector(px, centerY, pz, physics.getMovedVelocity(), 1.0F, 0.3F, 1.0F, strength);  // magenta: how far it really moved
    }

    // an arrow from (x, y, z) along v (blocks per second): a thick antialiased shaft and a four-barbed head on the tip, like the arrows of RagdollOverlay
    private void vector(double x, double y, double z, double[] v, float r, float g, float b, float strength) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length < 1.0E-3) {
            return;
        }

        double dx = v[0] / length;
        double dy = v[1] / length;
        double dz = v[2] / length;
        double reach = length * this.vectorSeconds;
        double ex = x + dx * reach;
        double ey = y + dy * reach;
        double ez = z + dz * reach;

        glLineWidth(2.5F);
        glColor4f(r, g, b, 0.75F * strength);
        glBegin(GL_LINES);
        line(x, y, z, ex, ey, ez);
        glEnd();

        // two directions at right angles to the arrow and to each other, for the barbs
        double hx = Math.abs(dy) < 0.95 ? 0.0 : 1.0;
        double hy = Math.abs(dy) < 0.95 ? 1.0 : 0.0;
        double s1x = dy * 0.0 - dz * hy;
        double s1y = dz * hx - dx * 0.0;
        double s1z = dx * hy - dy * hx;
        double sl = Math.sqrt(s1x * s1x + s1y * s1y + s1z * s1z);
        s1x /= sl;
        s1y /= sl;
        s1z /= sl;
        double s2x = dy * s1z - dz * s1y;
        double s2y = dz * s1x - dx * s1z;
        double s2z = dx * s1y - dy * s1x;

        // a short vector has a smaller head, so the head never outgrows the shaft
        double fit = Math.min(1.0, reach / (HEAD_BACK * 2.0));
        glLineWidth(2.0F);
        glColor4f(r, g, b, 0.9F * strength);
        glBegin(GL_LINES);
        OverlayLayer.arrowBarbs(ex, ey, ez, dx, dy, dz, s1x, s1y, s1z, s2x, s2y, s2z, HEAD_BACK * fit, HEAD_SIDE * fit);
        glEnd();
    }

    // ---- primitives (line() only inside glBegin(GL_LINES) ... glEnd()) ----

    private static void line(double x0, double y0, double z0, double x1, double y1, double z1) {
        glVertex3d(x0, y0, z0);
        glVertex3d(x1, y1, z1);
    }

    private static void box(double x0, double y0, double z0, double x1, double y1, double z1) {
        glBegin(GL_LINES);

        line(x0, y0, z0, x1, y0, z0);
        line(x1, y0, z0, x1, y0, z1);
        line(x1, y0, z1, x0, y0, z1);
        line(x0, y0, z1, x0, y0, z0);

        line(x0, y1, z0, x1, y1, z0);
        line(x1, y1, z0, x1, y1, z1);
        line(x1, y1, z1, x0, y1, z1);
        line(x0, y1, z1, x0, y1, z0);

        line(x0, y0, z0, x0, y1, z0);
        line(x1, y0, z0, x1, y1, z0);
        line(x1, y0, z1, x1, y1, z1);
        line(x0, y0, z1, x0, y1, z1);

        glEnd();
    }
}