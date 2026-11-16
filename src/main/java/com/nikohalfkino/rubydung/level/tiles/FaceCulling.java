package com.nikohalfkino.rubydung.level.tiles;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.SubTile;
import com.nikohalfkino.rubydung.level.SubTiles;
import com.nikohalfkino.rubydung.entity.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * partial face culling: a neighbour that only fills PART of a face (a bottom slab, a sub-tile) hides only that part. works out which rectangles of a face are still visible and cuts the face down to them, so a full block beside a bottom slab draws just the upper half of its side
 *
 * neighbours are read from their real boxes (host + every sub-tile of the cell), so different slab kinds, or a slab beside a sub-tile, cull each other like same-kind ones
 */
public final class FaceCulling {

    private static final double E = 1.0E-4;

    private FaceCulling() {}

    /** one visible part of a quad: its corners, uvs and where they sit in the original quad */
    public static final class Piece {
        /** 4 vertices x (x, y, z), relative to the cell corner (same space as the original quad) */
        public final float[] pos = new float[12];
        /** 4 vertices x (u, v), same units as the original quad's */
        public final float[] uv = new float[8];
        /** per new vertex, its weight (4) on each ORIGINAL vertex, interpolates light and colour */
        public final float[] weights = new float[16];
    }

    /**
     * self = tile drawing the quad, selfCulls = hidden by the same kind of tile next to it, x,y,z = cell being drawn
     *
     * pos/uv = the quad (positions relative to the cell, uvs only interpolated), face = world face it lies on (Tile.FACE_*), ox,oy,oz = sub-tile offset (0 for a normal tile)
     *
     * returns null if the whole quad is visible, an empty list if none of it is, else the visible pieces
     */
    public static List<Piece> visiblePieces(Level level, Tile self, boolean selfCulls, int x, int y, int z,
                                            float[] pos, float[] uv, int face, float ox, float oy, float oz) {
        int[] n = Tile.FACE_NORMALS[face];
        int nx = x + n[0], ny = y + n[1], nz = z + n[2];

        SubTiles subTiles = level.getSubTiles();
        boolean hasHost = level.getTile(nx, ny, nz) != 0;
        boolean hasSubs = subTiles.has(nx, ny, nz);
        if (!hasHost && !hasSubs) {
            return null;
        }

        int axis = face < 2 ? 1 : face < 4 ? 2 : 0;
        boolean positive = (face & 1) == 1;
        int t1 = (axis + 1) % 3;
        int t2 = (axis + 2) % 3;
        double[] off = {ox, oy, oz};
        double[] base = {x, y, z};

        double[] a = new double[4];
        double[] b = new double[4];
        double plane = base[axis] + pos[axis] + off[axis];
        for (int v = 0; v < 4; v++) {
            if (Math.abs(base[axis] + pos[v * 3 + axis] + off[axis] - plane) > E) {
                return null;                       // not flat on the cell edge: leave it alone
            }
            a[v] = base[t1] + pos[v * 3 + t1] + off[t1];
            b[v] = base[t2] + pos[v * 3 + t2] + off[t2];
        }

        double minA = Math.min(Math.min(a[0], a[1]), Math.min(a[2], a[3]));
        double maxA = Math.max(Math.max(a[0], a[1]), Math.max(a[2], a[3]));
        double minB = Math.min(Math.min(b[0], b[1]), Math.min(b[2], b[3]));
        double maxB = Math.max(Math.max(b[0], b[1]), Math.max(b[2], b[3]));
        if (maxA - minA < E || maxB - minB < E) {
            return null;
        }

        // Which original vertex sits at which corner of the rectangle.
        int[][] corner = {{-1, -1}, {-1, -1}};
        int[] ca = new int[4];
        int[] cb = new int[4];
        double midA = (minA + maxA) * 0.5;
        double midB = (minB + maxB) * 0.5;
        for (int v = 0; v < 4; v++) {
            ca[v] = a[v] > midA ? 1 : 0;
            cb[v] = b[v] > midB ? 1 : 0;
            if (corner[ca[v]][cb[v]] != -1) {
                return null;                       // not a plain rectangle: leave it alone
            }
            corner[ca[v]][cb[v]] = v;
        }

        // What the neighbour covers of this plane.
        List<double[]> covers = new ArrayList<>();
        if (hasHost && level.getMultiTile(nx, ny, nz) == null) {
            Tile host = Tile.byId(level.getTile(nx, ny, nz));
            if (coversFor(host, self, selfCulls)) {
                addCovers(covers, host.getOccupancyBoxes(nx, ny, nz, level.getData(nx, ny, nz)),
                        axis, positive, plane, t1, t2);
            }
        }
        if (hasSubs) {
            for (SubTile sub : subTiles.at(nx, ny, nz)) {
                Tile tile = Tile.byId(sub.tileId);
                if (coversFor(tile, self, selfCulls)) {
                    addCovers(covers, subTiles.occupancy(sub, nx, ny, nz), axis, positive, plane, t1, t2);
                }
            }
        }
        if (covers.isEmpty()) {
            return null;
        }

        List<double[]> visible = new ArrayList<>();
        visible.add(new double[]{minA, maxA, minB, maxB});
        for (double[] cover : covers) {
            List<double[]> next = new ArrayList<>();
            for (double[] rect : visible) {
                subtract(rect, cover, next);
            }
            visible = next;
            if (visible.isEmpty()) {
                return Collections.emptyList();
            }
        }

        if (visible.size() == 1) {
            double[] r = visible.get(0);
            if (Math.abs(r[0] - minA) < E && Math.abs(r[1] - maxA) < E
                    && Math.abs(r[2] - minB) < E && Math.abs(r[3] - maxB) < E) {
                return null;                       // nothing was covered after all
            }
        }

        List<Piece> pieces = new ArrayList<>(visible.size());
        for (double[] r : visible) {
            Piece piece = new Piece();
            for (int v = 0; v < 4; v++) {
                double na = ca[v] == 1 ? r[1] : r[0];
                double nb = cb[v] == 1 ? r[3] : r[2];
                float s = (float) ((na - minA) / (maxA - minA));
                float t = (float) ((nb - minB) / (maxB - minB));

                float[] w = piece.weights;
                w[v * 4 + corner[0][0]] = (1.0F - s) * (1.0F - t);
                w[v * 4 + corner[1][0]] = s * (1.0F - t);
                w[v * 4 + corner[0][1]] = (1.0F - s) * t;
                w[v * 4 + corner[1][1]] = s * t;

                for (int k = 0; k < 4; k++) {
                    float wk = w[v * 4 + k];
                    piece.pos[v * 3]     += wk * pos[k * 3];
                    piece.pos[v * 3 + 1] += wk * pos[k * 3 + 1];
                    piece.pos[v * 3 + 2] += wk * pos[k * 3 + 2];
                    piece.uv[v * 2]      += wk * uv[k * 2];
                    piece.uv[v * 2 + 1]  += wk * uv[k * 2 + 1];
                }
            }
            pieces.add(piece);
        }
        return pieces;
    }

