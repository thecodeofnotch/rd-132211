package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Draws the liquid layer of chunk meshes. The GLSL lives next to the clean edge shader:
 *   resources/assets/rubydung/shaders/liquid.vert
 *   resources/assets/rubydung/shaders/liquid.frag
 *
 * Before compiling, two things are filled in so the shader always matches LiquidTile.MAX_LIQUIDS:
 *   - "#define MAX_LIQUIDS n" is added under the #version line (both files)
 *   - the "//@SAMPLERS" line of the fragment shader becomes the liqTex0..n-1 samplers and sampleLiq()
 *     (GLSL 120 can't index a sampler array dynamically, hence the if-chain)
 *
 * Each liquid's sheet (textures/block/liquid/NAME.png, cells of 16x16) is bound to its own texture unit and
 * laid over the world like WrappedTile: one cell per block, repeating every columns x rows blocks. Everything that decides how the liquid looks is in the .frag (see the tunables
 * at the top: PIXELS_PER_BLOCK, TINT, COLOR_LEVELS, WARP_BLOCKS).
 *
 * A second, tiny program (liquid_depth.vert / liquid_depth.frag) draws the depth pre-pass: same positions, same discards
 * (distance dither and ocean fade), none of the shading. See bindDepth().
 *
 * The ocean fade: OceanSurface bakes into each column's quad (texcoord unit 1, s) the sea height at which that column first gets
 * water. The vertex shader turns "surface - that height" into vOceanFade (0..1 over OCEAN_FADE_DEPTH blocks of water) and the
 * fragment shader dithers with it, so columns fade in / out as the sea rises / falls instead of popping in a whole contour at once.
 *
 * Vertex colour layout (written by LiquidTile.renderLiquid, decoded in liquid.vert):
 *   r, g, b = sky light, block light, face shade
 *   a       = packed integer / 32768 (edge mask, corner mask, top flag, surface flag, foam weight, liquid index)
 */
public final class LiquidShader {

    private static final String VERTEX_PATH = "/assets/rubydung/shaders/liquid.vert";
    private static final String FRAGMENT_PATH = "/assets/rubydung/shaders/liquid.frag";

    // blob texture the foam is made from, bound to the texture unit after the liquid sheets
    private static final String DISTORTION_PATH = "/assets/rubydung/textures/block/liquid/distortion.png";

    private static final String DEPTH_VERTEX_PATH = "/assets/rubydung/shaders/liquid_depth.vert";
    private static final String DEPTH_FRAGMENT_PATH = "/assets/rubydung/shaders/liquid_depth.frag";

    private static final String SAMPLERS_MARKER = "//@SAMPLERS";

    /** blocks of water over a column until it is fully opaque. keep it under 0.875 (the default sea sits that far over the shallowest column) or the resting sea is speckled */
    private static final float OCEAN_FADE_DEPTH = 0.5F;

    private static final String OCEAN_VERTEX_DECLARATIONS =
            "uniform float uSeaSurface;\n" +
                    "varying float vOceanFade;\n";

    private static final String OCEAN_FRAGMENT_DECLARATIONS =
            "uniform vec3 uSunTint;\n" +
                    "varying float vOceanFade;\n" +
                    "void applyOceanFade() {\n" +
                    "    if (vOceanFade >= 1.0) return;\n" +
                    "    vec2 coord = floor(gl_FragCoord.xy);\n" +
                    // the same noise as Fog's applyDither(), so the two fades nest instead of fighting
                    "    float noise = fract(52.9829189 * fract(dot(coord, vec2(0.06711056, 0.00583715))));\n" +
                    "    if (vOceanFade <= noise) discard;\n" +
                    "}\n";

    private static final long START = System.nanoTime();

    private static int program = 0;
    private static int distortionTexture = 0;
    private static int timeLocation = -1;
    private static int ambientLocation = -1;
    private static int sunTintLocation = -1;
    private static int colorLocation = -1;
    private static int foamLocation = -1;
    private static int motionLocation = -1;
    private static int sheetLocation = -1;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;
    private static int seaSurfaceLocation = -1;
    private static int entityBoxLocation = -1;
    private static int entityInfoLocation = -1;
    private static int entityCountLocation = -1;
    private static int wakeLocation = -1;
    private static int wakeInfoLocation = -1;

    // the depth-only program
    private static int depthProgram = 0;
    private static int depthSeaSurfaceLocation = -1;
    private static int depthFogParamsLocation = -1;
    private static int depthFogEyeLocation = -1;

    private static final FloatBuffer COLORS = BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * 4);
    private static final FloatBuffer FOAMS = BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * 4);
    private static final FloatBuffer MOTIONS = BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * 4);
    private static final FloatBuffer SHEETS = BufferUtils.createFloatBuffer(LiquidTile.MAX_LIQUIDS * 4);

    private LiquidShader() {}

    /** seconds on the clock the shader's `time` uniform runs on (LiquidEntities stamps its wake with it) */
    public static float now() {
        return (System.nanoTime() - START) * 1.0E-9F;
    }

    /** the cheap program for the depth pre-pass: positions and discards only. seaSurface is LiquidLayer.getSurfaceHeight() */
    public static void bindDepth(float seaSurface) {
        if (depthProgram == 0) {
            initDepth();
        }
        GL20.glUseProgram(depthProgram);
        GL20.glUniform1f(depthSeaSurfaceLocation, seaSurface);
        Fog.upload(depthFogParamsLocation, -1, depthFogEyeLocation);   // the fog colour isn't used here
    }

    /** the full program for the colour pass. seaSurface is LiquidLayer.getSurfaceHeight() */
    public static void bind(float ambient, float seaSurface) {
        if (program == 0) {
            init();
        }

        GL20.glUseProgram(program);
        GL20.glUniform1f(timeLocation, now());
        GL20.glUniform1f(ambientLocation, ambient);
        WorldTint.upload(sunTintLocation);
        GL20.glUniform1f(seaSurfaceLocation, seaSurface);
        Fog.upload(fogParamsLocation, fogColorLocation, fogEyeLocation);
        uploadLiquids();
        LiquidEntities.upload(entityBoxLocation, entityInfoLocation, entityCountLocation, wakeLocation, wakeInfoLocation);
        bindTextures();
    }

    public static void unbind() {
        for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + LiquidTile.MAX_LIQUIDS);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL20.glUseProgram(0);
    }

    // texture unit i holds liquid i's texture, the unused units repeat liquid 0 so every sampler is valid
    private static void bindTextures() {
        List<LiquidTile> liquids = LiquidTile.all();
        if (liquids.isEmpty()) {
            return;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            LiquidTile liquid = i < liquids.size() ? liquids.get(i) : liquids.get(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, liquid.getFluidTextureId());
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + LiquidTile.MAX_LIQUIDS);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, distortionTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void uploadLiquids() {
        COLORS.clear();
        FOAMS.clear();
        MOTIONS.clear();
        SHEETS.clear();

        List<LiquidTile> liquids = LiquidTile.all();
        for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
            if (i < liquids.size()) {
                LiquidTile liq = liquids.get(i);
                COLORS.put(liq.getRed()).put(liq.getGreen()).put(liq.getBlue()).put(liq.getOpacity());
                FOAMS.put(liq.getFoamRed()).put(liq.getFoamGreen()).put(liq.getFoamBlue()).put(liq.getFoamAmount());
                MOTIONS.put(liq.getWarp()).put(liq.getFlowSpeed()).put(liq.getWaveHeight()).put(0.0F);
                SHEETS.put(liq.getSheetColumns()).put(liq.getSheetRows()).put(0.0F).put(0.0F);
            } else {
                COLORS.put(0.0F).put(0.0F).put(0.0F).put(1.0F);
                FOAMS.put(0.0F).put(0.0F).put(0.0F).put(0.0F);
                MOTIONS.put(0.0F).put(0.0F).put(0.0F).put(0.0F);
                SHEETS.put(1.0F).put(1.0F).put(0.0F).put(0.0F);
            }
        }
        COLORS.flip();
        FOAMS.flip();
        MOTIONS.flip();
        SHEETS.flip();

        GL20.glUniform4(colorLocation, COLORS);
        GL20.glUniform4(foamLocation, FOAMS);
        GL20.glUniform4(motionLocation, MOTIONS);
        GL20.glUniform4(sheetLocation, SHEETS);
    }

    /** compiles and links the program now (it is otherwise built on its first use, in the middle of a frame): called from ShaderWarmup while the world loads */
    public static void warmUp() {
        if (program == 0) {
            init();
        }
    }

    private static void init() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERTEX_PATH);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_PATH);   // a missing marker shows up as undefined liqTex0

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Liquid shader link failed:\n" + GL20.glGetProgramInfoLog(p, 4096));
        }

        timeLocation = GL20.glGetUniformLocation(p, "time");
        ambientLocation = GL20.glGetUniformLocation(p, "ambient");
        sunTintLocation = GL20.glGetUniformLocation(p, "uSunTint");
        colorLocation = GL20.glGetUniformLocation(p, "liqColor");
        foamLocation = GL20.glGetUniformLocation(p, "liqFoam");
        motionLocation = GL20.glGetUniformLocation(p, "liqMotion");
        sheetLocation = GL20.glGetUniformLocation(p, "liqSheet");
        fogParamsLocation = GL20.glGetUniformLocation(p, "uFogParams");
        fogColorLocation = GL20.glGetUniformLocation(p, "uFogColor");
        fogEyeLocation = GL20.glGetUniformLocation(p, "uFogEye");
        seaSurfaceLocation = GL20.glGetUniformLocation(p, "uSeaSurface");
        entityBoxLocation = GL20.glGetUniformLocation(p, "uEntityBox");
        entityInfoLocation = GL20.glGetUniformLocation(p, "uEntityInfo");
        entityCountLocation = GL20.glGetUniformLocation(p, "uEntityCount");
        wakeLocation = GL20.glGetUniformLocation(p, "uWake");
        wakeInfoLocation = GL20.glGetUniformLocation(p, "uWakeInfo");

        GL20.glUseProgram(p);
        for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "liqTex" + i), i);   // sampler i reads texture unit i
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "foamTex"), LiquidTile.MAX_LIQUIDS);   // the foam image sits after the liquid sheets
        GL20.glUseProgram(0);

        distortionTexture = loadDistortionTexture();
        program = p;
    }

    /** the depth pre-pass program, see warmUp() */
    public static void warmUpDepth() {
        if (depthProgram == 0) {
            initDepth();
        }
    }

    private static void initDepth() {
        int vs = compile(GL20.GL_VERTEX_SHADER, DEPTH_VERTEX_PATH);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, DEPTH_FRAGMENT_PATH);

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Liquid depth shader link failed:\n" + GL20.glGetProgramInfoLog(p, 4096));
        }

        depthSeaSurfaceLocation = GL20.glGetUniformLocation(p, "uSeaSurface");
        depthFogParamsLocation = GL20.glGetUniformLocation(p, "uFogParams");
        depthFogEyeLocation = GL20.glGetUniformLocation(p, "uFogEye");
        depthProgram = p;
    }

    // distortion.png as a repeating, smoothly filtered GL texture (a flat grey one if it can't be read)
    private static int loadDistortionTexture() {
        int w = 1, h = 1;
        int[] argb = {0xFF808080};
        try (InputStream in = LiquidShader.class.getResourceAsStream(DISTORTION_PATH)) {
            BufferedImage image = in == null ? null : ImageIO.read(in);
            if (image != null) {
                w = image.getWidth();
                h = image.getHeight();
                argb = image.getRGB(0, 0, w, h, null, 0, w);
            } else {
                System.err.println("[LiquidShader] missing " + DISTORTION_PATH);
            }
        } catch (IOException e) {
            System.err.println("[LiquidShader] could not read " + DISTORTION_PATH + ": " + e);
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
        for (int c : argb) {
            buffer.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >> 24));
        }
        buffer.flip();

        int id = GL11.glGenTextures();
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return id;
    }

    private static int compile(int type, String resource) {
        String source = addFog(prepare(readResource(resource)), type == GL20.GL_FRAGMENT_SHADER);

        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new RuntimeException(resource + " failed to compile (line numbers are +1 because of the injected #define):\n" + log);
        }
        return shader;
    }

    // adds the MAX_LIQUIDS define under #version and fills in the sampler marker
    private static String prepare(String source) {
        int newline = source.indexOf('\n');
        String version = newline < 0 ? source : source.substring(0, newline);
        String rest = newline < 0 ? "" : source.substring(newline + 1);
        int marker = rest.indexOf("\n" + SAMPLERS_MARKER);
        int from = marker >= 0 ? marker + 1 : rest.startsWith(SAMPLERS_MARKER) ? 0 : -1;
        if (from >= 0) {
            rest = rest.substring(0, from) + buildSamplers() + rest.substring(from + SAMPLERS_MARKER.length());
        }
        return version + "\n#define MAX_LIQUIDS " + LiquidTile.MAX_LIQUIDS
                + "\n#define MAX_ENTITIES " + LiquidEntities.MAX_ENTITIES
                + "\n#define MAX_WAKES " + LiquidEntities.MAX_WAKES
                + "\n#define WAKE_LIFE " + LiquidEntities.WAKE_LIFE + "\n" + rest;
    }

    private static final java.util.regex.Pattern MAIN = java.util.regex.Pattern.compile("void\\s+main\\s*\\(\\s*(void)?\\s*\\)");

    /**
     * adds distance fog and dither fading without touching the shader files: the original main() is renamed and called from a new one.
     */
    private static String addFog(String source, boolean fragment) {
        java.util.regex.Matcher m = MAIN.matcher(source);
        if (!m.find()) {
            System.err.println("[LiquidShader] no main() found, liquids are drawn without fog/dither");
            return source;
        }
        String renamed = source.substring(0, m.start()) + "void liquidMain()" + source.substring(m.end());
        String declarations = fragment
                ? (Fog.GLSL_FRAGMENT_DECLARATIONS + Fog.GLSL_DITHER_DECLARATIONS + OCEAN_FRAGMENT_DECLARATIONS)
                : (Fog.GLSL_VERTEX_DECLARATIONS + OCEAN_VERTEX_DECLARATIONS);
        // texcoord unit 1, s = the sea height this column first gets water at (OceanSurface), a huge negative number for everything else
        String main = fragment
                ? "\nvoid main() {\n    applyDither();\n    applyOceanFade();\n    liquidMain();\n    gl_FragColor.rgb = applyFog(gl_FragColor.rgb * uSunTint);\n}\n"
                : "\nvoid main() {\n    liquidMain();\n    vFogWorldXZ = gl_Vertex.xz;\n"
                + "    vOceanFade = clamp((uSeaSurface - gl_MultiTexCoord1.s) * " + (1.0F / OCEAN_FADE_DEPTH) + ", 0.0, 1.0);\n}\n";

        int afterHeader = 0;
        while (true) {   // the #version line and every #define under it (prepare() adds several)
            int newline = renamed.indexOf('\n', afterHeader);
            if (newline < 0) {
                break;
            }
            String line = renamed.substring(afterHeader, newline).trim();
            if (!line.startsWith("#version") && !line.startsWith("#define")) {
                break;
            }
            afterHeader = newline + 1;
        }
        return renamed.substring(0, afterHeader) + declarations + renamed.substring(afterHeader) + main;
    }

    private static String buildSamplers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
            sb.append("uniform sampler2D liqTex").append(i).append(";\n");
        }
        sb.append("vec4 sampleLiq(int idx, vec2 uv) {\n");
        for (int i = 0; i < LiquidTile.MAX_LIQUIDS; i++) {
            sb.append("    if (idx == ").append(i).append(") return texture2D(liqTex").append(i).append(", uv);\n");
        }
        sb.append("    return texture2D(liqTex0, uv);\n}\n");
        return sb.toString();
    }

    private static String readResource(String resource) {
        try (InputStream in = LiquidShader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new RuntimeException("Missing shader resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + resource, e);
        }
    }
}