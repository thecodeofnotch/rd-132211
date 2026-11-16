package com.nikohalfkino.rubydung.entity.player;

import com.nikohalfkino.rubydung.level.DoorRegistry;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.special.DoorColor;

/**
 * arriving through a dimensional door: puts the player in front of one of the world's doors matching the colour they came through, looking at it
 *
 * that door is also the way back, since the world they came from has a matching door too
 */
public final class DoorTravel {

    private static final double PLAYER_EYE_HEIGHT = 1.62D;

    // neighbour offsets tried around a door, nearest first
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private DoorTravel() {
    }

    /** moves the player to a door of the level matching picked (an exact colour match beats a regular door), false (player stays put) if there's no matching door or no free spot next to one */
    public static boolean arrive(Level level, Player player, DoorColor picked) {
        double[] spot = findArrival(level, picked);
        if (spot == null) {
            return false;
        }

        player.setPositionAndRotation(spot[0], spot[1], spot[2], (float) spot[3], 0.0F);
        return true;
    }

    /**
     * where a player coming through a door of this colour stands: {x, y (eye height), z, yaw}, null if the level has no
     * matching door or no free spot next to one. a server uses this to place a player without having a Player for them
     */
    public static double[] findArrival(Level level, DoorColor picked) {
        DoorRegistry.Entry best = null;
        int bestScore = Integer.MAX_VALUE;

        for (DoorRegistry.Entry door : level.getDoors()) {
            if (!picked.matches(door.color)) {
                continue;
            }

            int score = door.color == picked ? 0 : 1;
            if (score < bestScore) {
                best = door;
                bestScore = score;
            }
        }

        if (best == null) {
            return null;
        }

        int[] spot = findStandingSpot(level, best);
        if (spot == null) {
            return null;
        }

        double px = spot[0] + 0.5D;
        double pz = spot[1] + 0.5D;

        // Forward is (sin(yaw), -cos(yaw)) in (x, z), see Player#moveRelative.
        double toDoorX = best.x + 0.5D - px;
        double toDoorZ = best.z + 0.5D - pz;
        double yaw = Math.toDegrees(Math.atan2(toDoorX, -toDoorZ));

        return new double[]{px, best.y + PLAYER_EYE_HEIGHT + 0.05D, pz, yaw};
    }

    // a cell beside the door where the player fits (feet and head free), preferring one with ground under it
    private static int[] findStandingSpot(Level level, DoorRegistry.Entry door) {
        for (int pass = 0; pass < 2; pass++) {
            boolean requireGround = pass == 0;

            for (int distance = 1; distance <= 2; distance++) {
                for (int[] direction : DIRECTIONS) {
                    int x = door.x + direction[0] * distance;
                    int z = door.z + direction[1] * distance;

                    if (x < 0 || z < 0 || x >= level.width || z >= level.height) {
                        continue;
                    }

                    boolean free = !level.isSolidTile(x, door.y, z) && !level.isSolidTile(x, door.y + 1, z);
                    boolean ground = level.isSolidTile(x, door.y - 1, z);

                    if (free && (!requireGround || ground)) {
                        return new int[]{x, z};
                    }
                }
            }
        }
        return null;
    }
}