package com.nikohalfkino.rubydung.level.tiles;

/**
 * one light a tile gives off. a tile may have any number of them (Tile.addLight), each from its own spot and in its own shape:
 *
 *   LightEmitter.top().radius(12).cone(0, -1, 0, 40)     a lamppost: a cone pointing down, its point on the topmost cell
 *   LightEmitter.everyCell().radius(10)                   the plain kind: a ball of light around every cell (what setLightRadius gives)
 *   LightEmitter.top().offset(2, 0, 0).radius(8)          a ball two cells beside the top cell
 *
 * the spot is a cell: the anchor cell plus the offset, in whole cells (the light sits at that cell's centre)
 */
public final class LightEmitter {

    /** which cell of the tile the light is measured from */
    public enum Anchor {
        /** every cell the tile occupies has its own copy of the light (a tall multi-tile lights its whole height) */
        EVERY_CELL,
        /** only the topmost cell of the tile: the one with no cell of the same tile above it */
        TOP,
        /** only the lowest cell of the tile */
        BOTTOM
    }

    private final Anchor anchor;
    private int offsetX, offsetY, offsetZ;
    private float radius = 8.0F;

    private boolean cone = false;
    private float dirX, dirY = -1.0F, dirZ;
    private float halfAngle = 45.0F;
    private float softness = 0.35F;
    private float falloff = 2.0F;
    private float intensity = 1.0F;

    private LightEmitter(Anchor anchor) {
        this.anchor = anchor;
    }

    public static LightEmitter everyCell() {
        return new LightEmitter(Anchor.EVERY_CELL);
    }

    public static LightEmitter top() {
        return new LightEmitter(Anchor.TOP);
    }

    public static LightEmitter bottom() {
        return new LightEmitter(Anchor.BOTTOM);
    }

    /** moves the light this many cells from its anchor cell */
    public LightEmitter offset(int x, int y, int z) {
        this.offsetX = x;
        this.offsetY = y;
        this.offsetZ = z;
        return this;
    }

    /** blocks the light reaches (capped by Level.MAX_LIGHT_RADIUS) */
    public LightEmitter radius(float radius) {
        this.radius = Math.max(radius, 0.0F);
        return this;
    }

    /**
     * makes it a cone: the light only reaches cells within halfAngleDegrees of the direction (dx, dy, dz) (any length, not zero),
     * seen from the light's cell. (0, -1, 0) points straight down
     */
    public LightEmitter cone(float dx, float dy, float dz, float halfAngleDegrees) {
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6F) {
            throw new IllegalArgumentException("a cone needs a direction");
        }
        this.cone = true;
        this.dirX = dx / length;
        this.dirY = dy / length;
        this.dirZ = dz / length;
        this.halfAngle = Math.min(Math.max(halfAngleDegrees, 1.0F), 179.0F);
        return this;
    }

    /** how much of the cone's edge fades out, 0 = a hard edge, 1 = fades from the middle line to the edge (default 0.35) */
    public LightEmitter softness(float softness) {
        this.softness = Math.min(Math.max(softness, 0.0F), 1.0F);
        return this;
    }

    /**
     * how fast the light dies out with the distance from the light: the brightness is (1 - distance / radius) to this power.
     * 2 = the default, bright at the lamp and dim far from it. 1 = linear, 0.5 or less = nearly flat, so the far end of a cone
     * (its base, on the ground) is almost as bright as the lamp itself. below 0 is treated as 0
     */
    public LightEmitter falloff(float falloff) {
        this.falloff = Math.max(falloff, 0.0F);
        return this;
    }

    /** multiplies the brightness (it can't pass full light): 1 = as is, 1.5 = brighter everywhere, so the dim far end gets more of it too */
    public LightEmitter intensity(float intensity) {
        this.intensity = Math.max(intensity, 0.0F);
        return this;
    }

    public float getFalloff() {
        return this.falloff;
    }

    public float getIntensity() {
        return this.intensity;
    }

    public Anchor getAnchor() {
        return this.anchor;
    }

    public int getOffsetX() {
        return this.offsetX;
    }

    public int getOffsetY() {
        return this.offsetY;
    }

    public int getOffsetZ() {
        return this.offsetZ;
    }

    public float getRadius() {
        return this.radius;
    }

    public boolean isCone() {
        return this.cone;
    }

    /** the unit direction of the cone */
    public float getDirX() {
        return this.dirX;
    }

    public float getDirY() {
        return this.dirY;
    }

    public float getDirZ() {
        return this.dirZ;
    }

    /** cosine of the angle where the cone's light has faded out completely */
    public float getCosOuter() {
        return (float) Math.cos(Math.toRadians(this.halfAngle));
    }

    /** cosine of the angle up to which the cone is at full strength */
    public float getCosInner() {
        return (float) Math.cos(Math.toRadians(this.halfAngle * (1.0F - this.softness)));
    }
}