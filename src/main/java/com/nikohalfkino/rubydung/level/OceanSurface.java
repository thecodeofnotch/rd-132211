package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.Frustum;

import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.GL_TEXTURE2;
import static org.lwjgl.opengl.GL13.GL_TEXTURE3;
import static org.lwjgl.opengl.GL13.GL_TEXTURE4;
import static org.lwjgl.opengl.GL13.glMultiTexCoord2f;
import static org.lwjgl.opengl.GL13.glMultiTexCoord4f;

/**
 * the surface of the ocean: a flat plane of quads, one per wet column, drawn with the liquid shader.
 *
 * the quads are built at y = 0 and the whole thing is moved up to the sea surface with one glTranslatef when it
 * is drawn (to LiquidLayer.getSurfaceHeight(), the set height minus SEA_DIP), so changing the sea height costs nothing and no mesh is rebuilt. what does need a rebuild is which columns are
 * wet, and that only changes when the top cell changes (seaTop), when the ocean is made / loaded / swaps liquid, or when
 * a block / liquid changes at the surface level. the columns are split in Chunk.SIZE x Chunk.SIZE tiles, each its own
 * display list, so a change at the surface only rebuilds the tile(s) around it.
 *
 * the vertex format is the one LiquidShader expects (see liquid.vert): r, g, b = sky light, block light, face shade,
 * texcoord = position inside the cell, a = packed flags / 131072
 *
 * each quad also carries, in texcoord unit 1 (s), the sea height its column first gets water at (columnThreshold + 1). the shader
 * fades the column in over the first OCEAN_FADE_DEPTH blocks of water, so a rising / falling sea doesn't pop a whole contour in at once.
 * everything that isn't the ocean surface must see NO_FADE there (LevelRenderer sets it before the chunk liquids)
 *
 * shapes that only fill PART of a cell (doors, panes, stairs, slabs, mixed / sub tiles, model tiles) are not walls: the shore flags only
 * stand for tiles that cover the whole cell. every partial footprint within SHORE_REACH of the cell (its own box or a neighbour's) is sent
 * instead as a rectangle in cell coordinates (x0, z0, x1, z1) in texcoord units 2..4, and the shader measures the shore from it. units
 * 2..4 must be 0 for everything that isn't the ocean surface, draw() puts them back
 *
 * drawing is split in two: prepare() once per frame (rebuilds dirty tiles, culls, fills one list of display list ids) and draw() once
 * per pass, so the depth pre-pass and the colour pass share the culling and each is a single glCallLists
 */
final class OceanSurface {

    /** the value of the fade texcoord that means "fully faded in at any sea height" */
    static final float NO_FADE = -1.0E4F;

    /** is a rectangle of the map (blocks, x/z) inside the render distance */
    @FunctionalInterface
    interface DistanceTest {
        boolean inRange(double minX, double minZ, double maxX, double maxZ);
    }

    private static final int SIZE = Chunk.SIZE;
    private static final int MAX_REBUILDS_PER_FRAME = 64;
    private static final float ALPHA_RANGE = 131072.0F;

    // packed flags, see liquid.vert
    private static final int FLAG_TOP = 1 << 8;
    private static final int FLAG_SURFACE = 1 << 9;
    private static final int FOAM_FULL = 3 << 10;

    private final Level level;
    private final int tilesX, tilesZ;
    private final int base;
    private final boolean[] dirty;
    private final boolean[] empty;

    // a body of water (columns connected side to side) with fewer columns than this is a pond, it gets no foam
    private static final int MIN_FOAM_BODY = 256;

    // partial footprints near a column (see fillRects)
    private static final float SHORE_REACH = 1.0F;
    private static final int MAX_RECTS = 3;
    private static final double EPS = 1.0E-3;
    private final float[] rects = new float[MAX_RECTS * 4];
    private final float[] rectDist = new float[MAX_RECTS];
    private final List<double[]> boxScratch = new ArrayList<>();

    private final boolean[] wet;           // per column (z * width + x): the surface has a quad here
    private final boolean[] small;         // per column: wet, but part of a body too small for foam
    private final int[] floodQueue;
    private boolean bodiesStale = true;

