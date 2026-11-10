package com.nikohalfkino.rubydung.level.tiles.common;

import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.UnderwaterTint;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.Billboard;
import com.nikohalfkino.rubydung.render.BillboardMesh;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.Textures;

/**
 * a sprite that always faces the player. ordinary ones need no class of their own, they are declared in TileRegistry:
 *
 *   new BillboardTile("barrel", 2).occupyHeight()           a 2 high sprite that takes up 2 cells
 *   new BillboardTile("rock", 1).setFootprint(1, 1, 1)      a sprite that takes up a footprint of its own
 *   new BillboardTile("lamppost", 8).occupyHeight()         and lights, sounds etc. are added on the tile (addLight...)
 *
 * a subclass is only for a tile with code of its own (TreeTile, the doors)
 */
public class BillboardTile extends Tile {

    protected static final float BILLBOARD_DISTANCE_THRESHOLD = 2.0F;
    protected static final float MAX_CLAMP_ANGLE = 40.0F;

    private final float height;

    public static final int PIXELS_PER_BLOCK = 16;

    private float spriteSize = -1.0F;

    private float atlasExtent = -1.0F;

    private BillboardAura aura = null;

    /** the texture has the tile's name */
    public BillboardTile(String name, float height) {
        this(name, name, height);
    }

    public BillboardTile(String name, String textureName, float height) {
        super(name, textureName);
        this.height = height;
        setTransparent(true);
    }

    /**
     * a dithered glow around the sprite, drawn by the shader (see BillboardAura). keep the glow out of the texture:
     * a glow baked into partly transparent pixels makes the sprite blended, and blended sprites don't write depth,
     * so liquids and other sprites behind them are not drawn
     */
    public BillboardTile setAura(BillboardAura aura) {
        this.aura = aura;
        return this;
    }

    public BillboardAura getAura() {
        return this.aura;
    }

    public float getHeight() {
        return this.height;
    }

    // -1 = not looked at yet. benign race: the answer is always the same
    private int translucent = -1;

    /**
     * whether the sprite has partly transparent pixels (soft edges, a glow) and so has to be blended. sprites that don't (every pixel
     * fully opaque or fully clear) are drawn as cut-outs, which needs no ordering and lets the depth test reject hidden pixels early
     */
    public boolean isTranslucent() {
        if (this.translucent < 0) {
            this.translucent = Textures.hasPartialAlpha(getTexture(FACE_NORTH)) ? 1 : 0;
        }
        return this.translucent == 1;
    }

    public float getSpriteSize() {
        if (this.spriteSize < 0.0F) {
            int pixels = Textures.getSourceWidth(getTexture(FACE_NORTH));
            if (pixels == 0) {
                return this.height;
            }
            this.spriteSize = pixels / (float) PIXELS_PER_BLOCK;
        }
        return this.spriteSize;
    }

    private float getAtlasExtent() {
        if (this.atlasExtent < 0.0F) {
            this.atlasExtent = Textures.getAtlasExtent(getTexture(FACE_NORTH));
        }
        return this.atlasExtent;
    }

    @Override
    public boolean isBillboard() {
        return true;
    }

    public float getPivotOffsetX() {
        return (getSizeX(0) - 1) / 2.0F;
    }

    public float getPivotOffsetZ() {
        return (getSizeZ(0) - 1) / 2.0F;
    }

    @Override
    public AABB getAABB(int x, int y, int z) {
        return null;
    }

    public static float getDistanceScale(float distance) {
        if (distance < 20.0F) {
            return 1.0F;
        } else if (distance < 150.0F) {
            return 1.0F + (distance - 20.0F) / 260.0F;
        } else if (distance < 500.0F) {
            return 1.5F + (distance - 150.0F) / 700.0F;
        } else {
            return 2.0F;
        }
    }

    /** makes the tile take up 1 x height x 1 cells (a tall object is a real multi-tile, not one cell drawing a tall sprite) */
    public BillboardTile occupyHeight() {
        setFootprint(1, (int) Math.ceil(this.height), 1);
        return this;
    }

    @Override
    public AABB getPickAABB(int x, int y, int z) {
        return new AABB(x, y, z, x + getSizeX(0), y + this.height, z + getSizeZ(0));
    }

