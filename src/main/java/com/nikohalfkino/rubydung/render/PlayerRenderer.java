package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.player.Player;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * the player as a Doom-style prerendered sprite: one frame per viewing angle, picked from where the camera is relative to the way the body faces
 *
 * sheet: FRAME_WIDTH x FRAME_HEIGHT frames side by side, frame 0 seen from in front, then every 45 degrees toward the player's right (1 front-right, 2 right, 3 back-right, 4 behind, 5 back-left, 6 left, 7 front-left). feet on the frame's bottom edge, sprite centred horizontally on the player
 */
public final class PlayerRenderer {

    public static final String SHEET_RESOURCE = "/assets/rubydung/textures/player/hakumo_idle.png";

    public static final int FRAME_WIDTH = 64;
    public static final int FRAME_HEIGHT = 96;
    private static final int ANGLES = 8;

    // same pixel density as the terrain and billboard tiles
    private static final int PIXELS_PER_BLOCK = 40;

    private int texture = 0;
    private boolean loadFailed = false;
    private int frames = ANGLES;
    private int sheetWidth = FRAME_WIDTH * ANGLES;
    private int sheetHeight = FRAME_HEIGHT;

    // the sprite's own shader: the see-through and silhouette dithers alternate per sprite pixel (a texel of the sheet),
    // not per screen pixel, so the pattern sits on the sprite's pixel grid whatever size it is drawn at
    private static final String VERT =
            "#version 120\n" +
                    "varying vec4 vColor;\n" +
                    "void main() {\n" +
                    "    gl_Position = ftransform();\n" +
                    "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
                    "    vColor = gl_Color;\n" +
                    "}\n";

    // mode 0 = plain, 1 = half see-through checkerboard, 2 = dark checkerboard silhouette
    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform vec2 sheetSize;\n" +
                    "uniform float mode;\n" +
                    "varying vec4 vColor;\n" +
                    "void main() {\n" +
                    "    vec2 st = gl_TexCoord[0].st;\n" +
                    "    vec4 c = texture2D(tex, st);\n" +
                    "    if (c.a < 0.5) discard;\n" +
                    "    vec2 texel = floor(st * sheetSize);\n" +
                    "    float odd = mod(texel.x + texel.y, 2.0);\n" +
                    "    if (mode > 0.5 && odd > 0.5) discard;\n" +
                    "    if (mode > 1.5) {\n" +
                    "        gl_FragColor = vec4(0.0, 0.0, 0.0, 0.7);\n" +
                    "    } else {\n" +
                    "        gl_FragColor = vec4(c.rgb * vColor.rgb, c.a);\n" +
                    "    }\n" +
                    "}\n";

    private int spriteProgram = 0;
    private int sheetSizeLocation = -1;
    private int modeLocation = -1;

    public void render(Level level, Player player, PlayerCamera camera, float partialTicks) {
        if (!camera.isPlayerVisible()) {
            return;
        }

        renderSeenFrom(level, player, partialTicks, camera.getX(), camera.getZ(), camera.getYaw());
    }

    /** another player of a multiplayer game: always drawn, from its own (already smoothed) position */
    public void renderRemote(Level level, double x, double feetY, double z, float bodyYaw, boolean dithered, PlayerCamera camera) {
        draw(level, x, feetY, z, bodyYaw, camera.getX(), camera.getZ(), camera.getYaw(), dithered);
    }

    /**
     * the local player, always drawn, for a viewer that is not the PlayerCamera (screenshots). viewX / viewZ is where the
     * viewer is (it picks the frame), viewYaw the way it looks (the sprite turns to face it)
     */
    public void renderSeenFrom(Level level, Player player, float partialTicks, double viewX, double viewZ, float viewYaw) {
        double x = player.prevX + (player.x - player.prevX) * partialTicks;
        double z = player.prevZ + (player.z - player.prevZ) * partialTicks;
        double feetY = player.prevY + (player.y - player.prevY) * partialTicks - Player.EYE_HEIGHT;

        draw(level, x, feetY, z, player.getBodyYaw(partialTicks), viewX, viewZ, viewYaw, player.noClip);
    }

    /** another player of a multiplayer game, for a viewer that is not the PlayerCamera (see renderSeenFrom) */
    public void renderRemoteSeenFrom(Level level, double x, double feetY, double z, float bodyYaw, boolean dithered,
                                     double viewX, double viewZ, float viewYaw) {
        draw(level, x, feetY, z, bodyYaw, viewX, viewZ, viewYaw, dithered);
    }

