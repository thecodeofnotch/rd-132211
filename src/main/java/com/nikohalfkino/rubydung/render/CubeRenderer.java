package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.WorldEntity;
import com.nikohalfkino.rubydung.entity.cube.Dice;
import com.nikohalfkino.rubydung.entity.cube.PhysCube;
import com.nikohalfkino.rubydung.entity.piece.ChessPiece;
import com.nikohalfkino.rubydung.level.CameraRay;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.UnderwaterTint;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.entity.phys.AABB;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * draws the weighted cubes: a textured box turned by the cube's orientation, lit like a billboard standing in the same spot (the level's light at the cube's cell), shaded per face so the turning shows
 *
 * the chess pieces are drawn here too, but as the billboards they were: renderPieces uses StaticBillboardShader like the sprites of the chunks (the same light and day/night ambient, growth with distance, fog, water tint, reach and cut-out rules), one quad per piece with its own texture, which is not square (the atlas only holds square ones). the position is blended between the last two ticks, which is what makes a piece glide. the highlight of the targeted one is the blink a targeted billboard gets (LevelRenderer.renderBillboardBlink)
 *
 * fixed-function drawing with the matrices the camera set up, so it is called between the terrain and the liquids
 */
public final class CubeRenderer {

    public static final String TEXTURE = "/assets/rubydung/textures/block/weighted_cube.png";

    // corners of each face as (x, y, z) in -1..1, in the order top-left, bottom-left, bottom-right, top-right seen from outside (the top and bottom faces seen from above / below with north up)
    private static final float[][] CORNERS = {
            {-1, 1, -1, -1, 1, 1, 1, 1, 1, 1, 1, -1},      // top (+y)
            {-1, -1, 1, -1, -1, -1, 1, -1, -1, 1, -1, 1},  // bottom (-y)
            {1, 1, -1, 1, -1, -1, -1, -1, -1, -1, 1, -1},  // north (-z)
            {-1, 1, 1, -1, -1, 1, 1, -1, 1, 1, 1, 1},      // south (+z)
            {-1, 1, -1, -1, -1, -1, -1, -1, 1, -1, 1, 1},  // west (-x)
            {1, 1, 1, 1, -1, 1, 1, -1, -1, 1, 1, -1}       // east (+x)
    };
    private static final float[] UV = {0, 0, 0, 1, 1, 1, 1, 0};

    // outward normal of each face in the cube's own space
    private static final float[][] NORMALS = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    // the dice: face index (as in CORNERS) -> the number on it. opposite faces add up to 7, Dice.upFaceValue uses the same
    private static final int[] DICE_FACE_VALUE = {1, 6, 2, 5, 3, 4};
    // dice1.png .. dice6.png for the white one, dice1_red.png ... for the others (Dice.Color.textureSuffix). the red and blue ones are semitransparent: they are drawn after everything else, blended, back faces first (see render)
    private static final String DICE_TEXTURE = "/assets/rubydung/textures/block/dice/dice%d%s.png";
    private final int[][] diceTextures = new int[Dice.Color.values().length][6];
    private final boolean[] diceTexturesFailed = new boolean[Dice.Color.values().length];
    private final java.util.List<PhysCube> seeThrough = new java.util.ArrayList<>();

    // the number over a dice that has rolled is a tag like a player's name or a door's destination (see NameTags): projected to the screen while the 3D matrices are active (collectDiceTags), drawn later with the HUD (renderDiceTags)
    private final NameTags diceTags = new NameTags();

    private final float[] matrix = new float[16];
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    private int texture = 0;
    private boolean textureFailed = false;

    // the same numbers as LevelRenderer's sprites
    private static final float BILLBOARD_REACH_PER_BLOCK = 400.0F;
    private static final float BILLBOARD_MAX_REACH = 4096.0F;
    private static final float CUTOUT_ALPHA = 0.5F;
    private static final float BLENDED_ALPHA = 0.01F;

    // the textures of the pieces: index = type ordinal * 2 + (white ? 1 : 0). 0 = not loaded, -1 = failed. translucent: whether the texture has partly transparent pixels (BillboardTile.isTranslucent), 0 = not known, 1 = no, 2 = yes
    private final int[] pieceTextures = new int[ChessPieceTile.Type.values().length * 2];
    private final int[] pieceTranslucent = new int[ChessPieceTile.Type.values().length * 2];
    private final BillboardMesh pieceMesh = new BillboardMesh();
    private final java.util.List<ChessPiece> cutouts = new java.util.ArrayList<>();
    private final java.util.List<ChessPiece> blended = new java.util.ArrayList<>();

