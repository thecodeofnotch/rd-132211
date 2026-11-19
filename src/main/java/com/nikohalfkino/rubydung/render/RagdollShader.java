package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.entity.ragdoll.RagdollSkin;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * skins the ragdolls on the graphics card. a model is uploaded once (Mesh), and every frame only the bones go over: for each of the BONES bones a rotation and an origin, as uniforms. the vertex shader bends the mesh the way RagdollSkin.pose does on the CPU (origin + rotation * (rest - restOrigin) * scale, blended between the two bones a vertex follows), puts it through the camera's matrices and snaps it to the same coarse grid as ModelRenderer. the fragment shader shades every triangle flat from the slope of the posed surface, with the same light as ModelRenderer.drawSkinned
 *
 * the mesh has one vertex per triangle corner (so a triangle has a flat normal of its own), laid out as 11 floats:
 *   0..2  rest position relative to the rest origin of bone A      (gl_Vertex.xyz)
 *   3     share of bone B                                          (gl_Vertex.w)
 *   4..5  texture coordinate                                       (texture unit 0)
 *   6..8  rest position relative to the rest origin of bone B      (texture unit 1)
 *   9..10 index of bone A, index of bone B                         (texture unit 2)
 *
 * if the shader does not compile or link, available() is false and RagdollRenderer draws the CPU way (ModelRenderer.drawSkinned) as it did before
 *
 * use: bind(), then for each ragdoll setBones() / setLight() and draw(), then unbind(). the camera's matrices must be active (the shader reads gl_ModelViewProjectionMatrix)
 */
public final class RagdollShader {

    // the snap is PsxSnap's, the same as ModelRenderer's
    private static final String VERT =
            "#version 120\n" +
                    "const int BONES = " + RagdollSkin.BONES + ";\n" +
                    "uniform mat3 uRot[BONES];\n" +
                    "uniform vec3 uOrigin[BONES];\n" +
                    "uniform vec3 uEntity;\n" +
                    "uniform float uScale;\n" +
                    "uniform vec2 uStep;\n" +
                    PsxSnap.GLSL +
                    Fog.GLSL_VERTEX_DECLARATIONS +
                    "varying vec2 vUv;\n" +
                    // the position relative to the ragdoll: small numbers, so the slopes the fragment shader takes are exact
                    "varying vec3 vLocal;\n" +
                    "void main() {\n" +
                    "    int a = int(gl_MultiTexCoord2.x + 0.5);\n" +
                    "    int b = int(gl_MultiTexCoord2.y + 0.5);\n" +
                    "    vec3 pa = uOrigin[a] + uRot[a] * gl_Vertex.xyz * uScale;\n" +
                    "    vec3 pb = uOrigin[b] + uRot[b] * gl_MultiTexCoord1.xyz * uScale;\n" +
                    "    vec3 local = mix(pa, pb, gl_Vertex.w);\n" +
                    "    vLocal = local;\n" +
                    "    vFogWorldXZ = (local + uEntity).xz;\n" +
                    "    vUv = gl_MultiTexCoord0.st;\n" +
                    "    vec4 c = gl_ModelViewProjectionMatrix * vec4(local + uEntity, 1.0);\n" +
                    "    c = psxSnap(c, uStep, 1.0);\n" +
                    "    gl_Position = c;\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform float uBrightness;\n" +
                    "varying vec2 vUv;\n" +
                    "varying vec3 vLocal;\n" +
                    Fog.GLSL_FRAGMENT_DECLARATIONS +
                    Fog.GLSL_DITHER_DECLARATIONS +
                    "void main() {\n" +
                    // the flat normal: across the triangle's surface, from how the position changes over the screen.
                    // that one points at the viewer, the outward one is the same on a front face and turned round on a back face
                    "    vec3 n = cross(dFdx(vLocal), dFdy(vLocal));\n" +
                    "    float len = length(n);\n" +
                    "    if (len > 1.0e-12) {\n" +
                    "        n /= len;\n" +
                    "        if (!gl_FrontFacing) n = -n;\n" +
                    "    } else {\n" +
                    "        n = vec3(0.0, 1.0, 0.0);\n" +
                    "    }\n" +
                    "    float shade = clamp(0.8 + 0.2 * n.y - 0.1 * abs(n.x), 0.45, 1.0) * uBrightness;\n" +
                    "    vec4 t = texture2D(tex, vUv);\n" +
                    // after the derivatives above: a discarded pixel must not take part in them
                    "    applyDither();\n" +
                    "    gl_FragColor = vec4(applyFog(t.rgb * shade), t.a);\n" +
                    "}\n";

    private static final int FLOATS = 11;
    private static final int STRIDE = FLOATS * 4;

    // 0 = not tried, 1 = ready, -1 = failed (the CPU path is used)
    private static int state = 0;
    private static int program = 0;
    private static int rotLocation = -1;
    private static int originLocation = -1;
    private static int entityLocation = -1;
    private static int scaleLocation = -1;
    private static int stepLocation = -1;
    private static int brightnessLocation = -1;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;

    private static final FloatBuffer ROT = BufferUtils.createFloatBuffer(RagdollSkin.BONES * 9);
    private static final FloatBuffer ORIGIN = BufferUtils.createFloatBuffer(RagdollSkin.BONES * 3);

    private RagdollShader() {
    }

    /** a model's vertices on the graphics card */
    public static final class Mesh {
        private final int buffer;
        private final int vertexCount;
        private final float scale;

