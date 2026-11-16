package com.nikohalfkino.rubydung.entity.cube;

import com.nikohalfkino.rubydung.entity.EntityTypes;

import java.util.Random;

/**
 * a dice is a cube that shows a number after a throw: it rolls instead of settling at once, takes a random knock on every hit, and tracks when a throw has ended (see trackRoll). the spin is fake, so it is tuned to make the result depend on the whole throw instead of the first touch
 *
 * its piece code uses the negative codes, so the save file and the network need no new field: -1 is a plain cube (WeightedCube), -2 a dice that has not rolled, -3 .. -8 a dice that shows 1 .. 6
 */
public final class Dice extends PhysCube {

    /** told (on the machine that runs the physics) when a thrown dice has come to rest. holderId is the id of the player who threw it (see PhysCube.heldBy), -1 for the player of this machine / the LAN host */
    public interface RollListener {
        void rolled(Dice dice, int value, int holderId);
    }

    public static final int CODE_IDLE = -2;

    private static final Random RNG = new Random();
    private static final double GROUND_FRICTION = 3.0D;   // lower than a cube's so the roll lasts
    private static final double ROLL_FACTOR = 0.8D;       // 1 = rolling without slipping
    private static final double ROLL_MIN_SPEED = 0.8D;    // blocks/s: slower than this the dice settles instead of rolling
    private static final double ROLL_BLEND = 8.0D;        // 1/s, how fast the spin follows the roll
    private static final double HIT_MIN_SPEED = 3.5D;     // blocks/s: slower hits give no kick (a drop of half a block lands at 5)
    private static final double KICK_PER_SPEED = 1.6D;    // rad/s of kick per block/s of impact over HIT_MIN_SPEED
    private static final double KICK_MAX = 30.0D;         // rad/s
    private static final double BOUNCE = 0.4D;            // share of the speed kept, a bit more lively than a cube
    private static final double BOUNCE_MIN_SPEED = 4.0D;
    private static final double SETTLE_SPEED = ROLL_MIN_SPEED;
    private static final double BUOYANCY = 0.45D;         // share of gravity pushing it up in a liquid, a dice floats (a plain cube sinks)
    private static final double FLOAT_REST_SPEED = 0.8D;  // blocks/s: bobbing slower than this counts as lying still on the water

    /** the spin a thrown dice leaves the hand with (rad/s): a random axis, uniform on the sphere, and a magnitude that is never small */
    public static final double SPIN_MIN = 18.0D;
    public static final double SPIN_MAX = 34.0D;

    // a throw has ended when the dice has lain still this many ticks
    private static final int REST_TICKS = 15;
    private static final int GIVE_UP_TICKS = 60 * 30;
    private static final double THROW_SPEED = 3.0D;   // blocks/s at the moment it is let go, slower is just put down
    private static final double REST_SPEED = 0.15D;
    private static final double REST_SPIN = 0.5D;

    // server / single player only, never sent: what is needed to notice that a throw has ended
    public boolean wasHeld;
    public boolean rollArmed;
    public int lastHolder = -1;
    public int restTicks;
    public int rollTicks;

    private int code = CODE_IDLE;

    public Dice(double x, double y, double z) {
        super(EntityTypes.DICE, x, y, z);
    }

    public static double[] randomSpin(Random random) {
        double zc = random.nextDouble() * 2.0D - 1.0D;
        double a = random.nextDouble() * Math.PI * 2.0D;
        double r = Math.sqrt(1.0D - zc * zc);
        double spin = SPIN_MIN + random.nextDouble() * (SPIN_MAX - SPIN_MIN);
        return new double[]{r * Math.cos(a) * spin, zc * spin, r * Math.sin(a) * spin};
    }

    // ---------------------------------------------------------------- the number

    @Override
    public boolean isDice() {
        return true;
    }

    @Override
    public int pieceCode() {
        return this.code;
    }

    @Override
    public void applyPieceCode(int code) {
        if (code <= CODE_IDLE) {
            this.code = Math.max(code, codeOf(6));
        }
    }

    /** the number the dice shows, 0 if it has not rolled */
    public int diceValue() {
        return this.code < CODE_IDLE ? CODE_IDLE - this.code : 0;
    }

    public static int codeOf(int value) {
        return value < 1 || value > 6 ? CODE_IDLE : CODE_IDLE - value;
    }

