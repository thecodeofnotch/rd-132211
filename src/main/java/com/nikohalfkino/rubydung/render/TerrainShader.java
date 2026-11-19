package com.nikohalfkino.rubydung.render;

import org.lwjgl.opengl.GL20;

public final class TerrainShader {

    // patches per block along each axis of a face: 1.0 = one light per block face, 2.0 = quarters (2x2), 4.0 = 4x4
    private static final float LIGHT_GRID = 2.0F;

    // number of brightness steps between darkness and full light (4 = 0, .25, .5, .75, 1), lower = more obvious steps
    private static final float LIGHT_STEPS = 15.0F;

    // power curve on the sky/block light before it is stepped: 1.0 = unchanged, higher = darker shadows
    private static final float LIGHT_CONTRAST = 1.5F;

    // texture pixels along a block face, the light is decided once per texel so patch edges always follow the texture's pixels
    private static final float TEXELS_PER_BLOCK = 16.0F;

    // antialiasing of the edge between two light levels, in light steps: how wide the fade between them is, centred on the
    // edge. 0 = plain bands, 0.5 = patches within a quarter step of an edge take the level in between, 1 = no flat bands at all.
    // it works from each patch's own light only (never from the patches around it), so a patch is always one flat square
    private static final float LIGHT_SMOOTH = 0.75F;

    private static final String VERT =
            "#version 120\n" +
                    "varying vec4 vColor;\n" +
                    "varying vec3 vWorldPos;\n" +
                    Fog.GLSL_VERTEX_DECLARATIONS +
                    "void main() {\n" +
                    "    gl_Position = ftransform();\n" +
                    // chunk vertices are in world coordinates (the fog relies on the same thing)
                    "    vWorldPos = gl_Vertex.xyz;\n" +
                    "    vFogWorldXZ = gl_Vertex.xz;\n" +
                    "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
                    "    vColor = gl_Color;\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform float ambient;\n" +
                    "uniform vec3 uSunTint;\n" +
                    "varying vec4 vColor;\n" +
                    "varying vec3 vWorldPos;\n" +
                    LiquidTinting.GLSL_DECLARATIONS +
                    Fog.GLSL_FRAGMENT_DECLARATIONS +
                    Fog.GLSL_DITHER_DECLARATIONS +

                    "const float LIGHT_GRID = " + LIGHT_GRID + ";\n" +
                    "const float LIGHT_STEPS = " + LIGHT_STEPS + ";\n" +
                    "const float LIGHT_CONTRAST = " + LIGHT_CONTRAST + ";\n" +
                    "const float TEXELS_PER_BLOCK = " + TEXELS_PER_BLOCK + ";\n" +
                    "const float LIGHT_SMOOTH = " + LIGHT_SMOOTH + ";\n" +

                    // One patch: its light is read at the world position c (the packed colour is linear across a face, M moves it
                    // from the pixel at p to c), curved, then rounded to a light step. The rounding is soft: a patch whose light is
                    // within LIGHT_SMOOTH / 2 steps of the edge between two levels takes a level in between, how far across the edge
                    // it is, which antialiases a band's edge and corners like a pixel partly covered by the band. x = the brightness,
                    // y = the face shade.
                    "vec2 patchLight(vec3 c, vec3 p, mat3 M) {\n" +
                    "    vec3 col = clamp(vColor.rgb + M * (c - p), 0.0, 1.0);\n" +
                    // the contrast curves the sky light and the block light themselves, before the ambient scales the sky: the
                    // terrain's own brightness at night is the ambient's business, a curve must not crush it to black
                    "    float s = pow(clamp(col.r, 0.0, 1.0), LIGHT_CONTRAST) * ambient;\n" +
                    "    float b = pow(clamp(col.g, 0.0, 1.0), LIGHT_CONTRAST);\n" +
                    "    float l = clamp(s + (1.0 - s) * b, 0.0, 1.0);\n" +
                    "    float w = max(LIGHT_SMOOTH, 1.0e-4);\n" +
                    "    float q = l * LIGHT_STEPS + 0.5 - 0.5 * w;\n" +
                    "    float level = floor(q) + smoothstep(1.0 - w, 1.0, fract(q));\n" +
                    "    return vec2(level / LIGHT_STEPS, col.b);\n" +
                    "}\n" +

                    // Every patch is one flat square, lit by the light at its centre. Reading only the centre matters: the quad
                    // is two triangles with different colour planes, but a patch's centre is always on the shared diagonal or wholly
                    // inside one triangle, so every pixel of a patch agrees on it. (Reading the patches around it from this pixel's
                    // plane does not agree between the two triangles, and showed up as harsh triangles along dark edges.)
                    // patchY (out) = the height of the centre of the patch: the depth tint under the sea is read there, so it changes in the same
                    // half-block squares the light does, not once per cell
                    "float faceLight(out float patchY) {\n" +
                    "    vec3 p = vWorldPos;\n" +
                    // all derivatives first, so they run in uniform control flow
                    "    vec3 dpx = dFdx(p);\n" +
                    "    vec3 dpy = dFdy(p);\n" +
                    "    vec3 dcx = dFdx(vColor.rgb);\n" +
                    "    vec3 dcy = dFdy(vColor.rgb);\n" +

