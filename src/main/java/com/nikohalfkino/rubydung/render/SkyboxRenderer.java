package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.SunPhase;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

public class SkyboxRenderer {

    private static final float SKY_RADIUS = 2000.0F;

    private static final int LATITUDE_SEGMENTS = 48;
    private static final int LONGITUDE_SEGMENTS = 96;

    // one colour per latitude ring (see SkyDomeShader)
    static final int RING_COUNT = LATITUDE_SEGMENTS + 1;

    private static final String ENVIRONMENT_DIR = "/assets/rubydung/textures/enviroment/";
    private static final String MOON_TEXTURE = "/assets/rubydung/textures/enviroment/moon.png";

    // Aristotle's order outwards: Moon, Mercury (1450), Venus (1500), Sun, Mars (1600) ... see CelestialSphere.
    // Both are inside SKY_RADIUS. Quad sizes are scaled by distance / BODY_REFERENCE_DISTANCE so they keep the same apparent size.
    private static final float MOON_DISTANCE = 1400.0F;
    private static final float SUN_DISTANCE = 1550.0F;
    private static final float BODY_REFERENCE_DISTANCE = 1500.0F;

    // world units per texture pixel: 1.0 = the quad is exactly as many units wide/tall as the image is pixels
    private static final float BODY_PIXEL_SCALE = 6.0F;

    // tilt of the sun/moon orbit away from the east-west plane (radians), so noon isn't exactly overhead
    private static final float ORBIT_TILT = 0.4F;
    private static final float SIN_TILT = (float) Math.sin(ORBIT_TILT);
    private static final float COS_TILT = (float) Math.cos(ORBIT_TILT);

    /** sin of the orbit tilt, shared with CelestialSphere so the stars and planets turn about the same axis as the sun */
    static float sinTilt() {
        return SIN_TILT;
    }

    /** cos of the orbit tilt */
    static float cosTilt() {
        return COS_TILT;
    }

    private final Tessellator tessellator = new Tessellator();

    // everything beyond the sun and moon: planets, the fixed stars and the constellations
    private final CelestialSphere celestialSphere = new CelestialSphere();

    private boolean bodyTexturesLoaded = false;
    // one sun texture per phase, {id, width, height}. a phase without a file of its own uses the albedo sun (sun.png)
    private final int[][] sunTextures = new int[SunPhase.values().length][];
    private int moonTexture = 0, moonWidth = 0, moonHeight = 0;

    // The current cycle reference, updated each frame before rendering.
    private DayNightCycle cycle;

    // When true the sky writes and tests depth, so it can be looked at from outside (freecam) without its far side
    // painting over its near side. Off normally: the camera is inside, and the sky is just a backdrop drawn first.
    private boolean solid = false;

    public void setSolid(boolean solid) {
        this.solid = solid;
    }

    /**
     * Draws only the sky dome. Sky colours come from the day-night cycle, so the mesh is rendered directly every frame
     * instead of cached in a display list.
     * The sun and moon are NOT drawn here: call {@link #renderSunAndMoon} between the two cloud layers
     * (see the betweenLayers callback of CloudRenderer.render).
     */
    public void render(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle) {
        this.cycle = cycle;

        setupRenderState(true);

        glPushMatrix();
        glTranslated(eyeX, eyeY, eyeZ);
        renderMesh();

        // Stars, constellations and planets sit on top of the dome, behind the clouds, sun and moon. They only test
        // depth (when solid), never write it, so they can't hide anything drawn after them.
        glDepthMask(false);
        this.celestialSphere.render(cycle);
        glDepthMask(this.solid);
        glPopMatrix();

        cleanupRenderState();
    }

    /** Draws the sun and moon on top of whatever is already in the sky (call between the white and coloured cloud layers). */
    public void renderSunAndMoon(double eyeX, double eyeY, double eyeZ, DayNightCycle cycle) {
        this.cycle = cycle;

        setupRenderState(false);

        glPushMatrix();
        glTranslated(eyeX, eyeY, eyeZ);
        drawSunAndMoon();
        glPopMatrix();

        cleanupRenderState();
    }

