package com.nikohalfkino.rubydung.entity.player.hold;

import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.cube.Dice;
import com.nikohalfkino.rubydung.entity.phys.GrabPick;
import com.nikohalfkino.rubydung.entity.phys.PhysEntity;
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
 * while a cube is held PlayerInteraction does no block work at all, so tile selection, placing and breaking are locked. the cube is not solid for the player while carried, see PhysEntity.held
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

    // a body made of parts (a ragdoll) is not let go of because of one bad tick. what lets go of it, and for how long it has to last (game ticks):
    //  - the player is standing on it
    //  - the grabbed point is further from the player than they can reach (something holds it back, or they walked away from it)
    private static final int STOOD_ON_LET_GO_TICKS = 6;
    private static final int FAR_LET_GO_TICKS = 20;
    private static final double MAX_HOLD_RANGE = MAX_DISTANCE + 3.5D;
    // the point the hand pulls toward is kept this near the grabbed point: a limb that is held back by the floor or a wall (looking down at the floor while holding a ragdoll far out) is not pulled harder and harder, and the hand is never "too far" from it just because of where the player looks
    private static final double MAX_PULL = 2.5D;

    // on a server the cube is moved by it: where the carried one should be is sent this often (game ticks)
    private static final int HOLD_SEND_EVERY_TICKS = 2;

    private final Player player;
    private final Random random = new Random();

    // the loose cube the crosshair is on (set every frame by the renderer), null when none
    private PhysEntity targeted;
    // the part of it under the crosshair (a limb of a ragdoll) and where along it
    private final GrabPick targetedPick = new GrabPick();

    private PhysEntity held;
    private double distance = DEFAULT_DISTANCE;
    private float lastYaw;
    private boolean breakWasDown;
    private int ticksSinceSend;
    private int stoodOnTicks;
    private int farTicks;

    // the turn the player has just made by hand, in radians about the world's up and about their own right-hand side, fading out (the overlay shows which way it is going)
    private static final double SPIN_FADE = 0.88D;   // per game tick
    private double spinUp;
    private double spinRight;

    public CubeHold(Player player) {
        this.player = player;
    }

    public PhysEntity getTargeted() {
        return this.targeted;
    }

    public void setTargeted(PhysEntity cube) {
        setTargeted(cube, null);
    }

    /** the body under the crosshair and the part of it that is (null pick = the body as a whole) */
    public void setTargeted(PhysEntity cube, GrabPick pick) {
        this.targeted = cube;
        if (pick != null) {
            this.targetedPick.copyFrom(pick);
        } else {
            this.targetedPick.clear();
        }
    }

    /** the part of the targeted body under the crosshair */
    public GrabPick getTargetedPick() {
        return this.targetedPick;
    }

    public PhysEntity getHeld() {
        return this.held;
    }

    public boolean isHolding() {
        return this.held != null;
    }

    /** the place button is held while carrying: the camera stays put and the mouse turns the cube */
    public boolean isRotating() {
        return this.held != null && KeyBinding.PLACE_BLOCK.isDown();
    }

    /** the recent turn about the world's up (positive = counter-clockwise seen from above), fading to 0 shortly after the mouse stops */
    public double getSpinAboutUp() {
        return this.spinUp;
    }

    /** the recent turn about the player's right-hand side, see getSpinAboutUp */
    public double getSpinAboutRight() {
        return this.spinRight;
    }

    public double getDistance() {
        return this.distance;
    }

    // ---------------------------------------------------------------- actions

    /** picks up the cube under the crosshair. false if there is none */
    public boolean pickUp() {
        PhysEntity cube = this.targeted;
        if (cube == null || this.held != null) {
            return false;
        }
        if (!cube.type.isHoldable() || !cube.type.getHooks().onPickup(cube, this.player)) {
            return false;
        }

        int part = this.targetedPick.part;
        double frac = this.targetedPick.frac;

        this.held = cube;
        cube.held = true;
        cube.ignorePlayer = false;
        cube.onGround = false;
        cube.beginGrab(part, frac);   // a ragdoll is taken by the limb that was picked, a cube by its middle

        // keeps it where it is: only as far as it already is from the eyes, so it doesn't jump
        double dx = cube.grabX() - this.player.x;
        double dy = cube.grabY() - this.player.y;
        double dz = cube.grabZ() - this.player.z;
        this.distance = clamp(Math.sqrt(dx * dx + dy * dy + dz * dz), MIN_DISTANCE, MAX_DISTANCE);

        this.lastYaw = this.player.yRotation;
        this.breakWasDown = KeyBinding.BREAK_BLOCK.isDown();   // a break click already held does not throw it at once
        this.targeted = null;
        this.ticksSinceSend = HOLD_SEND_EVERY_TICKS;
        this.stoodOnTicks = 0;
        this.farTicks = 0;
        this.spinUp = 0.0D;
        this.spinRight = 0.0D;

        // on a server: asks to carry it (the server answers with a denial if it may not be)
        Multiplayer.cubePickup(cube, part, frac);
        actionSound(Protocol.SOUND_PICK_UP, "phys_pick_up", cube);
        return true;
    }

    /** lets go, the cube falls from where it is */
    public void drop() {
        PhysEntity cube = this.held;
        if (cube == null) {
            return;
        }

        release(cube);
        cube.type.getHooks().onDrop(cube, false);

        // it keeps the speed it really had (see PhysEntity.dropMotion), not the pull toward where the player looks
        cube.dropMotion(DROP_MAX_SPEED);
        cube.syncNetToSelf();   // joined to a server: it carries on from here until the server's state arrives
        Multiplayer.cubeRelease(cube, false, cube.vx, cube.vy, cube.vz, 0.0D, 0.0D, 0.0D);
        actionSound(Protocol.SOUND_PUT_DOWN, "phys_put_down", cube);
    }

    /**
     * freezes the carried part where it is in the air and lets go of it (the gmod freeze): the rest of the body hangs from it. false if the carried body can not be frozen
     */
    public boolean freeze() {
        PhysEntity cube = this.held;
        if (cube == null || !cube.freezeHeld()) {
            return false;
        }
        release(cube);
        cube.type.getHooks().onDrop(cube, false);
        cube.syncNetToSelf();
        Multiplayer.cubeFreeze(cube, 0);
        actionSound(Protocol.SOUND_PUT_DOWN, "phys_put_down", cube);
        return true;
    }

    /** the body under the crosshair has parts frozen: they are all free again. false if it has none (or nothing is targeted, or something is carried) */
    public boolean unfreezeTargeted() {
        PhysEntity cube = this.targeted;
        if (cube == null || this.held != null || !cube.hasFrozenParts()) {
            return false;
        }
        cube.unfreezeAll();
        Multiplayer.cubeFreeze(cube, 1);
        actionSound(Protocol.SOUND_PICK_UP, "phys_pick_up", cube);
        return true;
    }

    /** the sound of picking up / putting down / throwing: heard here and by the players near the cube */
    private static void actionSound(int event, String name, PhysEntity cube) {
        SoundEffects.play(name);
        Multiplayer.sound(event, 1.0F, (int) Math.floor(cube.x), (int) Math.floor(cube.y), (int) Math.floor(cube.z));
    }

    /** throws it along the look direction, with a bit of spin */
    public void fling() {
        PhysEntity cube = this.held;
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
        double tvx = look[0] * FLING_SPEED + (this.player.x - this.player.prevX) / PhysEntity.DT;
        double tvy = look[1] * FLING_SPEED + 1.5D;
        double tvz = look[2] * FLING_SPEED + (this.player.z - this.player.prevZ) / PhysEntity.DT;
        double[] spin;
        if (cube instanceof Dice) {
            // strong spin about a random axis: the flight is short, so it has to be enough to forget how the dice was held
            spin = Dice.randomSpin(this.random);
        } else {
            spin = new double[]{
                    (this.random.nextDouble() - 0.5D) * 2.0D * FLING_SPIN,
                    (this.random.nextDouble() - 0.5D) * 2.0D * FLING_SPIN,
                    (this.random.nextDouble() - 0.5D) * 2.0D * FLING_SPIN};
        }
        cube.throwWith(tvx, tvy, tvz, spin[0], spin[1], spin[2]);
        cube.syncNetToSelf();   // joined to a server: it carries on from here until the server's state arrives
        Multiplayer.cubeRelease(cube, true, tvx, tvy, tvz, spin[0], spin[1], spin[2]);
        actionSound(Protocol.SOUND_THROW, "phys_throw", cube);
    }

    private void release(PhysEntity cube) {
        cube.held = false;
        // not an obstacle for the player until it has left them, see PhysEntity.ignorePlayer
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
        PhysEntity cube = this.held;
        if (cube == null) {
            return;
        }

        double yaw = Math.toRadians(this.player.yRotation);

        // about the vertical: the world's up
        double aboutUp = -dx * ROTATE_PER_PIXEL;
        cube.rotateWorld(0.0D, 1.0D, 0.0D, aboutUp);
        // about the player's right-hand side
        double aboutRight = dy * ROTATE_PER_PIXEL;
        cube.rotateWorld(Math.cos(yaw), 0.0D, Math.sin(yaw), aboutRight);

        // what the overlay shows (several mouse events come in one tick, so they add up)
        this.spinUp += aboutUp;
        this.spinRight += aboutRight;
    }

    // ---------------------------------------------------------------- tick

    /** once per game tick while carrying */
    public void tick() {
        PhysEntity cube = this.held;
        if (cube == null) {
            return;
        }

        // turning the camera turns the cube with it, so the face that was toward the player stays toward them
        // (while rotating by hand the camera is locked, so this adds nothing)
        float yaw = this.player.yRotation;
        double turned = Math.toRadians(yaw - this.lastYaw);
        if (turned != 0.0D && cube.turnsWithCamera()) {
            cube.rotateWorld(0.0D, 1.0D, 0.0D, -turned);
        }
        this.lastYaw = yaw;
        this.spinUp *= SPIN_FADE;
        this.spinRight *= SPIN_FADE;

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
        cube.followVx = (this.player.x - this.player.prevX) / PhysEntity.DT;
        cube.followVy = (this.player.y - this.player.prevY) / PhysEntity.DT;
        cube.followVz = (this.player.z - this.player.prevZ) / PhysEntity.DT;

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
    private boolean shouldLetGo(PhysEntity cube) {
        if (cube.dead) {
            return true;
        }

        double dx = cube.targetX - cube.grabX();
        double dy = cube.targetY - cube.grabY();
        double dz = cube.targetZ - cube.grabZ();
        double lag2 = dx * dx + dy * dy + dz * dz;
        AABB body = this.player.boundingBox;

        if (cube.partCount() <= 1) {
            // a rigid cube: as it always was
            if (lag2 > MAX_LAG * MAX_LAG) {
                return true;
            }
            return body != null && cube.isStoodOnBy(body);
        }

        // a body made of parts. it is let go of only when something goes on for a while, never because of one tick: a limb brushing the player's side, or dragged along the floor, is not "standing on it"
        if (lag2 > MAX_PULL * MAX_PULL) {
            double k = MAX_PULL / Math.sqrt(lag2);
            cube.targetX = cube.grabX() + dx * k;
            cube.targetY = cube.grabY() + dy * k;
            cube.targetZ = cube.grabZ() + dz * k;
        }

        double ex = cube.grabX() - this.player.x;
        double ey = cube.grabY() - this.player.y;
        double ez = cube.grabZ() - this.player.z;
        boolean far = ex * ex + ey * ey + ez * ez > MAX_HOLD_RANGE * MAX_HOLD_RANGE && !cube.holdStrained();
        this.farTicks = far ? this.farTicks + 1 : 0;

        boolean stoodOn = body != null && cube.isStoodOnBy(body);
        this.stoodOnTicks = stoodOn ? this.stoodOnTicks + 1 : 0;

        return this.farTicks >= FAR_LET_GO_TICKS || this.stoodOnTicks >= STOOD_ON_LET_GO_TICKS;
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