package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.level.generation.WorldGenStatus;
import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.phys.AABB;
import com.nikohalfkino.rubydung.util.SaveQueue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class Level {

    public static final int GROUND_HEIGHT = 64 * 2 / 3;

    public static final int SHADOW_FADE_DISTANCE = 10;

    public static final float SHADOW_DARKEST = 0.5F;

    public static final int MAX_LIGHT_RADIUS = 16;

    public final int width;
    public final int height;
    public final int depth;

    private final String worldName;

    private long seed;
    private LevelGenerator.WorldType type;

    public long getSeed() {
        return this.seed;
    }

    public LevelGenerator.WorldType getWorldType() {
        return this.type;
    }

    public String getWorldName() {
        return this.worldName;
    }

    private TilePalette palette;

    private final byte[] blocks;
    private final byte[] data;

    final LevelLighting lighting;

    private final HashMap<Integer, MultiTileInstance> multiTileCells = new HashMap<>();
    private final Set<MultiTileInstance> multiTiles = new LinkedHashSet<>();

    private List<int[]> generatedMultiTiles;
    private int[] generatedWater;

    final ArrayList<LevelListener> levelListeners = new ArrayList<>();

    // multiplayer: told about every change that has to reach other players (null in a single player game)
    private LevelEditListener editListener;
    // above 0 while a change that came from the network is applied: those are not reported back
    private int networkApplyDepth = 0;
    // a world downloaded from a server is never written to disk
    private boolean readOnly = false;

    private final SubTiles subTiles = new SubTiles(this);

    // the liquid of every cell, separate from the block array so a block can share a cell with it
    private final LiquidLayer liquids;

    // the weighted cubes: free-standing bodies, saved next to the level (see cubeFile)
    private final CubeManager cubes = new CubeManager(this);

    public boolean hasSavedPosition = false;
    public double savedPlayerX, savedPlayerY, savedPlayerZ;
    public float savedPlayerYRotation, savedPlayerXRotation;

    public boolean hasFlightState = false;
    public boolean savedFlying, savedNoClip;
    public float savedFlySpeedMultiplier = 1.0F;

    public void setSkyAmbient(float ambient) {
        this.lighting.setSkyAmbient(ambient);
    }

    public float getSkyAmbient() {
        return this.lighting.getSkyAmbient();
    }

    public void setPlayerFlightState(boolean flying, boolean noClip, float flySpeedMultiplier) {
        this.savedFlying = flying;
        this.savedNoClip = noClip;
        this.savedFlySpeedMultiplier = flySpeedMultiplier;
        this.hasFlightState = true;
    }

    public Level(String worldName, int width, int height, int depth, LevelGenerator.WorldType type) {
        this(worldName, width, height, depth, type, new Random().nextLong(), true);
    }

    public Level(String worldName, int width, int height, int depth, LevelGenerator.WorldType type, long seed) {
        this(worldName, width, height, depth, type, seed, false);
    }

    private Level(String worldName, int width, int height, int depth, LevelGenerator.WorldType type, long seed,
                  boolean loadFromDisk) {
        this.worldName = worldName;
        this.width  = width;
        this.height = height;
        this.depth  = depth;
        this.type   = type;
        this.seed   = seed;

        boolean hasSave = loadFromDisk && WorldManager.levelFile(worldName).isFile();
        LevelGenerator.Result generated = hasSave
                ? null
                : LevelGenerator.generateWorld(type, width, height, depth, seed);
        this.blocks = hasSave ? new byte[width * height * depth] : generated.blocks;
        this.generatedMultiTiles = hasSave ? null : generated.multiTileOrigins;
        this.generatedWater = hasSave ? null : generated.waterTops;
        this.data = new byte[width * height * depth];
        WorldGenStatus.set("Preparing the world...");
        long mark = System.nanoTime();

        this.liquids = new LiquidLayer(this, width, depth, height);

        this.palette  = TilePalette.fromCurrentRegistry();
        this.lighting = new LevelLighting(this);
        mark = LevelGenerator.lap("level arrays", mark);

        WorldGenStatus.set("Measuring shadows...");
        this.lighting.calcLightDepths(0, 0, width, height);
        mark = LevelGenerator.lap("light depths", mark);

        if (loadFromDisk) {
            load();
            LevelGenerator.lap("load", mark);
        } else {
            WorldGenStatus.set("Flooding the seas...");
            registerMultiTiles(this.generatedMultiTiles);
            applyGeneratedWater();
            mark = LevelGenerator.lap("water + objects", mark);
            WorldGenStatus.set("Lighting lamps...");
            this.lighting.rebuildBlockLight();
            LevelGenerator.lap("block light", mark);
        }

        this.generatedMultiTiles = null;
        this.generatedWater = null;
        // what comes next is the loading screen building the chunk meshes
        WorldGenStatus.set("Building terrain...");
    }

    /**
     * a world made of arrays received from a server (see NetworkSnapshot). nothing is generated or read from disk,
     * the caller fills in multi-tiles, sub-tiles and liquids and then lights the level
     */
    Level(String worldName, int width, int height, int depth, LevelGenerator.WorldType type, long seed,
          byte[] blocks, byte[] data) {
        this.worldName = worldName;
        this.width  = width;
        this.height = height;
        this.depth  = depth;
        this.type   = type;
        this.seed   = seed;
        this.blocks = blocks;
        this.data   = data;
        this.liquids = new LiquidLayer(this, width, depth, height);
        this.palette = TilePalette.fromCurrentRegistry();
        this.lighting = new LevelLighting(this);
    }

    byte[] blocksArray() {
        return this.blocks;
    }

    byte[] dataArray() {
        return this.data;
    }

    // multiplayer

    public void setEditListener(LevelEditListener listener) {
        this.editListener = listener;
    }

    public LevelEditListener getEditListener() {
        return this.editListener;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() {
        return this.readOnly;
    }

    /** a tile change that came from the network: applied, but not reported to the edit listener */
    public void applyTileFromNetwork(int x, int y, int z, int id, int data) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return;
        }
        this.networkApplyDepth++;
        try {
            int currentId = getTile(x, y, z);
            if (currentId == (id & 0xFF) && getData(x, y, z) == (data & 0xFF)) {
                return;
            }
            Tile tile = Tile.byId(id & 0xFF);
            if (currentId == (id & 0xFF) && tile != null && tile.isMultiTile()) {
                setData(x, y, z, data & 0xFF);
            } else {
                setTile(x, y, z, id & 0xFF, data & 0xFF);
            }
        } finally {
            this.networkApplyDepth--;
        }
    }

    /** a liquid cell that came from the network (tileId 0 = dry) */
    public void applyLiquidFromNetwork(int x, int y, int z, int tileId, int fill) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return;
        }
        this.networkApplyDepth++;
        try {
            Tile tile = Tile.byId(tileId & 0xFF);
            if (tile instanceof LiquidTile) {
                this.liquids.set(x, y, z, (LiquidTile) tile, fill);
            } else {
                this.liquids.clear(x, y, z);
            }
        } finally {
            this.networkApplyDepth--;
        }
    }

    /** server: a client asked for these sub-tiles in a cell. unlike applySubTilesFromNetwork this is reported, so everyone is told */
    public void setSubTilesOfCell(int x, int y, int z, List<SubTiles.Entry> entries) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return;
        }
        this.subTiles.replaceCell(x, y, z, entries);
    }

    /** the sub-tiles of a cell as the network says they are */
    public void applySubTilesFromNetwork(int x, int y, int z, List<SubTiles.Entry> entries) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return;
        }
        this.networkApplyDepth++;
        try {
            this.subTiles.replaceCell(x, y, z, entries);
        } finally {
            this.networkApplyDepth--;
        }
    }

    /** floods the oceans and lakes of a freshly generated world (nothing to do for loaded or flat worlds) */
    private void applyGeneratedWater() {
        if (this.generatedWater != null) {
            this.liquids.fillGenerated(this.generatedWater, (LiquidTile) Tile.WATER);
        }
    }

    public void load() {
        cancelSnapshot();
        File levelFile = WorldManager.levelFile(this.worldName);
        boolean hasSave = levelFile.isFile();

        LevelIO.SavedPosition position = LevelIO.load(levelFile, this.blocks, this.data);

        if (hasSave) {
            TilePalette saved = WorldManager.loadWorldPalette(this.worldName);
            this.palette = saved != null ? saved : TilePalette.legacy();
            this.palette.decode(this.blocks);
        } else {
            this.palette = TilePalette.fromCurrentRegistry();
        }

        if (hasSave) {
            registerMultiTiles(WorldManager.loadWorldMultiTiles(this.worldName));
        } else {
            registerMultiTiles(this.generatedMultiTiles);
            applyGeneratedWater();
        }

        if (hasSave) {
            this.subTiles.restore(WorldManager.loadWorldSubTiles(this.worldName));
            this.liquids.load(liquidFile());
            this.cubes.load(cubeFile());
        }

        this.lighting.calcLightDepths(0, 0, this.width, this.height);
        this.lighting.rebuildBlockLight();

        if (hasSave) {
            // chess pieces were tiles once, now they are entities
            this.cubes.convertChessTiles();
        }

        if (position.hasSeed) {
            this.seed = position.seed;
        }
        if (position.hasWorldType) {
            this.type = position.worldType;
        }

        if (position.hasFlightState) {
            this.hasFlightState = true;
            this.savedFlying    = position.flying;
            this.savedNoClip    = position.noClip;
            this.savedFlySpeedMultiplier = position.flySpeedMultiplier;
        }

        this.hasSavedPosition = position.present;
        if (position.present) {
            this.savedPlayerX        = position.x;
            this.savedPlayerY        = position.y;
            this.savedPlayerZ        = position.z;
            this.savedPlayerYRotation = position.yRotation;
            this.savedPlayerXRotation = position.xRotation;
        }

        for (LevelListener levelListener : this.levelListeners) {
            levelListener.allChanged();
        }
    }

    public void save() {
        if (this.readOnly) {
            return;
        }
        SaveQueue.flush();
        cancelSnapshot();
        byte[] encodedBlocks = writeSideFiles();
        LevelIO.save(WorldManager.levelFile(this.worldName), encodedBlocks, this.data, buildSavedPosition());
    }

    // set from the moment an autosave starts copying until it has reached the disk, see saveAsync
    private final AtomicBoolean asyncSaveRunning = new AtomicBoolean(false);

    // the autosave's copy of the block and data arrays. kept between autosaves, so a big world doesn't allocate
    // (and zero) two huge arrays every few minutes
    private byte[] blocksSnapshot;
    private byte[] dataSnapshot;
    private boolean snapshotPending = false;
    private int snapshotCopied = 0;
    private Runnable snapshotOnDone;

    // bytes of each array copied per tick: a couple of MB, a fraction of a millisecond
    private static final int SNAPSHOT_SLICE = 1 << 21;

    /**
     * the autosave. Copying the whole block and data arrays in one go was a visible hiccup in big worlds, so this
     * only starts it: tickAutosave() (called every game tick) copies a slice per tick, and setBlock / setData
     * write into the part already copied, so when the last slice is done the copy equals the live world.
     * Only then are the small snapshots taken and everything slow handed to the save thread: the palette encode,
     * the side files and the gzip + disk write.
     *
     * If the previous autosave has not reached the disk yet this one is skipped, it would reuse the copies it is
     * still writing. Saving on quit or door travel goes through save() and never skips.
     */
    public void saveAsync(Runnable onDone) {
        if (this.readOnly || !this.asyncSaveRunning.compareAndSet(false, true)) {
            return;
        }

        int size = this.blocks.length;
        if (this.blocksSnapshot == null || this.blocksSnapshot.length != size) {
            this.blocksSnapshot = new byte[size];
            this.dataSnapshot = new byte[size];
        }
        this.snapshotCopied = 0;
        this.snapshotOnDone = onDone;
        this.snapshotPending = true;
        this.liquids.beginSnapshot();
    }

    /** game thread, once per tick: copies the next slice of an autosave that is under way (does nothing otherwise) */
    public void tickAutosave() {
        if (!this.snapshotPending) {
            return;
        }

        int count = Math.min(SNAPSHOT_SLICE, this.blocks.length - this.snapshotCopied);
        System.arraycopy(this.blocks, this.snapshotCopied, this.blocksSnapshot, this.snapshotCopied, count);
        System.arraycopy(this.data, this.snapshotCopied, this.dataSnapshot, this.snapshotCopied, count);
        this.snapshotCopied += count;
        boolean liquidsDone = this.liquids.copySnapshotSlice(SNAPSHOT_SLICE);

        if (this.snapshotCopied >= this.blocks.length && liquidsDone) {
            finishSnapshot();
        }
    }

    // a full save (or a load) replaces what an autosave in progress was copying
    private void cancelSnapshot() {
        if (this.snapshotPending) {
            this.snapshotPending = false;
            this.snapshotOnDone = null;
            this.liquids.cancelSnapshot();
            this.asyncSaveRunning.set(false);
        }
    }

    private void finishSnapshot() {
        this.snapshotPending = false;
        Runnable onDone = this.snapshotOnDone;
        this.snapshotOnDone = null;

        try {
            String name = this.worldName;
            File file = WorldManager.levelFile(name);
            TilePalette savePalette = this.palette;
            LevelGenerator.WorldType saveType = this.type;

            byte[] blocksCopy = this.blocksSnapshot;
            byte[] dataCopy = this.dataSnapshot;
            LevelIO.SavedPosition position = buildSavedPosition();

            Runnable saveMultiTiles = deferred(collectMultiTileOrigins(),
                    origins -> WorldManager.saveWorldMultiTiles(name, origins));
            Runnable saveSubTiles = deferred(this.subTiles.snapshot(),
                    snapshot -> WorldManager.saveWorldSubTiles(name, snapshot));
            Runnable saveDoors = deferred(getDoors(),
                    doors -> DoorRegistry.save(name, doors));

            // the liquid layer was copied slice by slice alongside the blocks; its gzip write runs on the save thread
            Runnable saveLiquids = this.liquids.finishSnapshot(liquidFile());

            // the cubes are few: their bytes are taken here, on the game thread, the write runs on the save thread
            File cubesFile = cubeFile();
            Runnable saveCubes = deferred(this.cubes.snapshot(), bytes -> CubeManager.writeFile(cubesFile, bytes));

            SaveQueue.submit(() -> {
                try {
                    byte[] encodedBlocks = savePalette.encode(blocksCopy);
                    WorldManager.saveWorldPalette(name, savePalette);
                    saveMultiTiles.run();
                    saveSubTiles.run();
                    WorldManager.saveWorldType(name, saveType);
                    saveDoors.run();
                    saveLiquids.run();
                    saveCubes.run();
                    LevelIO.save(file, encodedBlocks, dataCopy, position);
                } finally {
                    this.asyncSaveRunning.set(false);
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
        } catch (RuntimeException | Error e) {
            this.asyncSaveRunning.set(false);
            throw e;
        }
    }

    /** a write that is run later with what the snapshot held when this was called */
    private static <T> Runnable deferred(T snapshot, Consumer<T> write) {
        return () -> write.accept(snapshot);
    }

    private byte[] writeSideFiles() {
        byte[] encodedBlocks = this.palette.encode(this.blocks);
        WorldManager.saveWorldPalette(this.worldName, this.palette);
        WorldManager.saveWorldMultiTiles(this.worldName, collectMultiTileOrigins());
        WorldManager.saveWorldSubTiles(this.worldName, this.subTiles.snapshot());
        this.liquids.save(liquidFile());
        this.cubes.save(cubeFile());
        WorldManager.saveWorldType(this.worldName, this.type);
        DoorRegistry.save(this.worldName, getDoors());
        return encodedBlocks;
    }

    private LevelIO.SavedPosition buildSavedPosition() {
        LevelIO.SavedPosition position = this.hasSavedPosition
                ? LevelIO.SavedPosition.of(this.savedPlayerX, this.savedPlayerY, this.savedPlayerZ,
                this.savedPlayerYRotation, this.savedPlayerXRotation)
                : LevelIO.SavedPosition.none();

        position = position.withMetadata(this.seed, this.type);

        if (this.hasFlightState) {
            position = position.withFlightState(this.savedFlying, this.savedNoClip, this.savedFlySpeedMultiplier);
        }
        return position;
    }

    public void setPlayerPosition(double x, double y, double z, float yRotation, float xRotation) {
        this.savedPlayerX         = x;
        this.savedPlayerY         = y;
        this.savedPlayerZ         = z;
        this.savedPlayerYRotation = yRotation;
        this.savedPlayerXRotation = xRotation;
        this.hasSavedPosition     = true;
    }

    public int getTile(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return 0;
        }
        int index = (y * this.height + z) * this.width + x;
        return this.blocks[index] & 0xFF;
    }

    public int getData(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return 0;
        }
        int index = (y * this.height + z) * this.width + x;
        return this.data[index] & 0xFF;
    }

    public void setData(int x, int y, int z, int val) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return;
        }
        int index = (y * this.height + z) * this.width + x;
        this.data[index] = (byte) val;
        if (this.snapshotPending && index < this.snapshotCopied) {
            this.dataSnapshot[index] = (byte) val;   // already copied, keep the copy current
        }

        for (LevelListener levelListener : this.levelListeners) {
            levelListener.tileChanged(x, y, z);
        }

        if (this.editListener != null && this.networkApplyDepth == 0) {
            this.editListener.tileEdited(x, y, z, getTile(x, y, z), getData(x, y, z));
        }
    }

    public boolean isTile(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return false;
        }
        int index = (y * this.height + z) * this.width + x;
        return this.blocks[index] != 0;
    }

    public boolean isSolidTile(int x, int y, int z) {
        Tile tile = Tile.byId(getTile(x, y, z));
        return tile != null && tile.isSolid();
    }

    public boolean isLightBlocker(int x, int y, int z) {
        return this.lighting.isLightBlocker(x, y, z);
    }

    public float getSkyLight(int x, int y, int z) {
        return this.lighting.getSkyLight(x, y, z);
    }

    public float getBillboardDrop(int x, int y, int z) {
        return this.lighting.getBillboardDrop(x, y, z);
    }

    public float getBrightness(int x, int y, int z) {
        return this.lighting.getBrightness(x, y, z);
    }

    public boolean hasLightSources() {
        return this.lighting.hasLightSources();
    }

    public float getBlockLight(int x, int y, int z) {
        return this.lighting.getBlockLight(x, y, z);
    }

    public boolean blocksBlockLight(int x, int y, int z) {
        return this.lighting.blocksBlockLight(x, y, z);
    }

    public SubTiles getSubTiles() {
        return this.subTiles;
    }

    public LiquidLayer getLiquids() {
        return this.liquids;
    }

    private File liquidFile() {
        return new File(WorldManager.levelFile(this.worldName).getPath() + ".liquids");
    }

    private File cubeFile() {
        return new File(WorldManager.levelFile(this.worldName).getPath() + ".cubes");
    }

    public CubeManager getCubeManager() {
        return this.cubes;
    }

    // a liquid was added, removed or swapped. only the chunk mesh needs to know: liquids neither block
    // light nor shade the cells under them
    void liquidChanged(int x, int y, int z, LiquidTile oldLiquid, LiquidTile newLiquid) {
        for (LevelListener levelListener : this.levelListeners) {
            levelListener.tileChanged(x, y, z);
        }

        if (this.editListener != null && this.networkApplyDepth == 0) {
            this.editListener.liquidChanged(x, y, z, newLiquid == null ? 0 : newLiquid.id,
                    newLiquid == null ? 0 : this.liquids.getFill(x, y, z));
        }
    }

    // the cells of a column between minY and maxY may have turned into ocean (its floor was dug out): rebuild what was baked from them
    void oceanFloorChanged(int x, int z, int minY, int maxY) {
        for (LevelListener levelListener : this.levelListeners) {
            levelListener.lightColumnChanged(x, z, Math.max(0, minY), maxY);
        }
    }

    void subTilesChanged(int x, int y, int z) {
        this.lighting.calcLightDepths(x, z, 1, 1);
        for (LevelListener levelListener : this.levelListeners) {
            levelListener.tileChanged(x, y, z);
            levelListener.lightColumnChanged(x, z, Math.max(0, y - SHADOW_FADE_DISTANCE), y);
        }

        if (this.editListener != null && this.networkApplyDepth == 0) {
            this.editListener.subTilesEdited(x, y, z);
        }
    }

    public boolean canSetHost(int x, int y, int z, Tile tile, int data) {
        if (tile == null || tile == Tile.AIR || !this.subTiles.has(x, y, z)) {
            return true;
        }
        List<AABB> host = tile.getOccupancyBoxes(x, y, z, data);
        for (SubTile subTile : this.subTiles.at(x, y, z)) {
            if (!Tile.mayOverlap(tile, Tile.byId(subTile.tileId))
                    && SubTiles.overlapsAny(this.subTiles.occupancy(subTile, x, y, z), host)) {
                return false;
            }
        }
        return true;
    }

    public boolean hasSubTilesIn(int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        if (this.subTiles.isEmpty()) {
            return false;
        }
        for (int cx = x; cx < x + sizeX; cx++) {
            for (int cy = y; cy < y + sizeY; cy++) {
                for (int cz = z; cz < z + sizeZ; cz++) {
                    if (this.subTiles.has(cx, cy, cz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** everything the player can bump into near the box: the blocks and the weighted cubes (a cube carried in the hands is not one) */
    public ArrayList<AABB> getCubes(AABB boundingBox) {
        ArrayList<AABB> boundingBoxList = getWorldCubes(boundingBox);
        this.cubes.addCollisionBoxes(boundingBox, boundingBoxList);
        return boundingBoxList;
    }

    /** the collision boxes of the blocks alone, what a weighted cube collides with */
    public ArrayList<AABB> getWorldCubes(AABB boundingBox) {
        ArrayList<AABB> boundingBoxList = new ArrayList<>();

        int minX = Math.max(0, (int) (Math.floor(boundingBox.minX) - 1));
        int maxX = Math.min(this.width,  (int) (Math.ceil(boundingBox.maxX) + 1));
        int minY = Math.max(0, (int) (Math.floor(boundingBox.minY) - 4));
        int maxY = Math.min(this.depth,  (int) (Math.ceil(boundingBox.maxY) + 1));
        int minZ = Math.max(0, (int) (Math.floor(boundingBox.minZ) - 1));
        int maxZ = Math.min(this.height, (int) (Math.ceil(boundingBox.maxZ) + 1));

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    Tile tile = Tile.byId(getTile(x, y, z));
                    if (tile != null && tile != Tile.AIR) {
                        boundingBoxList.addAll(tile.getCollisionBoxes(this, x, y, z));
                    }
                    for (SubTile subTile : this.subTiles.at(x, y, z)) {
                        boundingBoxList.addAll(this.subTiles.collision(subTile, x, y, z));
                    }
                }
            }
        }
        return boundingBoxList;
    }

    public void setTile(int x, int y, int z, int id) {
        setTile(x, y, z, id, 0);
    }

    public void setTile(int x, int y, int z, int id, int data) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return;
        }

        // a chess piece is never stored: placing one stands an entity on the square. a joined client only asks the server (it hears of the piece with the others)
        if (Tile.byId(id & 0xFF) instanceof ChessPieceTile) {
            placeChessPiece(x, y, z, id & 0xFF, data);
            return;
        }

        boolean liquid = setTileUnchecked(x, y, z, id, data);

        if (this.editListener != null && this.networkApplyDepth == 0) {
            if (liquid) {
                this.editListener.liquidPlaced(x, y, z, id & 0xFF);
            } else {
                this.editListener.tileEdited(x, y, z, getTile(x, y, z), getData(x, y, z));
            }
        }
    }

    private void placeChessPiece(int x, int y, int z, int id, int data) {
        if (this.networkApplyDepth > 0) {
            return;
        }
        if (this.cubes.isReplica()) {
            if (this.editListener != null) {
                this.editListener.tileEdited(x, y, z, id, data);
            }
            return;
        }
        ChessPieceTile piece = (ChessPieceTile) Tile.byId(id);
        this.cubes.spawnPiece(x, y, z, piece.getType(), piece.isWhite(), (data & ChessPieceTile.DATA_MOVED) != 0);
    }

    /** the work of setTile, true if the tile was a liquid (it went to the liquid layer) */
    private boolean setTileUnchecked(int x, int y, int z, int id, int data) {
        Tile newTile = Tile.byId(id & 0xFF);
        if (newTile instanceof LiquidTile) {
            // a liquid goes in the liquid layer, next to whatever block is in the cell
            this.liquids.set(x, y, z, (LiquidTile) newTile);
            return true;
        }
        if (newTile != null && newTile.isMultiTile()) {
            placeMultiTile(newTile, x, y, z, data);
            return false;
        }

        MultiTileInstance existing = this.multiTileCells.get(cellIndex(x, y, z));
        if (existing != null) {
            removeMultiTile(existing);
        }

        setBlock(x, y, z, id, data, false, false);
        return false;
    }

    private void setBlock(int x, int y, int z, int id, int data, boolean oldWasPart, boolean newIsPart) {
        Tile incoming = Tile.byId(id & 0xFF);
        if (LiquidLayer.displacesLiquid(incoming)) {
            this.liquids.clear(x, y, z);   // a block that fills the cell replaces the liquid in it
        }
        if (incoming != null && incoming != Tile.AIR && this.subTiles.has(x, y, z)) {
            this.subTiles.removeOverlapping(x, y, z, incoming, incoming.getOccupancyBoxes(x, y, z, data));
        }

        int index = cellIndex(x, y, z);
        Tile oldTile = Tile.byId(this.blocks[index] & 0xFF);
        this.blocks[index] = (byte) id;
        this.data[index]   = (byte) data;
        if (this.snapshotPending && index < this.snapshotCopied) {
            this.blocksSnapshot[index] = (byte) id;   // already copied, keep the copy current
            this.dataSnapshot[index]   = (byte) data;
        }
        this.liquids.blockChanged(x, y, z);   // the sea's floor may have just been dug out
        this.liquids.wake(x, y, z);   // liquid next to this cell may now flow in (block removed) or have to stop (block placed)

        this.lighting.calcLightDepths(x, z, 1, 1);
        this.lighting.updateBlockLight(x, y, z, oldTile, Tile.byId(id & 0xFF), oldWasPart, newIsPart);

        for (LevelListener levelListener : this.levelListeners) {
            levelListener.tileChanged(x, y, z);
            levelListener.lightColumnChanged(x, z, Math.max(0, y - SHADOW_FADE_DISTANCE), y);
        }
    }

    private int cellIndex(int x, int y, int z) {
        return (y * this.height + z) * this.width + x;
    }

    public MultiTileInstance getMultiTile(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
            return null;
        }
        return this.multiTileCells.get(cellIndex(x, y, z));
    }

    public boolean isMultiTilePart(int x, int y, int z) {
        MultiTileInstance instance = getMultiTile(x, y, z);
        return instance != null && !instance.isOrigin(x, y, z);
    }

    public boolean isSameMultiTile(int ax, int ay, int az, int bx, int by, int bz) {
        MultiTileInstance a = getMultiTile(ax, ay, az);
        return a != null && a == getMultiTile(bx, by, bz);
    }

    public boolean canPlaceTile(Tile tile, int x, int y, int z, int rotation) {
        if (tile == null || !tile.isMultiTile()) {
            return x >= 0 && y >= 0 && z >= 0 && x < this.width && y < this.depth && z < this.height;
        }
        return canPlaceMultiTile(tile, x, y, z, rotation);
    }

    public boolean canPlaceMultiTile(Tile tile, int x, int y, int z, int rotation) {
        rotation &= 3;
        int sizeX = tile.getSizeX(rotation);
        int sizeY = tile.getSizeY();
        int sizeZ = tile.getSizeZ(rotation);

        if (x < 0 || y < 0 || z < 0
                || x + sizeX > this.width || y + sizeY > this.depth || z + sizeZ > this.height) {
            return false;
        }

        for (int cx = x; cx < x + sizeX; cx++) {
            for (int cy = y; cy < y + sizeY; cy++) {
                for (int cz = z; cz < z + sizeZ; cz++) {
                    if (this.blocks[cellIndex(cx, cy, cz)] != 0 || this.subTiles.has(cx, cy, cz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public boolean placeMultiTile(Tile tile, int x, int y, int z, int rotation) {
        if (tile == null || !tile.isMultiTile() || !canPlaceMultiTile(tile, x, y, z, rotation)) {
            return false;
        }

        MultiTileInstance instance = new MultiTileInstance(tile, x, y, z, rotation);

        this.multiTiles.add(instance);
        for (int cx = x; cx < x + instance.sizeX; cx++) {
            for (int cy = y; cy < y + instance.sizeY; cy++) {
                for (int cz = z; cz < z + instance.sizeZ; cz++) {
                    this.multiTileCells.put(cellIndex(cx, cy, cz), instance);
                }
            }
        }

        for (int cx = x; cx < x + instance.sizeX; cx++) {
            for (int cy = y; cy < y + instance.sizeY; cy++) {
                for (int cz = z; cz < z + instance.sizeZ; cz++) {
                    setBlock(cx, cy, cz, tile.id, instance.rotation, false, !instance.isOrigin(cx, cy, cz));
                }
            }
        }
        return true;
    }

    public void removeMultiTile(MultiTileInstance instance) {
        if (!this.multiTiles.remove(instance)) {
            return;
        }

        for (int cx = instance.x; cx < instance.x + instance.sizeX; cx++) {
            for (int cy = instance.y; cy < instance.y + instance.sizeY; cy++) {
                for (int cz = instance.z; cz < instance.z + instance.sizeZ; cz++) {
                    this.multiTileCells.remove(cellIndex(cx, cy, cz));
                }
            }
        }

        for (int cx = instance.x; cx < instance.x + instance.sizeX; cx++) {
            for (int cy = instance.y; cy < instance.y + instance.sizeY; cy++) {
                for (int cz = instance.z; cz < instance.z + instance.sizeZ; cz++) {
                    setBlock(cx, cy, cz, 0, 0, !instance.isOrigin(cx, cy, cz), false);
                }
            }
        }
    }

    public List<DoorRegistry.Entry> getDoors() {
        List<DoorRegistry.Entry> doors = new ArrayList<>();
        for (MultiTileInstance instance : this.multiTiles) {
            if (instance.tile instanceof DimensionalDoorTile) {
                doors.add(new DoorRegistry.Entry(instance.x, instance.y, instance.z,
                        ((DimensionalDoorTile) instance.tile).getColor()));
            }
        }
        return doors;
    }

    List<int[]> collectMultiTileOrigins() {
        List<int[]> origins = new ArrayList<>(this.multiTiles.size());
        for (MultiTileInstance instance : this.multiTiles) {
            origins.add(new int[]{instance.x, instance.y, instance.z});
        }
        return origins;
    }

    void registerMultiTiles(List<int[]> origins) {
        this.multiTileCells.clear();
        this.multiTiles.clear();

        if (origins != null) {
            for (int[] origin : origins) {
                int x = origin[0], y = origin[1], z = origin[2];
                if (x < 0 || y < 0 || z < 0 || x >= this.width || y >= this.depth || z >= this.height) {
                    continue;
                }

                Tile tile = Tile.byId(getTile(x, y, z));
                if (tile == null || !tile.isMultiTile()) {
                    continue;
                }

                MultiTileInstance instance = new MultiTileInstance(tile, x, y, z, getData(x, y, z));
                if (!footprintMatches(instance)) {
                    continue;
                }

                this.multiTiles.add(instance);
                for (int cx = x; cx < x + instance.sizeX; cx++) {
                    for (int cy = y; cy < y + instance.sizeY; cy++) {
                        for (int cz = z; cz < z + instance.sizeZ; cz++) {
                            this.multiTileCells.put(cellIndex(cx, cy, cz), instance);
                        }
                    }
                }
            }
        }

        upgradeLegacyMultiTiles();
    }

    private void upgradeLegacyMultiTiles() {
        boolean[] multiIds = new boolean[256];
        boolean any = false;
        for (Tile tile : Tile.getTiles()) {
            if (tile.isMultiTile()) {
                multiIds[tile.id & 0xFF] = true;
                any = true;
            }
        }
        if (!any) {
            return;
        }

        for (int y = 0; y < this.depth; y++) {
            for (int z = 0; z < this.height; z++) {
                for (int x = 0; x < this.width; x++) {
                    int index = cellIndex(x, y, z);
                    int id = this.blocks[index] & 0xFF;
                    if (!multiIds[id] || this.multiTileCells.containsKey(index)) {
                        continue;
                    }

                    Tile tile = Tile.byId(id);
                    MultiTileInstance instance = new MultiTileInstance(tile, x, y, z, this.data[index]);
                    if (!footprintIsEmptyAround(instance)) {
                        continue;
                    }

                    this.multiTiles.add(instance);
                    for (int cx = x; cx < x + instance.sizeX; cx++) {
                        for (int cy = y; cy < y + instance.sizeY; cy++) {
                            for (int cz = z; cz < z + instance.sizeZ; cz++) {
                                int cell = cellIndex(cx, cy, cz);
                                this.blocks[cell] = (byte) id;
                                this.data[cell]   = (byte) instance.rotation;
                                this.multiTileCells.put(cell, instance);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean footprintIsEmptyAround(MultiTileInstance instance) {
        if (instance.x + instance.sizeX > this.width
                || instance.y + instance.sizeY > this.depth
                || instance.z + instance.sizeZ > this.height) {
            return false;
        }

        for (int cx = instance.x; cx < instance.x + instance.sizeX; cx++) {
            for (int cy = instance.y; cy < instance.y + instance.sizeY; cy++) {
                for (int cz = instance.z; cz < instance.z + instance.sizeZ; cz++) {
                    if (instance.isOrigin(cx, cy, cz)) {
                        continue;
                    }
                    int index = cellIndex(cx, cy, cz);
                    if (this.blocks[index] != 0 || this.multiTileCells.containsKey(index)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean footprintMatches(MultiTileInstance instance) {
        if (instance.x + instance.sizeX > this.width
                || instance.y + instance.sizeY > this.depth
                || instance.z + instance.sizeZ > this.height) {
            return false;
        }

        for (int cx = instance.x; cx < instance.x + instance.sizeX; cx++) {
            for (int cy = instance.y; cy < instance.y + instance.sizeY; cy++) {
                for (int cz = instance.z; cz < instance.z + instance.sizeZ; cz++) {
                    int index = cellIndex(cx, cy, cz);
                    if ((this.blocks[index] & 0xFF) != instance.tile.id
                            || this.multiTileCells.containsKey(index)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public void addListener(LevelListener levelListener) {
        this.levelListeners.add(levelListener);
    }
}