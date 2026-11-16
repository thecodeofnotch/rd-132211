package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * the "under liquid" look shared by every shader that draws things the sea can cover (TerrainShader, BillboardShader):
 * the GLSL that decodes the packed alpha of UnderwaterTint (keep the two in sync) and the uniforms it reads.
 *
 * a shader adds GLSL_DECLARATIONS to its fragment source, creates a Uniforms for its program, and calls upload() each time it is bound.
 * in the fragment shader: rgb = liquidTint(rgb, packedAlpha, sampleY). sampleY is the world height of the fragment, or any value below
 * -900 to use the height of the cell the face was packed for (what terrain does)
 */
final class LiquidTinting {

    // bands of a liquid that doesn't set its own palette
    private static final int DEFAULT_BANDS = 6;

    // the look is stepped like the terrain's lighting (TerrainShader.LIGHT_STEPS / LIGHT_SMOOTH): the tint is rounded to TINT_STEPS
    // levels per channel, with a soft edge between two levels (0 = hard bands, 1 = no flat bands at all). the same softness as the
    // light, so the two read as one thing. the depth itself is stepped by the caller: TerrainShader reads it at the centre of each
    // light patch (LIGHT_GRID per block), so the bands fall in the same squares as the light
    private static final float TINT_STEPS = 15.0F;
    private static final float TINT_SMOOTH = 0.75F;

    // however deep it gets, a block is never tinted darker than this share of the deepest band's colour at full brightness:
    // the bottom layer keeps its hue instead of going pitch black (0 = the old behaviour, 1 = no depth darkening at all)
    private static final float DEEP_FLOOR = 0.22F;

    static final String GLSL_DECLARATIONS =
            "uniform float seaHeight;\n" +
                    "uniform float oceanIdx;\n" +
                    "uniform vec4 liqTint[" + LiquidTile.MAX_LIQUIDS + "];\n" +
                    // depth bands (shallow to deep) of rgb per liquid, MAX_DEPTH_BANDS slots each, see LiquidTile.getDepthPalette
                    "uniform vec3 liqPalette[" + (LiquidTile.MAX_LIQUIDS * LiquidTile.MAX_DEPTH_BANDS) + "];\n" +
                    // how many of those slots each liquid really uses
                    "uniform float liqBands[" + LiquidTile.MAX_LIQUIDS + "];\n" +

                    "const float TINT_LEVELS = " + TINT_STEPS + ";\n" +
                    "const float TINT_SOFTNESS = " + TINT_SMOOTH + ";\n" +
                    "const float TINT_DEEP_FLOOR = " + DEEP_FLOOR + ";\n" +
                    // v rounded to n steps per unit, like TerrainShader's light: soft where it crosses from one step to the next
                    "float tintStep(float v, float n) {\n" +
                    "    float w = max(TINT_SOFTNESS, 1.0e-4);\n" +
                    "    float q = v * n + 0.5 - 0.5 * w;\n" +
                    "    return (floor(q) + smoothstep(1.0 - w, 1.0, fract(q))) / n;\n" +
                    "}\n" +

                    "vec3 liquidTint(vec3 rgb, float a, float sampleY) {\n" +
                    "    float p = floor(a * 524288.0 + 0.5);\n" +
                    "    float idx = floor(p / 256.0);\n" +
                    "    float depth = (p - idx * 256.0) / 8.0;\n" +
                    "    bool wet = true;\n" +
                    // a sprite in stored liquid: p = 266240 + idx * 16384 + surfaceEighths, depth = how far the fragment is under that surface
                    "    if (p >= 266240.0) {\n" +
                    "        float q = p - 266240.0;\n" +
                    "        idx = floor(q / 16384.0);\n" +
                    "        float surf = (q - idx * 16384.0) / 8.0;\n" +
                    "        float y = sampleY < -900.0 ? surf - 0.5 : sampleY;\n" +
                    "        depth = surf - y;\n" +
                    "        if (depth <= 0.0) wet = false;\n" +
                    "        depth = clamp(depth, 0.125, 31.875);\n" +
                    // the ocean: p = 2048 + wetFrom * 512 + cellY, wet once the sea is above wetFrom (see UnderwaterTint)
                    "    } else if (p >= 2048.0) {\n" +
                    "        float q = p - 2048.0;\n" +
                    "        float wetFrom = floor(q / 512.0);\n" +
                    "        float cellY = q - wetFrom * 512.0;\n" +
                    "        float y = sampleY < -900.0 ? cellY : sampleY;\n" +
                    "        idx = oceanIdx;\n" +
                    "        wet = seaHeight > wetFrom;\n" +
                    "        depth = seaHeight - y;\n" +
                    "        if (depth <= 0.0) wet = false;\n" +
                    "        depth = clamp(depth, 0.125, 31.875);\n" +
                    "    }\n" +
                    "    if (!wet) return rgb;\n" +

                    "    vec4 t = liqTint[0];\n" +
                    "    for (int i = 1; i < " + LiquidTile.MAX_LIQUIDS + "; i++) {\n" +
                    "        if (float(i) == idx) t = liqTint[i];\n" +
                    "    }\n" +

                    "    float band = floor(max(depth - 0.001, 0.0));\n" +

