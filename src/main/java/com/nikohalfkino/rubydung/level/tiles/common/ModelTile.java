package com.nikohalfkino.rubydung.level.tiles.common;

import com.nikohalfkino.rubydung.level.tiles.FaceCulling;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.UnderwaterTint;
import com.nikohalfkino.rubydung.level.tiles.model.BakedModel;
import com.nikohalfkino.rubydung.level.tiles.model.BakedQuad;
import com.nikohalfkino.rubydung.level.tiles.model.BlockStateDef;
import com.nikohalfkino.rubydung.level.tiles.model.BlockStateLoader;
import com.nikohalfkino.rubydung.level.tiles.model.ModelSource;
import com.nikohalfkino.rubydung.entity.phys.AABB;
import com.nikohalfkino.rubydung.render.Tessellator;
import com.nikohalfkino.rubydung.render.Textures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * a tile whose look comes from data: blockstates/NAME.toml says which model each data value uses (and how it's rotated), models are Minecraft-style JSON under /assets/rubydung/models
 *
 * everything is baked into quads at load, so drawing is just lighting and emitting quads. lighting, shading and the two render layers work exactly as in Tile.render
 */
public class ModelTile extends Tile {

    private static final BlockStateLoader LOADER = new BlockStateLoader(ModelSource.CLASSPATH);

    private final BlockStateDef blockState;

    /** uses the blockstate file named after the tile */
    public ModelTile(String name) {
        this(name, name);
    }

    public ModelTile(String name, String blockStateName) {
        super(name);
        this.blockState = LOADER.loadOrMissing(blockStateName, name);

        // Keep the six per-face textures meaningful (tile icons and the like read them): take them
        // from the default state, preferring quads that sit on the cell boundary.
        boolean[] assigned = new boolean[6];
        BakedModel defaultState = this.blockState.state(0);
        for (int pass = 0; pass < 2; pass++) {
            for (BakedQuad quad : defaultState.quads()) {
                boolean onBoundary = quad.cullFace >= 0;
                if (!assigned[quad.lightFace] && onBoundary == (pass == 0)) {
                    setTexture(quad.lightFace, quad.texture);
                    assigned[quad.lightFace] = true;
                }
            }
        }
    }

    public BlockStateDef getBlockState() {
        return this.blockState;
    }

    /** data byte for property values, e.g. dataOf("axis", "x") */
    public int dataOf(String... nameValuePairs) {
        return this.blockState.dataOf(nameValuePairs);
    }

    @Override
    public boolean occludesNeighbours() {
        return this.blockState.isFullCube();
    }

    // shape

    /** collision boxes of the cell's current state (slab, stairs...), in world coordinates */
    @Override
    public List<AABB> getCollisionBoxes(int x, int y, int z, int data) {
        List<AABB> boxes = new ArrayList<>();
        if (!isSolid()) {
            return boxes;
        }
        for (float[] b : this.blockState.state(data).collisionBoxes()) {
            boxes.add(new AABB(x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]));
        }
        return boxes;
    }

    /** rays hit the state's real shape (each step of a staircase, the half of a slab) */
    @Override
    public List<AABB> getPickBoxes(Level level, int x, int y, int z) {
        return getOccupancyBoxes(x, y, z, level.getData(x, y, z));
    }

    /** the state's collision boxes, or its outline box if it has none (so it can still be aimed at and packed) */
    @Override
    public List<AABB> getOccupancyBoxes(int x, int y, int z, int data) {
        List<AABB> boxes = getCollisionBoxes(x, y, z, data);
        if (boxes.isEmpty()) {
            float[] b = this.blockState.state(data).outlineBox();
            boxes.add(new AABB(x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]));
        }
        return boxes;
    }

    /** the selection outline follows the state's shape */
    @Override
    public AABB getOutlineAABB(Level level, int x, int y, int z) {
        float[] b = this.blockState.state(level.getData(x, y, z)).outlineBox();
        return new AABB(x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]);
    }

    // atlas

    @Override
    public Collection<String> getExtraTextures() {
        Set<String> paths = new LinkedHashSet<>();
        for (String name : this.blockState.textures()) {
            paths.add(Tile.texturePath(name));
        }
        return paths;
    }

    @Override
    public void onAtlasBuilt(Map<String, Integer> slotsByPath) {
        for (int state = 0; state < this.blockState.stateCount(); state++) {
            for (BakedQuad quad : this.blockState.state(state).quads()) {
                String path = Tile.texturePath(quad.texture);
                Integer slot = slotsByPath.get(path);
                quad.atlasSlot = slot == null ? 0 : slot;
                quad.atlasExtent = Textures.getAtlasExtent(path);
            }
        }
    }

    // drawing

    @Override
    public void render(Tessellator tessellator, Level level, int layer, int x, int y, int z,
                       int totalAtlasSlots, int direction) {
        boolean smooth     = level.hasLightSources();
        boolean fullBright = tessellator.isIconFullBright(); // tile icons: plain shade, drawn without the shader
        boolean emissive   = emitsLight();
        float[] block      = new float[4];

        // A sub-tile is drawn shifted inside its cell (see SubTile); a cell may also be shared.
        float offX = tessellator.getOffsetX();
        float offY = tessellator.getOffsetY();
        float offZ = tessellator.getOffsetZ();
        boolean shared = level.getSubTiles().has(x, y, z);

        for (BakedQuad quad : this.blockState.state(direction).quads()) {

            // A quad authored on the cell boundary is only still there if the shift didn't move it off it.
            int cullFace = quad.cullFace;
            if (cullFace >= 0) {
                float along = cullFace < 2 ? offY : cullFace < 4 ? offZ : offX;
                if (along != 0.0F) {
                    cullFace = -1;
                }
            }

            // The lid of a half block (see Tessellator.setClipY) faces into the free half of the cell:
            // always drawn, and lit by the cell itself.
            boolean cap = cullFace >= 0 && cullFace == tessellator.getClipCapFace();

            // Quads on the cell boundary are hidden by whatever the neighbour on that side is, but a
            // neighbour that only fills part of the face (a slab, a sub-tile) hides only that part.
            List<FaceCulling.Piece> pieces = null;
            if (cullFace >= 0 && !cap) {
                int[] side = FACE_NORMALS[cullFace];
                int nx = x + side[0], ny = y + side[1], nz = z + side[2];
                boolean selfCull = cullsSelfFace(quad.modelCullFace);
                if (selfCull && tessellator.iconTouchesSelf(cullFace)) {
                    continue;   // an icon: a self-culled face that would touch the same tile
                }
                boolean visible;
                if (selfCull && level.getTile(nx, ny, nz) == this.id) {
                    // The same kind of tile next door: what hides this face is decided below, by how
                    // much of it the neighbour's own boxes cover (a bottom half does not cover a top half).
                    visible = true;
                } else {
                    visible = shouldRenderFace(level, x, y, z, nx, ny, nz, quad.modelCullFace);
                }
                if (!visible) {
                    continue;
                }
                pieces = FaceCulling.visiblePieces(level, this, selfCull, x, y, z,
                        quad.positions, quad.uvs, cullFace, offX, offY, offZ);
                if (pieces != null && pieces.isEmpty()) {
                    continue;
                }
            }

            // Pressed flat against another tile that shares this cell: never visible.
            if (shared && !cap && !isTransparent()
                    && level.getSubTiles().isQuadHidden(x, y, z, quad, this.id, direction, offX, offY, offZ)) {
                continue;
            }

            // Lit by the cell in front of the quad (and, for quads inside the cell, the cell itself).
            int[] normal = FACE_NORMALS[quad.lightFace];
            int lx = x + normal[0];
            int ly = y + normal[1];
            int lz = z + normal[2];
            boolean inside = cullFace < 0 || cap;

            float shade = emissive ? 1.0F : (quad.shade ? FACE_SHADE[quad.lightFace] : 1.0F);

            float tint = (emissive || fullBright) ? UnderwaterTint.DRY : UnderwaterTint.pack(level, lx, ly, lz);
            float sky = 1.0F;
            if (!emissive && !fullBright) {
                sky = level.getSkyLight(lx, ly, lz);
                // A quad inside the cell is also lit by the cell itself. Not so for a downward-facing
                // quad of a shadow-casting tile (the underside of a top slab): the cell's own sky
                // light only looks at what lies ABOVE the cell, and knows nothing of this tile, so it
                // would read as open sky and cancel the shadow the tile casts on that very quad.
                boolean shadowedUnderside = inside && quad.lightFace == FACE_BOTTOM && castsShadow();
                if (inside && !shadowedUnderside) {
                    sky = Math.max(sky, level.getSkyLight(x, y, z));
                }
            }

            boolean fullyLit = true;
            for (int v = 0; v < 4; v++) {
                block[v] = 0.0F;
                if (emissive) {
                    block[v] = 1.0F;
                } else if (smooth && !fullBright) {
                    if (inside) {
                        block[v] = Math.max(level.getBlockLight(lx, ly, lz), level.getBlockLight(x, y, z));
                    } else {
                        // Smooth lighting like cubes: which corner of the face this vertex is at.
                        int sx = normal[0] == 0 ? (quad.positions[v * 3]     >= 0.5F ? 1 : -1) : 0;
                        int sy = normal[1] == 0 ? (quad.positions[v * 3 + 1] >= 0.5F ? 1 : -1) : 0;
                        int sz = normal[2] == 0 ? (quad.positions[v * 3 + 2] >= 0.5F ? 1 : -1) : 0;
                        block[v] = cornerBlockLight(level, lx, ly, lz, sx, sy, sz);
                    }
                }

                if (sky < 1.0F && block[v] < 1.0F) {
                    fullyLit = false;
                }
            }

            if (layer != LAYER_ALL && !((layer == 1) ^ fullyLit)) {
                continue;
            }

            if (pieces == null) {
                for (int v = 0; v < 4; v++) {
                    if (fullBright) {
                        tessellator.color(shade, shade, shade);
                    } else {
                        tessellator.color(sky, block[v], shade, tint);
                    }

                    float u = Chunk.atlasU(quad.atlasSlot, quad.uvs[v * 2] / 16.0F * quad.atlasExtent);
                    float t = Chunk.atlasV(quad.atlasSlot, quad.uvs[v * 2 + 1] / 16.0F * quad.atlasExtent);
                    tessellator.texture(u, t);

                    tessellator.vertex(x + quad.positions[v * 3], y + quad.positions[v * 3 + 1], z + quad.positions[v * 3 + 2]);
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

                        float u = Chunk.atlasU(quad.atlasSlot, piece.uv[v * 2] / 16.0F * quad.atlasExtent);
                        float t = Chunk.atlasV(quad.atlasSlot, piece.uv[v * 2 + 1] / 16.0F * quad.atlasExtent);
                        tessellator.texture(u, t);

                        tessellator.vertex(x + piece.pos[v * 3], y + piece.pos[v * 3 + 1], z + piece.pos[v * 3 + 2]);
                    }
                }
            }
        }
    }
}