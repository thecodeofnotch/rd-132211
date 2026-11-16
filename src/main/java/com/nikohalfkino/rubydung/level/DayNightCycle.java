package com.nikohalfkino.rubydung.level;

/**
 * time of day plus interpolated sky/fog/ambient values
 *
 * time is a float in [0, 1): 0 = midnight, 0.25 = dawn, 0.5 = noon, 0.75 = dusk
 *
 * a full cycle is CYCLE_SECONDS real seconds at speed 1.0
 */
public class DayNightCycle {

    /** real seconds for a full cycle at speed 1.0 (10 minutes) */
    public static final float CYCLE_SECONDS = 600.0F;

    /** noon, so a new world starts in daylight */
    public static final float DEFAULT_START_TIME = 0.5F;

    // sky colour key-frames: each row is {R, G, B} at the time in the same row of SKY_TIMES
    // times: midnight(0), late night(0.18), dawn(0.25), noon(0.5), dusk(0.75), nightfall(0.83)
    // the two night rows hold the night dark for a good part of the cycle (it used to be dark only at exactly midnight)
    private static final float[] SKY_TIMES = {0.0f, 0.18f, 0.25f, 0.5f, 0.75f, 0.83f};

    private static final float[][] ZENITH_COLORS = {
            {0.006f, 0.008f, 0.028f},  // midnight  - near-black
            {0.008f, 0.010f, 0.036f},  // late night
            {0.35f, 0.25f, 0.45f},     // dawn      - violet-purple
            {0.20f, 0.50f, 0.80f},     // noon      - clear blue (original value)
            {0.20f, 0.25f, 0.55f},     // dusk      - deep indigo
            {0.016f, 0.020f, 0.060f},  // nightfall
    };
    private static final float[][] HORIZON_COLORS = {
            {0.020f, 0.020f, 0.050f},  // midnight
            {0.040f, 0.035f, 0.075f},  // late night
            {0.90f, 0.55f, 0.30f},     // dawn      - warm orange
            {0.40f, 0.70f, 1.00f},     // noon      - sky blue (original)
            {0.95f, 0.45f, 0.20f},     // dusk      - burnt orange
            {0.090f, 0.055f, 0.110f},  // nightfall - a last glow
    };
    private static final float[][] NADIR_COLORS = {
            {0.020f, 0.020f, 0.035f},  // midnight
            {0.025f, 0.025f, 0.040f},  // late night
            {0.60f, 0.75f, 0.85f},     // dawn
            {0.70f, 0.87f, 1.00f},     // noon      - original
            {0.55f, 0.65f, 0.80f},     // dusk
            {0.040f, 0.040f, 0.060f},  // nightfall
    };

    // fog colour key-frames
    private static final float[][] FOG_COLORS = {
            {0.010f, 0.010f, 0.030f},  // midnight
            {0.015f, 0.015f, 0.035f},  // late night
            {0.55f, 0.35f, 0.20f},     // dawn
            {0.055f, 0.043f, 0.039f},  // noon      - original (14/255, 11/255, 10/255)
            {0.50f, 0.25f, 0.15f},     // dusk
            {0.030f, 0.022f, 0.050f},  // nightfall
    };

    // ambient brightness key-frames, multiplied into Level.getBrightness() (1.0 = fully lit, 0.05 = very dark night)
    // cloud tint key-frames follow just below
    private static final float[] AMBIENT_LEVELS = {
            0.05f,  // midnight
            0.55f,  // dawn
            1.00f,  // noon
            0.55f,  // dusk
    };

    // key-frame times in [0, 1)
    private static final float[] KEY_TIMES = {0.0f, 0.25f, 0.5f, 0.75f};

