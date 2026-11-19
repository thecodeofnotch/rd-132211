package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * the liquid of every cell, in its own grid next to the level's block array
 *
 * a cell holds a normal tile (the host, in the block array) AND a liquid at the same time, so a slab, a stair, a pane or a half block can sit in water without pushing it out. the only thing that removes a liquid is a tile that fills the whole cell (see displacesLiquid), and a liquid can't be put into such a cell
 *
 * two bytes per cell: liquid index + 1 (0 = no liquid) and how full the cell is (1..MAX_FILL, 0 when empty).
 * the fill tells the LiquidSimulator what a cell is: MAX_FILL is a source (permanent), 1..MAX_FILL-1 is flowing liquid that
 * only lasts while something feeds it (so set(..., fill) with a partial amount drains again unless it is next to a source)
 *
 * THE OCEAN: the water a world is generated with is not stored cell by cell. it is one liquid level (seaHeight) plus, per column, a floor and a limit:
 *
 *   a cell is ocean when  y > floor[column]  and  y <= seaTop  and  seaTop > limit[column]  and no block fills it
 *
 *   floor  the highest block of the column when the world was made (for a sea column: the sea bed). it never changes afterwards
 *   limit  -1 for the columns that held water when the world was made (the SEA). for every other column the higher of its floor and the
 *          starting sea level, so a dry column is ocean once the sea is over its top
 *
 * the sea never writes water tiles: a dry column has a limit of its top block (or the starting sea level), so when the sea rises over it the
 * column's open cells simply become virtual ocean. cells under overhangs and in tunnels below a column's top block are not reached (they are
 * under the floor). stored water that already exists (old saves, buckets, the simulation) is ordinary liquid: when the sea goes down it is
 * woken (wakeLayerLiquids) and the finite simulation lets it run downhill
 *
 * the ocean answers get / has / getFill like any stored liquid (always a full source), so swimming, the raycaster and the simulator don't care where a cell's water comes from. what is stored in cells / fills is only what differs from it: another liquid in an ocean cell, a flowing cell, or a hole (the `holes` set: ocean water that was taken away).
 * changing the sea height is one number: no cell is written and no chunk mesh is rebuilt (see OceanSurface and UnderwaterTint)
 */
public final class LiquidLayer {

    /** a full cell of liquid. the simulator works in these units (1 unit = 1/8 of a block of liquid) */
    public static final int MAX_FILL = 8;

    /** the sea surface sits this far below the height you set, so it rests like a liquid tile does instead of flush with the block face */
    public static final float SEA_DIP = 0.1F;

    // the sea only slows down inside this many blocks of its target, everywhere else it moves at the rate you gave
    private static final float EASE_DISTANCE = 0.5F;
    private static final float MIN_SPEED_FACTOR = 0.08F;

    private final Level level;
    private final int width, depth, height;
    private final int layerSize;       // cells in one horizontal layer: the index step for one block up, and the number of columns
    private final byte[] cells;
    private final byte[] fills;
    private final LiquidSimulator simulator;

    // ---- the ocean ----
    private volatile boolean oceanOn = false;
    private volatile float seaHeight = 0.0F;
    private volatile int seaTop = -1;
    private LiquidTile oceanLiquid;
    private byte oceanValue;                    // oceanLiquid.getLiquidIndex() + 1
    private short[] floor;                      // per column (z * width + x)
    private short[] limit;
    private final BitSet holes = new BitSet();  // cells whose ocean water was taken away
    // the highest layer that real water tiles have been placed in for the current sea height (see syncFlood), MIN_VALUE = not set yet
    private int floodedTo = Integer.MIN_VALUE;
    private volatile int oceanVersion = 0;      // bumped when the ocean is made / loaded / changes liquid: everything built from it is out of date

    // the sea rising or falling by itself
    private float seaTarget = 0.0F;
    private float seaStart = 0.0F;              // where the current move began, for the easing
    private float seaRate = 0.0F;               // blocks per second, 0 = still

    // which tiles fill a whole cell, by tile id (0 = not looked at yet, 1 = no, 2 = yes)
    private final byte[] displaceCache = new byte[256];

    private static final int OCEAN_MAGIC = 0x4F43454E;     // "OCEN"