    // sun and moon

    // camera-facing quads on a tilted circular orbit driven by the cycle's time (0.25 = sunrise, 0.5 = highest, 0.75 = sunset, the moon is opposite)
    // the textures have a black background, so they're blended additively and the black vanishes
    private void drawSunAndMoon() {
        if (!this.bodyTexturesLoaded) {
            this.bodyTexturesLoaded = true;
            for (SunPhase phase : SunPhase.values()) {
                int[] tex = loadTexture(ENVIRONMENT_DIR + phase.texture(), phase != SunPhase.ALBEDO);
                this.sunTextures[phase.ordinal()] = tex[0] != 0 || phase == SunPhase.ALBEDO ? tex : this.sunTextures[0];
            }
            int[] moon = loadTexture(MOON_TEXTURE);
            this.moonTexture = moon[0];
            this.moonWidth = moon[1];
            this.moonHeight = moon[2];
        }

        float angle = (this.cycle.getTime() - 0.25F) * (float) (2.0 * Math.PI);
        float sunX = (float) Math.cos(angle);
        float sunY = (float) Math.sin(angle) * COS_TILT;
        float sunZ = (float) Math.sin(angle) * SIN_TILT;

        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);

        // The sun textures have a black background and are blended additively (GL_ONE, GL_ONE), so the black adds
        // nothing and only the sun shows. The phase changes at midnight, when the sun is far below the horizon.
        int[] sun = this.sunTextures[this.cycle.getSunPhase().ordinal()];
        if (sun != null && sun[0] != 0) {
            // Fade out as it drops below the horizon, and turn warm while it is low.
            float visibility = clamp01((sunY + 0.1F) / 0.25F);
            float warm = clamp01(1.0F - sunY / 0.45F);
            drawBody(sun[0], SUN_DISTANCE, sunX, sunY, sunZ,
                    sun[1] * 0.5F * BODY_PIXEL_SCALE,
                    sun[2] * 0.5F * BODY_PIXEL_SCALE,
                    lerp(1.0F, 1.0F, warm) * visibility,
                    lerp(1.0F, 0.6F, warm) * visibility,
                    lerp(1.0F, 0.3F, warm) * visibility);
        }

