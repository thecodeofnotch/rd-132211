package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.Entity;
import com.nikohalfkino.rubydung.entity.WorldEntity;
import com.nikohalfkino.rubydung.entity.cube.PhysCube;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LiquidLayer;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What the liquid shader needs to know about the things standing and moving in the liquid, collected once per frame (update) and
 * uploaded when the liquid program is bound (upload):
 *
 *   entities  the footprint of every entity whose body is in a liquid surface. liquid.frag treats each one like a wall of the
 *             shore, so it gets the same foam rim (and, in a big body of liquid, the same foam waves) hugging it
 *   wake      a trail of foam. an entity that moves through a liquid leaves a stretch of wake every WAKE_SPACING blocks: a churned
 *             strip down its path and two arms that spread sideways as they age, so the body trails a V. it stays where it was
 *             dropped, the water doesn't move with the body. a body that falls in kicks up a splash of foam instead
 *
 * MAX_ENTITIES and MAX_WAKES are #defined into the shader by LiquidShader.prepare(), so they can't get out of step.
 * Only the local player and the entities of the CubeManager (cubes, dice, chess pieces) are collected here
 */
public final class LiquidEntities {

    public static final int MAX_ENTITIES = 24;
    public static final int MAX_WAKES = 64;

    /** seconds a stretch of wake lasts. also #defined into the shader */
    public static final float WAKE_LIFE = 1.2F;

    // entities further than this from the eye (blocks) get no foam of their own
    private static final double RANGE = 48.0D;

    // blocks an entity has to move before it drops its next stretch of wake, and the slowest it may go to leave a wake at all (blocks per second)
    private static final double WAKE_SPACING = 0.35D;
    private static final double MIN_WAKE_SPEED = 0.6D;

    // how far above and below its hull a liquid surface still counts as touching it
    private static final double TOUCH = 0.15D;

    // vec4 per entity: minX, minZ, maxX, maxZ.   vec4 per entity: minY, maxY, corner radius, 0
    private static final FloatBuffer BOXES = BufferUtils.createFloatBuffer(MAX_ENTITIES * 4);
    private static final FloatBuffer INFO = BufferUtils.createFloatBuffer(MAX_ENTITIES * 4);
    // vec4 per wake stretch: x, z, birth time (LiquidShader.now()), surface y.
    // vec4 per stretch: unit direction x, z it was travelling, strength 0..1, half width of the body (blocks). a splash has no direction and a NEGATIVE w: minus its radius
    private static final FloatBuffer WAKES = BufferUtils.createFloatBuffer(MAX_WAKES * 4);
    private static final FloatBuffer WAKE_INFO = BufferUtils.createFloatBuffer(MAX_WAKES * 4);

    private static final float[] wakeData = new float[MAX_WAKES * 4];
    private static final float[] wakeInfo = new float[MAX_WAKES * 4];
    private static int wakeHead = 0;

    private static int entityCount = 0;

    // what each entity did the last time it was seen, to drop wake at the right spacing and to notice a fall into the liquid
    private static final class Trail {
        double lastX, lastZ;
        boolean wet;
        int seen;
    }

    private static final Map<Entity, Trail> TRAILS = new IdentityHashMap<>();
    private static int frame = 0;

    static {
        for (int i = 0; i < MAX_WAKES; i++) {
            wakeData[i * 4 + 2] = -1.0E4F;   // born long ago: expired
        }
    }

    private LiquidEntities() {
    }

    /** gathers this frame's entities and wake. call once per frame, before the liquids are drawn. eye is where the camera is */
    public static void update(Level level, Player player, CubeManager cubes, float partialTicks, double eyeX, double eyeZ) {
        frame++;
        BOXES.clear();
        INFO.clear();
        entityCount = 0;
        float now = LiquidShader.now();

        if (level.getLiquids() != null && LiquidTile.all().size() > 0) {
            if (player != null) {
                collect(level, player, partialTicks, eyeX, eyeZ, now, -1.0D);
            }
            if (cubes != null) {
                for (WorldEntity entity : cubes.all()) {
                    boolean cube = entity instanceof PhysCube;
                    collect(level, entity, partialTicks, eyeX, eyeZ, now, cube ? 0.06D : -1.0D);
                }
            }
        }

        BOXES.flip();
        INFO.flip();

        // forget entities that are gone
        Iterator<Trail> it = TRAILS.values().iterator();
        while (it.hasNext()) {
            if (it.next().seen != frame) {
                it.remove();
            }
        }
    }