    LiquidLayer(Level level, int width, int depth, int height) {
        this.level = level;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.layerSize = width * height;
        this.cells = new byte[width * depth * height];
        this.fills = new byte[width * depth * height];
        this.simulator = new LiquidSimulator(this, this.cells, this.fills, width, depth, height);
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < this.width && y < this.depth && z < this.height;
    }

    private int index(int x, int y, int z) {
        return (y * this.height + z) * this.width + x;
    }

    /** whether a tile fills its cell, so a liquid can't share it (stone, glass, a full log...). slabs, stairs, panes, doors and sprites leave room for liquid */
    public static boolean displacesLiquid(Tile tile) {
        return tile != null && tile != Tile.AIR && tile.isSolid() && tile.occludesNeighbours()
                && !tile.isBillboard() && !(tile instanceof LiquidTile);
    }

    private boolean displacesId(int id) {
        int cached = this.displaceCache[id & 0xFF];
        if (cached == 0) {
            cached = displacesLiquid(Tile.byId(id & 0xFF)) ? 2 : 1;
            this.displaceCache[id & 0xFF] = (byte) cached;
        }
        return cached == 2;
    }

    // -------------------------------------------------------------------------
    // the ocean

    public boolean hasOcean() {
        return this.oceanOn;
    }

    /** the sea height you set, in blocks. the water is drawn SEA_DIP lower (see getSurfaceHeight) */
    public float getSeaHeight() {
        return this.seaHeight;
    }

    /** where the water really is: the set height minus SEA_DIP. the plane, the shader and the top cell all use this */
    public float getSurfaceHeight() {
        return this.seaHeight - SEA_DIP;
    }

    /** the highest cell that holds ocean, -1 if none */
    public int getSeaTop() {
        return this.seaTop;
    }

    public LiquidTile getOceanLiquid() {
        return this.oceanLiquid;
    }

    /** changes whenever everything built from the ocean (the surface meshes) is out of date */
    public int getOceanVersion() {
        return this.oceanVersion;
    }

    /** moves the whole sea to this height at once. nothing is written to any cell */
    public void setSeaHeight(float h) {
        if (!this.oceanOn) {
            return;
        }
        h = Math.max(0.0F, Math.min(h, (float) this.depth));
        this.seaHeight = h;
        this.seaTop = (int) Math.ceil(h - SEA_DIP) - 1;
        syncFlood();
    }

    /** lets the sea move towards a height by itself (the direction comes from the target). rate is blocks per second, held until the last half block where the move eases out. rate 0 stops it */
    public void riseTo(float target, float rate) {
        this.seaTarget = Math.max(0.0F, Math.min(target, (float) this.depth));
        this.seaStart = this.seaHeight;
        this.seaRate = Math.abs(rate);
    }

    public boolean isSeaMoving() {
        return this.oceanOn && this.seaRate > 0.0F && this.seaHeight != this.seaTarget;
    }

    public float getSeaTarget() {
        return this.seaTarget;
    }

    public float getSeaRate() {
        return this.seaRate;
    }

    /** the liquid the ocean is made of (water in a new world, lava for a lava escape...). cells that already hold something else keep it */
    public void setOceanLiquid(LiquidTile liquid) {
        if (!this.oceanOn || liquid == null || liquid == this.oceanLiquid) {
            return;
        }
        this.oceanLiquid = liquid;
        this.oceanValue = (byte) (liquid.getLiquidIndex() + 1);
        this.oceanVersion++;
    }

    /** the sea level at which this column starts to have ocean on top of it: the surface mesh has a quad for the column once seaTop is above it. -1 if there is no ocean */
    int columnThreshold(int x, int z) {
        if (!this.oceanOn) {
            return -1;
        }
        int c = z * this.width + x;
        return Math.max(this.floor[c], this.limit[c]);
    }

    /**
     * for the terrain tint (UnderwaterTint): the sea height the open cell (x, y, z) is under water from, i.e. it is ocean as soon as seaHeight is above the returned
     * number. -1 when the cell can never be ocean (no ocean, under the floor) or isn't plain ocean right now (it has its own liquid, or it is a hole)
     */
    public int oceanWetFrom(int x, int y, int z) {
        if (!this.oceanOn || !inside(x, y, z)) {
            return -1;
        }
        int c = z * this.width + x;
        if (y <= this.floor[c]) {
            return -1;
        }
        int i = index(x, y, z);
        if (this.cells[i] != 0 || this.holes.get(i)) {
            return -1;
        }
        return Math.max(y, this.limit[c] + 1);
    }