    @Override
    public AABB getOutlineAABB(Level level, int x, int y, int z) {
        AABB box = getPickAABB(x, y, z);
        float drop = level.getBillboardDrop(x, y, z);
        if (drop == 0.0F) {
            return box;
        }
        return new AABB(box.minX, box.minY - drop, box.minZ, box.maxX, box.maxY - drop, box.maxZ);
    }

    @Override
    public java.util.List<AABB> getPickBoxes(Level level, int x, int y, int z) {
        return java.util.Collections.singletonList(getOutlineAABB(level, x, y, z));
    }

    public float getEffectiveYaw(float tileX, float tileZ, float playerX, float playerZ, float currentCameraYaw) {
        float dx = playerX - tileX;
        float dz = playerZ - tileZ;
        float distanceSquared = (dx * dx) + (dz * dz);

        if (distanceSquared > (BILLBOARD_DISTANCE_THRESHOLD * BILLBOARD_DISTANCE_THRESHOLD)) {
            return Math.max(-MAX_CLAMP_ANGLE, Math.min(currentCameraYaw, MAX_CLAMP_ANGLE));
        }

        return currentCameraYaw;
    }

    /** whether the sprite at this cell is drawn flipped left to right. by default a hash of the position, so a field of the same sprite doesn't look stamped. a tile whose sprite has a facing (a chess piece) says false */
    protected boolean isMirrored(int x, int y, int z) {
        return ((x * 31220 + z * 452389 + y * 93901) & 1) == 0;
    }

    protected float spriteBrightness(Level level, int x, int y, int z) {
        if (emitsLight()) {
            return 1.0F;
        }
        float base = level.getBrightness(x, y, z);
        float block = level.getBlockLight(x, y, z);
        return base + (1.0F - base) * block;
    }

    @Override
    public void renderBillboard(Tessellator tessellator, Level level, int x, int y, int z, int totalAtlasSlots) {
        float brightness = spriteBrightness(level, x, y, z);
        tessellator.color(brightness, brightness, brightness, 1.0F);

        int slot = getAtlasSlot(FACE_NORTH);
        float extent = getAtlasExtent();
        float minU = Chunk.atlasU(slot, 0.0F);
        float maxU = Chunk.atlasU(slot, extent);
        float minV = Chunk.atlasV(slot, 0.0F);
        float maxV = Chunk.atlasV(slot, extent);

        if (isMirrored(x, y, z)) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        float quadHeight = getSpriteSize();
        float half = quadHeight / 2.0F;

        tessellator.texture(minU, minV);
        tessellator.vertex(-half, quadHeight, 0.0F);
        tessellator.texture(maxU, minV);
        tessellator.vertex(half, quadHeight, 0.0F);
        tessellator.texture(maxU, maxV);
        tessellator.vertex(half, 0.0F, 0.0F);
        tessellator.texture(minU, maxV);
        tessellator.vertex(-half, 0.0F, 0.0F);

        tessellator.texture(minU, minV);
        tessellator.vertex(half, quadHeight, 0.0F);
        tessellator.texture(maxU, minV);
        tessellator.vertex(-half, quadHeight, 0.0F);
        tessellator.texture(maxU, maxV);
        tessellator.vertex(-half, 0.0F, 0.0F);
        tessellator.texture(minU, maxV);
        tessellator.vertex(half, 0.0F, 0.0F);
    }

    public void renderBillboardBatched(Tessellator tessellator, Level level, int x, int y, int z, int totalAtlasSlots,
                                       float worldX, float worldY, float worldZ,
                                       float cosYaw, float sinYaw, float scale) {
        renderBillboardBatched(tessellator, level, x, y, z, totalAtlasSlots,
                worldX, worldY, worldZ, cosYaw, sinYaw, scale, -1.0F, UnderwaterTint.packSprite(level, x, y, z));
    }

    public void renderBillboardBatched(Tessellator tessellator, Level level, int x, int y, int z, int totalAtlasSlots,
                                       float worldX, float worldY, float worldZ,
                                       float cosYaw, float sinYaw, float scale,
                                       float brightnessOverride, float alpha) {
        float brightness = brightnessOverride >= 0.0F ? brightnessOverride : spriteBrightness(level, x, y, z);

        int slot = getAtlasSlot(FACE_NORTH);
        float extent = getAtlasExtent();
        float minU = Chunk.atlasU(slot, 0.0F);
        float maxU = Chunk.atlasU(slot, extent);
        float minV = Chunk.atlasV(slot, 0.0F);
        float maxV = Chunk.atlasV(slot, extent);

        if (isMirrored(x, y, z)) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        float quadHeight = getSpriteSize();

        Billboard.emit(tessellator, worldX, worldY, worldZ, quadHeight * scale, quadHeight * scale,
                cosYaw, -sinYaw, minU, minV, maxU, maxV,
                brightness, brightness, brightness, alpha, true);
    }

