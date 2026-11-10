package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.tiles.common.BillboardAura;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL13;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * The sprites of one chunk as static geometry: nothing here depends on the camera, so it is built once with the
 * chunk (on a worker thread) and compiled into a display list. The camera-facing turn, the growth with distance and
 * the distance cut-off are done in StaticBillboardShader's vertex shader.
 *
 * per vertex: the sprite's pivot (bottom centre) as the position, the atlas coordinate, the light (r = sky light,
 * g = block light, combined with the live ambient in StaticBillboardShader) with the liquid tint packed in alpha, and a corner (texture unit 1: x = -0.5 / +0.5 across, y = 0 / 1 up, z = sprite size)
 */
public final class BillboardMesh {

    private float[] positions = new float[12 * 32];
    private float[] corners = new float[12 * 32];
    private float[] uvs = new float[8 * 32];
    private float[] colors = new float[16 * 32];
    // per vertex, texture units 2 and 3: the aura (centre x, centre y, radius, density) and its colour (rgb, falloff). radius 0 = none
    private float[] auras = new float[16 * 32];
    private float[] auraColors = new float[16 * 32];
    // per vertex, texture unit 4: the aura's band count, the antialiased edge width in pixels, its blend mode and its grain
    private float[] auraBands = new float[16 * 32];
    // per vertex, texture units 5 and 6: the rim colour (rgb) with the squareness, and the core colour (rgb) with the rays
    private float[] auraRims = new float[16 * 32];
    private float[] auraCores = new float[16 * 32];
    private int vertices = 0;

    public boolean isEmpty() {
        return this.vertices == 0;
    }

    /** (u0, v0) is the texture coordinate of the top-left corner, (u1, v1) of the bottom-right, as in Billboard.emit */
    public void add(float x, float y, float z, float size, float u0, float v0, float u1, float v1,
                    float sky, float block, float tint) {
        add(x, y, z, size, u0, v0, u1, v1, sky, block, tint, null);
    }

    /** aura may be null */
    public void add(float x, float y, float z, float size, float u0, float v0, float u1, float v1,
                    float sky, float block, float tint, BillboardAura aura) {
        ensureRoom(4);
        this.pendingAura = aura;
        // same corner order as Billboard.emitSingle: top-right, top-left, bottom-left, bottom-right
        vertex(x, y, z, 0.5F, 1.0F, size, u0, v0, sky, block, tint);
        vertex(x, y, z, -0.5F, 1.0F, size, u1, v0, sky, block, tint);
        vertex(x, y, z, -0.5F, 0.0F, size, u1, v1, sky, block, tint);
        vertex(x, y, z, 0.5F, 0.0F, size, u0, v1, sky, block, tint);
        this.pendingAura = null;
    }

    /**
     * a sprite that is not square (a chess piece: 1 x 2 blocks, 2 x 3). the shader scales the corner by the size, so the size is the height and the corner carries the width as a share of it. same corner order and texture mapping as add
     */
    public void addRect(float x, float y, float z, float width, float height, float u0, float v0, float u1, float v1,
                        float sky, float block, float tint) {
        ensureRoom(4);
        this.pendingAura = null;
        float half = 0.5F * width / height;
        vertex(x, y, z, half, 1.0F, height, u0, v0, sky, block, tint);
        vertex(x, y, z, -half, 1.0F, height, u1, v0, sky, block, tint);
        vertex(x, y, z, -half, 0.0F, height, u1, v1, sky, block, tint);
        vertex(x, y, z, half, 0.0F, height, u0, v1, sky, block, tint);
    }

    /** empties the mesh to build another (one that is rebuilt every frame) */
    public void clear() {
        this.vertices = 0;
    }

    private BillboardAura pendingAura;

    private void ensureRoom(int extra) {
        int needed = this.vertices + extra;
        if (needed * 3 > this.positions.length) {
            int vertexCapacity = Math.max(needed, this.positions.length / 3 * 2);
            this.positions = java.util.Arrays.copyOf(this.positions, vertexCapacity * 3);
            this.corners = java.util.Arrays.copyOf(this.corners, vertexCapacity * 3);
            this.uvs = java.util.Arrays.copyOf(this.uvs, vertexCapacity * 2);
            this.colors = java.util.Arrays.copyOf(this.colors, vertexCapacity * 4);
            this.auras = java.util.Arrays.copyOf(this.auras, vertexCapacity * 4);
            this.auraColors = java.util.Arrays.copyOf(this.auraColors, vertexCapacity * 4);
            this.auraBands = java.util.Arrays.copyOf(this.auraBands, vertexCapacity * 4);
            this.auraRims = java.util.Arrays.copyOf(this.auraRims, vertexCapacity * 4);
            this.auraCores = java.util.Arrays.copyOf(this.auraCores, vertexCapacity * 4);
        }
    }

