package com.nikohalfkino.rubydung.level.tiles.special;

import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.common.WrappedTile;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LiquidLayer;
import com.nikohalfkino.rubydung.level.SubTile;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.Tessellator;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * common base of every liquid (water, lava, acid, sludge...): a non-solid tile drawn by its own pass
 * with LiquidShader.
 *
 * liquids are NOT in the level's block array: they live in the level's LiquidLayer, so a slab, stair or
 * pane can share a cell with them. a tile that fills its cell replaces the liquid (see
 * LiquidLayer.displacesLiquid). a liquid tile is only the "kind" (look and feel), the layer says where.
 *
 * the texture is textures/block/liquid/NAME.png, a sheet of 16x16 cells like a WrappedTile's. WrappedTile
 * slices it into the atlas for the tile icon, and it is also loaded as a standalone GL texture
 * (GL_REPEAT + GL_NEAREST) that the shader lays over the world (each block shows the cell for its
 * position, the picture repeats every columns x rows blocks) with Quake-style warp.
 *
 * foam / rim rules
 *   - rim (one-pixel shore lip): always shown where the surface touches a solid wall
 *   - foam waves: only when hasFoam is true AND the body of liquid is really big: the cells of the
 *     same liquid connected to this one on its level number at least FOAM_MIN_BODY
 *   - lava, acid and sludge call setNoFoam() to keep the rim without waves
 *
 * packed-alpha encoding (keep in sync with LiquidShader):
 *   bits 0-3   edge mask (−x,+x,−z,+z touch a solid)
 *   bits 4-7   corner mask (diagonals only)
 *   bit  8     top face
 *   bit  9     surface vertex
 *   bits 10-11 foamWeight (0-3)
 *   bits 12-14 liquidIndex (0-7)
 *   bit  15    a pane runs along x through this cell
 *   bit  16    a pane runs along z through this cell
 *   PACK_RANGE = 131072
 */
public class LiquidTile extends WrappedTile {

    /** the shader holds one row of look parameters per liquid */
    public static final int MAX_LIQUIDS = 8;

    /** height of an open surface inside its cell (14/16, like minecraft) */
    public static final float SURFACE_HEIGHT = 14.0F / 16.0F;

    private static final float FACE_INSET = 0.002F;

    /** width and height in pixels of one cell of a texture sheet (one cell = one block) */
    public static final int CELL_SIZE = 16;

    /** folder (under textures/block/) the liquid textures live in */
    public static final String TEXTURE_FOLDER = "liquid/";

    /** a body of liquid needs this many connected cells on one level before foam forms (~14x14) */
    public static final int FOAM_MIN_BODY = 200;

    // --- packed-alpha bit layout ---
    private static final float PACK_RANGE     = 131072.0F;
    private static final int FLAG_TOP_FACE       = 1 << 8;
    private static final int FLAG_SURFACE_VERTEX = 1 << 9;
    private static final int FOAM_WEIGHT_SHIFT   = 10;   // 2 bits → 0-3
    private static final int INDEX_SHIFT         = 12;   // 3 bits → 0-7
    private static final int PANE_SHIFT          = 15;   // 2 bits: pane along x, pane along z
    private static final float SHORE_EPS         = 0.001F;

    private static final List<LiquidTile> ALL = new ArrayList<>();

    private final int liquidIndex;

    // path to the sheet image used for the standalone tiled GL texture
    private final String fluidSheet;
    // the sheet is laid over the WORLD like WrappedTile: columns x rows cells of 16px, every block shows the
    // cell for its position and the picture repeats every `columns` blocks sideways and `rows` blocks up
    private final int sheetColumns;
    private final int sheetRows;
    // GL texture id, 0 = not yet loaded (OpenGL context may not be ready at construction time)
    private int fluidTextureId = 0;
    // the sheet's pixels (ARGB), kept until the GL texture is made, then dropped
    private int[] sheetPixels;
    private int sheetWidth, sheetHeight;