    // scratch for updateBodies(), kept so a sea that is rising doesn't allocate two level-sized arrays at every block
    private final boolean[] seenScratch;
    private final boolean[] nowScratch;

    // the display list ids of the tiles that are drawn this frame, and the height they are drawn at
    private final IntBuffer visibleLists;
    private float drawHeight;

    private int builtTop = Integer.MIN_VALUE;
    private int builtVersion = -1;

    OceanSurface(Level level) {
        this.level = level;
        this.tilesX = (level.width + SIZE - 1) / SIZE;
        this.tilesZ = (level.height + SIZE - 1) / SIZE;
        int count = this.tilesX * this.tilesZ;
        this.base = glGenLists(count);
        this.dirty = new boolean[count];
        this.empty = new boolean[count];
        this.wet = new boolean[level.width * level.height];
        this.small = new boolean[level.width * level.height];
        this.floodQueue = new int[level.width * level.height];
        this.seenScratch = new boolean[level.width * level.height];
        this.nowScratch = new boolean[level.width * level.height];
        this.visibleLists = BufferUtils.createIntBuffer(count);
        java.util.Arrays.fill(this.dirty, true);
    }

    void dispose() {
        glDeleteLists(this.base, this.tilesX * this.tilesZ);
    }

    // ---- invalidation (called by LevelRenderer's LevelListener methods) ----

    /** a block or liquid changed: it matters if it is at the surface level, and the neighbouring columns' shore flags look at it too */
    void cellChanged(int x, int y, int z) {
        int top = this.level.getLiquids().getSeaTop();
        if (y != top && y != top - 1) {
            return;         // top - 1: a water tile there makes the column beside the surface count as water (see solid)
        }
        if (y == top) {
            refreshWet(x, z, y);
        }
        markAround(x, z);
    }

    /** light changed in a column between minY and maxY: the surface quad bakes the light of its cell */
    void lightChanged(int x, int z, int minY, int maxY) {
        int top = this.level.getLiquids().getSeaTop();
        if (top >= minY && top <= maxY) {
            refreshWet(x, z, top);      // a dug-out floor changes which columns are wet
            markAround(x, z);
        }
    }

    private void refreshWet(int x, int z, int top) {
        if (x < 0 || z < 0 || x >= this.level.width || z >= this.level.height) {
            return;
        }
        int c = z * this.level.width + x;
        boolean now = isWet(this.level.getLiquids(), x, z, top);
        if (this.wet[c] != now) {
            this.wet[c] = now;
            this.bodiesStale = true;
        }
    }

