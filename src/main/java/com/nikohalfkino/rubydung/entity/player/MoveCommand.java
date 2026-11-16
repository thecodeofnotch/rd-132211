package com.nikohalfkino.rubydung.entity.player;

/**
 * One tick of player intent, the equivalent of Quake's usercmd_t.
 * forward / side / up are -1..1 (keyboard: -1, 0 or 1); PlayerPhysics scales them to Quake's
 * forwardmove / sidemove / upmove speeds.
 */
public class MoveCommand {

    /** +1 = walk forward, -1 = back */
    public float forward;
    /** +1 = strafe right, -1 = left */
    public float side;
    /** +1 = move up, -1 = down. Only used while swimming and flying (walking jumps with {@link #jump}) */
    public float up;
    /** jump button held (BUTTON_JUMP) */
    public boolean jump;

    /** look direction in degrees, same convention as Player.yRotation / xRotation */
    public float yaw, pitch;

    public void clearMovement() {
        this.forward = 0.0F;
        this.side = 0.0F;
        this.up = 0.0F;
        this.jump = false;
    }
}