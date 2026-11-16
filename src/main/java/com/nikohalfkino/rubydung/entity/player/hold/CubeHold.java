package com.nikohalfkino.rubydung.entity.player.hold;

import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.cube.Dice;
import com.nikohalfkino.rubydung.entity.cube.PhysCube;
import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.net.Protocol;
import com.nikohalfkino.rubydung.entity.phys.AABB;
import com.nikohalfkino.rubydung.util.KeyBinding;

import java.util.Random;

/**
 * carrying a weighted cube, like a gravity gun: the cube floats in front of the player at an adjustable distance
 *
 * - interact key picks it up (when it is under the crosshair) and drops it again
 * - the mouse wheel moves it nearer or farther
 * - holding the place button locks the camera and turns the cube with the mouse (see rotate)
 * - the break button throws it
 *
 * while a cube is held PlayerInteraction does no block work at all, so tile selection, placing and breaking are locked. the cube is not solid for the player while carried, see PhysCube.held
 */
public final class CubeHold {

    public static final double MIN_DISTANCE = 1.5D;
    public static final double MAX_DISTANCE = 4.5D;
    public static final double DEFAULT_DISTANCE = 2.2D;
    private static final double SCROLL_STEP = 0.25D;

    // blocks per second of a throw, and the largest speed a plain drop may keep (a cube swung round by a fast turn is let go of at the speed it has)
    private static final double FLING_SPEED = 22.0D;
    private static final double DROP_MAX_SPEED = 20.0D;
    private static final double FLING_SPIN = 9.0D;      // radians per second, at most, per axis

    private static final double ROTATE_PER_PIXEL = 0.010D;   // radians

    // the cube is let go of when it ends up this far from where it should be (wedged behind a wall)
    private static final double MAX_LAG = 3.0D;

    // on a server the cube is moved by it: where the carried one should be is sent this often (game ticks)
    private static final int HOLD_SEND_EVERY_TICKS = 2;

    private final Player player;
    private final Random random = new Random();

    // the loose cube the crosshair is on (set every frame by the renderer), null when none
    private PhysCube targeted;

    private PhysCube held;
    private double distance = DEFAULT_DISTANCE;
    private float lastYaw;
    private boolean breakWasDown;
    private int ticksSinceSend;

    public CubeHold(Player player) {
        this.player = player;
    }

    public PhysCube getTargeted() {
        return this.targeted;
    }

    public void setTargeted(PhysCube cube) {
        this.targeted = cube;
    }

    public PhysCube getHeld() {
        return this.held;
    }

    public boolean isHolding() {
        return this.held != null;
    }

    /** the place button is held while carrying: the camera stays put and the mouse turns the cube */
    public boolean isRotating() {
        return this.held != null && KeyBinding.PLACE_BLOCK.isDown();
    }

    public double getDistance() {
        return this.distance;
    }

    // ---------------------------------------------------------------- actions

    /** picks up the cube under the crosshair. false if there is none */
    public boolean pickUp() {
        PhysCube cube = this.targeted;
        if (cube == null || this.held != null) {
            return false;
        }
        if (!cube.type.isHoldable() || !cube.type.getHooks().onPickup(cube, this.player)) {
            return false;
        }

        this.held = cube;
        cube.held = true;
        cube.ignorePlayer = false;
        cube.onGround = false;

        // keeps it where it is: only as far as it already is from the eyes, so it doesn't jump
        double dx = cube.x - this.player.x;
        double dy = cube.y - this.player.y;
        double dz = cube.z - this.player.z;
        this.distance = clamp(Math.sqrt(dx * dx + dy * dy + dz * dz), MIN_DISTANCE, MAX_DISTANCE);

        this.lastYaw = this.player.yRotation;
        this.breakWasDown = KeyBinding.BREAK_BLOCK.isDown();   // a break click already held does not throw it at once
        this.targeted = null;
        this.ticksSinceSend = HOLD_SEND_EVERY_TICKS;

        // on a server: asks to carry it (the server answers with a denial if it may not be)
        Multiplayer.cubePickup(cube);
        actionSound(Protocol.SOUND_PICK_UP, "phys_pick_up", cube);
        return true;
    }

