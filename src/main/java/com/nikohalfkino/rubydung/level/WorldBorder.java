package com.nikohalfkino.rubydung.level;

/**
 * Numbers shared by everything that has an opinion about the edge of the world: the fake terrain drawn past it
 * (render/WorldBorderRenderer), and the rules that keep the player in (entity/player/Player).
 *
 * Note: the server clamps the position a client reports to [-64, size + 64] on x and z and [-64, depth + 256] on y (GameServer), which
 * only matters to the OTHER players, who see a noclipping player stop there. The player's own movement is not limited by it.
 */
public final class WorldBorder {

    /** how far past the real edge the fake terrain reaches, in blocks */
    public static final int FRINGE = 60;

    /** flying (not noclip): how far past the real edge the player may hover. The fringe has no collision, this is a look-over-the-edge allowance only */
    public static final double FLY_MARGIN = 20.0D;

    /** a player who is not in noclip and falls this far below the bottom of the world comes back in over the top */
    public static final double VOID_MARGIN = 48.0D;

    /**
     * where a player who has fallen out of the bottom of the world should come back in: this many blocks above the top of the world, not just over it.
     * Up there the world is still hidden in fog (see voidVisibility), and it fades in as the player comes down, so nothing pops into view
     */
    public static final double VOID_RETURN_ABOVE = 64.0D;

    /** how long the world takes to fade in after a player has been brought back from the void, in seconds */
    public static final float VOID_REVEAL_SECONDS = 3.0F;

    private WorldBorder() {
    }

    /**
     * How much of the world can be seen, 1 = all of it, near 0 = only fog: the render distance is scaled by this (Fog.update). It falls from 1 at the
     * bottom of the world to the minimum at VOID_MARGIN below it, where the player is brought back, so the terrain melts away as they fall instead
     * of being there one frame and gone the next
     */
    public static float voidVisibility(double eyeY) {
        double fall = Math.max(0.0D, Math.min(1.0D, -eyeY / VOID_MARGIN));
        double smooth = fall * fall * (3.0D - 2.0D * fall);
        return (float) (1.0D - smooth * 0.97D);
    }

    /** the shift that puts a player who fell out of the bottom (y) back in VOID_RETURN_ABOVE blocks above the top of a world of this depth */
    public static double voidReturnShift(double y, double depth) {
        return depth + VOID_RETURN_ABOVE - y;
    }

    /**
     * The shift that brings v back into [-margin, extent + margin), 0 if it is already there. The range is a torus of
     * period extent + 2 * margin; the result always lands strictly inside it, so a wrapped player is never on the limit
     * itself and cannot be wrapped back on the next tick.
     */
    public static double wrapShift(double v, double extent, double margin) {
        double period = extent + 2.0D * margin;
        return -period * Math.floor((v + margin) / period);
    }
}