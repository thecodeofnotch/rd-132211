package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.entity.player.interaction.HitResult;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.entity.phys.AABB;

public final class VoxelRaycaster {

    private static final int MAX_PICK_HEIGHT = computeMaxPickHeight();

    private VoxelRaycaster() {
    }

    private static int computeMaxPickHeight() {
        int max = 1;
        for (Tile tile : Tile.getTiles()) {
            AABB box = tile.getPickAABB(0, 0, 0);
            if (box != null) {
                max = Math.max(max, (int) Math.ceil(box.maxY - box.minY));
            }
        }
        return max;
    }

    public static HitResult raycast(Level level, double originX, double originY, double originZ,
                                     double dirX, double dirY, double dirZ, double maxDistance) {
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1.0E-8) {
            return null;
        }
        dirX /= length;
        dirY /= length;
        dirZ /= length;

        int x = (int) Math.floor(originX);
        int y = (int) Math.floor(originY);
        int z = (int) Math.floor(originZ);

        int stepX = Double.compare(dirX, 0.0) > 0 ? 1 : (dirX < 0.0 ? -1 : 0);
        int stepY = Double.compare(dirY, 0.0) > 0 ? 1 : (dirY < 0.0 ? -1 : 0);
        int stepZ = Double.compare(dirZ, 0.0) > 0 ? 1 : (dirZ < 0.0 ? -1 : 0);

        double tDeltaX = dirX != 0.0 ? Math.abs(1.0 / dirX) : Double.POSITIVE_INFINITY;
        double tDeltaY = dirY != 0.0 ? Math.abs(1.0 / dirY) : Double.POSITIVE_INFINITY;
        double tDeltaZ = dirZ != 0.0 ? Math.abs(1.0 / dirZ) : Double.POSITIVE_INFINITY;

        double tMaxX = tDeltaX == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY
                : ((stepX > 0 ? (x + 1 - originX) : (originX - x)) * tDeltaX);
        double tMaxY = tDeltaY == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY
                : ((stepY > 0 ? (y + 1 - originY) : (originY - y)) * tDeltaY);
        double tMaxZ = tDeltaZ == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY
                : ((stepZ > 0 ? (z + 1 - originZ) : (originZ - z)) * tDeltaZ);

        int enteredFace = -1;
        double traveled = 0.0;

        while (traveled <= maxDistance) {
            if (enteredFace != -1) {
                // Any cell of a multi-tile object selects the whole object, reported at its origin.
                MultiTileInstance object = level.getMultiTile(x, y, z);
                if (object != null) {
                    return new HitResult(object.x, object.y, object.z, 0, enteredFace, x, y, z);
                }

                // Tiles sharing a cell: report the cell, TilePicker.refine finds which one (if any) the ray struck.
                if (level.getSubTiles().has(x, y, z)) {
                    return new HitResult(x, y, z, 0, enteredFace, x, y, z);
                }

                // Tall single-cell tiles that aren't multi-tile objects: target the root, but report the
                // cell that was actually entered so placing lands next to the part that was clicked.
                int[] target = resolveTarget(level, x, y, z);
                if (target != null) {
                    return new HitResult(target[0], target[1], target[2], 0, enteredFace, x, y, z);
                }
            }

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                traveled = tMaxX;
                tMaxX += tDeltaX;
                enteredFace = stepX > 0 ? Tile.FACE_WEST : Tile.FACE_EAST;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                traveled = tMaxY;
                tMaxY += tDeltaY;
                enteredFace = stepY > 0 ? Tile.FACE_BOTTOM : Tile.FACE_TOP;
            } else {
                z += stepZ;
                traveled = tMaxZ;
                tMaxZ += tDeltaZ;
                enteredFace = stepZ > 0 ? Tile.FACE_NORTH : Tile.FACE_SOUTH;
            }
        }

        return null;
    }

    private static int[] resolveTarget(Level level, int x, int y, int z) {
        for (int rootY = y; rootY > y - MAX_PICK_HEIGHT; rootY--) {
            Tile tile = Tile.byId(level.getTile(x, rootY, z));
            if (tile == null || tile == Tile.AIR) {
                continue;
            }

            // Cells of registered multi-tile objects are handled by the caller. Their tall pick box
            // would otherwise select the empty air above the object.
            if (level.getMultiTile(x, rootY, z) != null) {
                continue;
            }

            AABB box = tile.getPickAABB(x, rootY, z);
            if (box != null && tile.isBillboard()) {
                float drop = level.getBillboardDrop(x, rootY, z);   // the sprite is lowered onto what it stands on
                if (drop != 0.0F) {
                    box = new AABB(box.minX, box.minY - drop, box.minZ, box.maxX, box.maxY - drop, box.maxZ);
                }
            }
            if (box != null && y >= box.minY && y < box.maxY) {
                return new int[]{x, rootY, z};
            }
        }
        return null;
    }
}
