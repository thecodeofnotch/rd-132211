package com.nikohalfkino.rubydung.audio;

/**
 * the sounds of a body moving: jumping, landing, going into water, treading water, and a pat. one tracker per body
 * (the player, a cube, any entity): each tick it is told how the body moves and it plays what changed
 *
 * it does not know the player or the level: the caller says what the body does, so anything that moves can use it.
 * the sound names are in audio.toml ([sounds.player_jump], player_land, water_enter, water_tread, pat, purr)
 *
 * the player's own tracker also reports every event to a Listener, which sends it to the other players. what comes
 * in from the network is played with play()
 */
public final class BodySounds {

    /** what a body did. strength (0..1) says how hard: a long fall is loud, a hop is quiet */
    public static final int JUMP = 0;
    public static final int LAND = 1;
    public static final int WATER_ENTER = 2;
    public static final int WATER_TREAD = 3;

    public interface Listener {
        void onEvent(int event, float strength);
    }

    /** blocks/s of fall below which landing makes no sound (stepping down a stair), and the speed that is full volume */
    private static final double LAND_MIN_SPEED = 3.5D;
    private static final double LAND_FULL_SPEED = 18.0D;

    private static final double ENTER_FULL_SPEED = 10.0D;

    /** moving slower than this under water is drifting, not swimming (blocks/s) */
    private static final double TREAD_MIN_SPEED = 1.5D;
    /** seconds between two swimming sounds: it is a stroke now and then, not a loop */
    private static final double TREAD_INTERVAL = 1.1D;

    /** the going-in splash needs the body to have been out of the water (above the waist) this long (ticks), so wading about at the edge does not splash again and again */
    private static final int ENTER_REARM_TICKS = 40;

    private static final float JUMP_STRENGTH = 0.6F;

    /** a splash needs the body to have been out of the water this long (ticks) and to come in at least this fast (blocks/s): a floating dice bobbing at the surface is not going in */
    private static final int SPLASH_REARM_TICKS = 45;
    private static final double SPLASH_MIN_SPEED = 2.0D;
    private int dryTicks = SPLASH_REARM_TICKS;

    private boolean primed = false;
    private boolean wasOnGround;
    private boolean wasWet;
    private double lastVy;
    private double treadTimer;

    // a walking body: whether it was in the water to the waist last tick, and for how long it has not been
    private boolean wasImmersed;
    private int outTicks = ENTER_REARM_TICKS;

    /** forget what happened before: the body was moved somewhere else or started flying, the next tick only looks */
    public void reset() {
        this.primed = false;
        this.wasImmersed = false;
        this.outTicks = ENTER_REARM_TICKS;
        this.treadTimer = 0.0D;
    }

    /**
     * a walking body. vy is its vertical speed and horizontal its speed along the ground (blocks/s), waterLevel is
     * 0 dry, 1 feet, 2 waist, 3 head. headUnder says that the whole head is under the surface: it is worked out from the
     * top of the body and not from where the eyes (the camera) are, so it does not change with how the player looks or
     * with how tall the view is. volume scales everything (distance, or 1.0 for the player's own)
     *
     * what is heard, and when:
     *  - the jump and the landing (on dry ground only)
     *  - the splash of going into the water: once the body is in to the waist, and not again until it has been out of
     *    it for a while (ENTER_REARM_TICKS), so wading about at the edge is quiet
     *  - the swimming sound: only with the head under, a stroke every TREAD_INTERVAL while moving
     *
     * returns true when it made a loud sound (a jump, a landing, the splash): the footsteps keep quiet for a moment
     * after one, so they do not play over it
     */
    public boolean tick(double vy, double horizontal, boolean onGround, int waterLevel, boolean headUnder,
                        double seconds, float volume, Listener listener) {
        boolean immersed = waterLevel >= 2;
        boolean loud = false;

        // the swimming timer only runs down, it is not reset by the head coming out: bobbing at the surface does not make a stroke every time
        this.treadTimer = Math.max(0.0D, this.treadTimer - seconds);

        if (!this.primed) {
            this.primed = true;
        } else {
            if (immersed && !this.wasImmersed) {
                if (this.outTicks >= ENTER_REARM_TICKS && Math.abs(vy) + horizontal * 0.5D >= SPLASH_MIN_SPEED) {
                    emit(WATER_ENTER, strengthOfEntering(vy, horizontal), volume, listener);
                    this.treadTimer = TREAD_INTERVAL;   // the first stroke does not come on top of the splash
                    loud = true;
                }
            } else if (waterLevel == 0) {
                if (this.wasOnGround && !onGround && vy > 0.0D) {
                    emit(JUMP, JUMP_STRENGTH, volume, listener);
                    loud = true;
                } else if (!this.wasOnGround && onGround) {
                    double fall = -Math.min(vy, this.lastVy);
                    if (fall >= LAND_MIN_SPEED) {
                        emit(LAND, (float) clamp(fall / LAND_FULL_SPEED, 0.3D, 1.0D), volume, listener);
                        loud = true;
                    }
                }
            }

            if (headUnder) {
                double speed = Math.max(horizontal, Math.abs(vy));
                if (speed >= TREAD_MIN_SPEED && this.treadTimer <= 0.0D) {
                    this.treadTimer = TREAD_INTERVAL;
                    emit(WATER_TREAD, (float) clamp(0.3D + speed / 14.0D, 0.3D, 0.6D), volume, listener);
                }
            }
        }

        this.outTicks = immersed ? 0 : Math.min(this.outTicks + 1, ENTER_REARM_TICKS);
        this.wasImmersed = immersed;
        this.wasOnGround = onGround;
        this.lastVy = vy;
        return loud;
    }

