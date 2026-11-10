package com.nikohalfkino.rubydung.render;

import org.lwjgl.opengl.GL20;

/**
 * Draws BillboardMesh geometry. The vertex shader does what LevelRenderer used to do on the CPU for every sprite on
 * every frame: turn the quad to face the camera (around Y only), grow it with distance (BillboardTile.getDistanceScale)
 * and drop it when it is past its reach. The fragment half is the same as BillboardShader's.
 */
public final class StaticBillboardShader {

    private static final String VERT =
            "#version 120\n" +
                    "varying vec4 vColor;\n" +
                    "varying float vWorldY;\n" +
                    Fog.GLSL_VERTEX_DECLARATIONS +
                    "uniform vec3 uEye;\n" +
                    "uniform vec2 uRight;\n" +
                    // x: reach per block of sprite size, y: reach whatever the size
                    "uniform vec2 uReach;\n" +
                    // aura (see BillboardAura): position in the quad as a fraction of its radius, density + falloff, colour
                    "varying vec2 vAuraLocal;\n" +
                    "varying vec4 vAuraArea;\n" +
                    "varying vec4 vAuraColor;\n" +
                    "varying vec3 vAuraBands;\n" +
                    "varying float vAuraBlend;\n" +
                    "varying vec4 vAuraRim;\n" +
                    "varying vec4 vAuraCore;\n" +
                    // 0 = the sprites, 1 = the auras that are blended with alpha (NORMAL and ADD), 2 = the SCREEN ones.
                    // the auras are drawn last (see LevelRenderer.renderBillboardAuras)
                    "uniform float uAuraPass;\n" +
                    "float distanceScale(float d) {\n" +
                    "    if (d < 20.0) return 1.0;\n" +
                    "    if (d < 150.0) return 1.0 + (d - 20.0) / 260.0;\n" +
                    "    if (d < 500.0) return 1.5 + (d - 150.0) / 700.0;\n" +
                    "    return 2.0;\n" +
                    "}\n" +
                    "void main() {\n" +
                    "    vec3 pivot = gl_Vertex.xyz;\n" +
                    "    vec3 corner = gl_MultiTexCoord1.xyz;\n" +
                    "    float d = distance(uEye, pivot);\n" +
                    "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
                    "    vColor = gl_Color;\n" +
                    "    vAuraArea = gl_MultiTexCoord2;\n" +
                    "    vAuraColor = gl_MultiTexCoord3;\n" +
                    "    vAuraBands = gl_MultiTexCoord4.xyw;\n" +
                    "    vAuraRim = gl_MultiTexCoord5;\n" +
                    "    vAuraCore = gl_MultiTexCoord6;\n" +
                    "    vAuraBlend = gl_MultiTexCoord4.z;\n" +
                    "    vWorldY = pivot.y;\n" +
                    "    vFogWorldXZ = pivot.xz;\n" +
                    "    bool gone = d > min(uReach.y, corner.z * uReach.x);\n" +
                    "    float scale = distanceScale(d);\n" +
                    "    vec3 p;\n" +
                    "    if (uAuraPass > 0.5) {\n" +
                    // no aura here, or one that belongs to the other pass
                    "        bool screen = gl_MultiTexCoord4.z > 1.5;\n" +
                    "        if (gl_MultiTexCoord2.z <= 0.0 || gl_MultiTexCoord2.w <= 0.0 || screen != (uAuraPass > 1.5)) gone = true;\n" +
                    // the aura's own quad: centred on the glow, 2 radii wide, so it is not limited by the sprite's texture.
                    // vAuraLocal stays in blocks from the sprite's bottom centre (unscaled), the same plane as the sprite
                    "        vec2 local = gl_MultiTexCoord2.xy + vec2(corner.x * 2.0, corner.y * 2.0 - 1.0) * gl_MultiTexCoord2.z;\n" +
                    "        vAuraLocal = local;\n" +
                    "        p = pivot + vec3(uRight.x * local.x * scale, local.y * scale, uRight.y * local.x * scale);\n" +
                    // a hair towards the eye (the screen position does not change), so the glow is not in the same depth as the sprite
                    "        p += normalize(uEye - p) * 0.05;\n" +
                    "    } else {\n" +
                    // where this vertex is on the sprite in blocks from its bottom centre (unscaled, the aura grows with the sprite)
                    "        vAuraLocal = corner.xy * corner.z;\n" +
                    "        float size = corner.z * scale;\n" +
                    "        p = pivot + vec3(uRight.x * corner.x * size, corner.y * size, uRight.y * corner.x * size);\n" +
                    "    }\n" +
                    "    if (gone) {\n" +
                    "        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);\n" +
                    "        return;\n" +
                    "    }\n" +
                    "    vWorldY = p.y;\n" +
                    "    vFogWorldXZ = p.xz;\n" +
                    "    gl_Position = gl_ModelViewProjectionMatrix * vec4(p, 1.0);\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform float ambient;\n" +
                    // pixels with less alpha than this are cut out (0.01 when the sprites are blended, 0.5 when they are opaque cut-outs)
                    "uniform float uCutoff;\n" +
                    "varying vec2 vAuraLocal;\n" +
                    "varying vec4 vAuraArea;\n" +
                    "varying vec4 vAuraColor;\n" +
                    "varying vec3 vAuraBands;\n" +
                    "varying float vAuraBlend;\n" +
                    "varying vec4 vAuraRim;\n" +
                    "varying vec4 vAuraCore;\n" +
                    "uniform float uAuraPass;\n" +
                    "varying vec4 vColor;\n" +
                    "varying float vWorldY;\n" +
                    LiquidTinting.GLSL_DECLARATIONS +
                    Fog.GLSL_FRAGMENT_DECLARATIONS +
                    Fog.GLSL_DITHER_DECLARATIONS +
                    "void main() {\n" +
                    "    applyDither();\n" +
                    // aura pass: the glow, lit as cartoon bands. the sprite's own pixels were drawn by the first pass
                    "    if (uAuraPass > 0.5) {\n" +
                    // one pixel per texel of the sprite (16 to the block) on the sprite's plane: the whole texel takes the value at its centre,
                    // so the rings are made of the same big pixels as the texture, at any distance
                    "        vec2 texel = floor(vAuraLocal * 16.0);\n" +
                    "        vec2 texelCentre = (texel + 0.5) / 16.0;\n" +
                    // the glow's centre moves to the middle of its texel: one centre pixel, and the rows and columns around it are symmetric
                    "        vec2 glowCentre = (floor(vAuraArea.xy * 16.0) + 0.5) / 16.0;\n" +
                    "        vec2 p = (texelCentre - glowCentre) / vAuraArea.z;\n" +
                    // squareness: from round (length) to square (the larger of the two axes)
                    "        float d = mix(length(p), max(abs(p.x), abs(p.y)), vAuraRim.w);\n" +
                    "        float bands = max(vAuraBands.x, 1.0);\n" +
                    // 0 at the edge, 1 at the centre, times the band count: every whole number is the border between two bands
                    "        float v = max(1.0 - d, 0.0001);\n" +
                    "        float t = pow(v, vAuraColor.w) * bands;\n" +
                    // how much t changes over one pixel (one texel): the width of the antialiased edge. a border texel gets a share
                    // of the next band instead of snapping to one or the other. d changes by 1 / (16 * radius) from texel to texel
                    "        float slope = vAuraColor.w * pow(v, vAuraColor.w - 1.0) * bands;\n" +
                    "        float fw = clamp(slope / (16.0 * vAuraArea.z) * vAuraBands.y, 0.0001, 0.9);\n" +
                    "        float h = fw * 0.5;\n" +
                    // rays: the single row and column of pixels through the centre are lit further out than the rest, a cross of streaks
                    "        if (vAuraCore.w > 0.0) {\n" +
                    "            float onAxis = step(min(abs(p.x), abs(p.y)) * vAuraArea.z, 0.25 / 16.0);\n" +
                    "            float along = max(abs(p.x), abs(p.y));\n" +
                    "            float rayT = bands * pow(max(1.0 - along, 0.0001), vAuraColor.w * 0.45) * vAuraCore.w;\n" +
                    "            t = max(t, onAxis * rayT);\n" +
                    "        }\n" +
                    // grain: every pixel gets its own small push up or down, in bands, so the borders wobble like a painted texture
                    "        if (vAuraBands.z > 0.0) {\n" +
                    "            float n = fract(sin(dot(texel, vec2(12.9898, 78.233))) * 43758.5453);\n" +
                    "            t += (n - 0.5) * vAuraBands.z;\n" +
                    "        }\n" +
                    "        if (d >= 1.0) discard;\n" +
                    // the band index as a float: whole inside a band and sliding to the next one across a border, over fw. the
                    // outer edge (t = 0) has only the inside half of the slide, since nothing is drawn past it
                    "        float nearest = floor(t + 0.5);\n" +
                    "        float level = nearest < 0.5 ? smoothstep(0.0, fw, t)\n" +
                    "                    : (nearest >= bands ? bands : nearest + smoothstep(nearest - h, nearest + h, t));\n" +
                    "        if (level < 0.002) discard;\n" +
                    // colour ramp: rim colour at the outermost band, the base colour half way, the core colour at the innermost
                    "        float ramp = bands > 1.0 ? clamp((level - 1.0) / (bands - 1.0), 0.0, 1.0) : 1.0;\n" +
                    "        vec3 glow = ramp < 0.5 ? mix(vAuraRim.rgb, vAuraColor.rgb, ramp * 2.0)\n" +
                    "                               : mix(vAuraColor.rgb, vAuraCore.rgb, ramp * 2.0 - 1.0);\n" +
                    "        if (vColor.a < 0.9997) glow = liquidTint(glow, vColor.a, vWorldY);\n" +
                    // vColor.b carries the aura's opacity and vAuraArea.w its strength: every band is one flat step of it
                    "        float a = vColor.b * vAuraArea.w * (level / bands);\n" +
                    // light does not turn into fog colour, it fades out in it: how much of the fog is still clear is the difference of fogging white and black
                    "        a *= applyFog(vec3(1.0)).r - applyFog(vec3(0.0)).r;\n" +
                    // the colour is premultiplied. NORMAL covers what is behind by the alpha, ADD (alpha 0) only adds. SCREEN is drawn
                    // with another blend function (src + dst * (1 - src)), which ignores the alpha
                    "        float coverage = vAuraBlend < 0.5 ? a : 0.0;\n" +
                    "        gl_FragColor = vec4(glow * a, coverage);\n" +
                    "        return;\n" +
                    "    }\n" +
                    "    vec4 c = texture2D(tex, gl_TexCoord[0].st);\n" +
                    "    if (c.a < uCutoff) discard;\n" +
                    // same light mix as TerrainShader: sky light (r) scaled by the live day/night ambient, block light (g) fills the rest
                    "    float s = vColor.r * ambient;\n" +
                    "    float l = s + (1.0 - s) * vColor.g;\n" +
                    "    vec3 rgb = c.rgb * l;\n" +
                    "    float alpha = vColor.a;\n" +
                    "    if (alpha < 0.9997) {\n" +
                    "        rgb = liquidTint(rgb, alpha, vWorldY);\n" +
                    "        alpha = 1.0;\n" +
                    "    }\n" +
                    "    gl_FragColor = vec4(applyFog(rgb), c.a * alpha);\n" +
                    "}\n";