    /**
     * adds this sprite to its chunk's static billboard mesh (built with the chunk, not every frame): everything that
     * depends on the camera is left to StaticBillboardShader
     */
    public void emitStatic(BillboardMesh mesh, Level level, int x, int y, int z) {
        // sky and block light go in separately, same as terrain: StaticBillboardShader applies the day/night ambient
        // every frame (baking it here would freeze it at whatever it was when the chunk last rebuilt).
        // emissive sprites are fully lit: block light 1 makes the shader's mix come out at 1 whatever the ambient
        boolean emissive = emitsLight();
        float sky = emissive ? 1.0F : level.getSkyLight(x, y, z);
        float block = emissive ? 1.0F : level.getBlockLight(x, y, z);
        float alpha = UnderwaterTint.packSprite(level, x, y, z);

        int slot = getAtlasSlot(FACE_NORTH);
        float extent = getAtlasExtent();
        float minU = Chunk.atlasU(slot, 0.0F);
        float maxU = Chunk.atlasU(slot, extent);
        float minV = Chunk.atlasV(slot, 0.0F);
        float maxV = Chunk.atlasV(slot, extent);

        if (isMirrored(x, y, z)) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        float worldX = x + 0.5F + getPivotOffsetX();
        float worldY = y - level.getBillboardDrop(x, y, z);
        float worldZ = z + 0.5F + getPivotOffsetZ();

        mesh.add(worldX, worldY, worldZ, getSpriteSize(), minU, minV, maxU, maxV, sky, block, alpha, this.aura);
    }

    /** Same sprite as renderBillboardBatched, but one quad instead of two. Face culling must be off while it is drawn. */
    public void renderBillboardSingle(Tessellator tessellator, Level level, int x, int y, int z,
                                      float worldX, float worldY, float worldZ,
                                      float cosYaw, float sinYaw, float scale) {
        float brightness = spriteBrightness(level, x, y, z);
        float alpha = UnderwaterTint.packSprite(level, x, y, z);

        int slot = getAtlasSlot(FACE_NORTH);
        float extent = getAtlasExtent();
        float minU = Chunk.atlasU(slot, 0.0F);
        float maxU = Chunk.atlasU(slot, extent);
        float minV = Chunk.atlasV(slot, 0.0F);
        float maxV = Chunk.atlasV(slot, extent);

        if (isMirrored(x, y, z)) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        float quadHeight = getSpriteSize();

        Billboard.emitSingle(tessellator, worldX, worldY, worldZ, quadHeight * scale, quadHeight * scale,
                cosYaw, -sinYaw, minU, minV, maxU, maxV,
                brightness, brightness, brightness, alpha);
    }

    @Override
    public void renderFace(Tessellator tessellator, int x, int y, int z, int face) {
        float minX = x + 0.0F;
        float maxX = x + 1.0F;
        float minY = y + 0.0F;
        float maxY = y + this.height;
        float minZ = z + 0.0F;
        float maxZ = z + 1.0F;

        if (face == FACE_BOTTOM) {
            tessellator.vertex(minX, minY, maxZ);
            tessellator.vertex(minX, minY, minZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(maxX, minY, maxZ);
        }
        if (face == FACE_TOP) {
            tessellator.vertex(maxX, maxY, maxZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(minX, maxY, maxZ);
        }
        if (face == FACE_NORTH) {
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(minX, minY, minZ);
        }
        if (face == FACE_SOUTH) {
            tessellator.vertex(minX, maxY, maxZ);
            tessellator.vertex(minX, minY, maxZ);
            tessellator.vertex(maxX, minY, maxZ);
            tessellator.vertex(maxX, maxY, maxZ);
        }
        if (face == FACE_WEST) {
            tessellator.vertex(minX, maxY, maxZ);
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(minX, minY, minZ);
            tessellator.vertex(minX, minY, maxZ);
        }
        if (face == FACE_EAST) {
            tessellator.vertex(maxX, minY, maxZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(maxX, maxY, maxZ);
        }
    }
}