    // look
    private float red = 0.10F, green = 0.30F, blue = 0.80F, opacity = 0.7F;
    private float foamRed = 1.0F, foamGreen = 1.0F, foamBlue = 1.0F, foamAmount = 1.0F;
    private float warp = 0.125F;
    private float flowSpeed = 1.0F;
    private float waveHeight = 0.03F;

    public static final int MAX_DEPTH_BANDS = 8;

    // what blocks look like under this liquid, 1..MAX_DEPTH_BANDS rgb bands from shallow to deep; null = derived from the liquid's colour
    private float[][] depthPalette;

    public LiquidTile setDepthPalette(float[]... bands) {
        if (bands.length < 1 || bands.length > MAX_DEPTH_BANDS) {
            throw new IllegalArgumentException("need 1.." + MAX_DEPTH_BANDS + " bands");
        }
        this.depthPalette = bands;
        return this;
    }

    public float[][] getDepthPalette() {
        return this.depthPalette;
    }

    /** when false the foam-wave animation is suppressed (rim/lip still drawn) */
    private boolean hasFoam = true;

    // gameplay
    private float viscosity = 0.6F;

    // a big enough body of this liquid is endless: see LiquidSimulator
    private boolean infinite = false;

    /** the texture is textures/block/liquid/NAME.png */
    public LiquidTile(String name) {
        this(name, TEXTURE_FOLDER + name);
    }

    public LiquidTile(String name, String textureName) {
        super(name, textureName);   // WrappedTile loads the sheet and slices it into the atlas

        if (ALL.size() >= MAX_LIQUIDS) {
            throw new IllegalStateException("Too many liquids – shader table holds " + MAX_LIQUIDS);
        }
        this.liquidIndex = ALL.size();
        ALL.add(this);

        // resolve the sheet path the same way WrappedTile does (strip any @col,row suffix)
        String path = Tile.texturePath(textureName);
        int at = path.indexOf('@');
        this.fluidSheet = at < 0 ? path : path.substring(0, at);

        // read the PNG ourselves, at its real size: the grid is its size in 16px cells
        readSheet();
        this.sheetColumns = Math.max(1, this.sheetWidth / CELL_SIZE);
        this.sheetRows = Math.max(1, this.sheetHeight / CELL_SIZE);

        setSolid(false);
        setTransparent(true);
        setCastsShadow(false);
    }

    // -------------------------------------------------------------------------
    // look setters

    /** body colour (0-1 per channel) and opacity (1 = fully opaque, like lava) */
    public LiquidTile setColor(float r, float g, float b, float opacity) {
        this.red = r; this.green = g; this.blue = b; this.opacity = opacity;
        return this;
    }

    /** colour of the shore foam/rim and how much of it forms (0 = none at all) */
    public LiquidTile setFoam(float r, float g, float b, float amount) {
        this.foamRed = r; this.foamGreen = g; this.foamBlue = b; this.foamAmount = amount;
        return this;
    }

    /** suppress the foam-wave animation (rim edge is still drawn at shores) */
    public LiquidTile setNoFoam() {
        this.hasFoam = false;
        return this;
    }

    /** strength of the Quake-style turbulence (0 = still, 0.125 = water default) */
    public LiquidTile setWarp(float warp) { this.warp = warp; return this; }

    /** speed of all animation: turbulence, foam, bobbing */
    public LiquidTile setFlowSpeed(float s) { this.flowSpeed = s; return this; }

    /** vertical surface bob amplitude in blocks */
    public LiquidTile setWaveHeight(float h) { this.waveHeight = h; return this; }

    // -------------------------------------------------------------------------
    // gameplay setters (it's a creative sandbox: liquids never hurt)

    public LiquidTile setViscosity(float v) { this.viscosity = v; return this; }