    /** lets go, the cube falls from where it is */
    public void drop() {
        PhysCube cube = this.held;
        if (cube == null) {
            return;
        }

        release(cube);
        cube.type.getHooks().onDrop(cube, false);

        // it keeps the speed it really had: how far it moved in the last tick. what a held cube has as its velocity is the pull toward where the player looks, which is not how fast it is going (it is huge when the camera turns, and says nothing when the cube is stuck against a wall)
        cube.vx = (cube.x - cube.prevX) / PhysCube.DT;
        cube.vy = (cube.y - cube.prevY) / PhysCube.DT;
        cube.vz = (cube.z - cube.prevZ) / PhysCube.DT;
        double speed = cube.speed();
        if (speed > DROP_MAX_SPEED) {
            double k = DROP_MAX_SPEED / speed;
            cube.vx *= k;
            cube.vy *= k;
            cube.vz *= k;
        }
        cube.syncNetToSelf();   // joined to a server: it carries on from here until the server's state arrives
        Multiplayer.cubeRelease(cube);
        actionSound(Protocol.SOUND_PUT_DOWN, "phys_put_down", cube);
    }

    /** the sound of picking up / putting down / throwing: heard here and by the players near the cube */
    private static void actionSound(int event, String name, PhysCube cube) {
        SoundEffects.play(name);
        Multiplayer.sound(event, 1.0F, (int) Math.floor(cube.x), (int) Math.floor(cube.y), (int) Math.floor(cube.z));
    }

    /** throws it along the look direction, with a bit of spin */
    public void fling() {
        PhysCube cube = this.held;
        if (cube == null) {
            return;
        }

        if (!cube.type.isThrowable()) {
            drop();   // a type that can not be thrown is just let go of
            return;
        }

        double[] look = lookDirection();
        release(cube);
        cube.type.getHooks().onDrop(cube, true);

        // what the player is doing adds to the throw, so running forward gives a faster one
        cube.vx = look[0] * FLING_SPEED + (this.player.x - this.player.prevX) / PhysCube.DT;
        cube.vy = look[1] * FLING_SPEED + 1.5D;
        cube.vz = look[2] * FLING_SPEED + (this.player.z - this.player.prevZ) / PhysCube.DT;
        if (cube instanceof Dice) {
            // strong spin about a random axis: the flight is short, so it has to be enough to forget how the dice was held
            double[] spin = Dice.randomSpin(this.random);
            cube.avx = spin[0];
            cube.avy = spin[1];
            cube.avz = spin[2];
        } else {
            cube.avx = (this.random.nextDouble() - 0.5D) * 2.0D * FLING_SPIN;
            cube.avy = (this.random.nextDouble() - 0.5D) * 2.0D * FLING_SPIN;
            cube.avz = (this.random.nextDouble() - 0.5D) * 2.0D * FLING_SPIN;
        }
        cube.syncNetToSelf();   // joined to a server: it carries on from here until the server's state arrives
        Multiplayer.cubeRelease(cube);
        actionSound(Protocol.SOUND_THROW, "phys_throw", cube);
    }

    private void release(PhysCube cube) {
        cube.held = false;
        // not an obstacle for the player until it has left them, see PhysCube.ignorePlayer
        cube.ignorePlayer = true;
        this.held = null;
    }

    /** the server does not let this player carry the cube (or no longer): it is let go of at once, without telling the server */
    public void denied(int cubeId) {
        if (this.held != null && this.held.id == cubeId) {
            this.held.held = false;
            this.held = null;
        }
    }

