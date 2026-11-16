package com.nikohalfkino.rubydung.render;

import org.lwjgl.opengl.GL20;

/**
 * Colours the clouds. The cloud texture is only a light level (red = green = blue) and a coverage (alpha), see
 * CloudTextureGenerator. Every pixel is a mix of two colours the day-night cycle gives, by that light level: the lit colour
 * where the cloud catches the sun and the shadow colour where it is thick. Both come from the sky of the moment (and so
 * from the sun phase), which is why the clouds blend into a yellow or red sky instead of sitting on it as a blue sheet.
 * The drift is the texture matrix, and the edge fade and the layer's alpha are the vertex alpha, as before.
 */
final class CloudShader {

    private static final String VERT =
            "#version 120\n" +
                    "varying float vAlpha;\n" +
                    "void main() {\n" +
                    "    gl_Position = ftransform();\n" +
                    "    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;\n" +
                    "    vAlpha = gl_Color.a;\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform vec3 uLit;\n" +
                    "uniform vec3 uShadow;\n" +
                    "uniform float uBias;\n" +
                    "varying float vAlpha;\n" +
                    "void main() {\n" +
                    "    vec4 t = texture2D(tex, gl_TexCoord[0].st);\n" +
                    "    float light = clamp(t.r + uBias, 0.0, 1.0);\n" +
                    "    gl_FragColor = vec4(mix(uShadow, uLit, light), t.a * vAlpha);\n" +
                    "}\n";

    private static int program = 0;
    private static int litLocation = -1;
    private static int shadowLocation = -1;
    private static int biasLocation = -1;

    private CloudShader() {
    }

    static void bind() {
        if (program == 0) {
            init();
        }
        GL20.glUseProgram(program);
    }

    static void unbind() {
        GL20.glUseProgram(0);
    }

    /** bias: added to the texture's light level, to make a layer a little brighter or darker than the other */
    static void setColors(float[] lit, float[] shadow, float bias) {
        GL20.glUniform3f(litLocation, lit[0], lit[1], lit[2]);
        GL20.glUniform3f(shadowLocation, shadow[0], shadow[1], shadow[2]);
        GL20.glUniform1f(biasLocation, bias);
    }

    private static void init() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Cloud shader link failed: " + GL20.glGetProgramInfoLog(p, 4096));
        }
        GL20.glUseProgram(p);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
        GL20.glUseProgram(0);
        litLocation = GL20.glGetUniformLocation(p, "uLit");
        shadowLocation = GL20.glGetUniformLocation(p, "uShadow");
        biasLocation = GL20.glGetUniformLocation(p, "uBias");
        program = p;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Cloud shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }
}