    /**
     * for a thing that only splashes (a cube): the strength (0..1) of the splash if it went into water this tick, else 0.
     * it does not play it, the caller decides who hears it (see CubeManager.SplashListener)
     */
    public float tickWater(double vy, double horizontal, boolean inWater) {
        float splash = 0.0F;
        if (this.primed && inWater && !this.wasWet && this.dryTicks >= SPLASH_REARM_TICKS
                && Math.abs(vy) + horizontal * 0.5D >= SPLASH_MIN_SPEED) {
            splash = strengthOfEntering(vy, horizontal);
        }
        // something that floats bobs in and out of the surface: it only splashes again after it has really been out of the water for a while
        this.dryTicks = inWater ? 0 : Math.min(this.dryTicks + 1, SPLASH_REARM_TICKS);
        this.primed = true;
        this.wasWet = inWater;
        return splash;
    }

    private static float strengthOfEntering(double vy, double horizontal) {
        return (float) clamp((Math.abs(vy) + horizontal * 0.5D) / ENTER_FULL_SPEED, 0.35D, 1.0D);
    }

    private static void emit(int event, float strength, float volume, Listener listener) {
        play(event, strength, volume);
        if (listener != null) {
            listener.onEvent(event, strength);
        }
    }

    /** plays one event. also what the other players' events are played with, volume is how near they are */
    public static void play(int event, float strength, float volume) {
        float gain = strength * volume;
        if (gain <= 0.001F) {
            return;
        }
        switch (event) {
            case JUMP:
                SoundEffects.play("player_jump", gain);
                break;
            case LAND:
                SoundEffects.play("player_land", gain);
                break;
            case WATER_ENTER:
                SoundEffects.play("water_enter", gain);
                break;
            case WATER_TREAD:
                SoundEffects.play("water_tread", gain);
                break;
            default:
                break;
        }
    }

    /** below this the hit of a ragdoll is a limb flopping, from it on a body thudding */
    private static final float RAGDOLL_THUD_GAIN = 0.4F;

    /** a ragdoll hit a wall, the floor, something else or a player: a soft flop for a light knock, a dull thud for a hard one (gain is strength times how near it is) */
    public static void ragdollImpact(float gain) {
        if (gain <= 0.001F) {
            return;
        }
        if (gain >= RAGDOLL_THUD_GAIN) {
            SoundEffects.play("ragdoll_thud", Math.min(1.0F, gain));
        } else {
            SoundEffects.play("ragdoll_flop", Math.min(1.0F, gain * 1.6F));
        }
    }

    /** a ragdoll went into the water: a bigger, lower splash than a cube makes */
    public static void ragdollSplash(float gain) {
        if (gain <= 0.001F) {
            return;
        }
        SoundEffects.play("ragdoll_splash", Math.min(1.0F, gain));
    }

    /** a pat: the pat, then the purr a moment later */
    public static void pat(float volume) {
        SoundEffects.play("pat", volume);
        SoundEffects.playLater("purr", volume, 250L);
    }

    /** how loud something is at this distance from the listener: squared fall-off, 0 beyond range */
    public static float volumeAt(double dx, double dy, double dz, double range) {
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance >= range) {
            return 0.0F;
        }
        float closeness = (float) (1.0D - distance / range);
        return Math.max(0.05F, closeness * closeness);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