    // radius < 0 = a round body: the rim follows the whole footprint (a chess piece)
    private static void collect(Level level, Entity e, float partial, double eyeX, double eyeZ, float now, double radius) {
        if (e.dead) {
            return;
        }
        // the hull is where the entity is now, draw it where the frame is between the last tick and this one
        double sx = (e.prevX - e.x) * (1.0D - partial);
        double sy = (e.prevY - e.y) * (1.0D - partial);
        double sz = (e.prevZ - e.z) * (1.0D - partial);
        double minX = e.minX() + sx, maxX = e.maxX() + sx;
        double minY = e.minY() + sy, maxY = e.maxY() + sy;
        double minZ = e.minZ() + sz, maxZ = e.maxZ() + sz;
        double cx = (minX + maxX) * 0.5D;
        double cz = (minZ + maxZ) * 0.5D;

        Trail trail = TRAILS.get(e);
        if (trail == null) {
            trail = new Trail();
            trail.lastX = cx;
            trail.lastZ = cz;
            trail.wet = false;
            TRAILS.put(e, trail);
        }
        trail.seen = frame;

        if (Math.abs(cx - eyeX) > RANGE || Math.abs(cz - eyeZ) > RANGE) {
            trail.wet = false;
            return;
        }

        LiquidLayer layer = level.getLiquids();
        double surface = surfaceUnder(level, layer, cx, cz, minY, maxY);
        if (Double.isNaN(surface)) {
            // the corners too: a wide body can have only a side in the liquid
            surface = surfaceUnder(level, layer, minX, minZ, minY, maxY);
            if (Double.isNaN(surface)) surface = surfaceUnder(level, layer, maxX, minZ, minY, maxY);
            if (Double.isNaN(surface)) surface = surfaceUnder(level, layer, minX, maxZ, minY, maxY);
            if (Double.isNaN(surface)) surface = surfaceUnder(level, layer, maxX, maxZ, minY, maxY);
        }
        if (Double.isNaN(surface)) {
            trail.wet = false;
            trail.lastX = cx;
            trail.lastZ = cz;
            return;
        }

        // wake
        double speed = Math.hypot(e.vx, e.vz);
        double moved = Math.hypot(cx - trail.lastX, cz - trail.lastZ);
        float halfWidth = (float) Math.min(maxX - minX, maxZ - minZ) * 0.5F;
        if (!trail.wet) {
            // just fell in (or walked in): a splash of foam, bigger the harder it hit
            float hit = (float) Math.min(1.0D, 0.55D + Math.abs(e.vy) * 0.08D + speed * 0.05D);
            addWake(cx, cz, now, surface, 0.0F, 0.0F, hit, -(halfWidth + 0.15F));
            trail.lastX = cx;
            trail.lastZ = cz;
        } else if (moved >= WAKE_SPACING && speed >= MIN_WAKE_SPEED) {
            float strength = (float) Math.min(0.9D, 0.4D + speed * 0.08D);
            addWake(cx, cz, now, surface, (float) ((cx - trail.lastX) / moved), (float) ((cz - trail.lastZ) / moved), strength, halfWidth);
            trail.lastX = cx;
            trail.lastZ = cz;
        } else if (speed < MIN_WAKE_SPEED) {
            trail.lastX = cx;
            trail.lastZ = cz;
        }
        trail.wet = true;

        // foam rim
        if (entityCount < MAX_ENTITIES) {
            double half = Math.min(maxX - minX, maxZ - minZ) * 0.5D;
            double corner = radius < 0.0D ? half : Math.min(radius, half);
            BOXES.put((float) minX).put((float) minZ).put((float) maxX).put((float) maxZ);
            INFO.put((float) minY).put((float) maxY).put((float) corner).put(0.0F);
            entityCount++;
        }
    }

    // the height of the liquid surface in the column of (x, z) that this body reaches, NaN if it doesn't touch one
    private static double surfaceUnder(Level level, LiquidLayer layer, double x, double z, double minY, double maxY) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        int lo = (int) Math.floor(minY);
        int hi = (int) Math.floor(maxY) + 1;
        for (int cy = lo; cy <= hi; cy++) {
            if (!layer.has(cx, cy, cz) || layer.has(cx, cy + 1, cz)) {
                continue;
            }
            LiquidTile liquid = layer.get(cx, cy, cz);
            double y = layer.isOceanCell(cx, cy, cz)
                    ? cy + layer.oceanCellHeight(cy)
                    : liquid.getSurfaceY(level, cx, cy, cz);
            if (y >= minY - TOUCH && y <= maxY + TOUCH) {
                return y;
            }
        }
        return Double.NaN;
    }

    private static void addWake(double x, double z, float now, double surfaceY, float dirX, float dirZ, float strength, float halfWidth) {
        int i = wakeHead * 4;
        wakeData[i] = (float) x;
        wakeData[i + 1] = (float) z;
        wakeData[i + 2] = now;
        wakeData[i + 3] = (float) surfaceY;
        wakeInfo[i] = dirX;
        wakeInfo[i + 1] = dirZ;
        wakeInfo[i + 2] = strength;
        wakeInfo[i + 3] = halfWidth;
        wakeHead = (wakeHead + 1) % MAX_WAKES;
    }

    /** the program must be in use. a location of -1 (not used by the shader) is skipped */
    static void upload(int boxes, int info, int count, int wakes, int wakeInfoLoc) {
        if (count >= 0) {
            GL20.glUniform1f(count, entityCount);
        }
        if (boxes >= 0 && entityCount > 0) {
            // only the first entityCount rows are read, the rest of the array keeps whatever it had
            GL20.glUniform4(boxes, BOXES);
        }
        if (info >= 0 && entityCount > 0) {
            GL20.glUniform4(info, INFO);
        }
        if (wakes >= 0) {
            WAKES.clear();
            WAKES.put(wakeData).flip();
            GL20.glUniform4(wakes, WAKES);
        }
        if (wakeInfoLoc >= 0) {
            WAKE_INFO.clear();
            WAKE_INFO.put(wakeInfo).flip();
            GL20.glUniform4(wakeInfoLoc, WAKE_INFO);
        }
    }
}
