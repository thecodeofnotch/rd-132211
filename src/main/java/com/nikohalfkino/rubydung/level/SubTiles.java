package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.model.BakedQuad;
import com.nikohalfkino.rubydung.entity.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * every SubTile of a level, by cell. a cell's normal tile (the "host", in the level's block array) and its sub-tiles together are the cell's contents and may not overlap
 *
 * lists are replaced, never edited in place, so code iterating one (chunk mesher, raycaster) is never disturbed by a change
 */
public final class SubTiles {

    /** sub-tiles per cell, not counting the host */
    public static final int MAX_PER_CELL = 8;

    /** boxes closer than this are touching, not overlapping */
    public static final double EPSILON = 1.0E-4;

    private static final List<SubTile> NONE = Collections.emptyList();

    private final Level level;
    private final HashMap<Long, List<SubTile>> cells = new HashMap<>();
    private int nextId = 1;

    SubTiles(Level level) {
        this.level = level;
    }

    private static long key(int x, int y, int z) {
        return (x & 0xFFFFFL) | ((z & 0xFFFFFL) << 20) | ((y & 0xFFFFFL) << 40);
    }

    // lookup

    public boolean isEmpty() {
        return this.cells.isEmpty();
    }

    public boolean has(int x, int y, int z) {
        return !this.cells.isEmpty() && this.cells.containsKey(key(x, y, z));
    }

    /** never null, don't modify */
    public List<SubTile> at(int x, int y, int z) {
        if (this.cells.isEmpty()) {
            return NONE;
        }
        List<SubTile> list = this.cells.get(key(x, y, z));
        return list == null ? NONE : list;
    }

    public SubTile find(int x, int y, int z, int id) {
        for (SubTile subTile : at(x, y, z)) {
            if (subTile.id == id) {
                return subTile;
            }
        }
        return null;
    }

    /** whether any sub-tile in the cell darkens the cells below it */
    public boolean castsShadowAt(int x, int y, int z) {
        for (SubTile subTile : at(x, y, z)) {
            Tile tile = Tile.byId(subTile.tileId);
            if (tile != null && tile.castsShadow()) {
                return true;
            }
        }
        return false;
    }

    // change

    public SubTile add(int x, int y, int z, int tileId, int data, float ox, float oy, float oz) {
        SubTile subTile = new SubTile(this.nextId++, tileId, data, ox, oy, oz);
        List<SubTile> next = new ArrayList<>(at(x, y, z));
        next.add(subTile);
        this.cells.put(key(x, y, z), Collections.unmodifiableList(next));
        this.level.subTilesChanged(x, y, z);
        return subTile;
    }

    public boolean remove(int x, int y, int z, int id) {
        List<SubTile> current = at(x, y, z);
        List<SubTile> next = new ArrayList<>(current.size());
        for (SubTile subTile : current) {
            if (subTile.id != id) {
                next.add(subTile);
            }
        }
        if (next.size() == current.size()) {
            return false;
        }
        store(x, y, z, next);
        this.level.subTilesChanged(x, y, z);
        return true;
    }

    // drops every sub-tile of the cell that overlaps one of the boxes (a new host tile about to fill part of the cell)
    // no notification, the caller is already changing the cell
    void removeOverlapping(int x, int y, int z, Tile incoming, List<AABB> hostBoxes) {
        List<SubTile> current = at(x, y, z);
        if (current.isEmpty() || hostBoxes.isEmpty()) {
            return;
        }
        List<SubTile> next = new ArrayList<>(current.size());
        for (SubTile subTile : current) {
            // A sub-tile the incoming tile may pass through (a slab and a pane) stays.
            if (Tile.mayOverlap(incoming, Tile.byId(subTile.tileId))
                    || !overlapsAny(occupancy(subTile, x, y, z), hostBoxes)) {
                next.add(subTile);
            }
        }
        if (next.size() != current.size()) {
            store(x, y, z, next);
        }
    }

    private void store(int x, int y, int z, List<SubTile> list) {
        if (list.isEmpty()) {
            this.cells.remove(key(x, y, z));
        } else {
            this.cells.put(key(x, y, z), Collections.unmodifiableList(list));
        }
    }

    // shapes (world coordinates)

    /** space a sub-tile takes up in the cell, used by picking and overlap tests */
    public List<AABB> occupancy(SubTile subTile, int x, int y, int z) {
        Tile tile = Tile.byId(subTile.tileId);
        if (tile == null) {
            return Collections.emptyList();
        }
        List<AABB> boxes = tile.getOccupancyBoxes(x, y, z, SubTile.baseData(subTile.data));
        return moved(clipBoxes(boxes, y, SubTile.clipOf(subTile.data)), subTile);
    }

    /** what entities collide with */
    public List<AABB> collision(SubTile subTile, int x, int y, int z) {
        Tile tile = Tile.byId(subTile.tileId);
        if (tile == null) {
            return Collections.emptyList();
        }
        List<AABB> boxes = tile.getCollisionBoxes(x, y, z, SubTile.baseData(subTile.data));
        return moved(clipBoxes(boxes, y, SubTile.clipOf(subTile.data)), subTile);
    }

