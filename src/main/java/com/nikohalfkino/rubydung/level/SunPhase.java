package com.nikohalfkino.rubydung.level;

/**
 * the sun's alchemical phase. the world walks through them in this order, DayNightCycle.DAYS_PER_PHASE days each:
 * albedo (whitening) -> citrinitas (yellowing) -> rubedo (reddening) -> nigredo (blackening) -> albedo again
 *
 * each phase has its own sun texture, its own daytime sky (zenith, horizon, nadir, clouds) and a light tint for the
 * world. all of it only shows in daylight: DayNightCycle fades it in with the sun, so dawn, dusk and night keep their
 * own colours (and the phase changes unseen at midnight)
 */
public enum SunPhase {

    ALBEDO("albedo", "sun.png",
            new float[]{0.97F, 0.99F, 1.00F},   // world light tint
            new float[]{0.38F, 0.60F, 0.88F},   // sky: top
            new float[]{0.70F, 0.85F, 1.00F},   // sky: horizon
            new float[]{0.82F, 0.92F, 1.00F},   // sky: below the horizon
            new float[]{1.00F, 1.00F, 1.00F},   // clouds (both layers)
            1.00F),

    CITRINITAS("citrinitas", "sun_citrinitas.png",
            new float[]{1.00F, 0.95F, 0.80F},
            new float[]{0.50F, 0.60F, 0.62F},
            new float[]{0.98F, 0.84F, 0.45F},
            new float[]{0.95F, 0.88F, 0.62F},
            new float[]{1.00F, 0.93F, 0.72F},
            1.00F),

    RUBEDO("rubedo", "sun_rubedo.png",
            new float[]{1.00F, 0.84F, 0.80F},
            new float[]{0.50F, 0.18F, 0.25F},
            new float[]{0.95F, 0.34F, 0.25F},
            new float[]{0.78F, 0.42F, 0.40F},
            new float[]{1.00F, 0.72F, 0.68F},
            1.00F),

    NIGREDO("nigredo", "sun_nigredo.png",
            new float[]{0.82F, 0.84F, 0.92F},
            new float[]{0.14F, 0.16F, 0.22F},
            new float[]{0.34F, 0.36F, 0.42F},
            new float[]{0.34F, 0.36F, 0.42F},
            new float[]{0.50F, 0.52F, 0.58F},
            0.90F);

    private final String displayName;
    private final String texture;
    private final float[] tint;
    private final float[] zenith;
    private final float[] horizon;
    private final float[] nadir;
    private final float[] cloud;
    private final float ambientScale;

    SunPhase(String displayName, String texture, float[] tint, float[] zenith, float[] horizon, float[] nadir,
             float[] cloud, float ambientScale) {
        this.displayName = displayName;
        this.texture = texture;
        this.tint = tint;
        this.zenith = zenith;
        this.horizon = horizon;
        this.nadir = nadir;
        this.cloud = cloud;
        this.ambientScale = ambientScale;
    }

    public String displayName() {
        return this.displayName;
    }

    /** file name in textures/enviroment/ */
    public String texture() {
        return this.texture;
    }

    /** multiplied into the light of the world (terrain, plants, water) and the fog at full daylight, 1.0 = no change */
    public float[] tint() {
        return this.tint;
    }

    /** the colours the daytime sky is pulled towards */
    public float[] zenith() {
        return this.zenith;
    }

    public float[] horizon() {
        return this.horizon;
    }

    public float[] nadir() {
        return this.nadir;
    }

    /** the colour the sunlit part of the clouds is pulled towards (the shadowed part follows the sky) */
    public float[] cloud() {
        return this.cloud;
    }

    /** multiplied into the ambient light at full daylight (nigredo is a little darker) */
    public float ambientScale() {
        return this.ambientScale;
    }

    public SunPhase next() {
        SunPhase[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** case-insensitive, null if there is no such phase */
    public static SunPhase byName(String name) {
        if (name == null) {
            return null;
        }
        for (SunPhase phase : values()) {
            if (phase.displayName.equalsIgnoreCase(name)) {
                return phase;
            }
        }
        return null;
    }

    public static String[] names() {
        SunPhase[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            out[i] = all[i].displayName;
        }
        return out;
    }
}
