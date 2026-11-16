package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;

import static org.lwjgl.opengl.GL11.glRotatef;
import static org.lwjgl.opengl.GL11.glTranslated;
import static org.lwjgl.opengl.GL11.glTranslatef;

/**
 * where the world is seen from, call update() once per frame before apply()
 *
 * first person: the camera sits at the player's eyes
 *
 * third person: the camera orbits the eyes a few blocks behind, pulled in by blocks in the way. the mouse still aims the player (crosshair, picking and movement keys work like first person, the aim ray goes through the eyes) but the body is free to face another way, see Player.bodyYaw
 *
 * freecam: the camera is detached and the player stands still (no input, no picking) so the player sprite can be looked at. the mouse turns the camera, movement keys fly it
 */
public final class PlayerCamera {

    public enum View {
        FIRST_PERSON("First Person"),
        THIRD_PERSON("Third Person");

        private final String displayName;

        View(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }
    }

    // same fixed pull-back as CameraUtil, so first person looks identical
    private static final float CAMERA_BACK_OFFSET = 0.3F;

    private static final float THIRD_PERSON_DISTANCE = 4.0F;
    private static final double COLLISION_STEP = 0.1D;
    private static final double COLLISION_MARGIN = 0.25D;
    // how fast the orbit distance eases back out after a wall let go of the camera (per second)
    private static final float DISTANCE_EASE = 10.0F;

    public static final float DEFAULT_FREECAM_SPEED = 10.0F;
    public static final float MIN_FREECAM_SPEED = 0.5F;
    public static final float MAX_FREECAM_SPEED = 2048.0F;

    private static final float MOUSE_SENSITIVITY = 0.15F;

    private View view = View.FIRST_PERSON;

    private boolean freecam = false;
    private double freeX, freeY, freeZ;
    private float freeYaw, freePitch;
    private float freecamSpeed = DEFAULT_FREECAM_SPEED;

    // The pose of the current frame.
    private double pivotX, pivotY, pivotZ;
    private float yaw, pitch;
    private float distance = 0.0F;
    private double posX, posY, posZ;

    private long lastFrameNanos = 0L;

    // Views

    public View getView() {
        return this.view;
    }

    /** first <-> third person, does nothing while the freecam is on */
    public boolean cycleView() {
        if (this.freecam) {
            return false;
        }
        this.view = this.view == View.FIRST_PERSON ? View.THIRD_PERSON : View.FIRST_PERSON;
        return true;
    }

    public boolean isFreecam() {
        return this.freecam;
    }

    /** starts the freecam from wherever the camera is now, or goes back to the player's view */
    public void setFreecam(boolean on) {
        if (on == this.freecam) {
            return;
        }
        if (on) {
            this.freeX = this.posX;
            this.freeY = this.posY;
            this.freeZ = this.posZ;
            this.freeYaw = this.yaw;
            this.freePitch = this.pitch;
        }
        this.freecam = on;
    }

    public float getFreecamSpeed() {
        return this.freecamSpeed;
    }

    /** blocks per second */
    public void setFreecamSpeed(float speed) {
        this.freecamSpeed = Math.max(MIN_FREECAM_SPEED, Math.min(MAX_FREECAM_SPEED, speed));
    }

    public boolean isFirstPerson() {
        return !this.freecam && this.view == View.FIRST_PERSON;
    }

    public boolean isPlayerVisible() {
        return !isFirstPerson();
    }

    // Pose

    public double getX() {
        return this.posX;
    }

    public double getY() {
        return this.posY;
    }

    public double getZ() {
        return this.posZ;
    }

    public float getYaw() {
        return this.yaw;
    }

    public float getPitch() {
        return this.pitch;
    }

    // Input

    /** mouse movement: turns the freecam, or the player (whose aim the third-person camera follows) */
    public void turn(Player player, float dx, float dy) {
        if (this.freecam) {
            this.freeYaw += dx * MOUSE_SENSITIVITY;
            this.freePitch -= dy * MOUSE_SENSITIVITY;
            this.freePitch = Math.max(-90.0F, Math.min(90.0F, this.freePitch));
        } else {
            player.turn(dx, dy);
        }
    }

    /** works out this frame's camera pose. playing = the game has the keyboard (no screen or console open), so the freecam only flies while it should */
    public void update(Player player, Level level, float partialTicks, boolean playing) {
        long now = System.nanoTime();
        float dt = this.lastFrameNanos == 0L ? 0.0F : Math.min(0.1F, (now - this.lastFrameNanos) / 1.0E9F);
        this.lastFrameNanos = now;

        // The player's body follows the mouse only in first person, and ignores the keys in freecam.
        player.bodyFollowsLook = isFirstPerson();
        player.controlsLocked = this.freecam;

        if (this.freecam) {
            if (playing) {
                flyFreecam(dt);
            }
            this.pivotX = this.freeX;
            this.pivotY = this.freeY;
            this.pivotZ = this.freeZ;
            this.yaw = this.freeYaw;
            this.pitch = this.freePitch;
            this.distance = 0.0F;
        } else {
            this.pivotX = player.prevX + (player.x - player.prevX) * partialTicks;
            this.pivotY = player.prevY + (player.y - player.prevY) * partialTicks;
            this.pivotZ = player.prevZ + (player.z - player.prevZ) * partialTicks;
            this.yaw = player.yRotation;
            this.pitch = player.xRotation;

            if (this.view == View.FIRST_PERSON) {
                this.distance = 0.0F;
            } else {
                float target = clearDistance(level, player);
                if (target < this.distance) {
                    this.distance = target; // never let a wall sit between camera and player
                } else {
                    this.distance += (target - this.distance) * Math.min(1.0F, dt * DISTANCE_EASE);
                }
            }
        }

        double[] dir = direction(this.yaw, this.pitch);
        this.posX = this.pivotX - dir[0] * this.distance;
        this.posY = this.pivotY - dir[1] * this.distance;
        this.posZ = this.pivotZ - dir[2] * this.distance;
    }

