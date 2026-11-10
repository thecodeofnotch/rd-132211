package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;

/**
 * how much liquid lies over a terrain face, packed into the vertex alpha for TerrainShader (keep the two in sync).
 *
 * two kinds of value, both packed / PACK_RANGE, and exactly 1.0 means "no liquid", which is what every other vertex already writes:
 *
 *   STORED liquid (flowing / poured / another liquid): p = liquidIndex * 256 + eighths, always below OCEAN_BASE.
 *     the depth is the liquid above the cell in front of the face: its own fill plus every liquid cell stacked directly over it,
 *     in eighths of a block (LiquidLayer.MAX_FILL per block)
 *
 *   OCEAN: p = OCEAN_BASE + wetFrom * 512 + cellY. nothing about the current sea height is baked in: the cell is under the sea
 *     once seaHeight > wetFrom (LiquidLayer.oceanWetFrom) and its depth is seaHeight - cellY. the shader gets seaHeight and the
 *     ocean's liquid as uniforms, so moving the sea needs no chunk to be rebuilt
 *
 * the liquid's own colour and opacity tint and darken the face, so the deeper the water, the darker the sea floor
 */
public final class UnderwaterTint {

    /** the alpha of a face that isn't under liquid */
    public static final float DRY = 1.0F;

    private static final int MAX_EIGHTHS = 255;
    private static final float PACK_RANGE = 524288.0F;
    private static final int OCEAN_BASE = 2048;
    // SPRITE kind, above everything the ocean can pack (2048 + 511 * 512 + 511): p = SURFACE_BASE + liquidIndex * 16384 + surfaceEighths
    private static final int SURFACE_BASE = 266240;

    private UnderwaterTint() {
    }

    /**
     * the alpha for a billboard sprite standing in the cell (x, y, z). the ocean packs the same as for a face (the shader measures its depth at each
     * fragment). stored liquid packs the height of its SURFACE instead of the depth over the cell, so the shader can do the same: the part of
     * the sprite above the water stays clear, and the part under it darkens by how far under it is
     */
    public static float packSprite(Level level, int x, int y, int z) {
        LiquidLayer liquids = level.getLiquids();
        if (liquids.oceanWetFrom(x, y, z) >= 0) {
            return pack(level, x, y, z);
        }
        LiquidTile liquid = liquids.get(x, y, z);
        if (liquid == null) {
            return DRY;
        }
        int top = y;
        for (int cy = y + 1; cy < level.depth && liquids.get(x, cy, z) == liquid; cy++) {
            top = cy;
        }
        float surface = top + LiquidTile.SURFACE_HEIGHT * liquids.getFill(x, top, z) / (float) LiquidLayer.MAX_FILL;
        int eighths = Math.max(1, Math.min(16383, Math.round(surface * 8.0F)));
        return (SURFACE_BASE + liquid.getLiquidIndex() * 16384 + eighths) / PACK_RANGE;
    }

    /** the alpha for a face whose open side is the cell (x, y, z) */
    public static float pack(Level level, int x, int y, int z) {
        LiquidLayer liquids = level.getLiquids();

        int wetFrom = liquids.oceanWetFrom(x, y, z);
        if (wetFrom >= 0) {
            return (OCEAN_BASE + Math.min(wetFrom, 511) * 512 + Math.min(y, 511)) / PACK_RANGE;
        }

        LiquidTile liquid = liquids.get(x, y, z);
        if (liquid == null) {
            return DRY;
        }
        int eighths = 0;
        for (int cy = y; cy < level.depth && eighths < MAX_EIGHTHS; cy++) {
            if (liquids.get(x, cy, z) != liquid) {
                break;
            }
            eighths += liquids.getFill(x, cy, z);
        }
        eighths = Math.max(1, Math.min(MAX_EIGHTHS, eighths));
        return (liquid.getLiquidIndex() * 256 + eighths) / PACK_RANGE;
    }
}