    // dithered: a player walking through blocks is drawn through a checkerboard, half see-through
    private void draw(Level level, double x, double feetY, double z, float bodyYaw,
                      double viewX, double viewZ, float viewYaw, boolean dithered) {
        if (!ensureTexture()) {
            return;
        }

        int frame = frameFor(bodyYaw, viewX - x, viewZ - z);

        // Lit like a billboard tile standing in the same spot.
        int cellX = (int) Math.floor(x);
        int cellY = (int) Math.floor(feetY + 0.9D);
        int cellZ = (int) Math.floor(z);
        float base = level.getBrightness(cellX, cellY, cellZ);
        float block = level.getBlockLight(cellX, cellY, cellZ);
        float brightness = base + (1.0F - base) * block;

        float u0 = frame / (float) this.frames;
        float u1 = (frame + 1) / (float) this.frames;

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (!ensureProgram()) {
            return;
        }
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT);
        GL20.glUseProgram(this.spriteProgram);
        GL20.glUniform2f(this.sheetSizeLocation, this.sheetWidth, this.sheetHeight);

        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, this.texture);
        glDisable(GL_FOG);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glDisable(GL_ALPHA_TEST);
        glDepthMask(true);

        float width = FRAME_WIDTH / (float) PIXELS_PER_BLOCK;
        float height = FRAME_HEIGHT / (float) PIXELS_PER_BLOCK;
        double rightX = Billboard.rightX(viewYaw);
        double rightZ = Billboard.rightZ(viewYaw);

        if (dithered) {
            // a player inside the blocks: where the blocks hide the sprite, its silhouette shows through them as a dark dither
            GL20.glUniform1f(this.modeLocation, 2.0F);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            int depthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_GREATER);
            glDepthMask(false);
            Billboard.draw(x, feetY, z, width, height, rightX, rightZ, u0, 0.0F, u1, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F);
            glDepthFunc(depthFunc);
            glDepthMask(true);
            glDisable(GL_BLEND);
        }

        // the sprite itself, half see-through when dithered
        GL20.glUniform1f(this.modeLocation, dithered ? 1.0F : 0.0F);
        Billboard.draw(x, feetY, z, width, height, rightX, rightZ, u0, 0.0F, u1, 1.0F,
                brightness, brightness, brightness, 1.0F);

        glPopAttrib();
        GL20.glUseProgram(program);
    }

    private boolean ensureProgram() {
        if (this.spriteProgram != 0) {
            return true;
        }
        if (this.loadFailed) {
            return false;
        }
        try {
            int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
            int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
                throw new RuntimeException(GL20.glGetProgramInfoLog(p, 4096));
            }
            GL20.glUseProgram(p);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
            GL20.glUseProgram(0);
            this.sheetSizeLocation = GL20.glGetUniformLocation(p, "sheetSize");
            this.modeLocation = GL20.glGetUniformLocation(p, "mode");
            this.spriteProgram = p;
            return true;
        } catch (RuntimeException e) {
            System.err.println("[PlayerRenderer] player sprite shader failed: " + e.getMessage());
            this.loadFailed = true;
            return false;
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException(GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }

    // which frame to show: the angle between the body's facing and the direction to the camera, in 45 degree steps counted toward the player's right
    static int frameFor(float bodyYaw, double toCameraX, double toCameraZ) {
        double length = Math.sqrt(toCameraX * toCameraX + toCameraZ * toCameraZ);
        if (length < 1.0E-4D) {
            return 0;
        }

        double rad = Math.toRadians(bodyYaw);
        double forwardX = Math.sin(rad);
        double forwardZ = -Math.cos(rad);
        double rightX = Math.cos(rad);
        double rightZ = Math.sin(rad);

        double angle = Math.toDegrees(Math.atan2(
                toCameraX * rightX + toCameraZ * rightZ,
                toCameraX * forwardX + toCameraZ * forwardZ));

        return Math.floorMod((int) Math.round(angle / (360.0D / ANGLES)), ANGLES);
    }

    private boolean ensureTexture() {
        if (this.texture != 0) {
            return true;
        }
        if (this.loadFailed) {
            return false;
        }

        try (InputStream in = PlayerRenderer.class.getResourceAsStream(SHEET_RESOURCE)) {
            if (in == null) {
                throw new IOException("missing resource " + SHEET_RESOURCE);
            }
            BufferedImage image = ImageIO.read(in);
            int width = image.getWidth();
            int height = image.getHeight();
            this.frames = Math.max(1, width / FRAME_WIDTH);
            this.sheetWidth = width;
            this.sheetHeight = height;

            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
            for (int argb : pixels) {
                buffer.put((byte) (argb >> 16 & 0xFF));
                buffer.put((byte) (argb >> 8 & 0xFF));
                buffer.put((byte) (argb & 0xFF));
                buffer.put((byte) (argb >>> 24));
            }
            buffer.flip();

            int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
            this.texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, this.texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            glBindTexture(GL_TEXTURE_2D, previous);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[PlayerRenderer] could not load the player sprite sheet: " + e.getMessage());
            this.loadFailed = true;
            return false;
        }
    }
}