    /** a body of this liquid that is big enough (LiquidSimulator.getInfiniteBodySize) never runs dry and absorbs extra liquid */
    public LiquidTile setInfinite(boolean infinite) { this.infinite = infinite; return this; }

    // -------------------------------------------------------------------------
    // getters

    public float getRed()           { return red; }
    public float getGreen()         { return green; }
    public float getBlue()          { return blue; }
    public float getOpacity()       { return opacity; }
    public float getFoamRed()       { return foamRed; }
    public float getFoamGreen()     { return foamGreen; }
    public float getFoamBlue()      { return foamBlue; }
    public float getFoamAmount()    { return hasFoam ? foamAmount : 0.0F; }
    public float getWarp()          { return warp; }
    public float getFlowSpeed()     { return flowSpeed; }
    public float getWaveHeight()    { return waveHeight; }
    public float getViscosity()     { return viscosity; }
    public boolean isInfinite()     { return infinite; }
    public int   getSheetColumns()  { return sheetColumns; }
    public int   getSheetRows()     { return sheetRows; }
    public int   getLiquidIndex()   { return liquidIndex; }

    /**
     * GL texture id for this liquid's sheet, loaded lazily on first call (GL context must be ready).
     * Uses GL_REPEAT + GL_NEAREST for pixelated tiling.
     */
    public int getFluidTextureId() {
        if (this.fluidTextureId == 0) {
            this.fluidTextureId = uploadSheet();
        }
        return this.fluidTextureId;
    }

    // loads the sheet PNG at full size (a 16x16 magenta square if it can't be read)
    private void readSheet() {
        try (InputStream in = LiquidTile.class.getResourceAsStream(this.fluidSheet)) {
            BufferedImage image = in == null ? null : ImageIO.read(in);
            if (image != null) {
                this.sheetWidth = image.getWidth();
                this.sheetHeight = image.getHeight();
                this.sheetPixels = image.getRGB(0, 0, this.sheetWidth, this.sheetHeight, null, 0, this.sheetWidth);
                return;
            }
        } catch (IOException e) {
            System.err.println("[LiquidTile] could not read " + this.fluidSheet + ": " + e);
        }
        this.sheetWidth = CELL_SIZE;
        this.sheetHeight = CELL_SIZE;
        this.sheetPixels = new int[CELL_SIZE * CELL_SIZE];
        java.util.Arrays.fill(this.sheetPixels, 0xFFFF00FF);
    }

