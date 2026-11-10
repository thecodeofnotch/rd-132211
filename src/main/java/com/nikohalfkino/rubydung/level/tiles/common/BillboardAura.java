package com.nikohalfkino.rubydung.level.tiles.common;

/**
 * a soft glow around a billboard, drawn by StaticBillboardShader instead of being baked into the sprite's texture.
 *
 * the glow is cut into flat bands, like a cartoon: each band is one solid opacity step. the border between two bands is
 * antialiased: the border pixels take a share of the next band (see antialias). the glow is pixelated to the sprite's texels
 * (1/16 block), so it matches the texture
 * the glow is drawn in its own pass after the liquids and the other sprites (LevelRenderer.renderBillboardAuras), blended
 * and without writing depth, so water and sprites behind it still render and show through it
 *
 * the glow is not clipped to the sprite: it gets its own camera facing quad, sized by its radius, so it can reach as far
 * as it likes past the edges of the texture. the texture itself only needs the sprite
 *
 * the blend mode says how the glow mixes with what is behind it (see Blend)
 *
 *   new BillboardTile("lamppost", 8).occupyHeight()
 *       .setAura(BillboardAura.of(1.0F, 0.85F, 0.5F).at(0.0F, 7.5F).radius(3.0F))
 */
public final class BillboardAura {

    /** how the glow combines with the pixels behind it. the ordinal is sent to the shader, keep the order */
    public enum Blend {
        /** plain alpha blending: the glow covers what is behind it, as a coloured film */
        NORMAL,
        /** adds the glow's light to what is behind it: strong and bright, can blow out to white on light surfaces */
        ADD,
        /** like ADD but never goes past white, light surfaces wash out gently and dark ones glow: the best for lamps */
        SCREEN
    }

    public final float red;
    public final float green;
    public final float blue;

    /** centre in blocks from the sprite's pivot (bottom centre): right of it, and up from it */
    private float offsetX = 0.0F;
    private float offsetY = 0.0F;
    private float radius = 2.0F;
    private float strength = 0.9F;
    private float falloff = 1.5F;
    private float opacity = 1.0F;
    private int bands = 4;
    private Blend blend = Blend.SCREEN;
    private float antialias = 1.0F;
    // colour ramp: rim (outermost band) -> base colour -> core (innermost band). null = the base colour
    private float[] rimColor = null;
    private float[] coreColor = null;
    private float squareness = 0.0F;
    private float rays = 0.0F;
    private float grain = 0.0F;

    private BillboardAura(float red, float green, float blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public static BillboardAura of(float red, float green, float blue) {
        return new BillboardAura(red, green, blue);
    }

    /** where the glow is centred, in blocks from the bottom centre of the sprite */
    public BillboardAura at(float right, float up) {
        this.offsetX = right;
        this.offsetY = up;
        return this;
    }

    /** how far it reaches, in blocks */
    public BillboardAura radius(float blocks) {
        this.radius = Math.max(blocks, 0.0F);
        return this;
    }

    /** 0..1, how strong the innermost band is (scales the opacity of every band) */
    public BillboardAura strength(float strength) {
        this.strength = Math.min(Math.max(strength, 0.0F), 1.0F);
        return this;
    }

    /** how fast the glow drops towards the edge: 1 = linear, bigger = a tighter core (the bands follow this curve) */
    public BillboardAura falloff(float falloff) {
        this.falloff = Math.max(falloff, 0.05F);
        return this;
    }

    /** 0..1, how solid the glow is at its strongest (1 = solid). the bands step from clear up to this */
    public BillboardAura opacity(float opacity) {
        this.opacity = Math.min(Math.max(opacity, 0.0F), 1.0F);
        return this;
    }

    /** how many flat steps the glow is cut into, from the edge to the centre (1 = a single flat disc) */
    public BillboardAura bands(int bands) {
        this.bands = Math.min(Math.max(bands, 1), 16);
        return this;
    }

    /** how wide the smooth edge between two bands is, in texels (0 = hard stepped edges, 1 = the border pixels are blended with the next band) */
    public BillboardAura antialias(float pixels) {
        this.antialias = Math.min(Math.max(pixels, 0.0F), 8.0F);
        return this;
    }

    /** how the glow mixes with what is behind it. the opacity scales how much light each band gives */
    public BillboardAura blend(Blend blend) {
        this.blend = blend == null ? Blend.NORMAL : blend;
        return this;
    }

    /** colour of the outermost band. the bands blend from it to the base colour (the one given to of) and on to the core colour */
    public BillboardAura rimColor(float red, float green, float blue) {
        this.rimColor = new float[] {red, green, blue};
        return this;
    }

    /** colour of the innermost band, the hot centre of the light (nearly white for a lamp) */
    public BillboardAura coreColor(float red, float green, float blue) {
        this.coreColor = new float[] {red, green, blue};
        return this;
    }

    /** 0..1, 0 = round glow, 1 = a square one. in between it is an octagon-ish, rounded square like a hand-drawn pixel light */
    public BillboardAura squareness(float squareness) {
        this.squareness = Math.min(Math.max(squareness, 0.0F), 1.0F);
        return this;
    }

    /** 0..1, how strong the one-pixel streaks along the horizontal and vertical axes through the centre are (0 = none) */
    public BillboardAura rays(float rays) {
        this.rays = Math.min(Math.max(rays, 0.0F), 1.0F);
        return this;
    }

    /** 0..1, per-pixel unevenness of the bands, in bands: the borders wobble and the pixels differ slightly, like a painted texture */
    public BillboardAura grain(float grain) {
        this.grain = Math.min(Math.max(grain, 0.0F), 1.0F);
        return this;
    }

    public float[] getRimColor() { return this.rimColor != null ? this.rimColor : new float[] {this.red, this.green, this.blue}; }
    public float[] getCoreColor() { return this.coreColor != null ? this.coreColor : new float[] {this.red, this.green, this.blue}; }
    public float getSquareness() { return this.squareness; }
    public float getRays() { return this.rays; }
    public float getGrain() { return this.grain; }
    public Blend getBlend() { return this.blend; }
    public int getBands() { return this.bands; }
    public float getAntialias() { return this.antialias; }
    public float getOffsetX() { return this.offsetX; }
    public float getOffsetY() { return this.offsetY; }
    public float getRadius()  { return this.radius; }
    public float getStrength() { return this.strength; }
    public float getFalloff() { return this.falloff; }
    public float getOpacity() { return this.opacity; }
}