    /** cuts boxes down to the lower or upper half of the cell at height y (clip = CLIP_BOTTOM / CLIP_TOP, 0 = unchanged), boxes wholly outside the half disappear */
    public static List<AABB> clipBoxes(List<AABB> boxes, int y, int clip) {
        if (clip == 0) {
            return boxes;
        }
        double lo = clip == SubTile.CLIP_BOTTOM ? y : y + 0.5;
        double hi = clip == SubTile.CLIP_BOTTOM ? y + 0.5 : y + 1.0;
        List<AABB> result = new ArrayList<>(boxes.size());
        for (AABB b : boxes) {
            double minY = Math.max(b.minY, lo);
            double maxY = Math.min(b.maxY, hi);
            if (maxY - minY > EPSILON) {
                result.add(new AABB(b.minX, minY, b.minZ, b.maxX, maxY, b.maxZ));
            }
        }
        return result;
    }

    private static List<AABB> moved(List<AABB> boxes, SubTile by) {
        List<AABB> result = new ArrayList<>(boxes.size());
        for (AABB b : boxes) {
            result.add(new AABB(b.minX + by.ox, b.minY + by.oy, b.minZ + by.oz,
                    b.maxX + by.ox, b.maxY + by.oy, b.maxZ + by.oz));
        }
        return result;
    }

    /** the host tile's occupancy in a cell (empty for air and billboards, the whole cell for multi-tile parts) */
    public List<AABB> hostOccupancy(int x, int y, int z) {
        if (this.level.getMultiTile(x, y, z) != null) {
            return Collections.singletonList(new AABB(x, y, z, x + 1, y + 1, z + 1));
        }
        Tile host = Tile.byId(this.level.getTile(x, y, z));
        if (host == null || host == Tile.AIR || host.isBillboard()) {
            return Collections.emptyList();
        }
        return host.getOccupancyBoxes(x, y, z, this.level.getData(x, y, z));
    }

    /** host plus every sub-tile: all the space that's taken */
    public List<AABB> cellOccupancy(int x, int y, int z) {
        List<AABB> all = new ArrayList<>(hostOccupancy(x, y, z));
        for (SubTile subTile : at(x, y, z)) {
            all.addAll(occupancy(subTile, x, y, z));
        }
        return all;
    }

    /** like cellOccupancy, minus whatever the tile about to be placed may pass through (a pane and a slab) */
    public List<AABB> cellOccupancyFor(int x, int y, int z, Tile newcomer) {
        List<AABB> all = new ArrayList<>();
        Tile host = Tile.byId(this.level.getTile(x, y, z));
        if (!Tile.mayOverlap(newcomer, host)) {
            all.addAll(hostOccupancy(x, y, z));
        }
        for (SubTile subTile : at(x, y, z)) {
            if (!Tile.mayOverlap(newcomer, Tile.byId(subTile.tileId))) {
                all.addAll(occupancy(subTile, x, y, z));
            }
        }
        return all;
    }

    public static boolean overlaps(AABB a, AABB b) {
        return a.minX < b.maxX - EPSILON && a.maxX > b.minX + EPSILON
                && a.minY < b.maxY - EPSILON && a.maxY > b.minY + EPSILON
                && a.minZ < b.maxZ - EPSILON && a.maxZ > b.minZ + EPSILON;
    }