    /**
     * the number on the face that points up, from the orientation. the faces are the ones CubeRenderer draws: +y 1, -y 6, -z 2, +z 5, -x 3, +x 4 (opposite faces add up to 7)
     */
    public int upFaceValue() {
        double w = this.qw, x = this.qx, y = this.qy, z = this.qz;
        // the world y of the cube's own x, y and z axes
        double ux = 2 * (x * y + z * w);
        double uy = 1 - 2 * (x * x + z * z);
        double uz = 2 * (y * z - x * w);
        double ax = Math.abs(ux), ay = Math.abs(uy), az = Math.abs(uz);
        if (ay >= ax && ay >= az) {
            return uy > 0.0D ? 1 : 6;
        }
        if (az >= ax) {
            return uz > 0.0D ? 5 : 2;
        }
        return ux > 0.0D ? 4 : 3;
    }

    // ---------------------------------------------------------------- how it differs from a cube

    @Override
    protected double groundFriction() {
        return GROUND_FRICTION;
    }

    @Override
    protected double buoyancy() {
        return BUOYANCY;
    }

    @Override
    protected void hitSurface(int axis, double speed, boolean bounce) {
        if (bounce && Math.abs(speed) > HIT_MIN_SPEED) {
            // a dice takes a real random knock on every hit (floor and walls), the harder the hit the bigger,
            // and bounces more readily than a cube so a throw gets a few chances to scramble
            double kick = Math.min(KICK_MAX, (Math.abs(speed) - HIT_MIN_SPEED) * KICK_PER_SPEED + 1.0D);
            this.avx += (RNG.nextDouble() - 0.5D) * 2.0D * kick;
            this.avy += (RNG.nextDouble() - 0.5D) * 2.0D * kick * 0.5D;
            this.avz += (RNG.nextDouble() - 0.5D) * 2.0D * kick;
            setAxisSpeed(axis, Math.abs(speed) > BOUNCE_MIN_SPEED ? -speed * BOUNCE : 0.0D);
        } else {
            super.hitSurface(axis, speed, bounce);
        }
    }

    @Override
    protected void settle() {
        double hs = Math.sqrt(this.vx * this.vx + this.vz * this.vz);
        if (hs > SETTLE_SPEED) {
            // still travelling: the spin follows the slide (rolling without slipping, w = up x v / radius) and the
            // dice does not settle yet, so the face it ends on depends on the whole slide, not on the first touch
            double k = 1.0D - Math.exp(-ROLL_BLEND * DT);
            this.avx += (this.vz / HALF * ROLL_FACTOR - this.avx) * k;
            this.avz += (-this.vx / HALF * ROLL_FACTOR - this.avz) * k;
            this.avy *= Math.exp(-SPIN_DAMP_GROUND * DT);
        } else {
            super.settle();
        }
    }

    // ---------------------------------------------------------------- rolling

    /**
     * the dice rolls when it is thrown (let go of with some speed) and counts when it has come to rest: then the face that points up is its number, shown over it (it is in the piece code, so the players get it with the rest of the state) and told to the listener. picking it up again clears the number. called by CubeManager once per tick on the machine that runs the physics
     */
    public void trackRoll(RollListener listener) {
        if (this.held) {
            this.wasHeld = true;
            this.lastHolder = this.heldBy;
            this.rollArmed = false;
            this.restTicks = 0;
            this.code = CODE_IDLE;
            return;
        }

        if (this.wasHeld) {
            // let go this tick: thrown if it has speed or spin
            this.wasHeld = false;
            if (speed() >= THROW_SPEED || spinSpeed() >= 2.0D) {
                this.rollArmed = true;
                this.restTicks = 0;
                this.rollTicks = 0;
            }
            return;
        }

        if (!this.rollArmed) {
            return;
        }

        // on the ground, or riding on water (it bobs, so it only has to be slow)
        boolean still = this.onGround ? speed() < REST_SPEED && spinSpeed() < REST_SPIN
                : isFloating() && speed() < FLOAT_REST_SPEED && spinSpeed() < REST_SPIN;
        this.restTicks = still ? this.restTicks + 1 : 0;
        this.rollTicks++;

        if (this.restTicks >= REST_TICKS) {
            this.rollArmed = false;
            int value = upFaceValue();
            this.code = codeOf(value);
            if (listener != null) {
                listener.rolled(this, value, this.lastHolder);
            }
        } else if (this.rollTicks > GIVE_UP_TICKS) {
            this.rollArmed = false;   // stuck on a moving thing: no roll
        }
    }

    private double spinSpeed() {
        return Math.sqrt(this.avx * this.avx + this.avy * this.avy + this.avz * this.avz);
    }
}