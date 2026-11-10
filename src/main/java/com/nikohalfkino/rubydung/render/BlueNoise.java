package com.nikohalfkino.rubydung.render;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;

/**
 * a small tileable blue noise texture, made with the void and cluster method when first needed (a few ms).
 * every value 0..255 appears equally often, so comparing a density 0..1 against it lights exactly that share of
 * pixels, spread evenly instead of clumping. used for the dithered billboard auras
 */
public final class BlueNoise {

    public static final int SIZE = 64;

    private static final float SIGMA = 1.5F;
    private static final int RADIUS = 5;
    private static final long SEED = 0x5EED_B10EL;

    private static int texture = 0;

    private BlueNoise() {}

    /** GL thread only */
    public static int texture() {
        if (texture == 0) {
            texture = upload(generate(SIZE, SEED));
        }
        return texture;
    }

    private static int upload(byte[] values) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(values.length);
        buffer.put(values).flip();

        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, SIZE, SIZE, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, previous);
        return id;
    }

    /** size * size bytes, the rank of each pixel scaled to 0..255 */
    static byte[] generate(int size, long seed) {
        int n = size * size;
        float[] kernel = new float[(2 * RADIUS + 1) * (2 * RADIUS + 1)];
        for (int dy = -RADIUS; dy <= RADIUS; dy++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                kernel[(dy + RADIUS) * (2 * RADIUS + 1) + dx + RADIUS] =
                        (float) Math.exp(-(dx * dx + dy * dy) / (2.0 * SIGMA * SIGMA));
            }
        }

        Field field = new Field(size, kernel);
        Random random = new Random(seed);

        // a random pattern of 10% ones, relaxed until the tightest cluster and the largest void are the same pixel
        int initial = n / 10;
        for (int placed = 0; placed < initial; ) {
            int p = random.nextInt(n);
            if (!field.set[p]) {
                field.add(p, 1.0F);
                placed++;
            }
        }
        for (int guard = 0; guard < n * 10; guard++) {
            int cluster = field.tightestCluster();
            field.add(cluster, -1.0F);
            int hole = field.largestVoid();
            field.add(hole, 1.0F);
            if (hole == cluster) {
                break;
            }
        }

        int[] rank = new int[n];
        boolean[] pattern = field.set.clone();
        float[] energy = field.energy.clone();

        // phase 1: take the ones out, tightest cluster first, they get the ranks below the initial pattern's size
        for (int ones = initial; ones > 0; ) {
            int p = field.tightestCluster();
            field.add(p, -1.0F);
            ones--;
            rank[p] = ones;
        }

        // phase 2 (and 3, it is the same rule): back to the initial pattern, fill the largest void until full
        field.restore(pattern, energy);
        for (int ones = initial; ones < n; ones++) {
            int p = field.largestVoid();
            field.add(p, 1.0F);
            rank[p] = ones;
        }

        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Math.round((rank[i] + 0.5F) / n * 255.0F);
        }
        return out;
    }

    /** a wrapping binary field with the gaussian energy of its ones kept up to date */
    private static final class Field {
        final int size;
        final float[] kernel;
        boolean[] set;
        float[] energy;

        Field(int size, float[] kernel) {
            this.size = size;
            this.kernel = kernel;
            this.set = new boolean[size * size];
            this.energy = new float[size * size];
        }

        void restore(boolean[] pattern, float[] energyCopy) {
            this.set = pattern.clone();
            this.energy = energyCopy.clone();
        }

        /** sign +1 sets the pixel, -1 clears it */
        void add(int p, float sign) {
            this.set[p] = sign > 0.0F;
            int px = p % this.size;
            int py = p / this.size;
            int width = 2 * RADIUS + 1;
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                int row = Math.floorMod(py + dy, this.size) * this.size;
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    this.energy[row + Math.floorMod(px + dx, this.size)] +=
                            sign * this.kernel[(dy + RADIUS) * width + dx + RADIUS];
                }
            }
        }

        /** the set pixel with the most energy around it */
        int tightestCluster() {
            int best = -1;
            float bestEnergy = -Float.MAX_VALUE;
            for (int i = 0; i < this.set.length; i++) {
                if (this.set[i] && this.energy[i] > bestEnergy) {
                    bestEnergy = this.energy[i];
                    best = i;
                }
            }
            return best;
        }

        /** the clear pixel with the least energy around it */
        int largestVoid() {
            int best = -1;
            float bestEnergy = Float.MAX_VALUE;
            for (int i = 0; i < this.set.length; i++) {
                if (!this.set[i] && this.energy[i] < bestEnergy) {
                    bestEnergy = this.energy[i];
                    best = i;
                }
            }
            return best;
        }
    }
}