    private static int program = 0;
    private static int eyeLocation = -1;
    private static int rightLocation = -1;
    private static int reachLocation = -1;
    private static int ambientLocation = -1;
    private static int cutoffLocation = -1;
    private static int auraPassLocation = -1;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;
    private static LiquidTinting.Uniforms tinting;

    private StaticBillboardShader() {}

    /**
     * rightX / rightZ: the screen-right vector in the XZ plane. reachPerBlock / maxReach: a sprite is dropped past
     * min(maxReach, its size * reachPerBlock) blocks from the eye. ambient: the day/night sky ambient, as for TerrainShader.
     */
    public static void bind(double eyeX, double eyeY, double eyeZ, float rightX, float rightZ,
                            float reachPerBlock, float maxReach, float ambient, float alphaCutoff) {
        if (program == 0) {
            init();
        }
        GL20.glUseProgram(program);
        GL20.glUniform1f(ambientLocation, ambient);
        GL20.glUniform1f(cutoffLocation, alphaCutoff);
        GL20.glUniform1f(auraPassLocation, 0.0F);
        Fog.upload(fogParamsLocation, fogColorLocation, fogEyeLocation);
        tinting.upload();
        GL20.glUniform3f(eyeLocation, (float) eyeX, (float) eyeY, (float) eyeZ);
        GL20.glUniform2f(rightLocation, rightX, rightZ);
        GL20.glUniform2f(reachLocation, reachPerBlock, maxReach);
    }

