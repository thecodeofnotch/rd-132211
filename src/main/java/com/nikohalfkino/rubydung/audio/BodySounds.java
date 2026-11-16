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

    /** moving slower than this in the water is floating, not treading (blocks/s) */
    private static final double TREAD_MIN_SPEED = 0.6D;
    private static final double TREAD_INTERVAL = 0.55D;

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

    /** forget what happened before: the body was moved somewhere else or started flying, the next tick only looks */
    public void reset() {
        this.primed = false;
    }

    /**
     * a walking body. vy is its vertical speed and horizontal its speed along the ground (blocks/s), waterLevel is
     * 0 dry, 1 feet, 2 waist, 3 head. volume scales everything (distance, or 1.0 for the player's own)
     */
    public void tick(double vy, double horizontal, boolean onGround, int waterLevel, double seconds,
                     float volume, Listener listener) {
        boolean wet = waterLevel >= 1;

        if (!this.primed) {
            this.primed = true;
        } else {
            if (wet && !this.wasWet) {
                emit(WATER_ENTER, strengthOfEntering(vy, horizontal), volume, listener);
            } else if (!wet) {
                if (this.wasOnGround && !onGround && vy > 0.0D && waterLevel == 0) {
                    emit(JUMP, JUMP_STRENGTH, volume, listener);
                } else if (!this.wasOnGround && onGround) {
                    double fall = -Math.min(vy, this.lastVy);
                    if (fall >= LAND_MIN_SPEED) {
                        emit(LAND, (float) clamp(fall / LAND_FULL_SPEED, 0.3D, 1.0D), volume, listener);
                    }
                }
            }

            if (waterLevel >= 2) {
                double speed = Math.max(horizontal, Math.abs(vy));
                this.treadTimer -= seconds;
                if (speed >= TREAD_MIN_SPEED && this.treadTimer <= 0.0D) {
                    this.treadTimer = TREAD_INTERVAL;
                    emit(WATER_TREAD, (float) clamp(0.35D + speed / 12.0D, 0.35D, 0.8D), volume, listener);
                }
            } else {
                this.treadTimer = 0.0D;
            }
        }

        this.wasOnGround = onGround;
        this.wasWet = wet;
        this.lastVy = vy;
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