    // Cloud colour key-frames (rows as in SKY_TIMES): the colour of the lit part of the clouds (see CloudShader).
    // a night colour that is not close to black shows as a bright sheet over the dark sky: keep the night rows dark
    private static final float[][] CLOUD_COLORS = {
            {0.030f, 0.035f, 0.070f},  // midnight
            {0.040f, 0.045f, 0.090f},  // late night
            {1.00f, 0.70f, 0.60f},     // dawn      - warm pink/orange underlighting
            {1.00f, 1.00f, 1.00f},     // noon      - unchanged
            {1.00f, 0.60f, 0.50f},     // dusk      - red-orange
            {0.140f, 0.115f, 0.170f},  // nightfall
    };

    /** how far the daytime sky is pulled towards the sun phase's own sky (0 = not at all, 1 = fully) */
    private static final float SKY_PHASE_STRENGTH = 0.8F;

    /** how many game days one sun phase lasts (albedo, citrinitas, rubedo, nigredo, then round again) */
    public static final int DAYS_PER_PHASE = 3;

    // state
    private int dayCount;  // game days since the world began (the real date's day number in real-time mode)
    private SunPhase sunPhaseOverride; // null = the phase follows dayCount
    private float time;    // [0, 1)
    private float speed;   // multiplier; 0 = frozen, 1 = normal, 2 = 2x, etc.
    private boolean frozen;
    private boolean realTime; // follow the machine's local clock instead of the game cycle

    public DayNightCycle() {
        this.time   = DEFAULT_START_TIME;
        this.speed  = 1.0F;
        this.frozen = false;
    }

    // tick

    /** advances one game tick, called once per tick at 60 tps */
    public void tick(float partialTicks) {
        if (this.frozen) {
            return;
        }
        if (this.realTime) {
            this.time = realTimeOfDay();
            this.dayCount = realDayCount();
            return;
        }
        if (this.speed == 0.0F) {
            return;
        }
        // Each game tick is 1/60 of a second.
        float secondsPerTick = 1.0F / 60.0F;
        float next = this.time + secondsPerTick * this.speed / CYCLE_SECONDS;
        if (next >= 1.0F) {
            this.dayCount++;   // midnight passed
        }
        this.time = next % 1.0F;
    }

    // sun phase

    /** game days since the world began, drives the sun phase. synced to the players of a server */
    public int getDayCount() {
        return this.dayCount;
    }

    public void setDayCount(int days) {
        this.dayCount = Math.max(0, days);
    }

    /** the phase the sun is in: the pinned one if there is one, else the one the day count says */
    public SunPhase getSunPhase() {
        return this.sunPhaseOverride != null ? this.sunPhaseOverride : phaseOfDay(this.dayCount);
    }

    public static SunPhase phaseOfDay(int day) {
        SunPhase[] all = SunPhase.values();
        return all[Math.floorMod(Math.max(0, day) / DAYS_PER_PHASE, all.length)];
    }

    /** 1..DAYS_PER_PHASE, which day of its phase it is (by the day count, even while a phase is pinned) */
    public int getDayInPhase() {
        return Math.max(0, this.dayCount) % DAYS_PER_PHASE + 1;
    }

    /** null = not pinned */
    public SunPhase getSunPhaseOverride() {
        return this.sunPhaseOverride;
    }

    public void setSunPhaseOverride(SunPhase phase) {
        this.sunPhaseOverride = phase;
    }

    /** for saving and the network: -1 = not pinned, else the ordinal of the pinned phase */
    public int getSunPhaseOverrideIndex() {
        return this.sunPhaseOverride == null ? -1 : this.sunPhaseOverride.ordinal();
    }

    public void setSunPhaseOverrideIndex(int index) {
        SunPhase[] all = SunPhase.values();
        this.sunPhaseOverride = index >= 0 && index < all.length ? all[index] : null;
    }

    // time control

    public float getTime() {
        return this.time;
    }

    /** 0 = midnight, 0.5 = noon */
    public void setTime(float t) {
        this.time = ((t % 1.0F) + 1.0F) % 1.0F;
        // Setting the time by hand leaves real-time mode, otherwise the next tick would undo it.
        this.realTime = false;
    }

