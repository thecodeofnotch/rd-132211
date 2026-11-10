package com.nikohalfkino.rubydung.player;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;

public class Player {

    public static final double EYE_HEIGHT = PlayerPhysics.EYE_HEIGHT;

    /** length of one tick; the physics runs on this (Quake's frametime). must match the game's tick rate */
    public static final double TICK_SECONDS = 1.0D / 60.0D;

    private final Level level;

    /** all the movement: acceleration, friction, gravity, collision, stairs, swimming (see PlayerPhysics) */
    private final PlayerPhysics physics;
    private final MoveCommand command = new MoveCommand();

    // eye position, what the camera uses. the physics origin is the middle of the feet
    public double x, y, z;
    public double prevX, prevY, prevZ;

    // velocity in blocks per tick (kept for code that reads it, the physics itself works in blocks per second)
    public double motionX, motionY, motionZ;
    public float xRotation, yRotation;

    public AABB boundingBox;

    public boolean noClip = false;

    public boolean flying = false;

    // way the body (player sprite) faces, same degrees as yRotation. in first person it's the look direction, otherwise it turns toward where the player walks, or the aim while breaking/placing
    // independent of where the camera looks
    public float bodyYaw, prevBodyYaw;

    // set by PlayerCamera: the body copies yRotation (first person) instead of turning on its own
    public boolean bodyFollowsLook = true;

    // set by PlayerCamera in freecam: movement keys are ignored (gravity and collisions still run)
    public boolean controlsLocked = false;

    /** a menu / the console has the keyboard while the world keeps running (multiplayer): no movement keys, no block work */
    public boolean screenLocked = false;

    private static final float BODY_TURN_PER_TICK = 40.0F;
    private static final double BODY_MOVE_EPSILON_SQ = 1.0E-5D;

    public float flySpeedMultiplier = 1.0F;

    public Player(Level level) {
        this.level = level;
        this.physics = new PlayerPhysics(level);

        resetPosition();
    }

    /** sets the eye position */
    private void setPosition(double x, double y, double z) {
        this.physics.teleport(x, y - EYE_HEIGHT, z);
        syncFromPhysics();

        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
    }

    // random columns tried for a dry spawn before giving up and taking the first one
    private static final int SPAWN_TRIES = 256;

    private void resetPosition() {
        int bestX = 0, bestZ = 0, bestGround = -1;

        for (int i = 0; i < SPAWN_TRIES; i++) {
            int x = (int) (Math.random() * this.level.width);
            int z = (int) (Math.random() * this.level.height);
            int ground = findGround(x, z);

            if (i == 0) {
                bestX = x;
                bestZ = z;
                bestGround = ground;   // fallback if nothing dry turns up
            }
            if (ground >= 0 && isDry(x, ground, z)) {
                bestX = x;
                bestZ = z;
                bestGround = ground;
                break;
            }
        }

        double y = (bestGround < 0 ? 0 : bestGround) + 1 + EYE_HEIGHT + 0.4D;
        setPosition(bestX + 0.5D, y, bestZ + 0.5D);
    }

    /** y of the highest solid cell in the column, -1 if there is none */
    private int findGround(int x, int z) {
        for (int y = this.level.depth - 1; y > 0; y--) {
            if (this.level.isSolidTile(x, y, z)) {
                return y;
            }
        }
        return this.level.isSolidTile(x, 0, z) ? 0 : -1;
    }

