package com.nikohalfkino.rubydung.level.tiles;

import com.nikohalfkino.rubydung.entity.player.interaction.HitResult;
import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.UnderwaterTint;
import com.nikohalfkino.rubydung.entity.phys.AABB;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.Textures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Tile {

    public static final int FACE_BOTTOM = 0;
    public static final int FACE_TOP    = 1;
    public static final int FACE_NORTH  = 2;
    public static final int FACE_SOUTH  = 3;
    public static final int FACE_WEST   = 4;
    public static final int FACE_EAST   = 5;

    private static final String BLOCK_TEXTURE_PATH = "/assets/rubydung/textures/block/";
    private static final String MISSING_TEXTURE    = "/assets/rubydung/textures/missing.png";

    public static final int MAX_FOOTPRINT = 16;

    // Every Tile self-registers here on construction so byId() works without any
    // dependency on TileRegistry.  Ids are assigned sequentially starting at 0.
    private static int idCounter = 0;
    private static final List<Tile> BY_ID = new ArrayList<>();

    // forwarding aliases: TileRegistry is the source of truth, these keep Tile.GRASS, Tile.byName(...) etc. compiling
    // initialising them triggers TileRegistry to load (and register all tiles)

    public static final Tile AIR               = TileRegistry.AIR;

    public static final Tile GRASS             = TileRegistry.GRASS;
    public static final Tile SAND              = TileRegistry.SAND;
    public static final Tile GRAVEL            = TileRegistry.GRAVEL;
    public static final Tile STONE             = TileRegistry.STONE;
    public static final Tile COBBLESTONE       = TileRegistry.COBBLESTONE;
    public static final Tile SLATE             = TileRegistry.SLATE;

    public static final Tile BRICKS            = TileRegistry.BRICKS;
    public static final Tile BRICKS_LARGE      = TileRegistry.BRICKS_LARGE;
    public static final Tile STONE_BRICKS      = TileRegistry.STONE_BRICKS;
    public static final Tile STONE_BRICKS_LARGE = TileRegistry.STONE_BRICKS_LARGE;

    public static final Tile MARBLE            = TileRegistry.MARBLE;
    public static final Tile PILLAR_TOP        = TileRegistry.PILLAR_TOP;
    public static final Tile PILLAR            = TileRegistry.PILLAR;
    public static final Tile PILLAR_BOTTOM     = TileRegistry.PILLAR_BOTTOM;
    public static final Tile COLUMN            = TileRegistry.COLUMN;
    public static final Tile STATUE            = TileRegistry.STATUE;

    public static final Tile METAL             = TileRegistry.METAL;
    public static final Tile METAL_GIRDER      = TileRegistry.METAL_GIRDER;
    public static final Tile METAL_GRATE       = TileRegistry.METAL_GRATE;
    public static final Tile GOLD              = TileRegistry.GOLD;

    public static final Tile LOG               = TileRegistry.LOG;
    public static final Tile PLANKS            = TileRegistry.PLANKS;
    public static final Tile WOOD              = TileRegistry.WOOD;
    public static final Tile WOOD_SLAB         = TileRegistry.WOOD_SLAB;

    public static final Tile GLASS             = TileRegistry.GLASS;
    public static final Tile LAMP              = TileRegistry.LAMP;
    public static final Tile LAMPPOST          = TileRegistry.LAMPPOST;

    public static final Tile WATER             = TileRegistry.WATER;
    public static final Tile LAVA              = TileRegistry.LAVA;
    public static final Tile ACID              = TileRegistry.ACID;
    public static final Tile SLUDGE            = TileRegistry.SLUDGE;

    public static final Tile TREE_SAPLING      = TileRegistry.TREE_SAPLING;
    public static final Tile TREE_MEDIUM       = TileRegistry.TREE_MEDIUM;
    public static final Tile TREE_LARGE        = TileRegistry.TREE_LARGE;
    public static final Tile TREE              = TileRegistry.TREE;

    public static final Tile PATH              = TileRegistry.PATH;

    public static Tile      byName(String name)  { return TileRegistry.byName(name); }
    public static List<Tile> getTiles()          { return TileRegistry.getTiles(); }
    public static List<Tile> getCycleOrder()     { return TileRegistry.getCycleOrder(); }

    public final int id;
    public final String name;

    private final String[] faceTextures   = new String[6];
    private final int[]    faceAtlasSlots = new int[6];

    private boolean isSolid      = true;
    private boolean isTransparent = false;

    // null = decide from the tile's other flags (see castsShadow)
    private Boolean castsShadow = null;

    // false keeps it out of the tile selector
    private boolean placeable = true;

    // faces not drawn when the neighbour on that side is the same tile (bit per FACE_*)
    // only matters for transparent tiles, which otherwise draw every face
    private int selfCullMask = 0;

    // radius in blocks of the plain ball of light it emits from every cell (0 = none), see setLightRadius
    private float lightRadius = 0.0F;

    // every light the tile gives off: the ball from setLightRadius first, then the ones added with addLight
    private LightEmitter radiusEmitter = null;
    private final List<LightEmitter> extraEmitters = new ArrayList<>();
    private List<LightEmitter> lightEmitters = Collections.emptyList();

    // cells covered when placed unrotated (see setFootprint), 1x1x1 = an ordinary tile
    private int footprintX = 1;
    private int footprintY = 1;
    private int footprintZ = 1;

    public Tile(String name) {
        this(name, name);
    }

    public Tile(String name, String defaultTextureName) {
        this.id   = idCounter++;
        this.name = name;
        BY_ID.add(this);   // index == id (tiles are always constructed in registration order)
        setTexture(defaultTextureName);
    }

    // lookup (id-based only, name-based lookup lives in TileRegistry)

    /** null if no tile has that id */
    public static Tile byId(int id) {
        return (id >= 0 && id < BY_ID.size()) ? BY_ID.get(id) : null;
    }

    // instance api

    public boolean isBillboard() {
        return false;
    }

    /** data byte a freshly placed tile gets, given the face (FACE_*) of the neighbour it was placed against. 0 for ordinary tiles */
    public int getPlacementData(int face) {
        return 0;
    }

    /** also gets where in the target cell the player pointed (fraction 0..1 per axis, NaN if unknown), a slab uses the height to pick its half */
    public int getPlacementData(int face, double fractionX, double fractionY, double fractionZ) {
        return getPlacementData(face);
    }

    /** also gets the player's horizontal look direction (need not be unit length), tiles that face the player (FacingTile) use it, the others ignore it. placement code should call this one */
    public int getPlacementData(int face, double fractionX, double fractionY, double fractionZ,
                                double lookX, double lookZ) {
        return getPlacementData(face, fractionX, fractionY, fractionZ);
    }

    /** data byte (model state) the tile icon is drawn with, a slab can ask for a particular half so its icon isn't whatever state is listed first */
    public int getIconData() {
        return 0;
    }

    /** placement hook for multi-tile objects: the rotation (0..3) it's really placed with, given the one the look direction chose and the cell (x, y, z) it's going into. doors use it to turn into a double door next to another door. default: unchanged */
    public int getMultiTileRotation(Level level, int x, int y, int z, int rotation) {
        return rotation;
    }

    /** whether the interact key does something when aimed at this tile (shows the "[E] Interact" prompt) */
    public boolean isInteractable() {
        return false;
    }

    /**
     * called before normal placement when the target cell (x, y, z) is already occupied by this tile. true = placement was handled (e.g. a bottom slab upgrading to a double slab) and the caller won't place normally, false (default) = the caller treats the occupied cell as a no-op
     *
     * fractionX/Y/Z are the hit-point fractions (0..1) in the cell or NaN, face is the clicked face of the neighbour
     */
    public boolean tryMergePlacement(Level level, int x, int y, int z,
                                     int face, double fractionX, double fractionY, double fractionZ) {
        return false;
    }

    /**
     * the one call placement should make before placing anything: merges into an existing cell if this tile can. default only tries the cell next to the clicked face, partial tiles such as slabs override it to also look at the clicked cell
     *
     * returns true if the placement was handled (the caller places nothing else, but should still consume the item / play the sound)
     */
    public boolean tryMergePlacement(Level level, HitResult hit,
                                     double fractionX, double fractionY, double fractionZ) {
        int[] n = FACE_NORMALS[hit.face];
        return tryMergePlacement(level, hit.cellX + n[0], hit.cellY + n[1], hit.cellZ + n[2],
                hit.face, fractionX, fractionY, fractionZ);
    }

    /**
     * called when the player breaks the tile at (x, y, z). returns the data byte the cell should be set to (the tile stays with new data, e.g. a double slab becoming a single half), or -1 to remove it entirely (default)
     *
     * hitBox is the sub-box the ray struck (may be null)
     */
    public int getBreakResult(Level level, int x, int y, int z, AABB hitBox) {
        return -1;
    }

    public void renderBillboard(Tessellator tessellator, Level level, int x, int y, int z, int totalAtlasSlots) {
    }

    public AABB getAABB(int x, int y, int z) {
        if (!this.isSolid()) {
            return null;
        }
        return new AABB(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
    }

    public AABB getPickAABB(int x, int y, int z) {
        return getAABB(x, y, z);
    }

    /** boxes entities collide with, world coordinates. unlike getAABB this knows the level, so tiles whose shape depends on data (slab, stairs) can answer per cell. empty = walk through */
    public List<AABB> getCollisionBoxes(Level level, int x, int y, int z) {
        return getCollisionBoxes(x, y, z, level.getData(x, y, z));
    }

    /** same for an explicit data byte, for a tile that isn't placed yet (is the spot free of the player?) where the cell still holds air */
    public List<AABB> getCollisionBoxes(int x, int y, int z, int data) {
        AABB box = getAABB(x, y, z);
        return box == null ? Collections.<AABB>emptyList() : Collections.singletonList(box);
    }

    /** boxes a ray is tested against when picking this cell, world coordinates. a ray through none of them goes on to the next cell. default: the pick box, or the whole cell if there is none */
    public List<AABB> getPickBoxes(Level level, int x, int y, int z) {
        AABB box = getPickAABB(x, y, z);
        if (box == null) {
            box = new AABB(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
        }
        return Collections.singletonList(box);
    }

    /** space this tile takes up in a cell for the given data byte, world coordinates of the cell at (x, y, z). decides whether tiles sharing a cell overlap, and picks sub-tiles. default: the collision boxes, or the whole cell for a solid tile with none */
    public List<AABB> getOccupancyBoxes(int x, int y, int z, int data) {
        List<AABB> boxes = new ArrayList<>(getCollisionBoxes(x, y, z, data));
        if (boxes.isEmpty() && isSolid()) {
            boxes.add(new AABB(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F));
        }
        return boxes;
    }

    /** whether this tile may be placed INTO a cell that already holds others, in whatever free space is left (see SubTiles). only tiles that don't fill their cell can, override to opt in or out */
    public boolean canShareCell() {
        return isSolid() && !isBillboard() && !isMultiTile() && !occludesNeighbours();
    }

    /** whether this tile may occupy the same space as other inside one cell, so they're never treated as overlapping when placing into a shared cell (see SubTiles). false by default, a thin pane lets slabs pass through (see PaneTile). only one of the two has to allow it, see mayOverlap */
    public boolean canIntersect(Tile other) {
        return false;
    }

    /** either one allows it */
    public static boolean mayOverlap(Tile a, Tile b) {
        return a != null && b != null && (a.canIntersect(b) || b.canIntersect(a));
    }

    /** whether this tile may go into the free half of a cell that holds a slab, drawn and collided as just the lower or upper half of itself. ordinary full blocks (stone, logs): everything that can't share a cell as a shape of its own */
    public boolean canClipToHalf() {
        return isSolid() && !isBillboard() && !isMultiTile() && occludesNeighbours()
                && !emitsLight() && !isInteractable();
    }

    /** whether the selection overlay follows getOutlineAABB even though it's a multi-tile object, which is otherwise outlined by its whole footprint. doors say true to be outlined by their real shape */
    public boolean hasCustomOutline() {
        return false;
    }

    /** box drawn as the selection outline of this cell (data-aware), defaults to getPickAABB */
    public AABB getOutlineAABB(Level level, int x, int y, int z) {
        return getPickAABB(x, y, z);
    }

    public Tile setTexture(String textureName) {
        for (int face = 0; face < 6; face++) {
            setTexture(face, textureName);
        }
        return this;
    }

    public Tile setTexture(int face, String textureName) {
        this.faceTextures[face] = texturePath(textureName);
        return this;
    }

    /** resource path of a block texture by name, or the missing-texture path if there's no such file */
    public static String texturePath(String textureName) {
        // "name@col,row" picks one square slice of a texture that is a multi-cell strip or sheet.
        String slice = "";
        int at = textureName.indexOf('@');
        if (at >= 0) {
            slice = textureName.substring(at);
            textureName = textureName.substring(0, at);
        }

        String path = BLOCK_TEXTURE_PATH + textureName + ".png";
        if (!Textures.resourceExists(path)) {
            return MISSING_TEXTURE;
        }

        // A bare name for a non-square texture means its first slice, so such a tile still works
        // wherever a texture name is expected (the atlas only holds squares).
        if (slice.isEmpty()) {
            int[] grid = Textures.sliceGrid(path);
            if (grid[0] * grid[1] > 1) {
                slice = "@0,0";
            }
        }
        return path + slice;
    }

    public Tile setTextures(String topBottomTextureName, String sideTextureName) {
        setTexture(FACE_TOP,    topBottomTextureName);
        setTexture(FACE_BOTTOM, topBottomTextureName);
        setTexture(FACE_NORTH,  sideTextureName);
        setTexture(FACE_SOUTH,  sideTextureName);
        setTexture(FACE_WEST,   sideTextureName);
        setTexture(FACE_EAST,   sideTextureName);
        return this;
    }

    public String getTexture(int face) {
        return this.faceTextures[face];
    }

    public void setAtlasSlot(int face, int slot) {
        this.faceAtlasSlots[face] = slot;
    }

    public int getAtlasSlot(int face) {
        return this.faceAtlasSlots[face];
    }

    /** texture paths this tile draws with beyond its six face textures (a model tile uses as many as its models name), the atlas includes them */
    public Collection<String> getExtraTextures() {
        return Collections.emptyList();
    }

    /** called once the atlas is built, with the slot of every texture path */
    public void onAtlasBuilt(Map<String, Integer> slotsByPath) {
    }

    /** whether it fills its whole cell, so a solid neighbour may hide the faces that touch it. false for slabs, stairs and other partial shapes */
    public boolean occludesNeighbours() {
        return true;
    }

    public boolean isSolid() {
        return this.isSolid;
    }

    public Tile setSolid(boolean solid) {
        this.isSolid = solid;
        return this;
    }

    public boolean isPlaceable() {
        return this.placeable;
    }

    public Tile setPlaceable(boolean placeable) {
        this.placeable = placeable;
        return this;
    }

    public boolean isTransparent() {
        return this.isTransparent;
    }

    public Tile setTransparent(boolean transparent) {
        this.isTransparent = transparent;
        return this;
    }

    /** the reach of its biggest light, 0 if it gives off none */
    public float getLightRadius() {
        float radius = this.lightRadius;
        for (LightEmitter emitter : this.extraEmitters) {
            radius = Math.max(radius, Math.min(emitter.getRadius(), Level.MAX_LIGHT_RADIUS));
        }
        return radius;
    }

    public boolean emitsLight() {
        return !this.lightEmitters.isEmpty();
    }

    /** every light this tile gives off, see LightEmitter. empty = none */
    public List<LightEmitter> getLightEmitters() {
        return this.lightEmitters;
    }

    /** a ball of light of this radius around every cell of the tile. 0 = none. (other shapes: addLight) */
    public Tile setLightRadius(float radius) {
        this.lightRadius = Math.min(Math.max(radius, 0.0F), Level.MAX_LIGHT_RADIUS);
        this.radiusEmitter = this.lightRadius > 0.0F ? LightEmitter.everyCell().radius(this.lightRadius) : null;
        refreshLightEmitters();
        return this;
    }

    /** adds a light of its own: a cone, a ball from the top cell, one beside the tile... as many as wanted, together with setLightRadius's */
    public Tile addLight(LightEmitter emitter) {
        this.extraEmitters.add(emitter);
        refreshLightEmitters();
        return this;
    }

    private void refreshLightEmitters() {
        List<LightEmitter> all = new ArrayList<>();
        if (this.radiusEmitter != null) {
            all.add(this.radiusEmitter);
        }
        all.addAll(this.extraEmitters);
        this.lightEmitters = Collections.unmodifiableList(all);
    }

    /** makes this a multi-tile object covering x * y * z cells: placing fills all of them (anchored at the clicked cell, extending +x/+y/+z), breaking any of them removes the whole object. only light emission comes from the origin cell alone */
    public Tile setFootprint(int x, int y, int z) {
        if (x < 1 || y < 1 || z < 1 || x > MAX_FOOTPRINT || y > MAX_FOOTPRINT || z > MAX_FOOTPRINT) {
            throw new IllegalArgumentException("Footprint of " + this.name + " must be 1.." + MAX_FOOTPRINT
                    + " per axis, got " + x + "x" + y + "x" + z);
        }
        this.footprintX = x;
        this.footprintY = y;
        this.footprintZ = z;
        return this;
    }

    /** hides the listed faces when the neighbouring cell on that side is the same tile */
    public Tile setSelfCull(int... faces) {
        for (int face : faces) {
            this.selfCullMask |= 1 << face;
        }
        return this;
    }

    /** hides every face that touches another cell of the same tile (e.g. glass) */
    public Tile setSelfCullAll() {
        this.selfCullMask = 0b111111;
        return this;
    }

    public boolean cullsSelfFace(int face) {
        return (this.selfCullMask & (1 << face)) != 0;
    }

    public boolean isMultiTile() {
        return this.footprintX * this.footprintY * this.footprintZ > 1;
    }

    /** footprint size along X after `rotation` quarter turns (odd rotations swap X and Z) */
    public int getSizeX(int rotation) {
        return (rotation & 1) == 0 ? this.footprintX : this.footprintZ;
    }

    public int getSizeY() {
        return this.footprintY;
    }

    public int getSizeZ(int rotation) {
        return (rotation & 1) == 0 ? this.footprintZ : this.footprintX;
    }

    /** whether this tile darkens the cells below it (the sky shadow). by default only solid, non-transparent tiles do, so glass and grates let light through. override per tile with setCastsShadow */
    public boolean castsShadow() {
        if (this.castsShadow != null) {
            return this.castsShadow;
        }
        return this.isSolid && !this.isTransparent;
    }

    public Tile setCastsShadow(boolean castsShadow) {
        this.castsShadow = castsShadow;
        return this;
    }

    /** whether it stops light emitted by other tiles from passing through */
    public boolean blocksLight() {
        return this.isSolid && !this.isTransparent;
    }

    protected boolean shouldRenderFace(Level level, int x, int y, int z) {
        int tileId = level.getTile(x, y, z);
        if (tileId == 0) {
            return true;  // air (id 0 is always AIR)
        }

        Tile neighbour = Tile.byId(tileId);
        if (neighbour == null) {
            return true;
        }

        if (neighbour.isTransparent() || this.isTransparent) {
            return true;
        }

        if (neighbour.isSolid() && neighbour.occludesNeighbours()) {
            return false;
        }

        return true;
    }

    /** also knows which cell the face belongs to, so seams between parts of one multi-tile object are never drawn (even for transparent tiles) */
    protected boolean shouldRenderFace(Level level, int fromX, int fromY, int fromZ, int x, int y, int z) {
        if (isMultiTile() && level.isSameMultiTile(fromX, fromY, fromZ, x, y, z)) {
            return false;
        }
        return shouldRenderFace(level, x, y, z);
    }

    /** face-aware: also applies this tile's self-cull faces. face is the tile's own face (FACE_*) looking at the neighbour cell (x, y, z) */
    protected boolean shouldRenderFace(Level level, int fromX, int fromY, int fromZ, int x, int y, int z, int face) {
        if (isMultiTile() && level.isSameMultiTile(fromX, fromY, fromZ, x, y, z)) {
            return false;
        }
        if (cullsSelfFace(face) && level.getTile(x, y, z) == this.id) {
            return false;
        }
        return shouldRenderFace(level, x, y, z);
    }

    public void render(Tessellator tessellator, Level level, int layer, int x, int y, int z, int totalAtlasSlots) {
        render(tessellator, level, layer, x, y, z, totalAtlasSlots, 0);
    }

    // Per face: outward normal, directional shade, and the 4 vertices as
    // {cornerX, cornerY, cornerZ, uSelect, vSelect} (corner 0 = min, 1 = max; uv 0 = min, 1 = max).
    public static final int[][]   FACE_NORMALS  = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };
    public static final float[]   FACE_SHADE    = {1.0F, 1.0F, 0.8F, 0.8F, 0.6F, 0.6F};
    public static final int[][][] FACE_VERTICES = {
            // BOTTOM
            {{0, 0, 1, 0, 1}, {0, 0, 0, 0, 0}, {1, 0, 0, 1, 0}, {1, 0, 1, 1, 1}},
            // TOP
            {{1, 1, 1, 1, 1}, {1, 1, 0, 1, 0}, {0, 1, 0, 0, 0}, {0, 1, 1, 0, 1}},
            // NORTH
            {{0, 1, 0, 1, 0}, {1, 1, 0, 0, 0}, {1, 0, 0, 0, 1}, {0, 0, 0, 1, 1}},
            // SOUTH
            {{0, 1, 1, 0, 0}, {0, 0, 1, 0, 1}, {1, 0, 1, 1, 1}, {1, 1, 1, 1, 0}},
            // WEST
            {{0, 1, 1, 1, 0}, {0, 1, 0, 0, 0}, {0, 0, 0, 0, 1}, {0, 0, 1, 1, 1}},
            // EAST
            {{1, 0, 1, 0, 1}, {1, 0, 0, 1, 1}, {1, 1, 0, 1, 0}, {1, 1, 1, 0, 0}}
    };

    // default UV corner selects per face and vertex (0 = min, 1 = max), from FACE_VERTICES
    private static final int[][][] BASE_UVS = buildBaseUvs();

    private static int[][][] buildBaseUvs() {
        int[][][] uvs = new int[6][4][2];
        for (int face = 0; face < 6; face++) {
            for (int v = 0; v < 4; v++) {
                uvs[face][v][0] = FACE_VERTICES[face][v][3];
                uvs[face][v][1] = FACE_VERTICES[face][v][4];
            }
        }
        return uvs;
    }

    // per-tile hooks for how a cube is drawn (a tile that isn't a cube, like ModelTile, overrides render itself)

    /** which of this tile's textures a world-space face uses (RotatableTile swaps them around) */
    protected int getTextureFace(int face, int direction) {
        return face;
    }

    /** atlas slot a world-space face of the cube at (x, y, z) is drawn with. fixed per face normally, a tile whose look depends on position (WrappedTile) overrides this */
    protected int getFaceAtlasSlot(Level level, int x, int y, int z, int face, int direction) {
        return getAtlasSlot(getTextureFace(face, direction));
    }

    /** the tile's own face that a world-space face corresponds to, for self-culling */
    protected int getCullFace(int face, int direction) {
        return face;
    }

    /** UV corner selects for the 4 vertices of a face: int[4][2] of {u, v}, 0 = min, 1 = max */
    protected int[][] getFaceUvs(int face, int direction) {
        return BASE_UVS[face];
    }

    /** the layer value that draws every face in one go, lit and shadowed alike (the terrain shader lights each vertex itself) */
    public static final int LAYER_ALL = -1;

    public void render(Tessellator tessellator, Level level, int layer, int x, int y, int z, int totalAtlasSlots, int direction) {
        boolean smooth    = level.hasLightSources();
        boolean fullBright = tessellator.isIconFullBright(); // tile icons: plain shade, drawn without the shader
        boolean emissive  = emitsLight();
        float[] block     = new float[4];

        for (int face = 0; face < 6; face++) {
            int[] normal = FACE_NORMALS[face];
            int nx = x + normal[0];
            int ny = y + normal[1];
            int nz = z + normal[2];

            // The lid of a half block (see Tessellator.setClipY) faces into the free half of the cell:
            // always drawn, and lit by the cell itself.
            boolean cap = face == tessellator.getClipCapFace();
            if (cap) {
                nx = x;
                ny = y;
                nz = z;
            } else {
                int cullFace = getCullFace(face, direction);
                if (cullsSelfFace(cullFace) && tessellator.iconTouchesSelf(face)) {
                    continue;   // an icon: a self-culled face that would touch the same tile
                }
                if (!shouldRenderFace(level, x, y, z, nx, ny, nz, cullFace)) {
                    continue;
                }
            }

            // A neighbour that only fills part of this face (a slab, a sub-tile) hides only that part.
            int[][] uvs = getFaceUvs(face, direction);
            float[] quadPos = new float[12];
            float[] quadUv = new float[8];
            for (int v = 0; v < 4; v++) {
                quadPos[v * 3]     = FACE_VERTICES[face][v][0];
                quadPos[v * 3 + 1] = FACE_VERTICES[face][v][1];
                quadPos[v * 3 + 2] = FACE_VERTICES[face][v][2];
                quadUv[v * 2]      = uvs[v][0];
                quadUv[v * 2 + 1]  = uvs[v][1];
            }
            boolean selfCull = cullsSelfFace(getCullFace(face, direction));
            List<FaceCulling.Piece> pieces = cap ? null
                    : FaceCulling.visiblePieces(level, this, selfCull, x, y, z,
                    quadPos, quadUv, face, tessellator.getOffsetX(), tessellator.getOffsetY(), tessellator.getOffsetZ());
            if (pieces != null && pieces.isEmpty()) {
                continue;
            }

            // Light-emitting tiles are fullbright: no sky shadow and no directional face shading.
            float shade    = emissive ? 1.0F : FACE_SHADE[face];
            int[][] vertices = FACE_VERTICES[face];

            float sky = (emissive || fullBright) ? 1.0F : level.getSkyLight(nx, ny, nz);
            // liquid over this face darkens and tints it (see UnderwaterTint)
            float tint = (emissive || fullBright) ? UnderwaterTint.DRY : UnderwaterTint.pack(level, nx, ny, nz);

            boolean fullyLit = true;
            for (int v = 0; v < 4; v++) {
                block[v] = 0.0F;
                if (emissive) {
                    block[v] = 1.0F;
                } else if (smooth && !fullBright) {
                    int sx = normal[0] == 0 ? vertices[v][0] * 2 - 1 : 0;
                    int sy = normal[1] == 0 ? vertices[v][1] * 2 - 1 : 0;
                    int sz = normal[2] == 0 ? vertices[v][2] * 2 - 1 : 0;
                    block[v] = cornerBlockLight(level, nx, ny, nz, sx, sy, sz);
                }

                if (sky < 1.0F && block[v] < 1.0F) {
                    fullyLit = false;
                }
            }

            if (layer != LAYER_ALL && !((layer == 1) ^ fullyLit)) {
                continue;
            }

            int slot  = getFaceAtlasSlot(level, x, y, z, face, direction);

            if (pieces == null) {
                for (int v = 0; v < 4; v++) {
                    int[] vertex = vertices[v];

                    if (fullBright) {
                        tessellator.color(shade, shade, shade);
                    } else {
                        tessellator.color(sky, block[v], shade, tint);
                    }
                    tessellator.texture(Chunk.atlasU(slot, uvs[v][0]), Chunk.atlasV(slot, uvs[v][1]));
                    tessellator.vertex(x + vertex[0], y + vertex[1], z + vertex[2]);
                }
            } else {
                for (FaceCulling.Piece piece : pieces) {
                    for (int v = 0; v < 4; v++) {
                        if (fullBright) {
                            tessellator.color(shade, shade, shade);
                        } else {
                            float light = 0.0F;
                            for (int k = 0; k < 4; k++) {
                                light += piece.weights[v * 4 + k] * block[k];
                            }
                            tessellator.color(sky, light, shade, tint);
                        }
                        tessellator.texture(Chunk.atlasU(slot, piece.uv[v * 2]), Chunk.atlasV(slot, piece.uv[v * 2 + 1]));
                        tessellator.vertex(x + piece.pos[v * 3], y + piece.pos[v * 3 + 1], z + piece.pos[v * 3 + 2]);
                    }
                }
            }
        }
    }

    /**
     * smooth lighting: the light at a face corner is the average of the 4 cells touching it on the open side of the face. opaque cells borrow the centre cell's value so walls don't make dark seams and light can't leak diagonally between two walls
     *
     * (sx, sy, sz) are the corner's signs along the face's two tangent axes, the normal axis is 0
     */
    public static float cornerBlockLight(Level level, int nx, int ny, int nz, int sx, int sy, int sz) {
        float center = level.getBlockLight(nx, ny, nz);

        // the cell of a light source itself (the only cells at full strength): averaging it with the dimmer cells around it
        // would make the face next to the source darker than the faces around that one, a dark ring around the lamp
        if (center >= 1.0F) {
            return center;
        }

        int ax, ay, az, bx, by, bz;
        if (sx == 0) {
            ax = 0; ay = sy; az = 0;
            bx = 0; by = 0;  bz = sz;
        } else if (sy == 0) {
            ax = sx; ay = 0; az = 0;
            bx = 0;  by = 0; bz = sz;
        } else {
            ax = sx; ay = 0;  az = 0;
            bx = 0;  by = sy; bz = 0;
        }

        boolean blockedA = level.blocksBlockLight(nx + ax, ny + ay, nz + az);
        boolean blockedB = level.blocksBlockLight(nx + bx, ny + by, nz + bz);

        float a = blockedA ? center : level.getBlockLight(nx + ax, ny + ay, nz + az);
        float b = blockedB ? center : level.getBlockLight(nx + bx, ny + by, nz + bz);

        float d;
        if (blockedA && blockedB) {
            d = center;
        } else {
            int dx = nx + ax + bx, dy = ny + ay + by, dz = nz + az + bz;
            d = level.blocksBlockLight(dx, dy, dz) ? center : level.getBlockLight(dx, dy, dz);
        }

        return (center + a + b + d) * 0.25F;
    }

    public void renderFace(Tessellator tessellator, int x, int y, int z, int face) {
        AABB aabb = getAABB(x, y, z);

        float minX, maxX, minY, maxY, minZ, maxZ;
        if (aabb != null) {
            minX = (float) aabb.minX;
            maxX = (float) aabb.maxX;
            minY = (float) aabb.minY;
            maxY = (float) aabb.maxY;
            minZ = (float) aabb.minZ;
            maxZ = (float) aabb.maxZ;
        } else {
            minX = x; maxX = x + 1.0f;
            minY = y; maxY = y + 1.0f;
            minZ = z; maxZ = z + 1.0f;
        }

        if (face == FACE_BOTTOM) {
            tessellator.vertex(minX, minY, maxZ);
            tessellator.vertex(minX, minY, minZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(maxX, minY, maxZ);
        }
        if (face == FACE_TOP) {
            tessellator.vertex(maxX, maxY, maxZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(minX, maxY, maxZ);
        }
        if (face == FACE_NORTH) {
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(minX, minY, minZ);
        }
        if (face == FACE_SOUTH) {
            tessellator.vertex(minX, maxY, maxZ);
            tessellator.vertex(minX, minY, maxZ);
            tessellator.vertex(maxX, minY, maxZ);
            tessellator.vertex(maxX, maxY, maxZ);
        }
        if (face == FACE_WEST) {
            tessellator.vertex(minX, maxY, maxZ);
            tessellator.vertex(minX, maxY, minZ);
            tessellator.vertex(minX, minY, minZ);
            tessellator.vertex(minX, minY, maxZ);
        }
        if (face == FACE_EAST) {
            tessellator.vertex(maxX, minY, maxZ);
            tessellator.vertex(maxX, minY, minZ);
            tessellator.vertex(maxX, maxY, minZ);
            tessellator.vertex(maxX, maxY, maxZ);
        }
    }
}