    // the whole sheet as one GL texture, GL_REPEAT + GL_NEAREST, never resized
    private int uploadSheet() {
        ByteBuffer buffer = BufferUtils.createByteBuffer(this.sheetWidth * this.sheetHeight * 4);
        for (int argb : this.sheetPixels) {
            buffer.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >> 24));
        }
        buffer.flip();

        int id = GL11.glGenTextures();
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.sheetWidth, this.sheetHeight, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);

        this.sheetPixels = null;
        return id;
    }

    /** every liquid in shader-table order */
    public static List<LiquidTile> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** the liquid with this shader-table index */
    public static LiquidTile byIndex(int index) {
        return ALL.get(index);
    }

    /** the liquid at cell (x,y,z), null if none */
    public static LiquidTile at(Level level, int x, int y, int z) {
        return level.getLiquids().get(x, y, z);
    }

    /** world Y of the liquid surface for swimming / buoyancy code (ignores visual bob) */
    public float getSurfaceY(Level level, int x, int y, int z) {
        return y + cellHeight(level, x, y, z);
    }

    // -------------------------------------------------------------------------
    // tile behaviour overrides

    @Override
    public boolean occludesNeighbours() { return false; }

    // -------------------------------------------------------------------------
    // mesh building

    // full cell: more liquid above, or a full block above closing a cell that holds a whole block of liquid
    private boolean isFull(Level level, int x, int y, int z) {
        LiquidLayer layer = level.getLiquids();
        if (layer.has(x, y + 1, z)) {
            return true;
        }
        return layer.getFill(x, y, z) >= LiquidLayer.MAX_FILL && hidesFaces(Tile.byId(level.getTile(x, y + 1, z)));
    }

    // height of the liquid in the cell: a whole block when full, else its fill of the open surface height
    private float cellHeight(Level level, int x, int y, int z) {
        if (isFull(level, x, y, z)) {
            return 1.0F;
        }
        int fill = Math.max(1, level.getLiquids().getFill(x, y, z));
        return SURFACE_HEIGHT * fill / (float) LiquidLayer.MAX_FILL;
    }

    private static boolean hidesFaces(Tile tile) {
        return tile != null && tile != Tile.AIR && tile.isSolid() && tile.occludesNeighbours()
                && !tile.isTransparent() && !tile.isBillboard();
    }

    private static boolean isWall(Tile tile) {
        return tile != null && tile != Tile.AIR && tile.isSolid() && tile.occludesNeighbours()
                && !tile.isBillboard();
    }

    // height of the surface at one corner of the cell: the four cells around the corner are averaged, so
    // neighbouring cells share the same corner height and the surface is continuous. a whole block counts
    // 10x (a full cell next to a puddle stays flat), air counts as height 0 (the edge of a puddle slopes
    // down), walls don't count. any full cell around the corner makes it a full 1.0
    private float cornerHeight(Level level, int x, int y, int z, int cx, int cz) {
        float sum = 0.0F;
        float weight = 0.0F;
        for (int ox = cx - 1; ox <= cx; ox++) {
            for (int oz = cz - 1; oz <= cz; oz++) {
                int px = x + ox, pz = z + oz;
                if (level.getLiquids().get(px, y, pz) == this) {
                    if (isFull(level, px, y, pz)) {
                        return 1.0F;
                    }
                    float w = level.getLiquids().getFill(px, y, pz) >= LiquidLayer.MAX_FILL ? 10.0F : 1.0F;
                    sum += cellHeight(level, px, y, pz) * w;
                    weight += w;
                } else if (!isWall(Tile.byId(level.getTile(px, y, pz)))) {
                    weight += 1.0F;
                }
            }
        }
        return weight <= 0.0F ? SURFACE_HEIGHT : sum / weight;
    }

    private static boolean spansSurface(AABB box, float surface) {
        return box.minY < surface - SHORE_EPS && box.maxY > surface + SHORE_EPS;
    }

    // everything standing in the cell: the tile of the cell and the tiles sharing it (a pane in a slab's cell...)
    private static List<AABB> cellBoxes(Level level, int x, int y, int z) {
        List<AABB> boxes = new ArrayList<>();
        Tile host = Tile.byId(level.getTile(x, y, z));
        if (host != null && host != Tile.AIR && !(host instanceof LiquidTile) && !host.isBillboard()) {
            boxes.addAll(host.getOccupancyBoxes(x, y, z, level.getData(x, y, z)));
        }
        if (level.getSubTiles().has(x, y, z)) {
            for (SubTile sub : level.getSubTiles().at(x, y, z)) {
                Tile tile = Tile.byId(sub.tileId);
                if (tile != null && !tile.isBillboard()) {
                    boxes.addAll(tile.getOccupancyBoxes(x, y, z, SubTile.baseData(sub.data)));
                }
            }
        }
        return boxes;
    }

    // whether the cell is walled off at the height of the liquid surface: a full block, or a partial tile
    // (top slab, grate...) with a part reaching across the surface that covers the whole cell
    private boolean blocksSurface(Level level, int x, int y, int z, float surface) {
        if (isWall(Tile.byId(level.getTile(x, y, z)))) {
            return true;
        }
        for (AABB box : cellBoxes(level, x, y, z)) {
            if (spansSurface(box, surface)
                    && box.minX <= x + SHORE_EPS && box.maxX >= x + 1 - SHORE_EPS
                    && box.minZ <= z + SHORE_EPS && box.maxZ >= z + 1 - SHORE_EPS) {
                return true;
            }
        }
        return false;
    }

    // panes (metal bars, framed glass) are flat planes through the middle of a cell, as long as the cell is wide:
    // bit 0 = one runs along x, bit 1 = one runs along z (both = a cross). the shader draws their 2px footprint
    // and puts foam around it. found from the thin, cell-long boxes of whatever stands in the cell
    private int paneMask(Level level, int x, int y, int z, float surface) {
        int mask = 0;
        for (AABB box : cellBoxes(level, x, y, z)) {
            if (!spansSurface(box, surface)) {
                continue;
            }
            double dx = box.maxX - box.minX;
            double dz = box.maxZ - box.minZ;
            if (dz <= 0.26 && dx >= 0.74) {
                mask |= 1;
            } else if (dx <= 0.26 && dz >= 0.74) {
                mask |= 2;
            }
        }
        return mask;
    }

    private int shoreMask(Level level, int x, int y, int z, float surface) {
        boolean negX = blocksSurface(level, x - 1, y, z, surface);
        boolean posX = blocksSurface(level, x + 1, y, z, surface);
        boolean negZ = blocksSurface(level, x, y, z - 1, surface);
        boolean posZ = blocksSurface(level, x, y, z + 1, surface);

        int mask = (negX ? 1 : 0) | (posX ? 2 : 0) | (negZ ? 4 : 0) | (posZ ? 8 : 0);

        if (!negX && !negZ && blocksSurface(level, x - 1, y, z - 1, surface)) mask |= 16;
        if (!posX && !negZ && blocksSurface(level, x + 1, y, z - 1, surface)) mask |= 32;
        if (!negX && !posZ && blocksSurface(level, x - 1, y, z + 1, surface)) mask |= 64;
        if (!posX && !posZ && blocksSurface(level, x + 1, y, z + 1, surface)) mask |= 128;
        return mask;
    }

    /**
     * Foam weight for this cell: 3 if it belongs to a really big body of this liquid, else 0 (no foam).
     * "Body" = same-liquid cells connected (4-way) on this cell's level. The search stops as soon as it has
     * counted FOAM_MIN_BODY cells, so small ponds are cheap to rule out and big lakes stop early too.
     * Always 0 when hasFoam is false.
     */
    private int computeFoamWeight(Level level, int x, int y, int z) {
        if (!this.hasFoam) {
            return 0;
        }
        return isBigBody(level.getLiquids(), x, y, z) ? 3 : 0;
    }

    private boolean isBigBody(LiquidLayer layer, int x, int y, int z) {
        int[] queueX = new int[FOAM_MIN_BODY];
        int[] queueZ = new int[FOAM_MIN_BODY];
        Set<Long> seen = new HashSet<>();

        int head = 0, tail = 0;
        queueX[tail] = x;
        queueZ[tail++] = z;
        seen.add(columnKey(x, z));

        while (head < tail) {
            int cx = queueX[head];
            int cz = queueZ[head++];
            for (int[] step : STEPS) {
                int nx = cx + step[0];
                int nz = cz + step[1];
                if (layer.get(nx, y, nz) != this || !seen.add(columnKey(nx, nz))) {
                    continue;
                }
                queueX[tail] = nx;
                queueZ[tail++] = nz;
                if (tail >= FOAM_MIN_BODY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private float pack(int flags, int foamWeight, int pane) {
        int packed = (pane << PANE_SHIFT) | (this.liquidIndex << INDEX_SHIFT) | (foamWeight << FOAM_WEIGHT_SHIFT) | flags;
        return packed / PACK_RANGE;
    }

    // 0 = no face (hidden by a full block, or more of the same liquid), 1 = open air, 2 = something partial
    // or another liquid touches it, so the face is pulled in a hair to stay out of its way
    private int faceMode(Level level, Tile block, int nx, int ny, int nz) {
        if (hidesFaces(block)) return 0;
        LiquidTile other = level.getLiquids().get(nx, ny, nz);
        if (other == this) return 0;
        if (other != null) return 2;
        if (block == null || block == Tile.AIR) return 1;
        return 2;
    }

    /**
     * Builds the liquid geometry for cell (x,y,z) into the tessellator.
     * Called by Chunk for the liquid layer; the block sharing this cell (a slab, say) is already
     * drawn in terrain layers 0/1, so liquids are fully independent.
     */
    public void renderLiquid(Tessellator tessellator, Level level, int x, int y, int z) {
        boolean emissive = emitsLight();
        boolean smooth   = level.hasLightSources();

        float[] heights = new float[4];
        for (int cz = 0; cz < 2; cz++) {
            for (int cx = 0; cx < 2; cx++) {
                heights[cx + cz * 2] = cornerHeight(level, x, y, z, cx, cz);
            }
        }

        int foamWeight = computeFoamWeight(level, x, y, z);

        float[] block = new float[4];

        for (int face = 0; face < 6; face++) {
            int[] normal = FACE_NORMALS[face];
            int nx = x + normal[0], ny = y + normal[1], nz = z + normal[2];

            Tile neighbour = Tile.byId(level.getTile(nx, ny, nz));   // the block there (liquid is in the layer)
            int mode = faceMode(level, neighbour, nx, ny, nz);
            // a cell that isn't full has its surface below the block above it, so that surface still shows
            if (mode == 0 && face == FACE_TOP && !isFull(level, x, y, z)) mode = 1;
            if (mode == 0) continue;
            float inset = mode == 2 ? FACE_INSET : 0.0F;

            int lx = nx, ly = ny, lz = nz;
            if (neighbour != null && neighbour.isSolid() && neighbour.occludesNeighbours()) {
                lx = x; ly = y; lz = z;
            }

            int[][] vertices = FACE_VERTICES[face];
            float shade = emissive ? 1.0F : FACE_SHADE[face];
            float sky   = emissive ? 1.0F : level.getSkyLight(lx, ly, lz);

            for (int v = 0; v < 4; v++) {
                block[v] = 0.0F;
                if (emissive) {
                    block[v] = 1.0F;
                } else if (smooth) {
                    int sx = normal[0] == 0 ? vertices[v][0] * 2 - 1 : 0;
                    int sy = normal[1] == 0 ? vertices[v][1] * 2 - 1 : 0;
                    int sz = normal[2] == 0 ? vertices[v][2] * 2 - 1 : 0;
                    block[v] = cornerBlockLight(level, lx, ly, lz, sx, sy, sz);
                }
            }

            boolean top   = face == FACE_TOP;
            // top and bottom are both horizontal: the shader lays the texture over XZ for them (not unrolled
            // along a wall, which stretched the bottom into streaks). only the top gets a shore
            boolean flat  = top || face == FACE_BOTTOM;
            float surface = y + cellHeight(level, x, y, z);
            int shore     = top ? shoreMask(level, x, y, z, surface) : 0;
            int pane      = top ? paneMask(level, x, y, z, surface) : 0;

            for (int v = 0; v < 4; v++) {
                int cx = vertices[v][0];
                int cy = vertices[v][1];
                int cz = vertices[v][2];

                float py = cy == 1 ? heights[cx + cz * 2] : 0.0F;

                int flags = shore;
                if (flat)  flags |= FLAG_TOP_FACE;
                if (cy == 1 && py < 1.0F) flags |= FLAG_SURFACE_VERTEX;   // a full corner doesn't bob, it would open a gap

                tessellator.color(sky, block[v], shade, pack(flags, foamWeight, pane));
                tessellator.texture(cx, cz);
                tessellator.vertex(x + cx - normal[0] * inset,
                        y + py  - normal[1] * inset,
                        z + cz  - normal[2] * inset);
            }
        }
    }
}