package com.nikohalfkino.rubydung.render;

import org.lwjgl.opengl.GL20;

public final class BillboardShader {

    private static final String VERT =
            "#version 120\n" +
                    "varying vec4 vColor;\n" +
                    "varying float vWorldY;\n" +
                    Fog.GLSL_VERTEX_DECLARATIONS +
                    "void main() {\n" +
                    "    gl_Position = ftransform();\n" +
                    "    vWorldY = gl_Vertex.y;\n" +
                    "    vFogWorldXZ = gl_Vertex.xz;\n" +
                    "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
                    "    vColor = gl_Color;\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "varying vec4 vColor;\n" +
                    "varying float vWorldY;\n" +
                    LiquidTinting.GLSL_DECLARATIONS +
                    Fog.GLSL_FRAGMENT_DECLARATIONS +
                    Fog.GLSL_DITHER_DECLARATIONS +
                    "void main() {\n" +
                    "    applyDither();\n" +
                    "    vec4 c = texture2D(tex, gl_TexCoord[0].st);\n" +
                    "    if (c.a < 0.01) discard;\n" +
                    "    vec3 rgb = c.rgb * vColor.rgb;\n" +
                    "    float alpha = vColor.a;\n" +
                    // an alpha below 1 is a packed liquid tint (see UnderwaterTint, BillboardTile): the sprite takes the colour and
                    // darkness of the water over it, measured at each fragment's own height so the part above the surface stays clear
                    "    if (alpha < 0.9997) {\n" +
                    "        rgb = liquidTint(rgb, alpha, vWorldY);\n" +
                    "        alpha = 1.0;\n" +
                    "    }\n" +
                    "    gl_FragColor = vec4(applyFog(rgb), c.a * alpha);\n" +
                    "}\n";

    private static int program = 0;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;
    private static LiquidTinting.Uniforms tinting;

    private BillboardShader() {}

    public static void bind() {
        if (program == 0) {
            init();
        }
        GL20.glUseProgram(program);
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
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);

        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "tex"), 0);
        GL20.glUseProgram(0);

        fogParamsLocation = GL20.glGetUniformLocation(program, "uFogParams");
        fogColorLocation = GL20.glGetUniformLocation(program, "uFogColor");
        fogEyeLocation = GL20.glGetUniformLocation(program, "uFogEye");
        tinting = new LiquidTinting.Uniforms(program);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Billboard shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }
}