                    "    vec3 water = liqPalette[0];\n" +
                    "    for (int i = 0; i < " + LiquidTile.MAX_LIQUIDS + "; i++) {\n" +
                    "        if (float(i) == idx) {\n" +
                    "            float bandC = min(band, liqBands[i] - 1.0);\n" +
                    "            for (int b = 0; b < " + LiquidTile.MAX_DEPTH_BANDS + "; b++) {\n" +
                    "                if (float(b) == bandC) water = liqPalette[i * " + LiquidTile.MAX_DEPTH_BANDS + " + b];\n" +
                    "            }\n" +
                    "        }\n" +
                    "    }\n" +

                    "    float opacity = clamp(t.a, 0.0, 1.0);\n" +
                    "    float amount = 1.0 - exp(-depth * opacity * 0.55);\n" +
                    "    vec3 tint = mix(vec3(1.0), water, amount);\n" +

                    "    float visible = max(1.0 - opacity, 0.3);\n" +
                    "    tint = pow(tint, vec3(1.0 / visible));\n" +
                    // the floor: the hue of this band at full brightness, scaled down. the deepest band alone used to multiply
                    // the block by 0.025 - 0.3 and then the curve above squared it again, which is black
                    "    vec3 hue = water / max(max(water.r, water.g), max(water.b, 0.001));\n" +
                    "    tint = max(tint, hue * TINT_DEEP_FLOOR);\n" +
                    "    tint = vec3(tintStep(tint.r, TINT_LEVELS), tintStep(tint.g, TINT_LEVELS), tintStep(tint.b, TINT_LEVELS));\n" +
                    "    return rgb * tint;\n" +
                    "}\n";

    // the ocean, set by LevelRenderer every frame before a shader is bound (a very low height = no ocean)
    private static float seaHeight = -1000.0F;
    private static float oceanIndex = 0.0F;

    private static final FloatBuffer TINTS = BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * 4);
    private static final FloatBuffer PALETTE =
            BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * LiquidTile.MAX_DEPTH_BANDS * 3);
    private static final FloatBuffer BAND_COUNTS = BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS);

    private LiquidTinting() {
    }

    static void setOcean(float height, int liquidIndex) {
        seaHeight = height;
        oceanIndex = liquidIndex;
    }

    /** the uniform locations of one program that includes GLSL_DECLARATIONS */
    static final class Uniforms {
        private final int seaHeightLocation;
        private final int oceanIdxLocation;
        private final int tintLocation;
        private final int paletteLocation;
        private final int bandsLocation;

        Uniforms(int program) {
            this.seaHeightLocation = GL20.glGetUniformLocation(program, "seaHeight");
            this.oceanIdxLocation = GL20.glGetUniformLocation(program, "oceanIdx");
            this.tintLocation = GL20.glGetUniformLocation(program, "liqTint");
            this.paletteLocation = GL20.glGetUniformLocation(program, "liqPalette");
            this.bandsLocation = GL20.glGetUniformLocation(program, "liqBands");
        }

        /** the program must be in use */
        void upload() {
            GL20.glUniform1f(this.seaHeightLocation, seaHeight);
            GL20.glUniform1f(this.oceanIdxLocation, oceanIndex);

            TINTS.clear();
            List<LiquidTile> liquids = LiquidTile.all();
            for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
                if (i < liquids.size()) {
                    LiquidTile liquid = liquids.get(i);
                    TINTS.put(liquid.getRed()).put(liquid.getGreen()).put(liquid.getBlue()).put(liquid.getOpacity());
                } else {
                    TINTS.put(0.0F).put(0.0F).put(0.0F).put(0.0F);
                }
            }
            TINTS.flip();
            GL20.glUniform4(this.tintLocation, TINTS);

            PALETTE.clear();
            BAND_COUNTS.clear();
            for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
                LiquidTile liquid = i < liquids.size() ? liquids.get(i) : null;
                float[][] custom = liquid == null ? null : liquid.getDepthPalette();
                int bands = custom != null ? custom.length : DEFAULT_BANDS;
                bands = Math.max(1, Math.min(LiquidTile.MAX_DEPTH_BANDS, bands));

                float[] last = {1.0F, 1.0F, 1.0F};
                for (int band = 0; band < LiquidTile.MAX_DEPTH_BANDS; band++) {
                    if (band < bands) {
                        last = liquid == null ? last : bandColor(liquid, custom, band, bands);
                    }
                    PALETTE.put(last[0]).put(last[1]).put(last[2]);   // unused slots repeat the deepest colour
                }
                BAND_COUNTS.put(bands);
            }
            PALETTE.flip();
            BAND_COUNTS.flip();
            GL20.glUniform3(this.paletteLocation, PALETTE);
            GL20.glUniform1(this.bandsLocation, BAND_COUNTS);
        }
    }

    /** the colour things take on under this liquid at a depth band (0 = right under the surface): the liquid's own palette if it has one, otherwise a ramp from a lightened to a darkened version of its colour */
    private static float[] bandColor(LiquidTile liquid, float[][] custom, int band, int bands) {
        if (custom != null) {
            return custom[band];
        }
        float k = bands <= 1 ? 0.0F : band / (float) (bands - 1);
        float[] rgb = {liquid.getRed(), liquid.getGreen(), liquid.getBlue()};
        float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            float shallow = rgb[c] + (1.0F - rgb[c]) * 0.35F;
            float deep = rgb[c] * 0.3F;
            out[c] = shallow + (deep - shallow) * k;
        }
        return out;
    }
}