        private Mesh(int buffer, int vertexCount, float scale) {
            this.buffer = buffer;
            this.vertexCount = vertexCount;
            this.scale = scale;
        }
    }

    /** true when the shader is built and works (builds it the first time: call it once before the first ragdoll frame to move the compile out of a frame, see warmUp) */
    public static boolean available() {
        if (state == 0) {
            init();
        }
        return state > 0;
    }

    /** compiles the shader now, so the first ragdoll on screen does not cost a frame of 30 ms. safe to call any time on the render thread */
    public static void warmUp() {
        available();
    }

    /** uploads a model: one vertex per triangle corner, see the class comment */
    public static Mesh upload(ObjModel model, RagdollSkin skin) {
        int[] tri = model.triangles;
        int[] boneA = skin.primaryBones();
        int[] boneB = skin.secondaryBones();
        float[] share = skin.shares();
        float[] pos = model.positions;
        float[] uv = model.uvs;

        FloatBuffer data = BufferUtils.createFloatBuffer(tri.length * FLOATS);
        for (int t = 0; t < tri.length; t++) {
            int i = tri[t];
            double[] ra = skin.restOrigin(boneA[i]);
            double[] rb = skin.restOrigin(boneB[i]);
            data.put((float) (pos[i * 3] - ra[0])).put((float) (pos[i * 3 + 1] - ra[1])).put((float) (pos[i * 3 + 2] - ra[2]));
            data.put(share[i]);
            data.put(uv[i * 2]).put(uv[i * 2 + 1]);
            data.put((float) (pos[i * 3] - rb[0])).put((float) (pos[i * 3 + 1] - rb[1])).put((float) (pos[i * 3 + 2] - rb[2]));
            data.put(boneA[i]).put(boneB[i]);
        }
        data.flip();

        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return new Mesh(buffer, tri.length, (float) skin.scale());
    }

    /**
     * starts drawing ragdolls: the program, the snap grid of the current viewport (stepX / stepY come from ModelRenderer.snapStep) and the texture unit. the GL state (depth, blending, culling) is the caller's
     */
    public static void bind(float stepX, float stepY) {
        GL20.glUseProgram(program);
        GL20.glUniform2f(stepLocation, stepX, stepY);
        Fog.upload(fogParamsLocation, fogColorLocation, fogEyeLocation);
    }

    /** the bones of the ragdoll about to be drawn: rotation 9 floats and origin 3 floats per bone (RagdollSkin.bones), the ragdoll's position and the light of the place */
    public static void setPose(float[] rotation, float[] origin, double x, double y, double z, float scale, float brightness) {
        ROT.clear();
        ROT.put(rotation, 0, RagdollSkin.BONES * 9).flip();
        ORIGIN.clear();
        ORIGIN.put(origin, 0, RagdollSkin.BONES * 3).flip();
        // the rotations are row-major, so they go over transposed
        GL20.glUniformMatrix3(rotLocation, true, ROT);
        GL20.glUniform3(originLocation, ORIGIN);
        GL20.glUniform3f(entityLocation, (float) x, (float) y, (float) z);
        GL20.glUniform1f(scaleLocation, scale);
        GL20.glUniform1f(brightnessLocation, brightness);
    }

    /** draws a model with the pose last given to setPose. the texture is the caller's, bound on unit 0 */
    public static void draw(Mesh mesh) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, mesh.buffer);

        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(4, GL_FLOAT, STRIDE, 0L);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(2, GL_FLOAT, STRIDE, 16L);

        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(3, GL_FLOAT, STRIDE, 24L);

        GL13.glClientActiveTexture(GL13.GL_TEXTURE2);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(2, GL_FLOAT, STRIDE, 36L);

        glDrawArrays(GL_TRIANGLES, 0, mesh.vertexCount);

        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /** the scale of the mesh's model in blocks per unit, as uploaded */
    public static float scaleOf(Mesh mesh) {
        return mesh.scale;
    }

    public static void unbind() {
        GL20.glUseProgram(0);
    }

    // ---------------------------------------------------------------- building

    private static void init() {
        try {
            int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
            int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
                throw new RuntimeException("link failed: " + GL20.glGetProgramInfoLog(p, 4096));
            }

            GL20.glUseProgram(p);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
            GL20.glUseProgram(0);

            rotLocation = GL20.glGetUniformLocation(p, "uRot[0]");
            originLocation = GL20.glGetUniformLocation(p, "uOrigin[0]");
            entityLocation = GL20.glGetUniformLocation(p, "uEntity");
            scaleLocation = GL20.glGetUniformLocation(p, "uScale");
            stepLocation = GL20.glGetUniformLocation(p, "uStep");
            brightnessLocation = GL20.glGetUniformLocation(p, "uBrightness");
            fogParamsLocation = GL20.glGetUniformLocation(p, "uFogParams");
            fogColorLocation = GL20.glGetUniformLocation(p, "uFogColor");
            fogEyeLocation = GL20.glGetUniformLocation(p, "uFogEye");
            if (rotLocation < 0 || originLocation < 0 || stepLocation < 0) {
                throw new RuntimeException("uniforms missing");
            }
            program = p;
            state = 1;
        } catch (RuntimeException e) {
            System.err.println("[RagdollShader] not used, ragdolls are skinned on the CPU: " + e.getMessage());
            state = -1;
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }
}