    // is the cell inside the ocean's reach, whatever it holds right now (blocks count: they are never ocean)
    private boolean regionCell(int i) {
        if (!this.oceanOn) {
            return false;
        }
        int top = this.seaTop;
        int y = i / this.layerSize;
        if (y > top) {
            return false;
        }
        int c = i - y * this.layerSize;
        if (y <= this.floor[c] || top <= this.limit[c]) {
            return false;
        }
        int x = c % this.width;
        int z = c / this.width;
        return !displacesId(this.level.getTile(x, y, z));
    }

    // package-private, for the simulator: the liquid of a cell as it really is (stored or ocean)

    /** liquid index + 1 of the cell, 0 = none */
    byte typeAt(int i) {
        byte v = this.cells[i];
        if (v != 0) {
            return v;
        }
        return this.oceanOn && regionCell(i) && !this.holes.get(i) ? this.oceanValue : 0;
    }

    /** fill of the cell, 0 = none */
    int fillAt(int i) {
        if (this.cells[i] != 0) {
            return this.fills[i] & 0xFF;
        }
        return this.oceanOn && regionCell(i) && !this.holes.get(i) ? MAX_FILL : 0;
    }

    /** plain ocean: no liquid of its own stored here and no hole. the simulator never changes these, it only lets them feed their neighbours */
    boolean isOceanCell(int i) {
        return this.oceanOn && this.cells[i] == 0 && regionCell(i) && !this.holes.get(i);
    }

    /** a cell that is ocean's own (even if empty or holding something else right now) */
    boolean isRegionCell(int i) {
        return regionCell(i);
    }

    public boolean isOceanCell(int x, int y, int z) {
        return inside(x, y, z) && isOceanCell(index(x, y, z));
    }

    /** the height of the surface of an ocean cell in blocks above the bottom of the cell: 1 under the top, seaHeight - y in the top cell */
    public float oceanCellHeight(int y) {
        return y < this.seaTop ? 1.0F : Math.max(0.0F, Math.min(1.0F, getSurfaceHeight() - y));
    }

    /** liquid index + 1 of the ocean's liquid */
    byte oceanValue() {
        return this.oceanValue;
    }

    int getSeaTopForSim() {
        return this.seaTop;
    }

    // -------------------------------------------------------------------------
    // reading

