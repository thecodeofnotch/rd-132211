package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.entity.phys.AABB;
import com.nikohalfkino.rubydung.entity.player.interaction.HitResult;

/**
 * finds the liquid the crosshair is on, for a player who holds that liquid's tile (so it can be broken, and built against)
 *
 * liquids aren't blocks, so VoxelRaycaster looks straight through them. this walks the same ray cell by cell and returns the first cell holding the wanted liquid that comes before the block the ray hit (blockHit, null = no block). other liquids are ignored, and so is the cell the eye is in (a swimmer would otherwise always "hit" the water around them)
 */
public final class LiquidRaycaster {

    private LiquidRaycaster() {}

    /** a hit (HitResult.liquid = true) or null if no wanted liquid lies on the ray before the block hit / the reach. dx, dy, dz is the unit look direction */
    public static HitResult raycast(Level level, double ox, double oy, double oz,
                                    double dx, double dy, double dz, double reach,
                                    LiquidTile wanted, HitResult blockHit) {
        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;

        double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        double tMaxX = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? x + 1 - ox : ox - x) * tDeltaX;
        double tMaxY = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? y + 1 - oy : oy - y) * tDeltaY;
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? z + 1 - oz : oz - z) * tDeltaZ;

        LiquidLayer liquids = level.getLiquids();

        while (true) {
            double t;
            int face;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
                face = stepX > 0 ? 4 : 5;          // entered through the west / east side
            } else if (tMaxY < tMaxZ) {
                t = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
                face = stepY > 0 ? 0 : 1;          // bottom / top
            } else {
                t = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
                face = stepZ > 0 ? 2 : 3;          // north / south
            }

            if (t > reach) {
                return null;
            }
            if (blockHit != null && x == blockHit.cellX && y == blockHit.cellY && z == blockHit.cellZ) {
                return null;                        // the block is reached first
            }
            if (liquids.get(x, y, z) == wanted) {
                float top = wanted.getSurfaceY(level, x, y, z);
                HitResult hit = new HitResult(x, y, z, 0, face);
                hit.liquid = true;
                return hit.withPoint(ox + dx * t, oy + dy * t, oz + dz * t,
                        new AABB(x, y, z, x + 1.0F, top, z + 1.0F));
            }
        }
    }
}