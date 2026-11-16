package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.LightEmitter;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.entity.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class LevelLighting {

    private static final int LIGHT_SCRATCH_SIZE =
            (2 * Level.MAX_LIGHT_RADIUS + 1)
                    * (2 * Level.MAX_LIGHT_RADIUS + 1)
                    * (2 * Level.MAX_LIGHT_RADIUS + 1);

    // columns around a shadowed cell to look for open sky (2 = 5x5), bigger = wider open-air fade but (2R+1)^2 lookups per shadowed sample
    private static final int OPEN_SKY_RADIUS = 2;

    // how much of a shadow's depth is filled back in when every surrounding column is open to the sky (0 = never fades, 1 = vanishes), no open columns = full darkness
    private static final float OPEN_SHADOW_LIFT = 0.6F;

    // calcLightDepths regions bigger than this many columns (world generation etc.) notify in bulk
    private static final long BULK_COLUMN_THRESHOLD = 256;

    private static final int[][] LIGHT_DIRECTIONS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    private static final class LightSource {
        final int x, y, z;
        final float radius;

        // the cell of the tile that gave this light (not always where the light is: an emitter may be offset, or sit on the
        // top cell of a tall tile), so the light can be found again when that cell changes
        final int ownerX, ownerY, ownerZ;

        // a cone: unit direction and the cosines of the angles where it is at full strength and where it has faded out
        final boolean cone;
        final float dirX, dirY, dirZ;
        final float cosOuter, cosInner;

        // how the brightness drops with the distance (see LightEmitter.falloff), and its multiplier
        final float falloff, intensity;

        LightSource(int x, int y, int z, float radius) {
            this(x, y, z, x, y, z, radius, null);
        }

        LightSource(int x, int y, int z, int ownerX, int ownerY, int ownerZ, float radius, LightEmitter emitter) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.ownerX = ownerX;
            this.ownerY = ownerY;
            this.ownerZ = ownerZ;
            // the scratch arrays are sized for MAX_LIGHT_RADIUS, a bigger radius would run off their end
            this.radius = Math.min(radius, (float) Level.MAX_LIGHT_RADIUS);
            this.falloff = emitter != null ? emitter.getFalloff() : 2.0F;
            this.intensity = emitter != null ? emitter.getIntensity() : 1.0F;
            this.cone = emitter != null && emitter.isCone();
            this.dirX = this.cone ? emitter.getDirX() : 0.0F;
            this.dirY = this.cone ? emitter.getDirY() : 0.0F;
            this.dirZ = this.cone ? emitter.getDirZ() : 0.0F;
            this.cosOuter = this.cone ? emitter.getCosOuter() : -1.0F;
            this.cosInner = this.cone ? emitter.getCosInner() : 1.0F;
        }

        /** how much of its light reaches the cell (dx, dy, dz) away from it: 1 in a ball, along the cone's line 1 and 0 outside of it */
        float coneFactor(int dx, int dy, int dz, float distance) {
            if (!this.cone || distance <= 0.0F) {
                return 1.0F;
            }
            float cos = (dx * this.dirX + dy * this.dirY + dz * this.dirZ) / distance;
            if (cos >= this.cosInner) {
                return 1.0F;
            }
            if (cos <= this.cosOuter) {
                return 0.0F;
            }
            float t = (cos - this.cosOuter) / (this.cosInner - this.cosOuter);
            return t * t * (3.0F - 2.0F * t);
        }

        /** the brightness 0..1 at 1 - distance / radius = t, before the cone: t squared unless the emitter asks for another curve */
        float shape(float t) {
            if (this.falloff == 2.0F) {
                return t * t;
            }
            return (float) Math.pow(t, this.falloff);
        }

        int cubeRadius() {
            return (int) Math.ceil(this.radius);
        }
    }

    private final Level level;

    final int[] lightDepths;
    private final byte[] blockLight;
    private final ArrayList<LightSource> lightSources = new ArrayList<>();

    private final boolean[] lightVisited = new boolean[LIGHT_SCRATCH_SIZE];
    private final int[] lightQueue    = new int[LIGHT_SCRATCH_SIZE];

    private float   skyAmbient = 1.0F;

    LevelLighting(Level level) {
        this.level      = level;
        this.lightDepths = new int[level.width * level.height];
        this.blockLight  = new byte[level.width * level.height * level.depth];
    }

    void setSkyAmbient(float ambient) {
        this.skyAmbient = ambient;
    }

    float getSkyAmbient() {
        return this.skyAmbient;
    }

    void calcLightDepths(int minX, int minZ, int maxX, int maxZ) {
        boolean bulk = (long) maxX * maxZ > BULK_COLUMN_THRESHOLD;
        int unionLo = Integer.MAX_VALUE;
        int unionHi = Integer.MIN_VALUE;

        for (int x = minX; x < minX + maxX; x++) {
            for (int z = minZ; z < minZ + maxZ; z++) {
                int prevDepth = this.lightDepths[x + z * this.level.width];

                int depth = this.level.depth - 1;
                while (depth > 0 && !isLightBlocker(x, depth, z)) {
                    depth--;
                }

                this.lightDepths[x + z * this.level.width] = depth;

                // Force notification on targeted single-column updates even if depth didn't change (underground/cave edits)
                boolean targeted = (maxX <= 2 && maxZ <= 2);
                if (prevDepth != depth || targeted) {
                    int lo = prevDepth == depth ? 0 : Math.min(prevDepth, depth);
                    int hi = prevDepth == depth ? this.level.depth - 1 : Math.max(prevDepth, depth);
                    notifyColumn(x, z, lo, hi);

                    if (bulk) {
                        unionLo = Math.min(unionLo, lo);
                        unionHi = Math.max(unionHi, hi);
                    } else {
                        notifyOpenSkyNeighbours(x, z, lo, hi);
                    }
                }
            }
        }

        // Large updates: rather than notifying 25 columns per changed column, notify the whole
        // region (plus the open-sky margin around it) once, over the combined height range.
        if (bulk && unionLo <= unionHi) {
            int r = OPEN_SKY_RADIUS;
            for (int x = Math.max(0, minX - r); x < Math.min(this.level.width, minX + maxX + r); x++) {
                for (int z = Math.max(0, minZ - r); z < Math.min(this.level.height, minZ + maxZ + r); z++) {
                    notifyColumn(x, z, unionLo, unionHi);
                }
            }
        }
    }

    private void notifyColumn(int x, int z, int minY, int maxY) {
        for (LevelListener l : this.level.levelListeners) {
            l.lightColumnChanged(x, z, minY, maxY);
        }
    }

    // a column's sky exposure changed, so shadowed cells within OPEN_SKY_RADIUS columns need re-lighting too (the column itself is already notified)
    private void notifyOpenSkyNeighbours(int x, int z, int minY, int maxY) {
        int r = OPEN_SKY_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if ((dx == 0 && dz == 0)
                        || nx < 0 || nz < 0 || nx >= this.level.width || nz >= this.level.height) {
                    continue;
                }
                notifyColumn(nx, nz, minY, maxY);
            }
        }
    }

    boolean isLightBlocker(int x, int y, int z) {
        Tile tile = Tile.byId(this.level.getTile(x, y, z));
        if (tile != null && tile.castsShadow()) {
            return true;
        }
        return this.level.getSubTiles().castsShadowAt(x, y, z);
    }

    float getSkyLight(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.level.width || y >= this.level.depth || z >= this.level.height) {
            return 1.0F;
        }

        float column = getColumnSkyLight(x, y, z);
        if (column >= 1.0F) {
            return 1.0F;
        }

        // Shadowed: sky light still scatters in sideways from open columns nearby, so the more of the
        // surrounding columns are open to the sky, the more of the shadow is filled back in.
        float lift = OPEN_SHADOW_LIFT * getSkyOpenness(x, y, z);
        return column + (1.0F - column) * lift;
    }

    // fraction (0..1) of the columns around this one with a clear view of the sky down to height y, outside the world counts as open, the column itself isn't counted
    private float getSkyOpenness(int x, int y, int z) {
        int r = OPEN_SKY_RADIUS;
        int width = this.level.width;
        int length = this.level.height;

        int open = 0;
        int total = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                total++;

                int nx = x + dx;
                int nz = z + dz;
                // lightDepths holds the height of each column's topmost light blocker.
                if (nx < 0 || nz < 0 || nx >= width || nz >= length
                        || (this.lightDepths[nx + nz * width] <= y && isSidewaysPathClear(x, y, z, dx, dz))) {
                    open++;
                }
            }
        }
        return open / (float) total;
    }

    // whether sky light can scatter sideways from the column dx, dz away to this cell: no light-blocking cell on the straight line between them
    private boolean isSidewaysPathClear(int x, int y, int z, int dx, int dz) {
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int i = 1; i <= steps; i++) {
            int cx = x + Math.round(dx * i / (float) steps);
            int cz = z + Math.round(dz * i / (float) steps);
            if (cx < 0 || cz < 0 || cx >= this.level.width || cz >= this.level.height) {
                return true;
            }
            if (blocksBlockLight(cx, y, cz)) {
                return false;
            }
        }
        return true;
    }

    // sky light from this cell's own column only, ignoring neighbours
    private float getColumnSkyLight(int x, int y, int z) {
        if (shadowCoversOwnCell(x, y, z)) {
            return Level.SHADOW_DARKEST;
        }

        int maxDistance = Math.min(Level.SHADOW_FADE_DISTANCE, this.level.depth - 1 - y);

        MultiTileInstance self = this.level.getMultiTile(x, y, z);

        for (int distance = 1; distance <= maxDistance; distance++) {
            if (isLightBlocker(x, y + distance, z)
                    && (self == null || !self.contains(x, y + distance, z))) {
                float t = (distance - 1) / (float) Level.SHADOW_FADE_DISTANCE;
                return Level.SHADOW_DARKEST + (1.0F - Level.SHADOW_DARKEST) * t;
            }
        }

        return 1.0F;
    }

    private boolean shadowCoversOwnCell(int x, int y, int z) {
        int id = this.level.getTile(x, y, z);
        if (id == 0 && this.level.getSubTiles().isEmpty()) {
            return false;
        }

        Tile host = Tile.byId(id);
        if (host != null && host != Tile.AIR && !host.occludesNeighbours() && host.castsShadow()
                && !host.isBillboard() && !host.isMultiTile()) {
            for (AABB b : host.getOccupancyBoxes(x, y, z, this.level.getData(x, y, z))) {
                if (coversTopOfCell(b, y)) {
                    return true;
                }
            }
        }
        for (SubTile subTile : this.level.getSubTiles().at(x, y, z)) {
            Tile tile = Tile.byId(subTile.tileId);
            if (tile != null && tile.castsShadow()) {
                for (AABB b : this.level.getSubTiles().occupancy(subTile, x, y, z)) {
                    if (coversTopOfCell(b, y)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean coversTopOfCell(AABB b, int y) {
        return b.maxY >= y + 1.0 - SubTiles.EPSILON
                && (b.maxX - b.minX) * (b.maxZ - b.minZ) >= 0.5;
    }

    float getBillboardDrop(int x, int y, int z) {
        if (y <= 0) {
            return 0.0F;
        }
        int belowId = this.level.getTile(x, y - 1, z);
        if (belowId == 0 && !this.level.getSubTiles().has(x, y - 1, z)) {
            return 0.0F;
        }
        Tile below = Tile.byId(belowId);
        if (below != null && below.occludesNeighbours() && !this.level.getSubTiles().has(x, y - 1, z)) {
            return 0.0F;
        }

        double centreX = x + 0.5;
        double centreZ = z + 0.5;
        double top = -1.0;
        for (AABB b : this.level.getSubTiles().cellOccupancy(x, y - 1, z)) {
            if (centreX > b.minX && centreX < b.maxX && centreZ > b.minZ && centreZ < b.maxZ) {
                top = Math.max(top, b.maxY);
            }
        }
        if (top < 0.0) {
            return 0.0F;
        }
        double relative = top - (y - 1);
        return relative > 0.0 && relative < 1.0 - SubTiles.EPSILON ? (float) (1.0 - relative) : 0.0F;
    }

    float getBrightness(int x, int y, int z) {
        return getSkyLight(x, y, z) * this.skyAmbient;
    }

    boolean hasLightSources() {
        return !this.lightSources.isEmpty();
    }

    float getBlockLight(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.level.width || y >= this.level.depth || z >= this.level.height) {
            return 0.0F;
        }
        int index = (y * this.level.height + z) * this.level.width + x;
        return (this.blockLight[index] & 0xFF) / 255.0F;
    }

    boolean blocksBlockLight(int x, int y, int z) {
        Tile tile = Tile.byId(this.level.getTile(x, y, z));
        return tile != null && tile.blocksLight();
    }

    void rebuildBlockLight() {
        Arrays.fill(this.blockLight, (byte) 0);
        this.lightSources.clear();

        // looking through every cell for light sources is read only, so each layer is searched on its own core.
        // the layers are joined in order afterwards, so the sources end up in the same order as before
        List<List<LightSource>> found = new ArrayList<>(Collections.nCopies(this.level.depth, (List<LightSource>) null));
        java.util.stream.IntStream.range(0, this.level.depth).parallel().forEach(y -> {
            List<LightSource> layer = null;
            for (int z = 0; z < this.level.height; z++) {
                for (int x = 0; x < this.level.width; x++) {
                    int id = this.level.getTile(x, y, z);
                    if (id == 0) {
                        continue;
                    }
                    Tile tile = Tile.byId(id);
                    // every cell of a multi-block is looked at: which of them give light is up to the tile's emitters
                    // (EVERY_CELL ones in each, TOP ones only in the topmost...)
                    if (tile != null && tile.emitsLight()) {
                        if (layer == null) {
                            layer = new ArrayList<>();
                        }
                        addSourcesOf(layer, tile, x, y, z);
                    }
                }
            }
            found.set(y, layer);
        });
        for (List<LightSource> layer : found) {
            if (layer != null) {
                this.lightSources.addAll(layer);
            }
        }

        for (LightSource source : this.lightSources) {
            spreadLight(source);
        }
    }

    /** the lights the tile at (x, y, z) gives off from this cell, added to out. read only, so it may run on several threads */
    private void addSourcesOf(List<LightSource> out, Tile tile, int x, int y, int z) {
        for (LightEmitter emitter : tile.getLightEmitters()) {
            switch (emitter.getAnchor()) {
                case TOP:
                    // another cell of the same tile above: not the top
                    if (this.level.getTile(x, y + 1, z) == tile.id) {
                        continue;
                    }
                    break;
                case BOTTOM:
                    if (y > 0 && this.level.getTile(x, y - 1, z) == tile.id) {
                        continue;
                    }
                    break;
                default:
                    break;
            }

            int sx = x + emitter.getOffsetX();
            int sy = y + emitter.getOffsetY();
            int sz = z + emitter.getOffsetZ();
            if (sx < 0 || sy < 0 || sz < 0 || sx >= this.level.width || sy >= this.level.depth || sz >= this.level.height) {
                continue;
            }
            out.add(new LightSource(sx, sy, sz, x, y, z, emitter.getRadius(), emitter));
        }
    }

    /** takes out (and returns) every light that the tile at (x, y, z) gave */
    private List<LightSource> removeSourcesOwnedBy(int x, int y, int z) {
        List<LightSource> removed = new ArrayList<>();
        for (int i = this.lightSources.size() - 1; i >= 0; i--) {
            LightSource s = this.lightSources.get(i);
            if (s.ownerX == x && s.ownerY == y && s.ownerZ == z) {
                removed.add(s);
                this.lightSources.remove(i);
            }
        }
        return removed;
    }

    private void spreadLight(LightSource source) {
        int r    = source.cubeRadius();
        int size = 2 * r + 1;
        float radius   = source.radius;
        float radiusSq = radius * radius;

        Arrays.fill(this.lightVisited, 0, size * size * size, false);

        int head = 0, tail = 0;
        int origin = (r * size + r) * size + r;
        this.lightVisited[origin] = true;
        this.lightQueue[tail++]   = origin;
        storeLight(source.x, source.y, source.z, 255);

        while (head < tail) {
            int current = this.lightQueue[head++];
            int lx = current % size;
            int lz = (current / size) % size;
            int ly = current / (size * size);

            for (int[] dir : LIGHT_DIRECTIONS) {
                int nlx = lx + dir[0];
                int nly = ly + dir[1];
                int nlz = lz + dir[2];

                if (nlx < 0 || nly < 0 || nlz < 0 || nlx >= size || nly >= size || nlz >= size) {
                    continue;
                }

                int dx = nlx - r, dy = nly - r, dz = nlz - r;
                float distSq = dx * dx + dy * dy + dz * dz;
                if (distSq >= radiusSq) {
                    continue;
                }

                int next = (nly * size + nlz) * size + nlx;
                if (this.lightVisited[next]) {
                    continue;
                }

                int wx = source.x + dx;
                int wy = source.y + dy;
                int wz = source.z + dz;
                if (wx < 0 || wy < 0 || wz < 0
                        || wx >= this.level.width || wy >= this.level.depth || wz >= this.level.height) {
                    continue;
                }

                this.lightVisited[next] = true;

                if (blocksBlockLight(wx, wy, wz)) {
                    continue;
                }

                float distance = (float) Math.sqrt(distSq);
                float t     = 1.0F - distance / radius;
                // a cone's light only lands inside the cone, but the spreading goes on through every cell either way:
                // the cells outside it are what the light has to travel through to reach the ones inside around a corner
                int   value = Math.min(255, (int) (source.shape(t) * source.intensity * source.coneFactor(dx, dy, dz, distance) * 255.0F + 0.5F));
                // The cell is still queued when it is in shadow, so the spreading carries on past it, but it only receives light
                // if the source can actually see it: otherwise light that wrapped around the end of a wall would be as bright
                // behind the wall as in front of it, because the brightness only depends on the straight-line distance.
                if (value > 0 && seesFromSource(source, wx, wy, wz)) {
                    storeLight(wx, wy, wz, value);
                }

                this.lightQueue[tail++] = next;
            }
        }
    }

    /**
     * whether the light can reach the cell (tx, ty, tz). A lamp is a whole block of glow, not a point at the middle of its cell, so a
     * single ray from the centre lets any block touching the lamp (the floor it stands on, a wall beside it) cut away far more than its
     * own shadow: every ray that grazes the edge of one of those blocks is thrown out. So the light also counts as coming from each
     * open cell right beside the source (the six face neighbours): light that gets out of the lamp's cell through any open face
     * can go on from there. The brightness still depends only on the distance from the source itself
     */
    private boolean seesFromSource(LightSource source, int tx, int ty, int tz) {
        if (hasLineOfSight(source.x, source.y, source.z, tx, ty, tz)) {
            return true;
        }
        for (int[] dir : LIGHT_DIRECTIONS) {
            int ox = source.x + dir[0];
            int oy = source.y + dir[1];
            int oz = source.z + dir[2];
            if (ox < 0 || oy < 0 || oz < 0
                    || ox >= this.level.width || oy >= this.level.depth || oz >= this.level.height
                    || blocksBlockLight(ox, oy, oz)) {
                continue;
            }
            if (hasLineOfSight(ox, oy, oz, tx, ty, tz)) {
                return true;
            }
        }
        return false;
    }

    // whether a straight line from the centre of the cell (sx, sy, sz) to the centre of the cell (tx, ty, tz) passes only through
    // cells that let light through (the two end cells themselves aren't tested). A line that runs exactly along an edge or
    // corner between cells needs all the cells around it to be open, so light can't squeeze between two diagonal walls
    private boolean hasLineOfSight(int sx, int sy, int sz, int tx, int ty, int tz) {
        int dx = tx - sx;
        int dy = ty - sy;
        int dz = tz - sz;
        int adx = Math.abs(dx);
        int ady = Math.abs(dy);
        int adz = Math.abs(dz);
        if (adx + ady + adz <= 1) {
            return true;
        }

        int stepX = Integer.signum(dx);
        int stepY = Integer.signum(dy);
        int stepZ = Integer.signum(dz);

        // the line runs from t = 0 (centre of the source) to t = 1 (centre of the target); tMax is where it next leaves
        // the current cell along each axis, tDelta is how much t one more cell takes
        double tDeltaX = adx == 0 ? Double.MAX_VALUE : 1.0 / adx;
        double tDeltaY = ady == 0 ? Double.MAX_VALUE : 1.0 / ady;
        double tDeltaZ = adz == 0 ? Double.MAX_VALUE : 1.0 / adz;
        double tMaxX = adx == 0 ? Double.MAX_VALUE : 0.5 / adx;
        double tMaxY = ady == 0 ? Double.MAX_VALUE : 0.5 / ady;
        double tMaxZ = adz == 0 ? Double.MAX_VALUE : 0.5 / adz;

        int x = sx;
        int y = sy;
        int z = sz;

        for (int guard = adx + ady + adz; guard >= 0; guard--) {
            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            int tied = 0;
            if (tMaxX - next < 1.0E-9) tied |= 1;
            if (tMaxY - next < 1.0E-9) tied |= 2;
            if (tMaxZ - next < 1.0E-9) tied |= 4;

            // leaving through an edge or corner: the cells beside it count as well
            for (int sub = (tied - 1) & tied; sub > 0; sub = (sub - 1) & tied) {
                if (blocksBlockLight(x + ((sub & 1) != 0 ? stepX : 0),
                        y + ((sub & 2) != 0 ? stepY : 0),
                        z + ((sub & 4) != 0 ? stepZ : 0))) {
                    return false;
                }
            }

            if ((tied & 1) != 0) {
                x += stepX;
                tMaxX += tDeltaX;
            }
            if ((tied & 2) != 0) {
                y += stepY;
                tMaxY += tDeltaY;
            }
            if ((tied & 4) != 0) {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }

            if (x == tx && y == ty && z == tz) {
                return true;
            }
            if (blocksBlockLight(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    private void storeLight(int x, int y, int z, int value) {
        int index = (y * this.level.height + z) * this.level.width + x;
        if ((this.blockLight[index] & 0xFF) < value) {
            this.blockLight[index] = (byte) value;
        }
    }

    void updateBlockLight(int x, int y, int z, Tile oldTile, Tile newTile,
                          boolean oldWasPart, boolean newIsPart) {
        // the lights this cell gave are replaced by the ones its new tile gives. the cell below is looked at too: whether it
        // is the top (or only) cell of a tile depends on what is above it, so its TOP and BOTTOM lights may switch on or off
        List<LightSource> removed = removeSourcesOwnedBy(x, y, z);
        List<LightSource> added = new ArrayList<>();
        if (newTile != null && newTile.emitsLight()) {
            addSourcesOf(added, newTile, x, y, z);
        }

        if (y > 0) {
            Tile below = Tile.byId(this.level.getTile(x, y - 1, z));
            if (below != null && below.emitsLight()) {
                List<LightSource> was = removeSourcesOwnedBy(x, y - 1, z);
                List<LightSource> now = new ArrayList<>();
                addSourcesOf(now, below, x, y - 1, z);
                removed.addAll(was);
                added.addAll(now);
            }
        }
        // the cell above for BOTTOM lights
        if (y + 1 < this.level.depth) {
            Tile above = Tile.byId(this.level.getTile(x, y + 1, z));
            if (above != null && above.emitsLight()) {
                removed.addAll(removeSourcesOwnedBy(x, y + 1, z));
                addSourcesOf(added, above, x, y + 1, z);
            }
        }

        boolean opacityChanged =
                (oldTile != null && oldTile.blocksLight()) != (newTile != null && newTile.blocksLight());

        this.lightSources.addAll(added);

        if (removed.isEmpty() && added.isEmpty() && !opacityChanged) {
            return;
        }

        // the lights whose area has to be done again: the ones that were taken out or put in, and every other that reaches this cell
        ArrayList<LightSource> affected = new ArrayList<>(removed);
        affected.addAll(added);
        for (LightSource s : this.lightSources) {
            int dx = s.x - x, dy = s.y - y, dz = s.z - z;
            if (dx * dx + dy * dy + dz * dz < s.radius * s.radius && !affected.contains(s)) {
                affected.add(s);
            }
        }
        if (affected.isEmpty()) {
            return;
        }

        for (LightSource a : affected) {
            int r = a.cubeRadius();
            for (int cy = Math.max(0, a.y - r); cy <= Math.min(this.level.depth  - 1, a.y + r); cy++) {
                for (int cz = Math.max(0, a.z - r); cz <= Math.min(this.level.height - 1, a.z + r); cz++) {
                    int row  = (cy * this.level.height + cz) * this.level.width;
                    int fromX = Math.max(0, a.x - r);
                    int toX   = Math.min(this.level.width - 1, a.x + r);
                    Arrays.fill(this.blockLight, row + fromX, row + toX + 1, (byte) 0);
                }
            }
        }

        for (LightSource s : this.lightSources) {
            for (LightSource a : affected) {
                int reach = s.cubeRadius() + a.cubeRadius();
                if (Math.abs(s.x - a.x) <= reach
                        && Math.abs(s.y - a.y) <= reach
                        && Math.abs(s.z - a.z) <= reach) {
                    spreadLight(s);
                    break;
                }
            }
        }

        for (LightSource a : affected) {
            int r    = a.cubeRadius() + 1;
            int minY = Math.max(0, a.y - r);
            int maxY = Math.min(this.level.depth - 1, a.y + r);

            for (int cx = Math.max(0, a.x - r); cx <= Math.min(this.level.width  - 1, a.x + r); cx++) {
                for (int cz = Math.max(0, a.z - r); cz <= Math.min(this.level.height - 1, a.z + r); cz++) {
                    for (LevelListener listener : this.level.levelListeners) {
                        listener.lightColumnChanged(cx, cz, minY, maxY);
                    }
                }
            }
        }
    }
}