    /** the liquid in the cell, null if none (or outside the level) */
    public LiquidTile get(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return null;
        }
        int value = typeAt(index(x, y, z));
        return value == 0 ? null : LiquidTile.byIndex(value - 1);
    }

    public boolean has(int x, int y, int z) {
        return inside(x, y, z) && typeAt(index(x, y, z)) != 0;
    }

    /** only the liquid stored in the cell, without the ocean (what the chunk meshes draw: the ocean has its own surface mesh) */
    public LiquidTile getStored(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return null;
        }
        int value = this.cells[index(x, y, z)];
        return value == 0 ? null : LiquidTile.byIndex(value - 1);
    }

    public boolean hasStored(int x, int y, int z) {
        return inside(x, y, z) && this.cells[index(x, y, z)] != 0;
    }

    /** how full the cell is: 0 = no liquid, MAX_FILL = a whole block */
    public int getFill(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return 0;
        }
        return fillAt(index(x, y, z));
    }

    // -------------------------------------------------------------------------
    // writing

    /** puts a full cell of liquid in the cell (replacing another one). false if nothing changed: outside the level, same liquid already there and full, or a full block in the way */
    public boolean set(int x, int y, int z, LiquidTile liquid) {
        return set(x, y, z, liquid, MAX_FILL);
    }

    /** same, with a given amount (1..MAX_FILL). the amount is what then spreads out when the simulation is on */
    public boolean set(int x, int y, int z, LiquidTile liquid, int fill) {
        if (liquid == null || fill <= 0) {
            return clear(x, y, z);
        }
        if (!inside(x, y, z) || displacesLiquid(Tile.byId(this.level.getTile(x, y, z)))) {
            return false;
        }
        byte value = (byte) (liquid.getLiquidIndex() + 1);
        byte amount = (byte) Math.min(fill, MAX_FILL);
        int i = index(x, y, z);
        if (typeAt(i) == value && fillAt(i) == amount) {
            return false;
        }
        applyRaw(i, value, amount);
        this.simulator.wake(x, y, z);
        return true;
    }

    /** takes the liquid out of the cell. in the ocean that leaves a hole, which the simulation then fills again from the sides */
    public boolean clear(int x, int y, int z) {
        if (!has(x, y, z)) {
            return false;
        }
        applyRaw(index(x, y, z), (byte) 0, (byte) 0);
        this.simulator.wake(x, y, z);
        return true;
    }

    // -------------------------------------------------------------------------
    // simulation

    /**
     * Level calls this after every block change. the floor of a column never changes (see the class comment), so all there is to do is to let
     * the sea in: a cell that was just opened (a block dug out) at or under the flood level, next to ocean water, fills up, and so does
     * everything open behind it. a hole that is not connected to water stays dry, and so does one above the flood level (the simulation
     * handles water there)
     */
    void blockChanged(int x, int y, int z) {
        // the sea never writes water tiles (they made rising and digging lag): the virtual ocean and the surface mesh are all it is
    }

    // ---- flooding: real water tiles where the sea reaches ----

    /** the highest layer whose tiles are covered by the sea surface: a stored cell's surface is SURFACE_HEIGHT above its bottom */
    private int floodLevel() {
        return Math.min(this.seaTop, (int) Math.floor(getSurfaceHeight() - LiquidTile.SURFACE_HEIGHT + 1.0E-3F));
    }

    /**
     * called whenever the sea height is set. going up places nothing (the virtual ocean covers it). going down: the
     * water tiles above the new level are woken so the simulation can drain them (or leave them, in a basin)
     */
    private void syncFlood() {
        int target = floodLevel();
        if (this.floodedTo == Integer.MIN_VALUE) {
            this.floodedTo = target;       // first time (world made / loaded): nothing has risen, the world is as it was made / saved
            return;
        }
        if (target == this.floodedTo) {
            return;
        }
        int old = this.floodedTo;
        this.floodedTo = target;
        if (target < old) {
            for (int y = Math.max(target + 1, 0); y <= Math.min(old, this.depth - 1); y++) {
                wakeLayerLiquids(y);
            }
        }
    }

    /** wakes the stored liquid of a layer, the simulation then lets it flow (the sea that held it up is gone) */
    private void wakeLayerLiquids(int y) {
        int base = y * this.layerSize;
        for (int c = 0; c < this.layerSize; c++) {
            if (this.cells[base + c] != 0) {
                this.simulator.wake(c % this.width, y, c / this.width);
            }
        }
    }

    /** an open cell with no liquid in it */
    private boolean openDry(int i) {
        int y = i / this.layerSize;
        int c = i - y * this.layerSize;
        return typeAt(i) == 0 && !displacesId(this.level.getTile(c % this.width, y, c / this.width));
    }

    /** makes the simulation look at this cell and its neighbours again. Level calls it for every block placed or removed; it costs nothing where there is no liquid around */
    public void wake(int x, int y, int z) {
        if (inside(x, y, z)) {
            this.simulator.wake(x, y, z);
        }
    }

    /** advances the simulation by real time, called every frame. does nothing while the simulation is off. also moves the sea when it is rising or falling */
    public void update(float seconds) {
        if (isSeaMoving()) {
            float diff = this.seaTarget - this.seaHeight;
            float remaining = Math.abs(diff);

            // constant speed, except for the last EASE_DISTANCE blocks where it smoothly slows down to settle on the target
            float t = Math.min(1.0F, remaining / EASE_DISTANCE);
            float factor = t >= 1.0F ? 1.0F : Math.max(MIN_SPEED_FACTOR, t * t * (3.0F - 2.0F * t));

            float step = this.seaRate * factor * seconds;
            if (remaining <= step || remaining < 0.002F) {
                setSeaHeight(this.seaTarget);
                this.seaRate = 0.0F;
            } else {
                setSeaHeight(this.seaHeight + Math.signum(diff) * step);
            }
        }
        this.simulator.update(seconds);
    }

    /** runs this many simulation ticks right now, even while the simulation is off */
    public void step(int ticks) {
        this.simulator.stepNow(ticks);
    }

    /** number of cells the simulation is currently keeping an eye on */
    public int getActiveCells() {
        return this.simulator.activeCount();
    }

    // package-private, for the simulator

    boolean passable(int x, int y, int z) {
        return inside(x, y, z) && !displacesId(this.level.getTile(x, y, z));
    }

    /**
     * writes a cell and tells the level, so the mesh is rebuilt. the same liquid with another fill counts as a change too.
     * inside the ocean the stored arrays only keep what differs from plain ocean water: writing a full cell of the ocean's liquid
     * removes whatever was stored (and the hole), writing nothing makes a hole
     */
    void applyRaw(int i, byte type, byte fill) {
        byte oldType = typeAt(i);
        int oldFill = fillAt(i);

        if (this.oceanOn && regionCell(i)) {
            if (type == this.oceanValue && (fill & 0xFF) >= MAX_FILL) {
                this.cells[i] = 0;
                this.fills[i] = 0;
                this.holes.clear(i);
            } else if (type == 0) {
                this.cells[i] = 0;
                this.fills[i] = 0;
                this.holes.set(i);
            } else {
                this.cells[i] = type;
                this.fills[i] = fill;
                this.holes.clear(i);
            }
        } else {
            this.cells[i] = type;
            this.fills[i] = fill;
            if (this.oceanOn && this.holes.get(i)) {
                this.holes.clear(i);
            }
        }

        if (this.snapshotActive && i < this.snapshotCopied) {
            this.cellsSnapshot[i] = this.cells[i];
            this.fillsSnapshot[i] = this.fills[i];
        }

        byte newType = typeAt(i);
        int newFill = fillAt(i);
        if (oldType == newType && oldFill == newFill) {
            return;
        }

        int x = i % this.width;
        int rest = i / this.width;
        int z = rest % this.height;
        int y = rest / this.height;
        this.level.liquidChanged(x, y, z,
                oldType == 0 ? null : LiquidTile.byIndex(oldType - 1),
                newType == 0 ? null : LiquidTile.byIndex(newType - 1));
    }

    // -------------------------------------------------------------------------
    // saving: a gzip file next to the level file. liquids are saved by tile NAME (a name table, then
    // runs of equal cells), so reordering the tile registry can't corrupt a save. after the cell runs
    // comes a second set of runs with the fill of every cell (older files stop before it: every cell is
    // then full). after that, if the world has an ocean: a marker, the sea height, the ocean's liquid name,
    // the holes, and the floor and limit of every column as runs (older files stop before it: no ocean,
    // everything is stored cell by cell like it used to be)

    void save(File file) {
        write(file, this.cells, this.fills, this.oceanOn, this.seaHeight,
                this.oceanOn ? this.oceanLiquid.name : null, this.holes, this.floor, this.limit);
    }

    // -------------------------------------------------------------------------
    // autosave snapshot: the cells and fills are copied a slice per tick (Level.tickAutosave) and applyRaw
    // writes into the part already copied, so when the last slice is done the copy equals the live layer.
    // the gzip + disk write then runs on the save thread from the copy, not from the live arrays

    private byte[] cellsSnapshot;
    private byte[] fillsSnapshot;
    private boolean snapshotActive = false;
    private int snapshotCopied = 0;

    void beginSnapshot() {
        if (this.cellsSnapshot == null || this.cellsSnapshot.length != this.cells.length) {
            this.cellsSnapshot = new byte[this.cells.length];
            this.fillsSnapshot = new byte[this.fills.length];
        }
        this.snapshotCopied = 0;
        this.snapshotActive = true;
    }

    /** copies up to count more cells into the snapshot, true once everything is copied */
    boolean copySnapshotSlice(int count) {
        int n = Math.min(count, this.cells.length - this.snapshotCopied);
        System.arraycopy(this.cells, this.snapshotCopied, this.cellsSnapshot, this.snapshotCopied, n);
        System.arraycopy(this.fills, this.snapshotCopied, this.fillsSnapshot, this.snapshotCopied, n);
        this.snapshotCopied += n;
        return this.snapshotCopied >= this.cells.length;
    }

    void cancelSnapshot() {
        this.snapshotActive = false;
    }

    /** drops the copy buffers (they are made again by the next beginSnapshot) */
    void releaseSnapshot() {
        this.snapshotActive = false;
        this.cellsSnapshot = null;
        this.fillsSnapshot = null;
    }

    /** call right after the last slice: takes the small parts (holes, floor, limit) and returns the write for the save thread */
    Runnable finishSnapshot(File file) {
        this.snapshotActive = false;
        final byte[] c = this.cellsSnapshot;
        final byte[] f = this.fillsSnapshot;
        final boolean ocean = this.oceanOn;
        final float sea = this.seaHeight;
        final String oceanName = ocean ? this.oceanLiquid.name : null;
        final BitSet holesCopy = (BitSet) this.holes.clone();
        final short[] floorCopy = this.floor == null ? null : this.floor.clone();
        final short[] limitCopy = this.limit == null ? null : this.limit.clone();
        return () -> write(file, c, f, ocean, sea, oceanName, holesCopy, floorCopy, limitCopy);
    }

    private void write(File file, byte[] cellData, byte[] fillData, boolean ocean, float sea, String oceanName,
                       BitSet holeSet, short[] floorData, short[] limitData) {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            writeBody(out, cellData, fillData, ocean, sea, oceanName, holeSet, floorData, limitData);
        } catch (IOException e) {
            System.err.println("[LiquidLayer] could not save " + file + ": " + e);
        }
    }

    /** the save format without the gzip and the file around it, so the network snapshot can put it inside its own stream */
    private void writeBody(DataOutputStream out, byte[] cellData, byte[] fillData, boolean ocean, float sea,
                           String oceanName, BitSet holeSet, short[] floorData, short[] limitData) throws IOException {
        List<LiquidTile> all = LiquidTile.all();
        out.writeInt(this.width);
        out.writeInt(this.depth);
        out.writeInt(this.height);
        out.writeInt(all.size());
        for (LiquidTile liquid : all) {
            out.writeUTF(liquid.name);
        }

        writeRuns(out, cellData);
        writeRuns(out, fillData);

        if (ocean) {
            out.writeInt(OCEAN_MAGIC);
            out.writeFloat(sea);
            out.writeUTF(oceanName);
            out.writeInt(holeSet.cardinality());
            for (int i = holeSet.nextSetBit(0); i >= 0; i = holeSet.nextSetBit(i + 1)) {
                out.writeInt(i);
            }
            writeShortRuns(out, floorData);
            writeShortRuns(out, limitData);
        }
    }

    // -------------------------------------------------------------------------
    // network snapshot: a copy of the layer made a slice per tick by the server (see NetworkSnapshot.Capture) and
    // written on another thread. cells that change while it is being copied are not mirrored into it on purpose:
    // every change is also broadcast to the joining player, who replays them over the snapshot, so each cell ends up
    // with its latest value either way

    /** a frozen copy of the layer, filled by copySlice */
    static final class Frozen {
        final byte[] cells;
        final byte[] fills;
        final boolean ocean;
        final float sea;
        final String oceanName;
        final BitSet holes;
        final short[] floor;
        final short[] limit;
        private int copied = 0;

        private Frozen(LiquidLayer layer) {
            this.cells = new byte[layer.cells.length];
            this.fills = new byte[layer.fills.length];
            this.ocean = layer.oceanOn;
            this.sea = layer.seaHeight;
            this.oceanName = this.ocean ? layer.oceanLiquid.name : null;
            this.holes = (BitSet) layer.holes.clone();
            this.floor = layer.floor == null ? null : layer.floor.clone();
            this.limit = layer.limit == null ? null : layer.limit.clone();
        }
    }

    /** takes the small parts (sea, holes, floor, limit) now, the big arrays are copied by copyFrozenSlice */
    Frozen freeze() {
        return new Frozen(this);
    }

    /** copies up to count more cells into the frozen copy, true once everything is copied */
    boolean copyFrozenSlice(Frozen frozen, int count) {
        int n = Math.min(count, this.cells.length - frozen.copied);
        System.arraycopy(this.cells, frozen.copied, frozen.cells, frozen.copied, n);
        System.arraycopy(this.fills, frozen.copied, frozen.fills, frozen.copied, n);
        frozen.copied += n;
        return frozen.copied >= this.cells.length;
    }

    /** any thread: writes a finished frozen copy in the save format, uncompressed (the caller's stream compresses) */
    void writeFrozen(Frozen frozen, DataOutputStream out) throws IOException {
        writeBody(out, frozen.cells, frozen.fills, frozen.ocean, frozen.sea, frozen.oceanName, frozen.holes,
                frozen.floor, frozen.limit);
    }

    private static void writeRuns(DataOutputStream out, byte[] data) throws IOException {
        int n = data.length;
        int i = 0;
        while (i < n) {
            byte value = data[i];
            int j = i + 1;
            while (j < n && data[j] == value) {
                j++;
            }
            out.writeByte(value);
            out.writeInt(j - i);
            i = j;
        }
    }

    private static void writeShortRuns(DataOutputStream out, short[] data) throws IOException {
        int n = data.length;
        int i = 0;
        while (i < n) {
            short value = data[i];
            int j = i + 1;
            while (j < n && data[j] == value) {
                j++;
            }
            out.writeShort(value);
            out.writeInt(j - i);
            i = j;
        }
    }

    private static short[] readShortRuns(DataInputStream in, int length) throws IOException {
        short[] data = new short[length];
        int i = 0;
        while (i < length) {
            short value = in.readShort();
            int run = in.readInt();
            if (run < 1 || i + run > length) {
                throw new IOException("corrupt column run at " + i);
            }
            java.util.Arrays.fill(data, i, i + run, value);
            i += run;
        }
        return data;
    }

    /**
     * makes the ocean of a freshly generated world from the water the generator reports: per column (index z * width + x) the y of the water surface, -1 for a dry column.
     * nothing is stored per cell: a wet column gets its floor (the first block under the surface, like the old fill went down until it hit one) and limit -1,
     * a dry column gets the height of its highest block as the floor and, as the limit, whichever is higher of that and the sea's surface, so it stays dry until the sea rises over it.
     * the sea starts at the generator's surface plus the height of an open cell. no notifications and no waking: the level isn't listened to yet and the simulation starts quiet
     */
    void fillGenerated(int[] surfaceY, LiquidTile liquid) {
        int topCell = -1;
        for (int top : surfaceY) {
            topCell = Math.max(topCell, top);
        }
        if (topCell < 0) {
            return;                       // no water in this world
        }
        final int surface = Math.min(topCell, this.depth - 1);

        short[] floors = new short[this.layerSize];
        short[] limits = new short[this.layerSize];

        // every row writes its own columns, so the rows run on all cores
        java.util.stream.IntStream.range(0, this.height).parallel().forEach(z -> {
            for (int x = 0; x < this.width; x++) {
                int c = z * this.width + x;
                int top = surfaceY[c];
                if (top >= 0) {
                    int y = Math.min(top, this.depth - 1);
                    while (y >= 0 && !displacesId(this.level.getTile(x, y, z))) {
                        y--;
                    }
                    floors[c] = (short) y;                 // -1 when nothing stops the water
                    limits[c] = -1;
                } else {
                    int y = this.depth - 1;
                    while (y >= 0 && !displacesId(this.level.getTile(x, y, z))) {
                        y--;
                    }
                    floors[c] = (short) y;
                    // dry land stays dry until the sea rises over its top: it then becomes virtual ocean, no tiles are written
                    limits[c] = (short) Math.max(y, surface);
                }
            }
        });

        this.floor = floors;
        this.limit = limits;
        this.oceanLiquid = liquid;
        this.oceanValue = (byte) (liquid.getLiquidIndex() + 1);
        this.holes.clear();
        this.oceanOn = true;
        this.floodedTo = Integer.MIN_VALUE;
        // + SEA_DIP so the drawn surface ends up exactly as high as an open liquid tile's
        setSeaHeight(surface + LiquidTile.SURFACE_HEIGHT + SEA_DIP);
        this.seaTarget = this.seaHeight;
        this.seaRate = 0.0F;
        this.oceanVersion++;
    }

    /** replaces everything with the saved liquids, false if there is no usable file (nothing is changed then). names that no longer exist are skipped, no notifications */
    boolean load(File file) {
        if (!file.isFile()) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            return load(in);
        } catch (IOException e) {
            System.err.println("[LiquidLayer] could not load " + file + ": " + e);
            return false;
        }
    }

    /** same as load(File) from an open stream (the network snapshot). the layer's data must be the last thing in the stream */
    boolean load(DataInputStream in) throws IOException {
        {
            if (in.readInt() != this.width || in.readInt() != this.depth || in.readInt() != this.height) {
                System.err.println("[LiquidLayer] saved liquids are for a different world size, ignored");
                return false;
            }

            int names = in.readInt();
            byte[] remap = new byte[names + 1];          // saved value -> current value
            String[] savedNames = new String[names];
            for (int k = 0; k < names; k++) {
                savedNames[k] = in.readUTF();
                Tile tile = Tile.byName(savedNames[k]);
                remap[k + 1] = tile instanceof LiquidTile ? (byte) (((LiquidTile) tile).getLiquidIndex() + 1) : 0;
            }

            byte[] loaded = new byte[this.cells.length];
            int i = 0;
            while (i < loaded.length) {
                int value = in.readByte();
                int run = in.readInt();
                if (run < 1 || i + run > loaded.length || value < 0 || value > names) {
                    throw new IOException("corrupt run at cell " + i);
                }
                byte mapped = remap[value];
                if (mapped != 0) {
                    java.util.Arrays.fill(loaded, i, i + run, mapped);
                }
                i += run;
            }

            // fills: missing in older files, then every liquid cell is a full block
            byte[] loadedFills = new byte[this.cells.length];
            boolean hasFills = true;
            try {
                i = 0;
                while (i < loadedFills.length) {
                    int value = in.readByte();
                    int run = in.readInt();
                    if (run < 1 || i + run > loadedFills.length || value < 0 || value > MAX_FILL) {
                        throw new IOException("corrupt fill run at cell " + i);
                    }
                    java.util.Arrays.fill(loadedFills, i, i + run, (byte) value);
                    i += run;
                }
            } catch (EOFException e) {
                java.util.Arrays.fill(loadedFills, (byte) MAX_FILL);
                hasFills = false;
            }
            for (int k = 0; k < loaded.length; k++) {
                if (loaded[k] == 0) {
                    loadedFills[k] = 0;
                } else if (loadedFills[k] == 0) {
                    loadedFills[k] = (byte) MAX_FILL;
                }
            }

            // the ocean: only in files that have it
            boolean ocean = false;
            float savedSea = 0.0F;
            LiquidTile savedOceanLiquid = null;
            BitSet savedHoles = new BitSet();
            short[] savedFloor = null;
            short[] savedLimit = null;
            if (hasFills) {
                try {
                    if (in.readInt() == OCEAN_MAGIC) {
                        savedSea = in.readFloat();
                        Tile tile = Tile.byName(in.readUTF());
                        int holeCount = in.readInt();
                        for (int k = 0; k < holeCount; k++) {
                            int cell = in.readInt();
                            if (cell < 0 || cell >= this.cells.length) {
                                throw new IOException("corrupt hole " + cell);
                            }
                            savedHoles.set(cell);
                        }
                        savedFloor = readShortRuns(in, this.layerSize);
                        savedLimit = readShortRuns(in, this.layerSize);
                        if (tile instanceof LiquidTile) {
                            savedOceanLiquid = (LiquidTile) tile;
                            ocean = true;
                        }
                    }
                } catch (EOFException e) {
                    ocean = false;       // an older file, no ocean in it
                }
            }

            System.arraycopy(loaded, 0, this.cells, 0, loaded.length);
            System.arraycopy(loadedFills, 0, this.fills, 0, loadedFills.length);

            this.holes.clear();
            this.seaRate = 0.0F;
            if (ocean) {
                this.floor = savedFloor;
                this.limit = savedLimit;
                this.holes.or(savedHoles);
                this.oceanLiquid = savedOceanLiquid;
                this.oceanValue = (byte) (savedOceanLiquid.getLiquidIndex() + 1);
                this.oceanOn = true;
                this.floodedTo = Integer.MIN_VALUE;
                setSeaHeight(savedSea);
                this.seaTarget = this.seaHeight;
                // a version of the game saved every dry column with the depth of the world as its limit, which made dry land
                // un-floodable (the sea rose but kept the shape it had when the world was made). those columns get the
                // limit a dry column has: the top of its floor, or the sea as it is now if that is higher, so a rising sea covers them again
                for (int c = 0; c < this.limit.length; c++) {
                    if (this.limit[c] >= this.depth) {
                        this.limit[c] = (short) Math.max(this.floor[c], this.seaTop);
                    }
                }
            } else {
                this.oceanOn = false;
                this.seaTop = -1;
            }
            this.oceanVersion++;

            this.simulator.wakeUnsettled();     // flowing liquid saved without a source drains once
            return true;
        }
    }
}