    /** forgets the cube without touching it (the level is going away) */
    public void forget() {
        if (this.held != null) {
            this.held.held = false;
        }
        this.held = null;
        this.targeted = null;
    }

    /** wheel step: positive moves the cube away, negative brings it closer */
    public void scroll(int direction) {
        this.distance = clamp(this.distance + direction * SCROLL_STEP, MIN_DISTANCE, MAX_DISTANCE);
    }

    /**
     * mouse movement while the place button is held: turns the cube as seen from the player, so moving the mouse right turns its front to the right and moving it up tips its front up
     * dx / dy are the raw mouse deltas, dy positive = mouse moved up
     */
    public void rotate(float dx, float dy) {
        PhysCube cube = this.held;
        if (cube == null) {
            return;
        }

        double yaw = Math.toRadians(this.player.yRotation);

        // about the vertical: the world's up
        cube.rotateWorld(0.0D, 1.0D, 0.0D, -dx * ROTATE_PER_PIXEL);
        // about the player's right-hand side
        cube.rotateWorld(Math.cos(yaw), 0.0D, Math.sin(yaw), dy * ROTATE_PER_PIXEL);
    }

    // ---------------------------------------------------------------- tick

    /** once per game tick while carrying */
    public void tick() {
        PhysCube cube = this.held;
        if (cube == null) {
            return;
        }

        // turning the camera turns the cube with it, so the face that was toward the player stays toward them
        // (while rotating by hand the camera is locked, so this adds nothing)
        float yaw = this.player.yRotation;
        double turned = Math.toRadians(yaw - this.lastYaw);
        if (turned != 0.0D) {
            cube.rotateWorld(0.0D, 1.0D, 0.0D, -turned);
        }
        this.lastYaw = yaw;

        // the break button throws it, on the press only
        boolean breakDown = KeyBinding.BREAK_BLOCK.isDown();
        boolean fling = breakDown && !this.breakWasDown;
        this.breakWasDown = breakDown;
        if (fling) {
            fling();
            return;
        }

        // the cube was taken away (removed or carried off on the server)
        if (!cube.held) {
            this.held = null;
            return;
        }

        double[] look = lookDirection();
        cube.targetX = this.player.x + look[0] * this.distance;
        cube.targetY = this.player.y + look[1] * this.distance;
        cube.targetZ = this.player.z + look[2] * this.distance;
        cube.followVx = (this.player.x - this.player.prevX) / PhysCube.DT;
        cube.followVy = (this.player.y - this.player.prevY) / PhysCube.DT;
        cube.followVz = (this.player.z - this.player.prevZ) / PhysCube.DT;

        if (shouldLetGo(cube)) {
            drop();
            return;
        }

        if (++this.ticksSinceSend >= HOLD_SEND_EVERY_TICKS) {
            this.ticksSinceSend = 0;
            Multiplayer.cubeHold(cube);
        }
    }

    // standing on the cube (it would lift the player), or stuck behind something far from where it should be
    private boolean shouldLetGo(PhysCube cube) {
        if (cube.dead) {
            return true;
        }

        double dx = cube.targetX - cube.x;
        double dy = cube.targetY - cube.y;
        double dz = cube.targetZ - cube.z;
        if (dx * dx + dy * dy + dz * dz > MAX_LAG * MAX_LAG) {
            return true;
        }

        AABB body = this.player.boundingBox;
        return body != null
                && body.minX < cube.maxX() && body.maxX > cube.minX()
                && body.minZ < cube.maxZ() && body.maxZ > cube.minZ()
                && body.minY < cube.maxY() && body.minY > cube.maxY() - 0.3D;
    }

    // unit vector the player looks along, the same convention as the pick ray
    private double[] lookDirection() {
        double yaw = Math.toRadians(this.player.yRotation);
        double pitch = Math.toRadians(this.player.xRotation);
        return new double[]{
                Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                -(Math.cos(yaw) * Math.cos(pitch))
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}