    /** 1.0 = normal, 0 = stopped, 2 = twice as fast */
    public float getSpeed() {
        return this.speed;
    }

    /** 0 effectively freezes the cycle (same as setFrozen), the frozen flag is independent so both must allow ticking */
    public void setSpeed(float speed) {
        this.speed = Math.max(0.0F, speed);
    }

    /** explicitly frozen, regardless of speed */
    public boolean isFrozen() {
        return this.frozen;
    }

    /** doesn't change the speed multiplier */
    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    // world time modes

    /** how a world's clock behaves, chosen when the world is created */
    public enum TimeMode {
        // follows the machine's local clock (midnight = 0.0, noon = 0.5)
        REAL_TIME,
        // the normal cycle, CYCLE_SECONDS long
        NORMAL,
        // stops at the chosen time of day
        FROZEN
    }

    /** sets up the clock for a brand-new world, startTime is ignored for REAL_TIME */
    public void applyCreationSettings(TimeMode mode, float startTime) {
        this.speed = 1.0F;
        this.dayCount = 0;
        this.sunPhaseOverride = null;
        switch (mode) {
            case REAL_TIME:
                this.realTime = true;
                this.frozen = false;
                this.time = realTimeOfDay();
                this.dayCount = realDayCount();
                break;
            case FROZEN:
                this.realTime = false;
                this.frozen = true;
                this.time = wrap(startTime);
                break;
            case NORMAL:
            default:
                this.realTime = false;
                this.frozen = false;
                this.time = wrap(startTime);
                break;
        }
    }

    /** restores saved per-world state (see WorldManager.loadWorldTime) */
    public void restoreState(float time, float speed, boolean frozen, boolean realTime) {
        this.time = Float.isFinite(time) ? wrap(time) : DEFAULT_START_TIME;
        this.speed = Float.isFinite(speed) ? Math.max(0.0F, speed) : 1.0F;
        this.frozen = frozen;
        this.realTime = realTime;
        if (realTime && !frozen) {
            this.time = realTimeOfDay();
        }
    }

    /** same, with the sun phase state (older saves don't have it: day 0, not pinned) */
    public void restoreState(float time, float speed, boolean frozen, boolean realTime, int dayCount, int phaseOverride) {
        restoreState(time, speed, frozen, realTime);
        this.dayCount = realTime && !frozen ? realDayCount() : Math.max(0, dayCount);
        setSunPhaseOverrideIndex(phaseOverride);
    }

    public boolean isRealTime() {
        return this.realTime;
    }

    // local time as [0, 1): 0 = midnight, 0.5 = noon
    private static float realTimeOfDay() {
        return (float) (java.time.LocalTime.now().toNanoOfDay() / 86_400_000_000_000.0);
    }

    // the local date as a day number, so the sun phase follows the calendar in real-time mode
    private static int realDayCount() {
        return (int) java.time.LocalDate.now().toEpochDay();
    }

    private static float wrap(float t) {
        return ((t % 1.0F) + 1.0F) % 1.0F;
    }

    /** broadly daytime (ambient >= 0.5) */
    public boolean isDay() {
        return getAmbient() >= 0.5F;
    }

    // interpolated values

    public float getAmbient() {
        float base = interpolate1(AMBIENT_LEVELS);
        // the phase only changes the daylight, so a night stays as dark as it was
        return base * lerp(1.0F, getSunPhase().ambientScale(), Math.min(1.0F, base));
    }

    public float[] getZenithColor()  { return phaseSky(interpolate3(ZENITH_COLORS),  getSunPhase().zenith());  }
    public float[] getHorizonColor() { return phaseSky(interpolate3(HORIZON_COLORS), getSunPhase().horizon()); }
    public float[] getNadirColor()   { return phaseSky(interpolate3(NADIR_COLORS),   getSunPhase().nadir());   }
    public float[] getCloudColor()   { return phaseSky(interpolate3(CLOUD_COLORS),   getSunPhase().cloud());   }
    public float[] getFogColor()     { return tinted(interpolate3(FOG_COLORS)); }

