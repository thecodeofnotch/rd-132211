package com.nikohalfkino.rubydung.render;

import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;

/**
 * Colours the sky dome. The dome's geometry never changes and its colour only depends on the latitude ring a vertex is on,
 * so the mesh is a static display list (SkyboxRenderer) whose vertices carry their ring index in texture coordinate 0, and the
 * colour of every ring is uploaded as one uniform array per frame. That replaces re-tessellating ~9300 vertices on the CPU
 * every frame. The varying is interpolated between vertices exactly like the fixed-function colour it replaces.
 */
public final class SkyDomeShader {

    private static final String VERT =
            "#version 120\n" +
                    "uniform vec3 uRing[" + SkyboxRenderer.RING_COUNT + "];\n" +
                    "varying vec3 vSky;\n" +
                    "void main() {\n" +
                    "    gl_Position = ftransform();\n" +
                    "    vSky = uRing[int(gl_MultiTexCoord0.x + 0.5)];\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "varying vec3 vSky;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = vec4(vSky, 1.0);\n" +
                    "}\n";

    private static int program = 0;
    private static int ringLocation = -1;

    private SkyDomeShader() {}

    /** ringColors: RING_COUNT rgb triples, flipped and ready to read */
    public static void bind(FloatBuffer ringColors) {
        if (program == 0) {
            init();
        }
        GL20.glUseProgram(program);
        GL20.glUniform3(ringLocation, ringColors);
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
            throw new RuntimeException("Sky dome shader link failed: " + GL20.glGetProgramInfoLog(p, 4096));
        }
        ringLocation = GL20.glGetUniformLocation(p, "uRing");
        program = p;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Sky dome shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }
}