    private void vertex(float x, float y, float z, float cornerX, float cornerY, float size,
                        float u, float v, float sky, float block, float tint) {
        int i = this.vertices++;
        this.positions[i * 3] = x;
        this.positions[i * 3 + 1] = y;
        this.positions[i * 3 + 2] = z;
        this.corners[i * 3] = cornerX;
        this.corners[i * 3 + 1] = cornerY;
        this.corners[i * 3 + 2] = size;
        this.uvs[i * 2] = u;
        this.uvs[i * 2 + 1] = v;
        // packed like terrain vertices (see TerrainShader): r = sky light, g = block light, b unused, a = liquid tint.
        // the day/night ambient is NOT baked in: it changes every frame while the chunk only rebuilds on a block change
        this.colors[i * 4] = sky;
        this.colors[i * 4 + 1] = block;
        this.colors[i * 4 + 2] = 1.0F;
        this.colors[i * 4 + 3] = tint;
        BillboardAura aura = this.pendingAura;
        if (aura != null) {
            this.auras[i * 4] = aura.getOffsetX();
            this.auras[i * 4 + 1] = aura.getOffsetY();
            this.auras[i * 4 + 2] = aura.getRadius();
            this.auras[i * 4 + 3] = aura.getStrength();
            this.colors[i * 4 + 2] = aura.getOpacity();   // b is unused by the light mix, the shader reads the aura's opacity from it
            this.auraColors[i * 4] = aura.red;
            this.auraColors[i * 4 + 1] = aura.green;
            this.auraColors[i * 4 + 2] = aura.blue;
            this.auraColors[i * 4 + 3] = aura.getFalloff();
            this.auraBands[i * 4] = aura.getBands();
            this.auraBands[i * 4 + 1] = aura.getAntialias();
            this.auraBands[i * 4 + 2] = aura.getBlend().ordinal();
            this.auraBands[i * 4 + 3] = aura.getGrain();
            float[] rim = aura.getRimColor();
            float[] core = aura.getCoreColor();
            this.auraRims[i * 4] = rim[0];
            this.auraRims[i * 4 + 1] = rim[1];
            this.auraRims[i * 4 + 2] = rim[2];
            this.auraRims[i * 4 + 3] = aura.getSquareness();
            this.auraCores[i * 4] = core[0];
            this.auraCores[i * 4 + 1] = core[1];
            this.auraCores[i * 4 + 2] = core[2];
            this.auraCores[i * 4 + 3] = aura.getRays();
        } else {
            this.auras[i * 4 + 2] = 0.0F;
            this.auras[i * 4 + 3] = 0.0F;
        }
    }

    private static FloatBuffer scratchPositions, scratchCorners, scratchUvs, scratchColors, scratchAuras, scratchAuraColors, scratchAuraBands, scratchAuraRims, scratchAuraCores;

    private static FloatBuffer scratch(FloatBuffer current, int floats) {
        if (current == null || current.capacity() < floats) {
            return BufferUtils.createFloatBuffer(Math.max(floats, 4096));
        }
        return current;
    }

    /** GL thread only: draws the sprites (inside a display list, this compiles them into the list) */
    public void draw() {
        if (this.vertices == 0) {
            return;
        }
        int n = this.vertices;
        scratchPositions = scratch(scratchPositions, n * 3);
        scratchCorners = scratch(scratchCorners, n * 3);
        scratchUvs = scratch(scratchUvs, n * 2);
        scratchColors = scratch(scratchColors, n * 4);
        scratchAuras = scratch(scratchAuras, n * 4);
        scratchAuraColors = scratch(scratchAuraColors, n * 4);
        scratchAuraBands = scratch(scratchAuraBands, n * 4);
        scratchAuraRims = scratch(scratchAuraRims, n * 4);
        scratchAuraCores = scratch(scratchAuraCores, n * 4);

        scratchPositions.clear();
        scratchPositions.put(this.positions, 0, n * 3).flip();
        scratchCorners.clear();
        scratchCorners.put(this.corners, 0, n * 3).flip();
        scratchUvs.clear();
        scratchUvs.put(this.uvs, 0, n * 2).flip();
        scratchColors.clear();
        scratchColors.put(this.colors, 0, n * 4).flip();
        scratchAuras.clear();
        scratchAuras.put(this.auras, 0, n * 4).flip();
        scratchAuraColors.clear();
        scratchAuraColors.put(this.auraColors, 0, n * 4).flip();
        scratchAuraBands.clear();
        scratchAuraBands.put(this.auraBands, 0, n * 4).flip();
        scratchAuraRims.clear();
        scratchAuraRims.put(this.auraRims, 0, n * 4).flip();
        scratchAuraCores.clear();
        scratchAuraCores.put(this.auraCores, 0, n * 4).flip();

        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(3, 0, scratchPositions);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(2, 0, scratchUvs);
        glEnableClientState(GL_COLOR_ARRAY);
        glColorPointer(4, 0, scratchColors);

        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(3, 0, scratchCorners);

        GL13.glClientActiveTexture(GL13.GL_TEXTURE2);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(4, 0, scratchAuras);

        GL13.glClientActiveTexture(GL13.GL_TEXTURE3);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(4, 0, scratchAuraColors);

        GL13.glClientActiveTexture(GL13.GL_TEXTURE4);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(4, 0, scratchAuraBands);

        GL13.glClientActiveTexture(GL13.GL_TEXTURE5);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(4, 0, scratchAuraRims);

        GL13.glClientActiveTexture(GL13.GL_TEXTURE6);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glTexCoordPointer(4, 0, scratchAuraCores);

        glDrawArrays(GL_QUADS, 0, n);

        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE5);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE4);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE3);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE2);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);

        glDisableClientState(GL_COLOR_ARRAY);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
    }
}