    /**
     * the colour of the shadowed part of the clouds (getCloudColor is the lit part): the sky of the moment, darkened, so
     * the clouds are always in tune with the sun phase's sky instead of a fixed colour of their own
     */
    public float[] getCloudShadowColor() {
        float[] lit = getCloudColor();
        float[] horizon = getHorizonColor();
        float[] zenith = getZenithColor();
        float[] sky = {(horizon[0] + zenith[0]) * 0.5F, (horizon[1] + zenith[1]) * 0.5F, (horizon[2] + zenith[2]) * 0.5F};
        float[] m = mix(lit, sky, 0.6F);
        return new float[]{m[0] * 0.62F, m[1] * 0.62F, m[2] * 0.62F};
    }

    /** multiplied into the light of the world (terrain, plants, water): the phase's tint in daylight, none at night */
    public float[] getWorldTint() {
        float[] tint = getSunPhase().tint();
        float s = Math.min(1.0F, interpolate1(AMBIENT_LEVELS));
        return new float[]{lerp(1.0F, tint[0], s), lerp(1.0F, tint[1], s), lerp(1.0F, tint[2], s)};
    }

    // 0 at night and dawn/dusk, 1 in full daylight: how much of the sun phase's own sky is shown
    private float phaseStrength() {
        float day = (interpolate1(AMBIENT_LEVELS) - 0.45F) / 0.55F;
        return SKY_PHASE_STRENGTH * Math.max(0.0F, Math.min(1.0F, day));
    }

    private float[] phaseSky(float[] c, float[] target) {
        return mix(c, target, phaseStrength());
    }

    private static float[] mix(float[] a, float[] b, float t) {
        return new float[]{lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)};
    }

    // the phase's light tint, as strong as the daylight is (none at night, so the phase changes unseen at midnight)
    private float[] tinted(float[] c) {
        float[] tint = getWorldTint();
        return new float[]{c[0] * tint[0], c[1] * tint[1], c[2] * tint[2]};
    }

    // interpolation helpers

    private float interpolate1(float[] values) {
        int a = keyframeBefore();
        int b = keyframeAfter();
        float t = localT(a, b);
        return smoothstep(values[a], values[b], t);
    }

    // the colour key-frames have their own times (SKY_TIMES), the ambient keeps KEY_TIMES
    private float[] interpolate3(float[][] values) {
        int a = SKY_TIMES.length - 1;
        for (int i = SKY_TIMES.length - 1; i >= 0; i--) {
            if (this.time >= SKY_TIMES[i]) {
                a = i;
                break;
            }
        }
        int b = (a + 1) % SKY_TIMES.length;
        float start = SKY_TIMES[a];
        float end = b > a ? SKY_TIMES[b] : SKY_TIMES[b] + 1.0F;
        float cur = this.time >= start ? this.time : this.time + 1.0F;
        float s = smoothT((cur - start) / (end - start));
        return new float[]{
                lerp(values[a][0], values[b][0], s),
                lerp(values[a][1], values[b][1], s),
                lerp(values[a][2], values[b][2], s),
        };
    }

    private int keyframeBefore() {
        for (int i = KEY_TIMES.length - 1; i >= 0; i--) {
            if (this.time >= KEY_TIMES[i]) return i;
        }
        return KEY_TIMES.length - 1;
    }

    private int keyframeAfter() {
        return (keyframeBefore() + 1) % KEY_TIMES.length;
    }

    private float localT(int a, int b) {
        float start = KEY_TIMES[a];
        float end   = b > a ? KEY_TIMES[b] : KEY_TIMES[b] + 1.0F;
        float cur   = this.time >= start ? this.time : this.time + 1.0F;
        return (cur - start) / (end - start);
    }

    private static float smoothT(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private static float smoothstep(float a, float b, float t) {
        return a + (b - a) * smoothT(t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}