    // whether a neighbouring occupant can hide faces of self at all
    private static boolean coversFor(Tile other, Tile self, boolean selfCulls) {
        if (other == null || other == Tile.AIR || !other.isSolid() || other.isBillboard() || other.isMultiTile()) {
            return false;
        }
        if (!other.isTransparent() && !self.isTransparent()) {
            return true;
        }
        return other == self && selfCulls;         // glass / grates only hide their own kind
    }

    private static double lo(AABB box, int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    private static double hi(AABB box, int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }

    private static void addCovers(List<double[]> out, List<AABB> boxes, int axis, boolean positive,
                                  double plane, int t1, int t2) {
        for (AABB box : boxes) {
            double facing = positive ? lo(box, axis) : hi(box, axis);   // the face looking back at the quad
            if (Math.abs(facing - plane) > E) {
                continue;
            }
            out.add(new double[]{lo(box, t1), hi(box, t1), lo(box, t2), hi(box, t2)});
        }
    }

    // rect minus cover, as up to four rectangles {minA, maxA, minB, maxB}
    private static void subtract(double[] rect, double[] cover, List<double[]> out) {
        double a0 = Math.max(rect[0], cover[0]);
        double a1 = Math.min(rect[1], cover[1]);
        double b0 = Math.max(rect[2], cover[2]);
        double b1 = Math.min(rect[3], cover[3]);
        if (a0 >= a1 - E || b0 >= b1 - E) {
            out.add(rect);                         // no overlap
            return;
        }
        add(out, rect[0], a0, rect[2], rect[3]);   // strip on the low-A side
        add(out, a1, rect[1], rect[2], rect[3]);   // strip on the high-A side
        add(out, a0, a1, rect[2], b0);             // below the cover
        add(out, a0, a1, b1, rect[3]);             // above the cover
    }

    private static void add(List<double[]> out, double a0, double a1, double b0, double b1) {
        if (a1 - a0 > E && b1 - b0 > E) {
            out.add(new double[]{a0, a1, b0, b1});
        }
    }
}
