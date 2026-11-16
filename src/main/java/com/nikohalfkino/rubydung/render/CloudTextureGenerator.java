package com.nikohalfkino.rubydung.render;

import java.awt.image.BufferedImage;
import java.util.Random;

public final class CloudTextureGenerator {

    private static final float BLOB_STRETCH = 1.15f;

    // the texture holds no colour: red, green and blue are all the same light level, alpha is the coverage.
    // CloudShader colours it from the sky (a lit colour for 1.0, a shadow colour for 0.0), so the clouds always match the
    // sun phase. CORE_SHADE: how much darker the thick core of a cloud is than its thin edge
    private static final float CORE_SHADE = 0.55f;

    private static final int SHADE_LEVELS = 16;

    private CloudTextureGenerator() {
    }

    public static BufferedImage generate(int size, long seed) {
        float[][] noise = fractalNoise(size, seed, 5, 0.5f);

        float[][] lightRaw = new float[size][size];
        float[][] alpha = new float[size][size];

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                float n = noise[x][y];

                float coverage = 0.42f;
                float edgeSoftness = 0.26f;
                alpha[x][y] = smoothstep(coverage - edgeSoftness, coverage + edgeSoftness, n);

                // 0 at the thin edge of a cloud, 1 deep inside it: the edge catches the light, the core is in shade
                float thickness = smoothstep(coverage - edgeSoftness, coverage + 0.30f, n);
                lightRaw[x][y] = 1.0f - CORE_SHADE * thickness;
            }
        }

        float[][] lightQuantized = floydSteinbergDither(lightRaw, SHADE_LEVELS, size);

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                int l = clamp255((int) (lightQuantized[x][y] * 255));
                int a = (int) (alpha[x][y] * 255);
                image.setRGB(x, y, (a << 24) | (l << 16) | (l << 8) | l);
            }
        }
        return image;
    }

    private static float[][] fractalNoise(int size, long seed, int octaves, float persistence) {
        float[][] result = new float[size][size];
        Random random = new Random(seed);

        float amplitude = 1.0f;
        float totalAmplitude = 0.0f;

        int latticeSizeY = 4;

        for (int octave = 0; octave < octaves; octave++) {
            int latticeSizeX = Math.max(1, Math.round(latticeSizeY / BLOB_STRETCH));
            float[][] lattice = buildLattice(random, latticeSizeX, latticeSizeY);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    result[x][y] += sampleLattice(lattice, latticeSizeX, latticeSizeY, size, x, y) * amplitude;
                }
            }
            totalAmplitude += amplitude;
            amplitude *= persistence;
            latticeSizeY *= 2;
        }

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                result[x][y] /= totalAmplitude;
            }
        }
        return result;
    }

    private static float[][] buildLattice(Random random, int latticeSizeX, int latticeSizeY) {
        float[][] lattice = new float[latticeSizeX][latticeSizeY];
        for (int i = 0; i < latticeSizeX; i++) {
            for (int j = 0; j < latticeSizeY; j++) {
                lattice[i][j] = random.nextFloat();
            }
        }
        return lattice;
    }

    private static float sampleLattice(float[][] lattice, int latticeSizeX, int latticeSizeY, int size, int x, int y) {
        float gridX = (x * (float) latticeSizeX) / size;
        float gridY = (y * (float) latticeSizeY) / size;

        int x0 = ((int) gridX) % latticeSizeX;
        int y0 = ((int) gridY) % latticeSizeY;
        int x1 = (x0 + 1) % latticeSizeX;
        int y1 = (y0 + 1) % latticeSizeY;

        float fracX = smoothstep(0, 1, gridX - (int) gridX);
        float fracY = smoothstep(0, 1, gridY - (int) gridY);

        float top = lerp(lattice[x0][y0], lattice[x1][y0], fracX);
        float bottom = lerp(lattice[x0][y1], lattice[x1][y1], fracX);
        return lerp(top, bottom, fracY);
    }

    private static float[][] floydSteinbergDither(float[][] source, int levels, int size) {
        float[][] work = new float[size][size];
        for (int x = 0; x < size; x++) {
            System.arraycopy(source[x], 0, work[x], 0, size);
        }

        float[][] result = new float[size][size];
        if (levels <= 1) {
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    result[x][y] = clamp01(work[x][y]);
                }
            }
            return result;
        }

        float steps = levels - 1;
        for (int y = 0; y < size; y++) {
            boolean leftToRight = (y % 2 == 0);
            for (int i = 0; i < size; i++) {
                int x = leftToRight ? i : (size - 1 - i);

                float oldValue = work[x][y];
                float quantized = clamp01(Math.round(clamp01(oldValue) * steps) / steps);
                result[x][y] = quantized;

                float error = oldValue - quantized;
                int ahead = leftToRight ? (x + 1) % size : (x - 1 + size) % size;
                int behind = leftToRight ? (x - 1 + size) % size : (x + 1) % size;
                int down = (y + 1) % size;

                work[ahead][y] += error * (7.0f / 16.0f);
                work[behind][down] += error * (3.0f / 16.0f);
                work[x][down] += error * (5.0f / 16.0f);
                work[ahead][down] += error * (1.0f / 16.0f);
            }
        }
        return result;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}