    /** the alpha below which a pixel is cut out, for the program already bound (0.01 for blended sprites, 0.5 for cut-outs) */
    public static void setCutoff(float alphaCutoff) {
        GL20.glUniform1f(cutoffLocation, alphaCutoff);
    }

    /**
     * for the program already bound: 0 draws the sprites, 1 only the auras blended with alpha (NORMAL, ADD) and 2 only
     * the SCREEN ones (see LevelRenderer.renderBillboardAuras)
     */
    public static void setAuraPass(int auraPass) {
        GL20.glUniform1f(auraPassLocation, auraPass);
    }

    public static void unbind() {
        GL20.glUseProgram(0);
    }

    private static void init() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Static billboard shader link failed: " + GL20.glGetProgramInfoLog(p, 4096));
        }

        GL20.glUseProgram(p);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
        GL20.glUseProgram(0);

        eyeLocation = GL20.glGetUniformLocation(p, "uEye");
        rightLocation = GL20.glGetUniformLocation(p, "uRight");
        reachLocation = GL20.glGetUniformLocation(p, "uReach");
        ambientLocation = GL20.glGetUniformLocation(p, "ambient");
        cutoffLocation = GL20.glGetUniformLocation(p, "uCutoff");
        auraPassLocation = GL20.glGetUniformLocation(p, "uAuraPass");
        fogParamsLocation = GL20.glGetUniformLocation(p, "uFogParams");
        fogColorLocation = GL20.glGetUniformLocation(p, "uFogColor");
        fogEyeLocation = GL20.glGetUniformLocation(p, "uFogEye");
        tinting = new LiquidTinting.Uniforms(p);
        program = p;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Static billboard shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }
}