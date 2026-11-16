package com.nikohalfkino.rubydung.level.tiles.special;

import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.common.FacingTile;
import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.model.BakedQuad;
import com.nikohalfkino.rubydung.entity.phys.AABB;
import com.nikohalfkino.rubydung.render.Tessellator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WoodenDoorTile extends FacingTile {

    public enum Swing {
        CLOSED("wooden_door"), IN("wooden_door_open_in"), OUT("wooden_door_open_out");

        final String tileName;

        Swing(String tileName) {
            this.tileName = tileName;
        }
    }

    public static final String CLOSED_NAME = Swing.CLOSED.tileName;

    private static final int DOUBLE_LOOK       = 4;
    private static final int DOUBLE_LOOK_RIGHT = 8;

    private static final int[][] DEFAULT_FRONT = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final int[][] DEFAULT_HINGE = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private static int[][] front = DEFAULT_FRONT;
    private static int[][] hinge = DEFAULT_HINGE;
    private static boolean geometryRead;

    private final Swing swing;

    public WoodenDoorTile(Swing swing) {
        super(swing.tileName);
        this.swing = swing;
        setFootprint(1, 2, 1);
        setTransparent(true);
    }

    public Swing getSwing() {
        return this.swing;
    }

    public boolean isOpen() {
        return this.swing != Swing.CLOSED;
    }

    @Override
    public boolean isInteractable() {
        return true;
    }

    public Tile getClosedForm() {
        return Tile.byName(CLOSED_NAME);
    }

    private static synchronized void readGeometry() {
        if (geometryRead) {
            return;
        }
        Tile open = Tile.byName(Swing.IN.tileName);
        if (open == null) {
            return;
        }
        geometryRead = true;

        int[][] newFront = new int[4][];
        int[][] newHinge = new int[4][];
        for (int facing = 0; facing < 4; facing++) {
            newFront[facing] = DEFAULT_FRONT[facing];
            newHinge[facing] = DEFAULT_HINGE[facing];

            double[] hull = hullOf(open.getCollisionBoxes(0, 0, 0, facing));
            if (hull == null) {
                continue;
            }

            boolean alongX = (facing & 1) == 0;
            double planeMin = alongX ? hull[0] : hull[2];
            double planeMax = alongX ? hull[3] : hull[5];
            double normalMin = alongX ? hull[2] : hull[0];
            double normalMax = alongX ? hull[5] : hull[3];
            if (planeMax - planeMin > 0.5) {
                continue;
            }

            int hingeSign = (planeMin + planeMax) * 0.5 > 0.5 ? 1 : -1;
            int backSign = (normalMax - 0.5) > (0.5 - normalMin) ? 1 : -1;

            newHinge[facing] = alongX ? new int[]{hingeSign, 0} : new int[]{0, hingeSign};
            newFront[facing] = alongX ? new int[]{0, -backSign} : new int[]{-backSign, 0};
        }
        front = newFront;
        hinge = newHinge;
    }

    private static int[] frontOf(int facing) {
        readGeometry();
        return front[facing & 3];
    }

    private static int[] hingeOf(int facing) {
        readGeometry();
        return hinge[facing & 3];
    }

    private static int planeOf(int facing) {
        return facing & 1;
    }

    private static int mirrored(int facing) {
        int[] h = hingeOf(facing);
        for (int other = 0; other < 4; other++) {
            int[] o = hingeOf(other);
            if (other != facing && planeOf(other) == planeOf(facing) && o[0] == -h[0] && o[1] == -h[1]) {
                return other;
            }
        }
        return (facing + 2) & 3;
    }

    private static double[] hullOf(List<AABB> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return null;
        }
        double[] hull = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (AABB box : boxes) {
            hull[0] = Math.min(hull[0], box.minX);
            hull[1] = Math.min(hull[1], box.minY);
            hull[2] = Math.min(hull[2], box.minZ);
            hull[3] = Math.max(hull[3], box.maxX);
            hull[4] = Math.max(hull[4], box.maxY);
            hull[5] = Math.max(hull[5], box.maxZ);
        }
        return hull;
    }

    private static boolean isUpperCell(Level level, int x, int y, int z) {
        return level.isSameMultiTile(x, y, z, x, y - 1, z);
    }

    private static int originY(Level level, int x, int y, int z) {
        return isUpperCell(level, x, y, z) ? y - 1 : y;
    }

    @Override
    public void render(Tessellator tessellator, Level level, int layer, int x, int y, int z,
                       int totalAtlasSlots, int data) {
        if (isUpperCell(level, x, y, z)) {
            return;
        }

        if (this.swing == Swing.CLOSED) {
            int facing = facingOf(data) & 3;
            WoodenDoorTile partner = partnerDoor(level, x, y, z, facing);
            if (partner != null && !partner.isOpen()) {
                int[] f = frontOf(facing);
                int[] h = hingeOf(facing);
                boolean rightHalf = h[0] == -f[1] && h[1] == f[0];
                data = facing + (rightHalf ? DOUBLE_LOOK_RIGHT : DOUBLE_LOOK);
            }
        }
        super.render(tessellator, level, layer, x, y, z, totalAtlasSlots, data);
    }

    @Override
    public boolean hasCustomOutline() {
        return true;
    }

    @Override
    public List<AABB> getCollisionBoxes(int x, int y, int z, int data) {
        float[] b = drawnBounds(data);
        if (b == null) {
            return super.getCollisionBoxes(x, y, z, data);
        }
        return Collections.singletonList(new AABB(x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]));
    }

    private float[] drawnBounds(int data) {
        List<BakedQuad> quads = getBlockState().state(data).quads();
        if (quads.isEmpty()) {
            return null;
        }
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (BakedQuad quad : quads) {
            for (int v = 0; v < 4; v++) {
                for (int a = 0; a < 3; a++) {
                    float value = quad.positions[v * 3 + a];
                    box[a] = Math.min(box[a], value);
                    box[a + 3] = Math.max(box[a + 3], value);
                }
            }
        }
        return box;
    }

    @Override
    public List<AABB> getCollisionBoxes(Level level, int x, int y, int z) {
        if (isUpperCell(level, x, y, z)) {
            return Collections.emptyList();
        }
        return getCollisionBoxes(x, y, z, level.getData(x, y, z));
    }

    @Override
    public List<AABB> getPickBoxes(Level level, int x, int y, int z) {
        return getOccupancyBoxes(x, originY(level, x, y, z), z, level.getData(x, y, z));
    }

    @Override
    public AABB getOutlineAABB(Level level, int x, int y, int z) {
        int oy = originY(level, x, y, z);
        int data = level.getData(x, oy, z);

        List<AABB> boxes = new ArrayList<>(getCollisionBoxes(x, oy, z, data));
        if (!isOpen()) {
            int[] partner = partnerOf(level, x, oy, z, facingOf(data));
            WoodenDoorTile partnerDoor = partner == null ? null : doorAt(level, partner[0], oy, partner[1]);
            if (partnerDoor != null && !partnerDoor.isOpen()) {
                boxes.addAll(partnerDoor.getCollisionBoxes(partner[0], oy, partner[1],
                        level.getData(partner[0], oy, partner[1])));
            }
        }

        double[] hull = hullOf(boxes);
        if (hull == null) {
            return super.getOutlineAABB(level, x, y, z);
        }
        return new AABB(hull[0], hull[1], hull[2], hull[3], hull[4], hull[5]);
    }

    private static WoodenDoorTile loneDoor(Level level, int x, int y, int z, int plane) {
        WoodenDoorTile door = doorAt(level, x, y, z);
        if (door == null || door.isOpen() || isUpperCell(level, x, y, z)) {
            return null;
        }
        int facing = facingOf(level.getData(x, y, z)) & 3;
        if (planeOf(facing) != plane || partnerOf(level, x, y, z, facing) != null) {
            return null;
        }
        return door;
    }

    @Override
    public int getMultiTileRotation(Level level, int x, int y, int z, int rotation) {
        rotation &= 3;
        int plane = planeOf(rotation);

        for (int pass = 0; pass < 2; pass++) {
            for (int side = -1; side <= 1; side += 2) {
                int dx = plane == 0 ? side : 0;
                int dz = plane == 0 ? 0 : side;
                int nx = x + dx;
                int nz = z + dz;

                if (loneDoor(level, nx, y, nz, plane) == null) {
                    continue;
                }
                int[] theirHinge = hingeOf(facingOf(level.getData(nx, y, nz)));
                boolean hingeOnFarEdge = theirHinge[0] == dx && theirHinge[1] == dz;
                if (hingeOnFarEdge != (pass == 0)) {
                    continue;
                }

                for (int facing : new int[]{rotation, mirrored(rotation)}) {
                    int[] h = hingeOf(facing);
                    if (h[0] == -dx && h[1] == -dz) {
                        return facing;
                    }
                }
            }
        }
        return rotation;
    }

    public static void onPlaced(Level level, int x, int y, int z) {
        y = originY(level, x, y, z);
        WoodenDoorTile door = doorAt(level, x, y, z);
        if (door == null || door.isOpen()) {
            return;
        }

        int facing = facingOf(level.getData(x, y, z)) & 3;
        if (partnerOf(level, x, y, z, facing) != null) {
            return;
        }

        int[] h = hingeOf(facing);
        int nx = x - h[0];
        int nz = z - h[1];
        WoodenDoorTile neighbour = loneDoor(level, nx, y, nz, planeOf(facing));
        if (neighbour == null) {
            return;
        }

        int neighbourFacing = facingOf(level.getData(nx, y, nz)) & 3;
        replace(level, nx, y, nz, neighbour, mirrored(neighbourFacing));
    }

    private static WoodenDoorTile partnerDoor(Level level, int x, int y, int z, int facing) {
        int[] partner = partnerOf(level, x, originY(level, x, y, z), z, facing);
        return partner == null ? null : doorAt(level, partner[0], originY(level, x, y, z), partner[1]);
    }

    private static int[] partnerOf(Level level, int x, int y, int z, int facing) {
        int[] h = hingeOf(facing);
        int px = x - h[0];
        int pz = z - h[1];
        if (doorAt(level, px, y, pz) == null || isUpperCell(level, px, y, pz)) {
            return null;
        }
        int[] theirs = hingeOf(facingOf(level.getData(px, y, pz)));
        return theirs[0] == -h[0] && theirs[1] == -h[1] ? new int[]{px, pz} : null;
    }

    private static WoodenDoorTile doorAt(Level level, int x, int y, int z) {
        Tile tile = Tile.byId(level.getTile(x, y, z));
        return tile instanceof WoodenDoorTile ? (WoodenDoorTile) tile : null;
    }

    public static void toggle(Level level, int x, int y, int z, double playerX, double playerZ) {
        y = originY(level, x, y, z);
        WoodenDoorTile door = doorAt(level, x, y, z);
        if (door == null) {
            return;
        }

        int facing = facingOf(level.getData(x, y, z)) & 3;
        int[] partner = partnerOf(level, x, y, z, facing);
        WoodenDoorTile partnerDoor = partner == null ? null : doorAt(level, partner[0], y, partner[1]);

        if (door.isOpen()) {
            swap(level, x, y, z, Swing.CLOSED);
            if (partnerDoor != null && partnerDoor.isOpen()) {
                swap(level, partner[0], y, partner[1], Swing.CLOSED);
            }
            SoundEffects.play("door_close");
            return;
        }

        int[] f = frontOf(facing);
        double side = (playerX - (x + 0.5)) * f[0] + (playerZ - (z + 0.5)) * f[1];
        Swing swing = side >= 0.0 ? Swing.IN : Swing.OUT;
        swap(level, x, y, z, swing);

        if (partnerDoor != null && !partnerDoor.isOpen()) {
            swap(level, partner[0], y, partner[1], swing == Swing.IN ? Swing.OUT : Swing.IN);
        }
        SoundEffects.play("door_open");
    }

    private static void swap(Level level, int x, int y, int z, Swing target) {
        replace(level, x, y, z, Tile.byName(target.tileName), level.getData(x, y, z) & 3);
    }

    private static void replace(Level level, int x, int y, int z, Tile tile, int rotation) {
        level.setTile(x, y, z, 0);
        level.setTile(x, y, z, tile.id, rotation & 3);
    }
}