    /** draws every cube but the see-through dice (the red and the blue one), which renderSeeThrough draws later. partialTicks blends the last two ticks */
    public void render(Level level, CubeManager manager, float partialTicks) {
        if (manager.count() == 0) {
            return;
        }

        boolean textured = ensureTexture();

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT | GL_POLYGON_BIT);

        if (textured) {
            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, this.texture);
        } else {
            glDisable(GL_TEXTURE_2D);
        }
        Fog.applyFixedFunction();   // the cubes melt into the fog like the terrain does
        glDisable(GL_BLEND);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_CULL_FACE);
        glDepthMask(true);

        for (WorldEntity entity : manager.all()) {
            // the boxes: a ragdoll is no PhysCube, it is a model and RagdollRenderer draws it
            if (entity instanceof PhysCube && !isSeeThrough((PhysCube) entity)) {
                draw(level, (PhysCube) entity, partialTicks, textured, false);
            }
        }

        glPopAttrib();
        GL20.glUseProgram(program);
    }

    /**
     * the see-through dice (the red and the blue one). call after everything opaque has been drawn (the terrain, the cubes, the players, the sprites, the pieces) and before the liquids: they are blended over what is already there, so it has to be there, and they write depth, so what is drawn after them (the water) is hidden by a dice that is in front of it and blends over one that is under it
     *
     * far to near as seen from the eye, so they show through each other. one dice is drawn in two passes, the faces turned away from the eye first and then the ones turned toward it, so its far side shows through its near side the right way round. the pixels that are all but transparent are not drawn, so they do not hide anything behind them
     */
    public void renderSeeThrough(Level level, CubeManager manager, float partialTicks, double eyeX, double eyeY, double eyeZ) {
        this.seeThrough.clear();
        for (WorldEntity entity : manager.all()) {
            if (entity instanceof PhysCube && isSeeThrough((PhysCube) entity)) {
                this.seeThrough.add((PhysCube) entity);
            }
        }
        if (this.seeThrough.isEmpty()) {
            return;
        }
        this.seeThrough.sort((a, b) -> Double.compare(distanceSq(b, eyeX, eyeY, eyeZ), distanceSq(a, eyeX, eyeY, eyeZ)));

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT | GL_POLYGON_BIT);
        try {
            glEnable(GL_TEXTURE_2D);
            Fog.applyFixedFunction();
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_GREATER, 0.02F);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(true);
            glEnable(GL_CULL_FACE);
            for (PhysCube cube : this.seeThrough) {
                draw(level, cube, partialTicks, false, true);
            }
        } finally {
            glPopAttrib();
            GL20.glUseProgram(program);
        }
    }

    private static int pieceIndex(ChessPiece piece) {
        return piece.pieceTypeOrdinal() * 2 + (piece.pieceWhite() ? 1 : 0);
    }

    private int pieceTexture(ChessPiece piece) {
        int index = pieceIndex(piece);
        if (this.pieceTextures[index] == 0) {
            ChessPieceTile.Type type = ChessPieceTile.Type.values()[piece.pieceTypeOrdinal()];
            String path = type.texturePath(piece.pieceWhite());
            try {
                this.pieceTextures[index] = Textures.loadTexture(path, GL_NEAREST);
                this.pieceTranslucent[index] = Textures.hasPartialAlpha(path) ? 2 : 1;
            } catch (RuntimeException e) {
                System.err.println("[CubeRenderer] chess texture " + path + " failed: " + e.getMessage());
                this.pieceTextures[index] = -1;
            }
        }
        return this.pieceTextures[index];
    }

    /**
     * the chess pieces, as sprites. call where the billboards are drawn (after the player, before the liquids): the cut-outs first and writing depth, the ones with soft edges afterwards far to near, exactly as LevelRenderer.renderBillboards does
     */
    public void renderPieces(Level level, CubeManager manager, float partialTicks, float cameraYaw,
                             double eyeX, double eyeY, double eyeZ) {
        if (!manager.hasPieces()) {
            return;
        }

        Frustum frustum = Frustum.getFrustum();
        this.cutouts.clear();
        this.blended.clear();
        for (WorldEntity entity : manager.all()) {
            if (!(entity instanceof ChessPiece) || pieceTexture((ChessPiece) entity) <= 0) {
                continue;
            }
            ChessPiece piece = (ChessPiece) entity;
            double x = piece.prevX + (piece.x - piece.prevX) * partialTicks;
            double y = piece.prevY + (piece.y - piece.prevY) * partialTicks;
            double z = piece.prevZ + (piece.z - piece.prevZ) * partialTicks;
            double half = piece.spriteW * 0.5D + 1.0D;
            if (!frustum.cubeInFrustum(new AABB(x - half, y - 1.0D, z - half, x + half, y + piece.spriteH + 1.0D, z + half))) {
                continue;
            }
            (this.pieceTranslucent[pieceIndex(piece)] == 2 ? this.blended : this.cutouts).add(piece);
        }
        if (this.cutouts.isEmpty() && this.blended.isEmpty()) {
            return;
        }

        // far to near
        this.blended.sort((a, b) -> Double.compare(distanceSq(b, eyeX, eyeY, eyeZ), distanceSq(a, eyeX, eyeY, eyeZ)));

        boolean cull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_CULL_FACE);

        float rotRad = (float) Math.toRadians(-cameraYaw);
        float rightX = (float) Math.cos(rotRad);
        float rightZ = (float) -Math.sin(rotRad);
        StaticBillboardShader.bind(eyeX, eyeY, eyeZ, rightX, rightZ, BILLBOARD_REACH_PER_BLOCK, BILLBOARD_MAX_REACH,
                level.getSkyAmbient(), CUTOUT_ALPHA);
        try {
            glEnable(GL_TEXTURE_2D);
            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_GREATER, 0.01F);
            glDepthMask(true);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            for (ChessPiece piece : this.cutouts) {
                drawPiece(level, piece, partialTicks);
            }
            if (!this.blended.isEmpty()) {
                StaticBillboardShader.setCutoff(BLENDED_ALPHA);
                for (ChessPiece piece : this.blended) {
                    drawPiece(level, piece, partialTicks);
                }
            }

            glDepthMask(true);
            glDisable(GL_BLEND);
            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_GREATER, 0.1F);
        } finally {
            StaticBillboardShader.unbind();
            glDisable(GL_TEXTURE_2D);
            if (cull) {
                glEnable(GL_CULL_FACE);
            }
        }
    }

    private static double distanceSq(ChessPiece piece, double eyeX, double eyeY, double eyeZ) {
        double dx = piece.x - eyeX;
        double dy = piece.y - eyeY;
        double dz = piece.z - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    // one sprite: the light is the level's at the cell it is over, packed the way a chunk packs it (the shader mixes it with the live ambient)
    private void drawPiece(Level level, ChessPiece piece, float partialTicks) {
        double x = piece.prevX + (piece.x - piece.prevX) * partialTicks;
        double y = piece.prevY + (piece.y - piece.prevY) * partialTicks;
        double z = piece.prevZ + (piece.z - piece.prevZ) * partialTicks;
        int cellX = (int) Math.floor(x);
        int cellY = piece.pieceCellY();
        int cellZ = (int) Math.floor(z);

        this.pieceMesh.clear();
        // u0 on the right, the mapping a sprite has in the chunk meshes (BillboardTile.emitStatic, not mirrored)
        this.pieceMesh.addRect((float) x, (float) y, (float) z, (float) piece.spriteW, (float) piece.spriteH,
                0.0F, 0.0F, 1.0F, 1.0F,
                level.getSkyLight(cellX, cellY, cellZ), level.getBlockLight(cellX, cellY, cellZ),
                UnderwaterTint.packSprite(level, cellX, cellY, cellZ));
        Textures.bind(this.pieceTextures[pieceIndex(piece)]);
        this.pieceMesh.draw();
    }

    // a dice of a colour with semitransparent textures (and the textures are there, if not it is drawn as a plain cube)
    private boolean isSeeThrough(PhysCube cube) {
        return cube instanceof Dice && ((Dice) cube).color().translucent && ensureDiceTextures(((Dice) cube).color());
    }

    private static double distanceSq(PhysCube cube, double x, double y, double z) {
        double dx = cube.x - x;
        double dy = cube.y - y;
        double dz = cube.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void draw(Level level, PhysCube cube, float partialTicks, boolean textured, boolean seeThrough) {
        double x = cube.prevX + (cube.x - cube.prevX) * partialTicks;
        double y = cube.prevY + (cube.y - cube.prevY) * partialTicks;
        double z = cube.prevZ + (cube.z - cube.prevZ) * partialTicks;
        if (Fog.beyond(x, z, 2.0D)) {
            return;   // past the fog: the terrain around it is gone too
        }

        int cellX = (int) Math.floor(x);
        int cellY = (int) Math.floor(y);
        int cellZ = (int) Math.floor(z);
        float base = level.getBrightness(cellX, cellY, cellZ);
        float block = level.getBlockLight(cellX, cellY, cellZ);
        float brightness = base + (1.0F - base) * block;

        cube.writeMatrix(partialTicks, this.matrix);
        this.matrixBuffer.clear();
        this.matrixBuffer.put(this.matrix).flip();

        glPushMatrix();
        glTranslated(x, y, z);
        glMultMatrix(this.matrixBuffer);
        float s = (float) PhysCube.HALF;
        glScalef(s, s, s);

        int color = cube instanceof Dice ? ((Dice) cube).color().ordinal() : 0;
        boolean dice = cube instanceof Dice && ensureDiceTextures(((Dice) cube).color());
        boolean uv = dice || textured;
        for (int pass = 0; pass < (seeThrough ? 2 : 1); pass++) {
            if (seeThrough) {
                glCullFace(pass == 0 ? GL_FRONT : GL_BACK);   // the faces are counter-clockwise seen from outside
            }
            if (!dice) {
                glBegin(GL_QUADS);
            }
            for (int face = 0; face < 6; face++) {
                float shade = shadeOf(face) * brightness;
                glColor3f(shade, shade, shade);
                float[] c = CORNERS[face];
                if (dice) {
                    glEnable(GL_TEXTURE_2D);
                    glBindTexture(GL_TEXTURE_2D, this.diceTextures[color][DICE_FACE_VALUE[face] - 1]);
                    glBegin(GL_QUADS);
                }
                for (int v = 0; v < 4; v++) {
                    if (uv) {
                        glTexCoord2f(UV[v * 2], UV[v * 2 + 1]);
                    }
                    glVertex3f(c[v * 3], c[v * 3 + 1], c[v * 3 + 2]);
                }
                if (dice) {
                    glEnd();
                }
            }
            if (!dice) {
                glEnd();
            }
        }

        glPopMatrix();

        // the next cube is drawn with the plain cube texture again
        if (dice) {
            if (textured) {
                glBindTexture(GL_TEXTURE_2D, this.texture);
            } else {
                glDisable(GL_TEXTURE_2D);
            }
        }
    }

    // the six face textures of a colour are read on first use; if one is missing the dice is drawn with the plain cube texture
    private boolean ensureDiceTextures(Dice.Color color) {
        int c = color.ordinal();
        if (this.diceTextures[c][0] != 0) {
            return true;
        }
        if (this.diceTexturesFailed[c]) {
            return false;
        }
        try {
            for (int i = 0; i < 6; i++) {
                this.diceTextures[c][i] = Textures.loadTexture(String.format(DICE_TEXTURE, i + 1, color.textureSuffix), GL_NEAREST);
            }
            return true;
        } catch (RuntimeException e) {
            System.err.println("[CubeRenderer] dice texture (" + color + ") failed: " + e.getMessage());
            this.diceTexturesFailed[c] = true;
            this.diceTextures[c][0] = 0;
            return false;
        }
    }

    /**
     * 3D pass: finds where the number over every dice that has rolled is on the screen, scaled by distance like a name tag. nothing is drawn here, renderDiceTags does that with the HUD. the camera's matrices must be active
     */
    public void collectDiceTags(CubeManager manager, float partialTicks, double cameraX, double cameraY, double cameraZ) {
        this.diceTags.clear();
        boolean any = false;
        for (WorldEntity entity : manager.all()) {
            if (entity instanceof Dice && ((Dice) entity).diceValue() > 0 && !((Dice) entity).held) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }

        this.diceTags.begin();
        for (WorldEntity entity : manager.all()) {
            if (entity instanceof Dice) {
                this.diceTags.addDice((Dice) entity, partialTicks, cameraX, cameraY, cameraZ);
            }
        }
    }

    /** HUD pass: the numbers found by collectDiceTags, in the box a name tag has (width and height are the HUD's size) */
    public void renderDiceTags(Font font, int width, int height) {
        this.diceTags.render(font, width, height);
    }

    // brightness of a face by where it points in the world (the matrix holds the cube's turn): up is brightest, down darkest
    private float shadeOf(int face) {
        float[] n = NORMALS[face];
        float wx = this.matrix[0] * n[0] + this.matrix[4] * n[1] + this.matrix[8] * n[2];
        float wy = this.matrix[1] * n[0] + this.matrix[5] * n[1] + this.matrix[9] * n[2];
        float shade = 0.8F + 0.2F * wy - 0.1F * Math.abs(wx);
        return Math.max(0.45F, Math.min(1.0F, shade));
    }

    /**
     * the highlight of the cube the crosshair is on: the same pulsing white glow a targeted block gets (see LevelRenderer.renderHit), on all six faces and turned with the cube. fixed-function, no texture
     */
    public void renderHighlight(WorldEntity cube, float partialTicks) {
        if (cube instanceof ChessPiece) {
            renderPieceBlink((ChessPiece) cube, partialTicks);
            return;
        }
        double x = cube.prevX + (cube.x - cube.prevX) * partialTicks;
        double y = cube.prevY + (cube.y - cube.prevY) * partialTicks;
        double z = cube.prevZ + (cube.z - cube.prevZ) * partialTicks;

        cube.writeMatrix(partialTicks, this.matrix);
        this.matrixBuffer.clear();
        this.matrixBuffer.put(this.matrix).flip();

        float pulse = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2F + 0.4F;

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_POLYGON_BIT | GL_CURRENT_BIT);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        glColor4f(1.0F, 1.0F, 1.0F, pulse);

        // pulled toward the viewer like the block highlight, so it does not fight with the cube's own faces
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0F, -4.0F);
        glDepthMask(false);

        glPushMatrix();
        glTranslated(x, y, z);
        glMultMatrix(this.matrixBuffer);
        float s = (float) PhysCube.HALF + 0.002F;
        glScalef(s, s, s);

        glBegin(GL_QUADS);
        for (int face = 0; face < 6; face++) {
            float[] c = CORNERS[face];
            for (int v = 0; v < 4; v++) {
                glVertex3f(c[v * 3], c[v * 3 + 1], c[v * 3 + 2]);
            }
        }
        glEnd();

        glPopMatrix();

        glPopAttrib();
        GL20.glUseProgram(program);
    }

    /**
     * the highlight of the piece the crosshair is on: what a targeted billboard gets (LevelRenderer.renderBillboardBlink), the sprite drawn again at full brightness, added over itself with the pulsing alpha, in the silhouette of the sprite (not the hitbox). fixed-function
     */
    private void renderPieceBlink(ChessPiece piece, float partialTicks) {
        float yaw = CameraRay.yaw();
        int tex = pieceTexture(piece);
        if (Float.isNaN(yaw) || tex <= 0) {
            return;
        }
        double x = piece.prevX + (piece.x - piece.prevX) * partialTicks;
        double y = piece.prevY + (piece.y - piece.prevY) * partialTicks;
        double z = piece.prevZ + (piece.z - piece.prevZ) * partialTicks;
        float pulse = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2F + 0.4F;

        double half = piece.spriteW * 0.5D;
        float dx = (float) (Billboard.rightX(yaw) * half);
        float dz = (float) (Billboard.rightZ(yaw) * half);
        float bottom = (float) y;
        float top = (float) (y + piece.spriteH);

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_POLYGON_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT);
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, tex);
        glDisable(GL_FOG);
        glDisable(GL_CULL_FACE);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.01F);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        glColor4f(1.0F, 1.0F, 1.0F, pulse);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0F, -4.0F);
        glDepthMask(false);

        // the corners and the texture the way the sprite itself is mapped (u0 on the right, see drawPiece)
        glBegin(GL_QUADS);
        glTexCoord2f(0.0F, 0.0F); glVertex3f((float) x + dx, top, (float) z + dz);
        glTexCoord2f(1.0F, 0.0F); glVertex3f((float) x - dx, top, (float) z - dz);
        glTexCoord2f(1.0F, 1.0F); glVertex3f((float) x - dx, bottom, (float) z - dz);
        glTexCoord2f(0.0F, 1.0F); glVertex3f((float) x + dx, bottom, (float) z + dz);
        glEnd();

        glPopAttrib();
        GL20.glUseProgram(program);
    }

    // the texture is read on first use; a missing file leaves the cubes plain grey instead of failing the frame
    private boolean ensureTexture() {
        if (this.texture != 0) {
            return true;
        }
        if (this.textureFailed) {
            return false;
        }
        try {
            this.texture = Textures.loadTexture(TEXTURE, GL_NEAREST);
            return true;
        } catch (RuntimeException e) {
            System.err.println("[CubeRenderer] cube texture " + TEXTURE + " failed: " + e.getMessage());
            this.textureFailed = true;
            return false;
        }
    }
}