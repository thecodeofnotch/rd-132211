package com.nikohalfkino.rubydung.entity.player.interaction;

import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.SubTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;

/** state shared by the breaker, placer and replacer: the world, player, selected tile, placement aim and the repeat timer that paces held-button actions */
final class InteractionContext {

    static final double MOVING_EPSILON = 1.0E-3;
    static final int BREAK_DELAY_TICKS = 5;
    static final double MAX_BREAKS_PER_TICK = 32.0;

    // prints to stderr why a click did nothing (no hit, hit already air)
    static final boolean DEBUG_CLICKS = false;

    final Level level;
    final Player player;
    final PlacementAim aim = new PlacementAim();

    Tile selectedTile = Tile.GRASS;

    // ticks until the held button may act again
    int actionCooldown = 0;

    // whether the current hold already did something (break, place, replace)
    boolean acted = false;

    InteractionContext(Level level, Player player) {
        this.level = level;
        this.player = player;
    }

    boolean inWorld(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.level.width && y < this.level.depth && z < this.level.height;
    }

    // whether there's still something at the hit to break (a sub-tile or the cell's normal tile)
    boolean hasTarget(HitResult hit) {
        if (hit.overlayId >= 0) {
            return this.level.getSubTiles().find(hit.x, hit.y, hit.z, hit.overlayId) != null;
        }
        return this.level.getTile(hit.x, hit.y, hit.z) != 0;
    }

    // the tile that was hit: the sub-tile's if one was struck, else the cell's normal tile, null if gone
    Tile targetTile(HitResult hit) {
        if (hit.overlayId >= 0) {
            SubTile subTile = this.level.getSubTiles().find(hit.x, hit.y, hit.z, hit.overlayId);
            return subTile == null ? null : Tile.byId(subTile.tileId);
        }
        return Tile.byId(this.level.getTile(hit.x, hit.y, hit.z));
    }
}
