package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import static org.lwjgl.opengl.GL11.*;

public class CloudRenderer {

    private static final float TILE_WORLD_SIZE = 128.0F;
    private static final float PLANE_HALF_SIZE = 800.0F;

    private static final int GRID_SEGMENTS = 144;
    private static final float CELL_SIZE = (PLANE_HALF_SIZE * 2.0F) / GRID_SEGMENTS;

    // world units covered by one repeat of the cloud texture
    private static final float UV_SCALE = TILE_WORLD_SIZE * 4.0F;

    private static final float FADE_START_FRACTION = 0.3F;

    // how many texture repeats the upper layer scrolls per millisecond (one full repeat every 100 s)
    private static final double DRIFT_TILES_PER_MS = 0.00002 / 2;

    // the lower layer scrolls slower than the upper one
    private static final double LOWER_SPEED_FACTOR = 0.7;

    public static final float HEIGHT_OFFSET = 180.0F;

    private static final float LAYER_GAP = 15.0F;

    // Per-layer alpha and light bias. The colours come from the sky (DayNightCycle.getCloudColor / getCloudShadowColor, see
    // CloudShader): the high layer is a little brighter, the low one a little darker, so the two read as two layers.
    private static final float LOWER_ALPHA = 1.0F;
    private static final float LOWER_BIAS = -0.08F;
    private static final float UPPER_ALPHA = 0.8F;
    private static final float UPPER_BIAS = 0.12F;

    private final Tessellator tessellator = new Tessellator();

    private final long driftStartNanos = System.nanoTime();

    // radial edge fade (1 = opaque, 0 = invisible) for every grid vertex; the grid is centred on the eye, so this never changes
    private final float[][] edgeAlpha = new float[GRID_SEGMENTS + 1][GRID_SEGMENTS + 1];

    private int textureId = -1;

    // The cloud plane never changes (the drift is done with the texture matrix), so each layer's mesh is
    // compiled once into a display list instead of being re-submitted vertex by vertex every frame.
    private int lowerList = -1;
    private int upperList = -1;

    public CloudRenderer() {
        for (int gx = 0; gx <= GRID_SEGMENTS; gx++) {
            for (int gz = 0; gz <= GRID_SEGMENTS; gz++) {
                float x = -PLANE_HALF_SIZE + gx * CELL_SIZE;
                float z = -PLANE_HALF_SIZE + gz * CELL_SIZE;
                float radiusFraction = (float) Math.sqrt(x * x + z * z) / PLANE_HALF_SIZE;
                float fade = ease(clamp01((radiusFraction - FADE_START_FRACTION) / (1.0F - FADE_START_FRACTION)));
                this.edgeAlpha[gx][gz] = 1.0F - fade;
            }
        }
    }

    private static float ease(float t) {
        float smooth = t * t * (3.0F - 2.0F * t);
        return smooth * smooth * (3.0F - 2.0F * smooth);
    }

    private static float clamp01(float value) {
        if (value < 0.0F) return 0.0F;
        if (value > 1.0F) return 1.0F;
        return value;
    }

    public void render(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle) {
        renderLayers(eyeX, eyeY, eyeZ, cycle, null);
    }

    /**
     * @param betweenLayers run after the lower (white) layer and before the upper (coloured) one, with the GL state
     *                      restored to normal; use it to draw the sun and moon between the two layers. May be null.
     */
    public void render(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle, Runnable betweenLayers) {
        renderLayers(eyeX, eyeY, eyeZ, cycle, betweenLayers);
    }

    public void renderLayers(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle, Runnable betweenLayers) {
        if (textureId == -1) {
            textureId = Textures.loadCloudTexture(CloudTextureGenerator.generate(1024, 42L));
        }
        if (lowerList == -1) {
            lowerList = buildLayerList(LOWER_ALPHA);
            upperList = buildLayerList(UPPER_ALPHA);
        }

        // Each layer wraps on its own full texture repeat, so both loop seamlessly.
        double elapsedMs = (System.nanoTime() - driftStartNanos) / 1_000_000.0;
        float upperShift = (float) ((elapsedMs * DRIFT_TILES_PER_MS) % 1.0);
        float lowerShift = (float) ((elapsedMs * DRIFT_TILES_PER_MS * LOWER_SPEED_FACTOR) % 1.0);

        float[] lit = cycle.getCloudColor();
        float[] shadow = cycle.getCloudShadowColor();

        setupRenderState();
        Textures.bind(textureId);

        drawLayer(lowerList, eyeX, eyeY + HEIGHT_OFFSET - LAYER_GAP, eyeZ, lit, shadow, LOWER_BIAS, lowerShift);

        if (betweenLayers != null) {
            // The callback draws with its own GL state, so hand it a clean one and set the cloud state up again afterwards.
            cleanupRenderState();
            betweenLayers.run();
            setupRenderState();
            Textures.bind(textureId);
        }

        drawLayer(upperList, eyeX, eyeY + HEIGHT_OFFSET + LAYER_GAP, eyeZ, lit, shadow, UPPER_BIAS, upperShift);

        cleanupRenderState();
    }

    // Compiles the whole cloud plane (positions, edge-fade alpha, unshifted UVs) once.
    private int buildLayerList(float alphaMul) {
        int list = glGenLists(1);
        glNewList(list, GL_COMPILE);

        for (int gx = 0; gx < GRID_SEGMENTS; gx++) {
            float x0 = -PLANE_HALF_SIZE + gx * CELL_SIZE;
            float x1 = x0 + CELL_SIZE;

            tessellator.init(GL_TRIANGLE_STRIP);

            for (int gz = 0; gz <= GRID_SEGMENTS; gz++) {
                float vz = -PLANE_HALF_SIZE + gz * CELL_SIZE;

                addVertex(x0, vz, this.edgeAlpha[gx][gz] * alphaMul);
                addVertex(x1, vz, this.edgeAlpha[gx + 1][gz] * alphaMul);
            }

            tessellator.flush();
        }

        glEndList();
        return list;
    }

    // The plane stays centred on the eye (so the edge fade never moves); the drift is a texture-matrix translation.
    private void drawLayer(int list, double x, double y, double z, float[] lit, float[] shadow, float bias, float shift) {
        CloudShader.setColors(lit, shadow, bias);

        glMatrixMode(GL_TEXTURE);
        glPushMatrix();
        glTranslatef(shift, 0.0F, 0.0F);
        glMatrixMode(GL_MODELVIEW);

        glPushMatrix();
        glTranslated(x, y, z);
        glCallList(list);
        glPopMatrix();

        glMatrixMode(GL_TEXTURE);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

    private void addVertex(float x, float z, float alpha) {
        // only the alpha matters here (the edge fade and the layer's alpha), the colour is the shader's
        tessellator.color(1.0F, 1.0F, 1.0F, alpha);
        tessellator.texture(x / UV_SCALE, z / UV_SCALE);
        tessellator.vertex(x, 0, z);
    }

    private void setupRenderState() {
        glDisable(GL_CULL_FACE);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        Textures.enableAlphaBlending();
        glDepthMask(false);
        glEnable(GL_TEXTURE_2D);

        CloudShader.bind();
    }

    private void cleanupRenderState() {
        CloudShader.unbind();
        // Restore the default texture environment for everything drawn after the clouds.
        glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_MODULATE);

        glDisable(GL_BLEND);
        glDepthMask(true);

        Textures.enableAlphaTest();
        glEnable(GL_FOG);
        glEnable(GL_CULL_FACE);
    }
}