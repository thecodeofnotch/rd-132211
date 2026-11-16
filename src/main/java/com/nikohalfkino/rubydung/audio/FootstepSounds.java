package com.nikohalfkino.rubydung.audio;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.SubTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.util.List;

/**
 * the player's footsteps: one step every STRIDE blocks walked on the ground, in the material of the block underfoot
 *
 * the sounds are "step_<material>" in audio.toml (concrete, dirt, grass, gravel, metal, sand, tile, wood), each with its
 * files (see the [sounds.step_*] entries). what a block sounds like is decided in materialOf, by the tile's name
 */
public final class FootstepSounds {

    /** blocks walked between two steps */
    private static final double STRIDE = 1.8D;
    /** a move longer than this in one tick is a teleport (a door), not a walk */
    private static final double MAX_STEP_MOVE = 1.0D;
    /** loudness of a step, also what is sent to the other players (as the strength) */
    public static final float VOLUME = 0.7F;

    private final Level level;
    private double walked;
    private boolean wasOnGround;

    public FootstepSounds(Level level) {
        this.level = level;
    }

    /** forget the distance so far: flying, swimming, teleported */
    public void reset() {
        this.walked = 0.0D;
        this.wasOnGround = false;
    }

    /**
     * once per tick. feetY is the height of the soles, dx / dz how far the player moved this tick. true when a step was played
     */
    public boolean tick(double x, double feetY, double z, boolean onGround, int waterLevel, double dx, double dz) {
        double moved = Math.hypot(dx, dz);
        if (!onGround || waterLevel > 0 || moved > MAX_STEP_MOVE) {
            this.wasOnGround = onGround;
            if (waterLevel > 0 || moved > MAX_STEP_MOVE) {
                this.walked = 0.0D;
            }
            return false;
        }

        if (!this.wasOnGround) {
            // just landed (the landing has its own sound): the next step is a whole stride away
            this.walked = 0.0D;
            this.wasOnGround = true;
            return false;
        }

        this.walked += moved;
        if (this.walked >= STRIDE) {
            this.walked -= STRIDE;
            SoundEffects.play("step_" + materialUnder(this.level, x, feetY, z), VOLUME);
            return true;
        }
        return false;
    }

    /** a step another player took on the cell (cx, cy, cz), cy being the cell under their feet. volume is how near they are */
    public static void playAt(Level level, int cx, int cy, int cz, float volume) {
        if (level == null) {
            return;
        }
        SoundEffects.play("step_" + materialUnder(level, cx + 0.5D, cy + 0.2D, cz + 0.5D), VOLUME * volume);
    }

    private static String materialUnder(Level level, double x, double feetY, double z) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        int cy = (int) Math.floor(feetY - 0.1D);

        // a cell of the level may be taken by sub-tiles (a half block...), they go first
        for (int y = cy; y >= cy - 1; y--) {
            List<SubTile> subTiles = level.getSubTiles().at(cx, y, cz);
            if (!subTiles.isEmpty()) {
                Tile tile = Tile.byId(subTiles.get(0).tileId & 0xFF);
                if (tile != null) {
                    return materialOf(tile.name);
                }
            }
            int id = level.getTile(cx, y, cz) & 0xFF;
            if (id != 0 && level.isSolidTile(cx, y, cz)) {
                Tile tile = Tile.byId(id);
                return tile == null ? "concrete" : materialOf(tile.name);
            }
        }
        return "concrete";   // standing on a cube or something else that is no block
    }

    /** the sound group of a tile, by its name */
    public static String materialOf(String tile) {
        switch (tile) {
            case "grass":
            case "tree_sapling":
            case "tree_medium":
            case "tree_large":
                return "grass";
            case "sand":
                return "sand";
            case "gravel":
                return "gravel";
            case "path":
                return "dirt";
            case "metal":
            case "metal_girder":
            case "metal_grate":
            case "metal_bars":
            case "gold":
            case "lamppost":
                return "metal";
            case "planks":
            case "wood":
            case "wood_slab":
            case "log":
            case "barrel":
                return "wood";
            case "stone_tiles":
            case "marble":
            case "slate":
            case "glass":
            case "framed_glass":
                return "tile";
            default:
                if (tile.startsWith("wooden_door")) {
                    return "wood";
                }
                return "concrete";
        }
    }
}