    public static boolean overlapsAny(List<AABB> as, List<AABB> bs) {
        for (AABB a : as) {
            for (AABB b : bs) {
                if (overlaps(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    // meshing: faces that two co-occupants press together are never seen

    private static double lo(AABB b, int axis) {
        return axis == 0 ? b.minX : axis == 1 ? b.minY : b.minZ;
    }

    private static double hi(AABB b, int axis) {
        return axis == 0 ? b.maxX : axis == 1 ? b.maxY : b.maxZ;
    }

    /**
     * whether a quad of the tile being drawn (id / data / offset say which occupant) is pressed flat against another opaque occupant of the cell and can't be seen (top of a bottom slab under a top slab, a post on a slab)
     *
     * without this the two coplanar faces z-fight
     */
    public boolean isQuadHidden(int x, int y, int z, BakedQuad quad,
                                int selfTileId, int selfData, float ox, float oy, float oz) {
        int face = quad.lightFace;
        int axis = face < 2 ? 1 : face < 4 ? 2 : 0;       // BOTTOM/TOP = y, NORTH/SOUTH = z, WEST/EAST = x
        boolean positive = (face & 1) == 1;                // TOP, SOUTH, EAST look toward +axis

        double[][] p = new double[4][3];
        for (int v = 0; v < 4; v++) {
            p[v][0] = x + quad.positions[v * 3] + ox;
            p[v][1] = y + quad.positions[v * 3 + 1] + oy;
            p[v][2] = z + quad.positions[v * 3 + 2] + oz;
        }
        double plane = p[0][axis];
        for (int v = 1; v < 4; v++) {
            if (Math.abs(p[v][axis] - plane) > EPSILON) {
                return false;                              // not axis-aligned: leave it alone
            }
        }

        int t1 = (axis + 1) % 3;
        int t2 = (axis + 2) % 3;
        double min1 = Double.MAX_VALUE, max1 = -Double.MAX_VALUE, min2 = Double.MAX_VALUE, max2 = -Double.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            min1 = Math.min(min1, p[v][t1]);
            max1 = Math.max(max1, p[v][t1]);
            min2 = Math.min(min2, p[v][t2]);
            max2 = Math.max(max2, p[v][t2]);
        }

        boolean selfIsZero = ox == 0.0F && oy == 0.0F && oz == 0.0F;
        int hostId = this.level.getTile(x, y, z);
        if (!(selfIsZero && hostId == selfTileId && this.level.getData(x, y, z) == selfData)
                && hidesBehind(hostOccupancy(x, y, z), hostId, axis, positive, plane, t1, t2, min1, max1, min2, max2)) {
            return true;
        }

        for (SubTile other : at(x, y, z)) {
            if (other.isSameAs(selfTileId, selfData, ox, oy, oz)) {
                continue;
            }
            if (hidesBehind(occupancy(other, x, y, z), other.tileId, axis, positive, plane, t1, t2, min1, max1, min2, max2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hidesBehind(List<AABB> boxes, int tileId, int axis, boolean positive, double plane,
                                       int t1, int t2, double min1, double max1, double min2, double max2) {
        Tile tile = Tile.byId(tileId);
        if (tile == null || !tile.isSolid() || tile.isTransparent()) {
            return false;
        }
        for (AABB b : boxes) {
            double facing = positive ? lo(b, axis) : hi(b, axis);   // the face of b that looks back at the quad
            if (Math.abs(facing - plane) > EPSILON) {
                continue;
            }
            if (lo(b, t1) <= min1 + EPSILON && hi(b, t1) >= max1 - EPSILON
                    && lo(b, t2) <= min2 + EPSILON && hi(b, t2) >= max2 - EPSILON) {
                return true;
            }
        }
        return false;
    }

    // saving (by tile NAME, so reordering the tile registry can't corrupt a save)

    /** one saved sub-tile */
    public static final class Entry {
        public final int x, y, z;
        public final String tileName;
        public final int data;
        public final float ox, oy, oz;

        public Entry(int x, int y, int z, String tileName, int data, float ox, float oy, float oz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.tileName = tileName;
            this.data = data;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }
    }

    public List<Entry> snapshot() {
        List<Entry> entries = new ArrayList<>();
        for (java.util.Map.Entry<Long, List<SubTile>> cell : this.cells.entrySet()) {
            long k = cell.getKey();
            int x = (int) (k & 0xFFFFFL);
            int z = (int) ((k >> 20) & 0xFFFFFL);
            int y = (int) ((k >> 40) & 0xFFFFFL);
            for (SubTile s : cell.getValue()) {
                Tile tile = Tile.byId(s.tileId);
                if (tile != null) {
                    entries.add(new Entry(x, y, z, tile.name, s.data, s.ox, s.oy, s.oz));
                }
            }
        }
        return entries;
    }

    /** the sub-tiles of one cell as saveable entries (what goes over the network) */
    public List<Entry> cellEntries(int x, int y, int z) {
        List<Entry> out = new ArrayList<>();
        for (SubTile s : at(x, y, z)) {
            Tile tile = Tile.byId(s.tileId);
            if (tile != null) {
                out.add(new Entry(x, y, z, tile.name, s.data, s.ox, s.oy, s.oz));
            }
        }
        return out;
    }

    /** replaces the sub-tiles of one cell with the entries (unknown tile names are skipped), notifies like add / remove do */
    void replaceCell(int x, int y, int z, List<Entry> entries) {
        List<SubTile> next = new ArrayList<>();
        for (Entry e : entries) {
            Tile tile = Tile.byName(e.tileName);
            if (tile == null || tile == Tile.AIR || next.size() >= MAX_PER_CELL) {
                continue;
            }
            next.add(new SubTile(this.nextId++, tile.id, e.data, e.ox, e.oy, e.oz));
        }
        store(x, y, z, next);
        this.level.subTilesChanged(x, y, z);
    }

    /** replaces everything with saved entries, unknown tile names (a removed tile) are skipped, no notifications */
    void restore(List<Entry> entries) {
        this.cells.clear();
        if (entries == null) {
            return;
        }
        for (Entry e : entries) {
            Tile tile = Tile.byName(e.tileName);
            if (tile == null || tile == Tile.AIR) {
                continue;
            }
            List<SubTile> next = new ArrayList<>(at(e.x, e.y, e.z));
            if (next.size() >= MAX_PER_CELL) {
                continue;
            }
            next.add(new SubTile(this.nextId++, tile.id, e.data, e.ox, e.oy, e.oz));
            this.cells.put(key(e.x, e.y, e.z), Collections.unmodifiableList(next));
        }
    }
}