    private void markAround(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = Math.floorDiv(x + dx, SIZE);
                int cz = Math.floorDiv(z + dz, SIZE);
                if (cx >= 0 && cz >= 0 && cx < this.tilesX && cz < this.tilesZ) {
                    this.dirty[cz * this.tilesX + cx] = true;
                }
            }
        }
    }

    // ---- drawing ----

    /**
     * once per frame, before the first pass: rebuilds the dirty tiles (a few per frame) and lists the display lists of the tiles that are
     * in range and in view. nothing is allocated here
     */
    void prepare(Frustum frustum, DistanceTest range) {
        this.visibleLists.clear();

        LiquidLayer liquids = this.level.getLiquids();
        int top = liquids.getSeaTop();
        if (!liquids.hasOcean() || top < 0 || liquids.getOceanLiquid() == null) {
            this.visibleLists.flip();
            return;
        }

        if (top != this.builtTop || liquids.getOceanVersion() != this.builtVersion) {
            this.builtTop = top;
            this.builtVersion = liquids.getOceanVersion();
            java.util.Arrays.fill(this.dirty, true);
            for (int z = 0; z < this.level.height; z++) {
                for (int x = 0; x < this.level.width; x++) {
                    this.wet[z * this.level.width + x] = isWet(liquids, x, z, top);
                }
            }
            this.bodiesStale = true;
        }
        if (this.bodiesStale) {
            this.bodiesStale = false;
            updateBodies();
        }

        float height = liquids.getSurfaceHeight();   // the set height minus LiquidLayer.SEA_DIP
        this.drawHeight = height;
        int budget = MAX_REBUILDS_PER_FRAME;

        for (int tz = 0; tz < this.tilesZ; tz++) {
            for (int tx = 0; tx < this.tilesX; tx++) {
                int i = tz * this.tilesX + tx;
                if (this.dirty[i] && budget > 0) {
                    rebuild(i, tx, tz, top, liquids);
                    budget--;
                }
                if (this.empty[i]) {
                    continue;
                }
                float x0 = tx * SIZE;
                float z0 = tz * SIZE;
                float x1 = Math.min(this.level.width, x0 + SIZE);
                float z1 = Math.min(this.level.height, z0 + SIZE);
                if (range.inRange(x0, z0, x1, z1) && frustum.cubeInFrustum(x0, height - 1.0F, z0, x1, height + 1.0F, z1)) {
                    this.visibleLists.put(this.base + i);
                }
            }
        }
        this.visibleLists.flip();
    }

    /** call with the liquid shader (or the depth program) bound and the state set up. draws what prepare() listed, once per pass */
    void draw() {
        if (this.visibleLists.limit() == 0) {
            return;
        }
        glPushMatrix();
        glTranslatef(0.0F, this.drawHeight, 0.0F);
        glCallLists(this.visibleLists);
        glPopMatrix();
        // the lists leave their last values as the current texcoords: nothing else may see them
        glMultiTexCoord4f(GL_TEXTURE2, 0.0F, 0.0F, 0.0F, 0.0F);
        glMultiTexCoord4f(GL_TEXTURE3, 0.0F, 0.0F, 0.0F, 0.0F);
        glMultiTexCoord4f(GL_TEXTURE4, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    // ---- foam: only on bodies of water big enough to be a sea ----

    private boolean isWet(LiquidLayer liquids, int x, int z, int top) {
        return liquids.columnThreshold(x, z) < top && liquids.isOceanCell(x, top, z);
    }

    /** flood fills the wet columns, marks the ones in a small body, and dirties the tiles whose foam changed */
    private void updateBodies() {
        int w = this.level.width;
        int n = w * this.level.height;
        boolean[] seen = this.seenScratch;
        boolean[] now = this.nowScratch;
        java.util.Arrays.fill(seen, false);
        java.util.Arrays.fill(now, false);
        int[] queue = this.floodQueue;

        for (int start = 0; start < n; start++) {
            if (!this.wet[start] || seen[start]) {
                continue;
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int c = queue[head++];
                int x = c % w;
                int z = c / w;
                if (x > 0 && this.wet[c - 1] && !seen[c - 1]) { seen[c - 1] = true; queue[tail++] = c - 1; }
                if (x < w - 1 && this.wet[c + 1] && !seen[c + 1]) { seen[c + 1] = true; queue[tail++] = c + 1; }
                if (z > 0 && this.wet[c - w] && !seen[c - w]) { seen[c - w] = true; queue[tail++] = c - w; }
                if (c + w < n && this.wet[c + w] && !seen[c + w]) { seen[c + w] = true; queue[tail++] = c + w; }
            }
            if (tail < MIN_FOAM_BODY) {
                for (int k = 0; k < tail; k++) {
                    now[queue[k]] = true;
                }
            }
        }

        for (int c = 0; c < n; c++) {
            if (now[c] != this.small[c]) {
                this.small[c] = now[c];
                this.dirty[(c / w / SIZE) * this.tilesX + (c % w) / SIZE] = true;
            }
        }
    }

    // ---- building ----

    private void rebuild(int i, int tx, int tz, int top, LiquidLayer liquids) {
        this.dirty[i] = false;

        int minX = tx * SIZE;
        int minZ = tz * SIZE;
        int maxX = Math.min(this.level.width, minX + SIZE);
        int maxZ = Math.min(this.level.height, minZ + SIZE);
        int index = liquids.getOceanLiquid().getLiquidIndex();
        // a liquid that gives off light (lava) is fully lit on its own surface, whatever the light map says there
        boolean glow = liquids.getOceanLiquid().emitsLight();
        boolean any = false;

        glNewList(this.base + i, GL_COMPILE);
        glBegin(GL_QUADS);
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                if (liquids.columnThreshold(x, z) >= top || !liquids.isOceanCell(x, top, z)) {
                    continue;
                }
                any = true;

                boolean west = solid(liquids, x - 1, z, top);
                boolean east = solid(liquids, x + 1, z, top);
                boolean north = solid(liquids, x, z - 1, top);
                boolean south = solid(liquids, x, z + 1, top);
                int flags = (west ? 1 : 0) | (east ? 2 : 0) | (north ? 4 : 0) | (south ? 8 : 0);
                if (!west && !north && solid(liquids, x - 1, z - 1, top)) flags |= 1 << 4;
                if (!east && !north && solid(liquids, x + 1, z - 1, top)) flags |= 1 << 5;
                if (!west && !south && solid(liquids, x - 1, z + 1, top)) flags |= 1 << 6;
                if (!east && !south && solid(liquids, x + 1, z + 1, top)) flags |= 1 << 7;
                flags |= FLAG_TOP | FLAG_SURFACE | (index << 12);
                if (!this.small[z * this.level.width + x]) {
                    flags |= FOAM_FULL;
                }

                fillRects(x, z, top);
                glMultiTexCoord4f(GL_TEXTURE2, this.rects[0], this.rects[1], this.rects[2], this.rects[3]);
                glMultiTexCoord4f(GL_TEXTURE3, this.rects[4], this.rects[5], this.rects[6], this.rects[7]);
                glMultiTexCoord4f(GL_TEXTURE4, this.rects[8], this.rects[9], this.rects[10], this.rects[11]);

                // the sea height this column first gets water at: the quad exists once the surface is above the top of its floor
                glMultiTexCoord2f(GL_TEXTURE1, liquids.columnThreshold(x, z) + 1, 0.0F);
                glColor4f(this.level.getSkyLight(x, top, z), glow ? 1.0F : this.level.getBlockLight(x, top, z), 1.0F,
                        flags / ALPHA_RANGE);
                glTexCoord2f(0.0F, 0.0F);
                glVertex3f(x, 0.0F, z);
                glTexCoord2f(1.0F, 0.0F);
                glVertex3f(x + 1, 0.0F, z);
                glTexCoord2f(1.0F, 1.0F);
                glVertex3f(x + 1, 0.0F, z + 1);
                glTexCoord2f(0.0F, 1.0F);
                glVertex3f(x, 0.0F, z + 1);
            }
        }
        glEnd();
        glEndList();

        this.empty[i] = !any;
    }

    // land next to the water at the surface level: a column the sea hasn't reached, or a tile in the surface cell that covers the whole cell
    // and sticks up through the water. partial shapes are not walls, they go through fillRects
    private boolean solid(LiquidLayer liquids, int x, int z, int top) {
        if (x < 0 || z < 0 || x >= this.level.width || z >= this.level.height) {
            return false;
        }
        if (liquids.columnThreshold(x, z) >= top) {
            // dry land, unless the sea has flooded it with real water tiles: those draw their own surface at the same height, and a
            // wall here would put a shore rim on the seam between the two
            return !holdsWater(liquids, x, z, top);
        }
        return coversCell(x, top, z);
    }

    /** a water tile in the surface cell, or in the one under it with nothing in the way above (the sea surface is partway up the top cell) */
    private boolean holdsWater(LiquidLayer liquids, int x, int z, int top) {
        if (liquids.get(x, top, z) != null) {
            return true;
        }
        return top > 0 && liquids.get(x, top - 1, z) != null && liquids.passable(x, top, z);
    }

    /** whether the cell holds something that breaks the surface AND fills the cell's whole footprint (a full block, a top slab, ...) */
    private boolean coversCell(int x, int y, int z) {
        if (LiquidLayer.displacesLiquid(Tile.byId(this.level.getTile(x, y, z)))) {
            return true;
        }
        this.boxScratch.clear();
        surfaceBoxes(x, y, z, this.boxScratch);
        for (double[] b : this.boxScratch) {
            if (b[0] <= x + EPS && b[2] >= x + 1 - EPS && b[1] <= z + EPS && b[3] >= z + 1 - EPS) {
                return true;
            }
        }
        return false;
    }

    /**
     * the footprints (world x/z: minX, minZ, maxX, maxZ) of everything in the cell that breaks the water's surface: any box that reaches the top of the cell
     * (a pane, a door, a top slab, stairs, a fence...), for the host tile and for every sub tile, with the sub tile's offset. a bottom slab doesn't (it sits under
     * the water). the answer can't depend on the sea height, the display lists are not rebuilt when it changes
     */
    private void surfaceBoxes(int x, int y, int z, List<double[]> out) {
        addReaching(Tile.byId(this.level.getTile(x, y, z)), x, y, z, this.level.getData(x, y, z), 0, 0, 0, out);
        if (this.level.getSubTiles().has(x, y, z)) {
            for (SubTile sub : this.level.getSubTiles().at(x, y, z)) {
                if (SubTile.clipOf(sub.data) == SubTile.CLIP_BOTTOM) {
                    continue;   // only fills the lower half of the cell
                }
                addReaching(Tile.byId(sub.tileId), x, y, z, SubTile.baseData(sub.data), sub.ox, sub.oy, sub.oz, out);
            }
        }
    }

    private static void addReaching(Tile tile, int x, int y, int z, int data, float ox, float oy, float oz, List<double[]> out) {
        if (tile == null || tile == Tile.AIR || !tile.isSolid() || tile.isBillboard() || tile instanceof LiquidTile) {
            return;
        }
        List<AABB> boxes = tile.getOccupancyBoxes(x, y, z, data);
        for (int i = 0; i < boxes.size(); i++) {
            AABB b = boxes.get(i);
            if (b.maxY + oy >= y + 1.0 - EPS) {
                out.add(new double[]{b.minX + ox, b.minZ + oz, b.maxX + ox, b.maxZ + oz});
            }
        }
    }

    /**
     * fills this.rects with the (up to MAX_RECTS) closest partial footprints around column (x, z), in that column's cell coordinates (0..1, so a
     * neighbour's box can lie outside that range). full-cell footprints are left out, they are the shore flags. unused rects stay all zero
     */
    private void fillRects(int x, int z, int top) {
        java.util.Arrays.fill(this.rects, 0.0F);
        int count = 0;
        LiquidLayer liquids = this.level.getLiquids();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx, nz = z + dz;
                if (nx < 0 || nz < 0 || nx >= this.level.width || nz >= this.level.height) {
                    continue;
                }
                if (liquids.columnThreshold(nx, nz) >= top) {
                    continue;   // inside the land
                }
                this.boxScratch.clear();
                surfaceBoxes(nx, top, nz, this.boxScratch);

                for (double[] b : this.boxScratch) {
                    float x0 = (float) (b[0] - x), z0 = (float) (b[1] - z);
                    float x1 = (float) (b[2] - x), z1 = (float) (b[3] - z);
                    if (x1 - x0 >= 1.0F - EPS && z1 - z0 >= 1.0F - EPS) {
                        continue;
                    }
                    if (x1 <= x0 || z1 <= z0) {
                        continue;
                    }
                    float gx = Math.max(Math.max(x0 - 1.0F, -x1), 0.0F);
                    float gz = Math.max(Math.max(z0 - 1.0F, -z1), 0.0F);
                    float dist = (float) Math.sqrt(gx * gx + gz * gz);
                    if (dist > SHORE_REACH) {
                        continue;
                    }

                    boolean duplicate = false;
                    for (int k = 0; k < count && !duplicate; k++) {
                        duplicate = this.rects[k * 4] == x0 && this.rects[k * 4 + 1] == z0
                                && this.rects[k * 4 + 2] == x1 && this.rects[k * 4 + 3] == z1;
                    }
                    if (duplicate) {
                        continue;
                    }

                    int slot = count;
                    if (count < MAX_RECTS) {
                        count++;
                    } else {
                        slot = 0;                       // full: replace the farthest, if this one is closer
                        for (int k = 1; k < MAX_RECTS; k++) {
                            if (this.rectDist[k] > this.rectDist[slot]) slot = k;
                        }
                        if (this.rectDist[slot] <= dist) continue;
                    }
                    this.rects[slot * 4] = x0;
                    this.rects[slot * 4 + 1] = z0;
                    this.rects[slot * 4 + 2] = x1;
                    this.rects[slot * 4 + 3] = z1;
                    this.rectDist[slot] = dist;
                }
            }
        }
    }
}