    /** sets the modelview matrix for the pose from the last update() */
    public void apply() {
        glTranslatef(0.0F, 0.0F, -(CAMERA_BACK_OFFSET + this.distance));

        glRotatef(this.pitch, 1.0F, 0.0F, 0.0F);
        glRotatef(this.yaw, 0.0F, 1.0F, 0.0F);

        glTranslated(-this.pivotX, -this.pivotY, -this.pivotZ);
    }

    /**
     * a copy of this frame's pose as a WorldView, for GameRenderer.renderWorld (and so for screenshots). call after update()
     *
     * the sky belongs to the player, not to the camera: in the freecam it stays centred on the player, so the camera can fly out and look at it up close (or from outside). otherwise the camera is the centre
     */
    public CameraSnapshot snapshot(Player player, float partialTicks) {
        double skyX = this.posX;
        double skyY = this.posY;
        double skyZ = this.posZ;
        if (this.freecam) {
            skyX = player.prevX + (player.x - player.prevX) * partialTicks;
            skyY = player.prevY + (player.y - player.prevY) * partialTicks;
            skyZ = player.prevZ + (player.z - player.prevZ) * partialTicks;
        }
        return new CameraSnapshot(this.pivotX, this.pivotY, this.pivotZ, CAMERA_BACK_OFFSET, this.distance,
                this.yaw, this.pitch, this.posX, this.posY, this.posZ, skyX, skyY, skyZ,
                this.freecam, isPlayerVisible());
    }

    // Internals

    // unit vector the camera looks along (same convention as the pick ray)
    private static double[] direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        return new double[]{
                Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                -(Math.cos(yawRad) * Math.cos(pitchRad))
        };
    }

    private void flyFreecam(float dt) {
        float forward = 0.0F;
        float strafe = 0.0F;
        float vertical = 0.0F;

        if (KeyBinding.FORWARD.isDown() || KeyMap.isDown(KeyMap.UP)) {
            forward++;
        }
        if (KeyBinding.BACK.isDown() || KeyMap.isDown(KeyMap.DOWN)) {
            forward--;
        }
        if (KeyBinding.RIGHT.isDown() || KeyMap.isDown(KeyMap.RIGHT)) {
            strafe++;
        }
        if (KeyBinding.LEFT.isDown() || KeyMap.isDown(KeyMap.LEFT)) {
            strafe--;
        }
        if (KeyBinding.JUMP.isDown() || KeyMap.isDown(KeyMap.LWIN) || KeyMap.isDown(KeyMap.RWIN)) {
            vertical++;
        }
        if (KeyBinding.SNEAK.isDown() || KeyMap.isDown(KeyMap.RSHIFT)) {
            vertical--;
        }

        double[] look = direction(this.freeYaw, this.freePitch);
        double rightX = Math.cos(Math.toRadians(this.freeYaw));
        double rightZ = Math.sin(Math.toRadians(this.freeYaw));

        double mx = look[0] * forward + rightX * strafe;
        double my = look[1] * forward + vertical;
        double mz = look[2] * forward + rightZ * strafe;

        double length = Math.sqrt(mx * mx + my * my + mz * mz);
        if (length < 1.0E-6) {
            return;
        }

        double step = this.freecamSpeed * dt / Math.max(1.0D, length);
        this.freeX += mx * step;
        this.freeY += my * step;
        this.freeZ += mz * step;
    }

    // how far behind the eyes the third-person camera can go before a block gets in the way
    private float clearDistance(Level level, Player player) {
        if (player.noClip) {
            return THIRD_PERSON_DISTANCE;
        }

        double[] dir = direction(this.yaw, this.pitch);
        double limit = THIRD_PERSON_DISTANCE + CAMERA_BACK_OFFSET + COLLISION_MARGIN;
        double free = limit;

        for (double t = COLLISION_STEP; t <= limit; t += COLLISION_STEP) {
            int x = (int) Math.floor(this.pivotX - dir[0] * t);
            int y = (int) Math.floor(this.pivotY - dir[1] * t);
            int z = (int) Math.floor(this.pivotZ - dir[2] * t);
            if (level.isSolidTile(x, y, z)) {
                free = t - COLLISION_STEP;
                break;
            }
        }

        double usable = free - CAMERA_BACK_OFFSET - COLLISION_MARGIN;
        return (float) Math.max(0.0D, Math.min(THIRD_PERSON_DISTANCE, usable));
    }
}