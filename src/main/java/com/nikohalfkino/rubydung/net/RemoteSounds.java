package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.player.Player;

/**
 * the sounds of what other players do. the player's own block sounds are played where they act (the breaker and placer),
 * a change that comes in from the network has no such caller, so a joined player heard nothing of what the others built.
 * main thread only
 */
final class RemoteSounds {

    /** farther than this (blocks) nothing is heard */
    private static final double RANGE = 32.0D;

    /** a block placed or broken more than once in this many nanoseconds is not played again, a big fill would be a roar */
    private static final long MIN_GAP_NANOS = 40_000_000L;

    private static long lastPlayed = 0L;

    private RemoteSounds() {
    }

    /**
     * a block changed because somebody else changed it. oldId / newId are the tile ids before and after, listener is
     * where the player is (null = no player yet)
     */
    static void blockChanged(Player listener, int x, int y, int z, int oldId, int newId) {
        if (oldId == newId || listener == null) {
            return;
        }

        Tile oldTile = Tile.byId(oldId & 0xFF);
        Tile newTile = Tile.byId(newId & 0xFF);
        if (oldTile instanceof LiquidTile || newTile instanceof LiquidTile) {
            return;
        }

        double dx = x + 0.5D - listener.x;
        double dy = y + 0.5D - listener.y;
        double dz = z + 0.5D - listener.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance >= RANGE) {
            return;
        }

        long now = System.nanoTime();
        if (now - lastPlayed < MIN_GAP_NANOS) {
            return;
        }
        lastPlayed = now;

        // quieter the farther away, squared so it falls off quickly
        float closeness = (float) (1.0D - distance / RANGE);
        float volume = Math.max(0.05F, closeness * closeness);

        if (newId == 0) {
            SoundEffects.playTile(oldTile, "break", volume);
        } else {
            SoundEffects.playTile(newTile, "place", volume);
        }
    }
}