    // liquids live in their own layer, so isSolidTile knows nothing about them: a column under the sea has a solid
    // sea floor and would otherwise be a fine spawn. The ground cell and the three cells of the body above it must be dry.
    private boolean isDry(int x, int groundY, int z) {
        for (int y = groundY; y <= groundY + 3 && y < this.level.depth; y++) {
            if (this.level.getLiquids().has(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    public void setPositionAndRotation(double x, double y, double z, float yRotation, float xRotation) {
        setPosition(x, y, z);

        this.yRotation = yRotation;
        this.xRotation = xRotation;
        this.bodyYaw = yRotation;
        this.prevBodyYaw = yRotation;
    }

    public void turn(float x, float y) {
        this.yRotation += x * 0.15F;
        this.xRotation -= y * 0.15F;

        this.xRotation = Math.max(-90.0F, this.xRotation);
        this.xRotation = Math.min(90.0F, this.xRotation);
    }

    public void adjustFlySpeed(int direction) {
        if (direction > 0) {
            this.flySpeedMultiplier *= 1.15F;
        } else if (direction < 0) {
            this.flySpeedMultiplier /= 1.15F;
        }
        this.flySpeedMultiplier = Math.max(0.1F, Math.min(20.0F, this.flySpeedMultiplier));
    }

    // ---- state the physics knows, for HUD / sounds / camera effects ----

    public boolean isOnGround()          { return this.physics.isOnGround(); }
    /** 0 = dry, 1 = feet, 2 = waist, 3 = head under */
    public int getWaterLevel()           { return this.physics.getWaterLevel(); }
    /** the liquid at the feet, null when not in one */
    public LiquidTile getLiquid()        { return this.physics.getWaterType(); }
    public PlayerPhysics getPhysics()    { return this.physics; }

    public void tick() {
        tickMovement();
        updateBody();
    }

    /** body yaw for rendering, blended between the last two ticks (short way round) */
    public float getBodyYaw(float partialTicks) {
        return this.prevBodyYaw + wrapDegrees(this.bodyYaw - this.prevBodyYaw) * partialTicks;
    }

    private void updateBody() {
        this.prevBodyYaw = this.bodyYaw;

        if (this.bodyFollowsLook) {
            this.bodyYaw = this.yRotation;
            this.prevBodyYaw = this.yRotation;
            return;
        }

        float target;
        double dx = this.x - this.prevX;
        double dz = this.z - this.prevZ;

        if (!this.controlsLocked && !this.screenLocked && (KeyBinding.BREAK_BLOCK.isDown() || KeyBinding.PLACE_BLOCK.isDown())) {
            target = this.yRotation; // working on a block: face it
        } else if (dx * dx + dz * dz > BODY_MOVE_EPSILON_SQ) {
            // Same convention as the look direction: forward is (sin yaw, -cos yaw).
            target = (float) Math.toDegrees(Math.atan2(dx, -dz));
        } else {
            return;
        }

        float diff = wrapDegrees(target - this.bodyYaw);
        diff = Math.max(-BODY_TURN_PER_TICK, Math.min(BODY_TURN_PER_TICK, diff));
        this.bodyYaw += diff;
    }

    private static float wrapDegrees(float degrees) {
        degrees %= 360.0F;
        if (degrees >= 180.0F) {
            degrees -= 360.0F;
        }
        if (degrees < -180.0F) {
            degrees += 360.0F;
        }
        return degrees;
    }

    private void tickMovement() {
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;

        if (!(this.noClip || this.flying)) {
            this.flySpeedMultiplier = 1.0F;
        }

        // keys -> usercmd
        MoveCommand cmd = this.command;
        cmd.clearMovement();

        if (!this.controlsLocked && !this.screenLocked) {
            if (KeyBinding.FORWARD.isDown() || KeyMap.isDown(KeyMap.UP)) {
                cmd.forward++;
            }
            if (KeyBinding.BACK.isDown() || KeyMap.isDown(KeyMap.DOWN)) {
                cmd.forward--;
            }
            if (KeyBinding.LEFT.isDown() || KeyMap.isDown(KeyMap.LEFT)) {
                cmd.side--;
            }
            if (KeyBinding.RIGHT.isDown() || KeyMap.isDown(KeyMap.RIGHT)) {
                cmd.side++;
            }

            boolean ascendHeld = KeyBinding.JUMP.isDown() || KeyMap.isDown(KeyMap.LWIN) || KeyMap.isDown(KeyMap.RWIN);
            boolean descendHeld = KeyBinding.SNEAK.isDown() || KeyMap.isDown(KeyMap.RSHIFT);

            // walking: the jump key is BUTTON_JUMP (jump, or swim up). flying / noclip: it is "up"
            cmd.jump = ascendHeld;
            if (this.noClip || this.flying) {
                cmd.up = (ascendHeld ? 1.0F : 0.0F) - (descendHeld ? 1.0F : 0.0F);
            } else if (descendHeld) {
                cmd.up = -1.0F;   // swim down (+movedown), ignored on land
            }
        }

        cmd.yaw = this.yRotation;
        cmd.pitch = this.xRotation;

        PlayerPhysics.Mode mode = this.noClip ? PlayerPhysics.Mode.NOCLIP
                : this.flying ? PlayerPhysics.Mode.FLY
                : PlayerPhysics.Mode.WALK;

        this.physics.spectatorSpeedScale = this.flySpeedMultiplier;
        this.physics.move(cmd, mode, TICK_SECONDS);

        syncFromPhysics();
    }

    private void syncFromPhysics() {
        double[] origin = this.physics.origin;
        double[] velocity = this.physics.velocity;

        this.x = origin[PlayerPhysics.X];
        this.y = origin[PlayerPhysics.Y] + EYE_HEIGHT;
        this.z = origin[PlayerPhysics.Z];

        this.motionX = velocity[PlayerPhysics.X] * TICK_SECONDS;
        this.motionY = velocity[PlayerPhysics.Y] * TICK_SECONDS;
        this.motionZ = velocity[PlayerPhysics.Z] * TICK_SECONDS;

        this.boundingBox = this.physics.buildBox();
    }

    /** moves by a fixed amount with collision (or straight through in noclip), without touching the velocity */
    public void move(double x, double y, double z) {
        if (this.noClip) {
            double[] origin = this.physics.origin;
            origin[PlayerPhysics.X] += x;
            origin[PlayerPhysics.Y] += y;
            origin[PlayerPhysics.Z] += z;
        } else {
            this.physics.moveBy(x, y, z);
        }
        syncFromPhysics();
    }
}