        if (this.moonTexture != 0) {
            float moonY = -sunY;
            float visibility = clamp01((moonY + 0.1F) / 0.25F);
            // A bright daytime sky washes the moon out, but it stays faintly visible.
            float dim = 1.0F - 0.65F * this.cycle.getAmbient();
            float v = visibility * dim;
            drawBody(this.moonTexture, MOON_DISTANCE, -sunX, moonY, -sunZ,
                    this.moonWidth * 0.5F * BODY_PIXEL_SCALE,
                    this.moonHeight * 0.5F * BODY_PIXEL_SCALE,
                    0.85F * v, 0.9F * v, 1.0F * v);
        }

        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_BLEND);
    }

    private void drawBody(int texture, float distance, float dx, float dy, float dz, float halfW, float halfH, float r, float g, float b) {
        if (r <= 0.0F && g <= 0.0F && b <= 0.0F) {
            return;
        }

        // keep the apparent size the same whatever sphere the body sits on
        float sizeScale = distance / BODY_REFERENCE_DISTANCE;
        halfW *= sizeScale;
        halfH *= sizeScale;

        // Camera-facing basis: right = dir x worldUp, up = right x dir.
        float rx = -dz;
        float rz = dx;
        float len = (float) Math.sqrt(rx * rx + rz * rz);
        if (len < 1.0E-4F) {
            rx = 1.0F;
            rz = 0.0F;
        } else {
            rx /= len;
            rz /= len;
        }
        float ux = -rz * dy;
        float uy = rz * dx - rx * dz;
        float uz = rx * dy;

        float cx = dx * distance;
        float cy = dy * distance;
        float cz = dz * distance;

        Textures.bind(texture);

        this.tessellator.init(GL_QUADS);
        this.tessellator.color(r, g, b, 1.0F);

        this.tessellator.texture(0.0F, 0.0F);
        this.tessellator.vertex(cx - rx * halfW + ux * halfH, cy + uy * halfH, cz - rz * halfW + uz * halfH);
        this.tessellator.texture(1.0F, 0.0F);
        this.tessellator.vertex(cx + rx * halfW + ux * halfH, cy + uy * halfH, cz + rz * halfW + uz * halfH);
        this.tessellator.texture(1.0F, 1.0F);
        this.tessellator.vertex(cx + rx * halfW - ux * halfH, cy - uy * halfH, cz + rz * halfW - uz * halfH);
        this.tessellator.texture(0.0F, 1.0F);
        this.tessellator.vertex(cx - rx * halfW - ux * halfH, cy - uy * halfH, cz - rz * halfW - uz * halfH);

        this.tessellator.flush();
    }

    private static int[] loadTexture(String path) {
        return loadTexture(path, false);
    }

    // loads a PNG from the classpath into a clamped, nearest-filtered RGBA texture
    // returns {id, width, height}, or {0, 0, 0} on failure
    // optional: a missing file is expected (the caller has a fallback), so it isn't reported
    static int[] loadTexture(String path, boolean optional) {
        try (InputStream in = SkyboxRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                if (!optional) {
                    System.out.println("Missing sky texture: " + path);
                }
                return new int[]{0, 0, 0};
            }

            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                System.out.println("Unreadable sky texture: " + path);
                return new int[]{0, 0, 0};
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

            // Premultiply by alpha so any transparent pixels contribute nothing when added.
            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
            for (int pixel : pixels) {
                int a = pixel >>> 24;
                buffer.put((byte) (((pixel >> 16) & 0xFF) * a / 255));
                buffer.put((byte) (((pixel >> 8) & 0xFF) * a / 255));
                buffer.put((byte) ((pixel & 0xFF) * a / 255));
                buffer.put((byte) a);
            }
            buffer.flip();

            int id = glGenTextures();
            Textures.bind(id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            return new int[]{id, width, height};
        } catch (IOException e) {
            System.out.println("Failed to load sky texture " + path + ": " + e.getMessage());
            return new int[]{0, 0, 0};
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    // Trig for the dome never changes, so it is computed once.
    private static final float[] RING_Y = new float[LATITUDE_SEGMENTS + 1];       // height of each latitude ring
    private static final float[] RING_RADIUS = new float[LATITUDE_SEGMENTS + 1];  // horizontal radius of each ring
    private static final float[] LON_COS = new float[LONGITUDE_SEGMENTS + 1];
    private static final float[] LON_SIN = new float[LONGITUDE_SEGMENTS + 1];

    static {
        for (int lat = 0; lat <= LATITUDE_SEGMENTS; lat++) {
            double theta = -Math.PI / 2.0 + Math.PI * lat / LATITUDE_SEGMENTS;
            RING_Y[lat] = (float) Math.sin(theta) * SKY_RADIUS;
            RING_RADIUS[lat] = (float) Math.cos(theta) * SKY_RADIUS;
        }
        for (int lon = 0; lon <= LONGITUDE_SEGMENTS; lon++) {
            double phi = 2.0 * Math.PI * lon / LONGITUDE_SEGMENTS;
            LON_COS[lon] = (float) Math.cos(phi);
            LON_SIN[lon] = (float) Math.sin(phi);
        }
    }

    // The colour only depends on the latitude, so it is worked out once per ring per frame, not once per vertex.
    private final float[] ringR = new float[LATITUDE_SEGMENTS + 1];
    private final float[] ringG = new float[LATITUDE_SEGMENTS + 1];
    private final float[] ringB = new float[LATITUDE_SEGMENTS + 1];

    // how fast the dome goes from the horizon colour to the nadir colour looking down (it was 0.75, which is already a quarter of the
    // way there 10 degrees below the horizon). higher = the horizon colour reaches further down
    private static final double NADIR_BLEND_EXPONENT = 1.6D;

    private void computeRingColors() {
        float[] zenith  = cycle.getZenithColor();
        float[] horizon = cycle.getHorizonColor();
        float[] nadir   = cycle.getNadirColor();

        for (int lat = 0; lat <= LATITUDE_SEGMENTS; lat++) {
            float y = RING_Y[lat];
            float r, g, b;

            if (y >= 0.0F) {
                float t = (y / SKY_RADIUS + 0.35F) / 1.35F;
                t = Math.max(0.0F, Math.min(1.0F, t));
                t = t * t * (3.0F - 2.0F * t);

                r = lerp(horizon[0], zenith[0], t);
                g = lerp(horizon[1], zenith[1], t);
                b = lerp(horizon[2], zenith[2], t);
            } else {
                // the sea's far edge (where the fog ends) is seen a few degrees below the horizon: a higher exponent keeps the
                // dome at the horizon colour down to there, so it matches the fog instead of showing a lighter band under it
                float t = (float) Math.pow(-y / SKY_RADIUS, NADIR_BLEND_EXPONENT);

                r = lerp(horizon[0], nadir[0], t);
                g = lerp(horizon[1], nadir[1], t);
                b = lerp(horizon[2], nadir[2], t);
            }

            this.ringR[lat] = r;
            this.ringG[lat] = g;
            this.ringB[lat] = b;
        }
    }

    // the dome is static geometry (compiled once), only its ring colours change. SkyDomeShader colours it from a uniform array
    private int domeList = 0;
    private final FloatBuffer ringUniform = BufferUtils.createFloatBuffer(RING_COUNT * 3);

    private void renderMesh() {
        computeRingColors();

        if (this.domeList == 0) {
            buildDomeList();
        }

        this.ringUniform.clear();
        for (int lat = 0; lat < RING_COUNT; lat++) {
            this.ringUniform.put(this.ringR[lat]).put(this.ringG[lat]).put(this.ringB[lat]);
        }
        this.ringUniform.flip();

        SkyDomeShader.bind(this.ringUniform);
        try {
            glCallList(this.domeList);
        } finally {
            SkyDomeShader.unbind();
        }
    }

    private void buildDomeList() {
        this.domeList = glGenLists(1);
        glNewList(this.domeList, GL_COMPILE);
        for (int lat = 0; lat < LATITUDE_SEGMENTS; lat++) {
            glBegin(GL_TRIANGLE_STRIP);
            for (int lon = 0; lon <= LONGITUDE_SEGMENTS; lon++) {
                domeVertex(lat, lon);
                domeVertex(lat + 1, lon);
            }
            glEnd();
        }
        glEndList();
    }

    // the ring index rides in texture coordinate 0, SkyDomeShader turns it into the ring's colour
    private static void domeVertex(int lat, int lon) {
        glTexCoord2f(lat, 0.0F);
        glVertex3f(RING_RADIUS[lat] * LON_COS[lon], RING_Y[lat], RING_RADIUS[lat] * LON_SIN[lon]);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // writeDepth: whether this pass writes depth when the sky is solid (the dome does, the sun and moon only test)
    private void setupRenderState(boolean writeDepth) {
        glDisable(GL_CULL_FACE);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);

        if (this.solid) {
            glEnable(GL_DEPTH_TEST);
            glDepthMask(writeDepth);
        } else {
            glDisable(GL_DEPTH_TEST);
            glDepthMask(false);
        }

        glShadeModel(GL_SMOOTH);
    }

    private void cleanupRenderState() {
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);

        glEnable(GL_ALPHA_TEST);
        glEnable(GL_FOG);
        glEnable(GL_CULL_FACE);
        glEnable(GL_TEXTURE_2D);
    }
}