                    // the axis the face is perpendicular to is left alone, only the two axes along the face are snapped
                    "    vec3 n = abs(cross(dpx, dpy));\n" +
                    "    vec3 axis = (n.x >= n.y && n.x >= n.z) ? vec3(1.0, 0.0, 0.0)\n" +
                    "              : (n.y >= n.z) ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);\n" +

                    // the pixel is moved to the centre of its texel, then to the centre of the patch that texel is in
                    "    vec3 pT = mix((floor(p * TEXELS_PER_BLOCK) + 0.5) / TEXELS_PER_BLOCK, p, axis);\n" +
                    "    vec3 c0 = mix((floor(pT * LIGHT_GRID) + 0.5) / LIGHT_GRID, pT, axis);\n" +

                    // how the packed colour changes per world unit on this face (zero if the face is edge-on)
                    "    float A = dot(dpx, dpx);\n" +
                    "    float B = dot(dpx, dpy);\n" +
                    "    float C = dot(dpy, dpy);\n" +
                    "    float det = A * C - B * B;\n" +
                    "    mat3 M = mat3(0.0);\n" +
                    "    if (det > 1.0e-6 * A * C && det > 0.0) {\n" +
                    "        vec3 av = (dpx * C - dpy * B) / det;\n" +
                    "        vec3 bv = (dpy * A - dpx * B) / det;\n" +
                    "        M = mat3(av.x * dcx + bv.x * dcy, av.y * dcx + bv.y * dcy, av.z * dcx + bv.z * dcy);\n" +
                    "    }\n" +

                    "    patchY = c0.y;\n" +
                    "    vec2 v = patchLight(c0, p, M);\n" +
                    "    return v.x * v.y;\n" +
                    "}\n" +

                    "void main() {\n" +
                    // before applyDither, which can discard (derivatives need every pixel of the 2x2 block)
                    "    float patchY;\n" +
                    "    float light = faceLight(patchY);\n" +
                    "    applyDither();\n" +
                    "    vec4 c = texture2D(tex, gl_TexCoord[0].st);\n" +
                    // a fully see-through texel draws nothing and writes no depth: the liquids are drawn after the terrain, and
                    // one behind a window or a grate would otherwise be hidden by the empty pixels of the pane
                    "    if (c.a < 0.02) {\n" +
                    "        discard;\n" +
                    "    }\n" +
                    "    vec3 rgb = c.rgb * light * uSunTint;\n" +

                    // terrain faces are tinted by how deep their patch is (the cell they were packed for says which liquid and whether it is wet).
                    // alpha is not snapped
                    "    if (vColor.a < 0.9997) {\n" +
                    "        rgb = liquidTint(rgb, vColor.a, patchY);\n" +
                    "    }\n" +

                    "    gl_FragColor = vec4(applyFog(rgb), c.a);\n" +
                    "}\n";

    private static int program = 0;
    private static int ambientLocation = -1;
    private static int sunTintLocation = -1;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;
    private static LiquidTinting.Uniforms tinting;

    /** the ocean, set by LevelRenderer every frame before bind (a very low height = no ocean). shared with the billboard shader */
    public static void setOcean(float height, int liquidIndex) {
        LiquidTinting.setOcean(height, liquidIndex);
    }

    private TerrainShader() {
    }

    public static void bind(float ambient) {
        if (program == 0) {
            init();
        }

        GL20.glUseProgram(program);
        GL20.glUniform1f(ambientLocation, ambient);
        WorldTint.upload(sunTintLocation);
        Fog.upload(fogParamsLocation, fogColorLocation, fogEyeLocation);
        tinting.upload();
    }

    public static void unbind() {
        GL20.glUseProgram(0);
    }

    /** compiles and links the program now (it is otherwise built on its first use, in the middle of a frame): called from ShaderWarmup while the world loads */
    public static void warmUp() {
        if (program == 0) {
            init();
        }
    }

    private static void init() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);

        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException(
                    "Terrain shader link failed: " +
                            GL20.glGetProgramInfoLog(p, 4096)
            );
        }

        GL20.glUseProgram(p);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
        GL20.glUseProgram(0);

        ambientLocation = GL20.glGetUniformLocation(p, "ambient");
        sunTintLocation = GL20.glGetUniformLocation(p, "uSunTint");
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
            throw new RuntimeException(
                    "Terrain shader compile failed: " +
                            GL20.glGetShaderInfoLog(shader, 4096)
            );
        }

        return shader;
    }
}