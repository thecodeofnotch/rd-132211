package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.BorderWarp;
import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.UnderwaterTint;
import com.nikohalfkino.rubydung.level.WorldBorder;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * A fake extension of the world drawn past its real edge: columns of terrain, the same one-block size as the real ones, that copy the
 * height and the look of the nearest edge column, droop, bend into waves, and finally break down (PS1 style) into a few floating triangles.
 *
 * Static display lists (one per REGION x REGION block patch, so patches out of range or out of view are skipped), built from the edge columns on the first frame.
 * When a block on the edge changes, only the patches its column feeds are rebuilt (after a short pause). It has no collision, see Player.keepInsideWorld for how the player is kept off it.
 *
 * Everything that changes with the distance from the real edge is a function of t = distance / WorldBorder.FRINGE (0 at the edge, 1 at the
 * outer rim), so it blends in from the real terrain with no seam. In order of distance:
 *   - the terrain heights wander and droop (the mesh, built on the CPU)
 *   - wavy: every vertex is pushed by a smooth field of its world position. a still distortion, no time in it, and since the push depends
 *     only on the position, the vertices two faces share move together and the surface stays whole
 *   - screen-space vertex snap, a grid that gets coarser with t (the PS1 wobble), and affine texture mapping mixed in with t
 *   - breakdown: triangles are left out of the mesh at random, more and more of them with t (decided here, so a lost triangle costs nothing to draw).
 *     the survivors come loose: they shift, turn a little and bob
 */
public final class WorldBorderRenderer {

    // ---- tuning ----------------------------------------------------------------------------------------------------------------------

    /** how far the tops wander up and down (blocks) at the outer rim, growing with t */
    private static final float HEIGHT_NOISE = 3.0F;

    /** how far the tops sink (blocks) at the outer rim, growing with t squared */
    private static final float DROOP = 16.0F;

    /** a column is at least this thick (blocks) when it is cut loose from the ground at the outer rim */
    private static final int MIN_THICKNESS = 3;

    /** the walls of the fringe go no deeper than this under the top of their column (blocks) */
    private static final int MAX_WALL = 12;

    // t where each effect starts and how strong it gets
    private static final float WAVE_FROM = BorderWarp.WAVE_FROM;            // the surface starts to bend ...
    private static final float WAVE_FULL = BorderWarp.WAVE_FULL;            // ... and is at full strength here. it keeps that to the rim
    private static final float WAVE = BorderWarp.WAVE;                  // about this many blocks of push at most (the field is faceted, see wave() in the shader). well over 1.5 and the surface folds through itself
    private static final float WAVE_CELL = BorderWarp.WAVE_CELL;             // the facets of the field are this many blocks across
    private static final float SNAP_START = 0.12F;           // below this the snap fades in from nothing, so the seam stays exact
    private static final float SNAP_COARSEST = 3.0F;         // the snap grid at the outer rim, times PsxSnap's grid (a few pixels: much coarser and one-block triangles slide over each other)
    private static final float AFFINE_FROM = 0.02F;          // texture mapping starts sliding from perspective-correct to affine
    private static final float AFFINE_TO = 0.30F;            // ... and is fully affine
    private static final float AFFINE_GAIN = 2.0F;           // above 1 the slide goes past affine: the texture swims about twice as far as a plain affine one (small one-block triangles show little of it otherwise)
    private static final float BREAK_FROM = BorderWarp.BREAK_FROM;           // triangles start to disappear and the rest to come loose
    private static final float KILL_MAX = BorderWarp.KILL_MAX;             // share of triangles gone at the rim (the rest are the floating shards)
    private static final float SHIFT_XZ = BorderWarp.SHIFT_XZ;              // how far a shard can sit off its place sideways (blocks, at the rim)
    private static final float SHIFT_Y = BorderWarp.SHIFT_Y;               // ... and up or down
    private static final float TURN = BorderWarp.TURN;                  // ... how far it can turn (radians)
    private static final float BOB = 0.3F;                   // blocks of slow up and down

    // ----------------------------------------------------------------------------------------------------------------------------------

    private static final long START_NANOS = System.nanoTime();
    private static final long REBUILD_DELAY_NANOS = 60_000_000L;

    private final Level level;
    /** side of one patch of the fringe, in blocks. every patch is its own display list with its own bounds, so it can be culled */
    private static final int REGION = 16;
    /** how far a vertex can end up from its place (waves, snapping, loose shards), in blocks. the bounds of a patch are grown by this */
    private static final float BOUNDS_PAD_XZ = 8.0F;
    private static final float BOUNDS_PAD_Y = 8.0F;

    private int[] visibleScratch = new int[0];
    private int[] lists = new int[0];
    private float[] bounds = new float[0];      // per list: minX, minY, minZ, maxX, maxY, maxZ
    private int listCount = 0;

    /** one patch of the fringe: its cells and its display list (0 if it has nothing to draw) */
    private static final class Patch {
        final List<Cell> cells = new ArrayList<>();
        int list = 0;
        final float[] box = new float[6];
    }

    private final Map<Long, Patch> patches = new HashMap<>();
    private Cell[] cellAt = new Cell[0];        // the cell on each block of the grid (null in the real world)
    private int[] gridTop = new int[0];
    private int[] gridBottom = new int[0];
    private int gridW, gridH;
    private MeshWriter writer;
    private boolean full = true;                // everything is rebuilt, not just the patches of the edited columns
    private final Set<Long> pendingEdits = new LinkedHashSet<>();
    private static final int MAX_PENDING_EDITS = 64;
    /** patches waiting to be written again, and how many are written per frame (a corner column is a dozen patches: spread, not one frame's hitch) */
    private final Set<Long> patchQueue = new LinkedHashSet<>();
    private static final int PATCHES_PER_FRAME = 3;
    private final float[] snapStep = new float[2];
    private boolean dirty = true;
    private long rebuildAt = 0L;

    public WorldBorderRenderer(Level level) {
        this.level = level;
    }

    /** everything changed: the whole mesh is rebuilt shortly (a burst of changes is one rebuild) */
    public void markDirty() {
        this.full = true;
        this.pendingEdits.clear();
        this.dirty = true;
        this.rebuildAt = System.nanoTime() + REBUILD_DELAY_NANOS;
    }

    /** the edge column (x, z) changed: the patches it feeds are rebuilt shortly (a burst of edits is one rebuild) */
    public void markDirty(int x, int z) {
        if (!this.full) {
            this.pendingEdits.add(((long) x << 32) | (z & 0xFFFFFFFFL));
            if (this.pendingEdits.size() > MAX_PENDING_EDITS) {
                this.full = true;
                this.pendingEdits.clear();
            }
        }
        this.dirty = true;
        this.rebuildAt = System.nanoTime() + REBUILD_DELAY_NANOS;
    }

    /** true if a block at (x, z) can change what the fringe looks like (it is on the outermost ring of columns) */
    public boolean isEdgeColumn(int x, int z) {
        return x <= 0 || z <= 0 || x >= this.level.width - 1 || z >= this.level.height - 1;
    }

    // ====================================================================================================================================
    // drawing

    private static int program = 0;
    private static int ambientLocation = -1;
    private static int sunTintLocation = -1;
    private static int fogParamsLocation = -1;
    private static int fogColorLocation = -1;
    private static int fogEyeLocation = -1;
    private static int timeLocation = -1;
    private static int worldLocation = -1;
    private static int fringeLocation = -1;
    private static int stepLocation = -1;
    private static LiquidTinting.Uniforms tinting;

    /** call after the terrain, with the same sea set on TerrainShader (LevelRenderer.render does) */
    public void draw(float ambient, double viewX, double viewZ, double reach) {
        if (this.dirty && System.nanoTime() >= this.rebuildAt) {
            if (this.full) {
                rebuildAll();
            } else {
                rebuildEdited();
            }
        }
        if (!this.patchQueue.isEmpty()) {
            drainPatchQueue();
        }
        if (this.listCount == 0) {
            return;
        }

        // pick the patches that are within the render distance (reach <= 0: no limit) and in view, before touching any GL state
        Frustum frustum = Frustum.getFrustum();
        boolean limited = reach > 0.0D && !Double.isNaN(viewX) && !Double.isNaN(viewZ);
        double reach2 = reach * reach;
        int[] visible = this.visibleScratch.length >= this.listCount ? this.visibleScratch : (this.visibleScratch = new int[this.listCount]);
        int count = 0;
        for (int i = 0; i < this.listCount; i++) {
            int b = i * 6;
            float minX = this.bounds[b], minY = this.bounds[b + 1], minZ = this.bounds[b + 2];
            float maxX = this.bounds[b + 3], maxY = this.bounds[b + 4], maxZ = this.bounds[b + 5];
            if (limited) {
                double dx = Math.max(Math.max(minX - viewX, viewX - maxX), 0.0D);
                double dz = Math.max(Math.max(minZ - viewZ, viewZ - maxZ), 0.0D);
                if (dx * dx + dz * dz > reach2) {
                    continue;
                }
            }
            if (!frustum.cubeInFrustum(minX, minY, minZ, maxX, maxY, maxZ)) {
                continue;
            }
            visible[count++] = this.lists[i];
        }
        if (count == 0) {
            return;
        }
        if (program == 0) {
            initShader();
        }

        GL20.glUseProgram(program);
        GL20.glUniform1f(ambientLocation, ambient);
        WorldTint.upload(sunTintLocation);
        Fog.upload(fogParamsLocation, fogColorLocation, fogEyeLocation);
        tinting.upload();
        GL20.glUniform1f(timeLocation, ((System.nanoTime() - START_NANOS) * 1.0E-9F) % 3600.0F);
        GL20.glUniform2f(worldLocation, this.level.width, this.level.height);
        GL20.glUniform1f(fringeLocation, WorldBorder.FRINGE);
        PsxSnap.step(this.snapStep);
        GL20.glUniform2f(stepLocation, this.snapStep[0], this.snapStep[1]);

        // shards turn over, so both sides of every triangle are drawn
        boolean cull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_CULL_FACE);
        glEnable(GL_TEXTURE_2D);
        Textures.bind(Chunk.getAtlasTexture());
        try {
            for (int i = 0; i < count; i++) {
                glCallList(visible[i]);
            }
        } finally {
            glDisable(GL_TEXTURE_2D);
            GL20.glUseProgram(0);
            if (cull) {
                glEnable(GL_CULL_FACE);
            }
            // the display list leaves its last centroid in texcoord 1, other passes expect it as they set it
            GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1, 0.0F, 0.0F);
        }
    }

    // ====================================================================================================================================
    // shader

    private static String f(float v) {
        return Float.toString(v);
    }

    private static final String VERT =
            "#version 120\n" +
                    "uniform float uTime;\n" +
                    "uniform vec2 uWorld;\n" +          // x size, z size of the real world
                    "uniform float uFringe;\n" +
                    "varying vec4 vColor;\n" +
                    "varying vec3 vWorldPos;\n" +
                    "varying vec3 vUvW;\n" +            // (uv * w, w): see the fragment shader
                    "varying vec2 vUv;\n" +
                    "varying float vT;\n" +
                    "uniform vec2 uStep;\n" +
                    PsxSnap.GLSL +
                    Fog.GLSL_VERTEX_DECLARATIONS +

                    "const float WAVE_FROM = " + f(WAVE_FROM) + ";\n" +
                    "const float WAVE_FULL = " + f(WAVE_FULL) + ";\n" +
                    "const float WAVE = " + f(WAVE) + ";\n" +
                    "const float WAVE_CELL = " + f(WAVE_CELL) + ";\n" +
                    "const float SNAP_START = " + f(SNAP_START) + ";\n" +
                    "const float SNAP_COARSEST = " + f(SNAP_COARSEST) + ";\n" +
                    "const float BREAK_FROM = " + f(BREAK_FROM) + ";\n" +
                    "const float SHIFT_XZ = " + f(SHIFT_XZ) + ";\n" +
                    "const float SHIFT_Y = " + f(SHIFT_Y) + ";\n" +
                    "const float TURN = " + f(TURN) + ";\n" +
                    "const float BOB = " + f(BOB) + ";\n" +

                    "float hash13(vec3 p) {\n" +
                    "    p = fract(p * 0.1031);\n" +
                    "    p += dot(p, p.yzx + 33.33);\n" +
                    "    return fract((p.x + p.y) * p.z);\n" +
                    "}\n" +
                    "vec3 hash33(vec3 p) {\n" +
                    "    p = fract(p * vec3(0.1031, 0.1030, 0.0973));\n" +
                    "    p += dot(p, p.yxz + 33.33);\n" +
                    "    return fract((p.xxy + p.yxx) * p.zyx);\n" +
                    "}\n" +
                    "vec3 rotY(vec3 v, float a) {\n" +
                    "    float c = cos(a);\n" +
                    "    float s = sin(a);\n" +
                    "    return vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z);\n" +
                    "}\n" +
                    "vec3 rotX(vec3 v, float a) {\n" +
                    "    float c = cos(a);\n" +
                    "    float s = sin(a);\n" +
                    "    return vec3(v.x, c * v.y - s * v.z, s * v.y + c * v.z);\n" +
                    "}\n" +

                    // The still distortion: random offsets on a lattice of WAVE_CELL blocks, blended linearly (not smoothed) between the lattice points, so the
                    // field is faceted: flat stretches with creases, and the surface bends in planes the way low-poly terrain does instead of rolling.
                    // Its slope stays well under 1, so it bends the blocks without folding them over
                    "vec3 latticeOffset(vec3 i) {\n" +
                    "    return hash33(i) * 2.0 - 1.0;\n" +
                    "}\n" +
                    "vec3 wave(vec3 p) {\n" +
                    "    vec3 q = p / WAVE_CELL;\n" +
                    "    vec3 i = floor(q);\n" +
                    "    vec3 f = q - i;\n" +
                    "    vec3 a = mix(mix(latticeOffset(i), latticeOffset(i + vec3(1.0, 0.0, 0.0)), f.x),\n" +
                    "                 mix(latticeOffset(i + vec3(0.0, 1.0, 0.0)), latticeOffset(i + vec3(1.0, 1.0, 0.0)), f.x), f.y);\n" +
                    "    vec3 b = mix(mix(latticeOffset(i + vec3(0.0, 0.0, 1.0)), latticeOffset(i + vec3(1.0, 0.0, 1.0)), f.x),\n" +
                    "                 mix(latticeOffset(i + vec3(0.0, 1.0, 1.0)), latticeOffset(i + vec3(1.0, 1.0, 1.0)), f.x), f.y);\n" +
                    "    return mix(a, b, f.z) * vec3(0.9, 1.0, 0.9);\n" +
                    "}\n" +

                    // 0 on the real world's edge (and inside it), 1 at the outer rim: the Chebyshev distance the mesh is built by
                    "float edgeT(vec3 p) {\n" +
                    "    float dx = max(max(-p.x, p.x - uWorld.x), 0.0);\n" +
                    "    float dz = max(max(-p.z, p.z - uWorld.y), 0.0);\n" +
                    "    return clamp(max(dx, dz) / uFringe, 0.0, 1.0);\n" +
                    "}\n" +

                    "void main() {\n" +
                    "    vec3 pos = gl_Vertex.xyz;\n" +
                    // the triangle's centroid, the same on all three of its corners: everything per triangle is keyed to it
                    "    vec3 cen = gl_MultiTexCoord1.xyz;\n" +
                    "    float tC = edgeT(cen);\n" +
                    "    float tV = edgeT(pos);\n" +
                    "    float h = hash13(cen);\n" +

                    "    vColor = gl_Color;\n" +
                    "    vUv = gl_MultiTexCoord0.st;\n" +
                    "    vT = tV;\n" +

                    // wavy: the vertex is pushed by the field, by how far out it is. the push depends only on the position, so two faces that share
                    // a corner move it the same way. (the centroid is pushed too: it is what the shard comes loose around)
                    "    vec3 wcen = cen + wave(cen) * smoothstep(WAVE_FROM, WAVE_FULL, tC) * WAVE;\n" +
                    "    pos += wave(pos) * smoothstep(WAVE_FROM, WAVE_FULL, tV) * WAVE;\n" +

                    // breakdown: the triangles left over come loose, a little shifted and turned about their centroid, with a slow bob. e is 0 until
                    // BREAK_FROM, where this changes nothing
                    "    float e = smoothstep(BREAK_FROM, 1.0, tC);\n" +
                    "    float e2 = e * e;\n" +
                    "    vec3 r = hash33(cen + 17.0) - 0.5;\n" +
                    "    vec3 rel = pos - wcen;\n" +
                    "    rel = rotY(rel, r.x * TURN * e2);\n" +
                    "    rel = rotX(rel, r.y * TURN * e2);\n" +
                    "    float bob = sin(uTime * 0.7 + h * 40.0) * BOB * e;\n" +
                    "    pos = wcen + rel + vec3(r.x * SHIFT_XZ * e2, r.y * SHIFT_Y * e2 + bob, r.z * SHIFT_XZ * e2);\n" +

                    "    vec4 clip = gl_ModelViewProjectionMatrix * vec4(pos, 1.0);\n" +

                    // PS1 vertex snap (PsxSnap, the grid the models snap to): the grid gets coarser with t, up to SNAP_COARSEST times as coarse at the rim. it
                    // fades in from SNAP_START, so right at the edge the vertices are exactly where the real chunks put theirs
                    "    float gridScale = mix(1.0, SNAP_COARSEST, pow(tV, 0.7));\n" +
                    "    clip = psxSnap(clip, uStep * gridScale, smoothstep(0.0, SNAP_START, tV));\n" +
                    "    gl_Position = clip;\n" +

                    // for the affine mapping, see the fragment shader
                    "    vUvW = vec3(gl_MultiTexCoord0.st * clip.w, clip.w);\n" +
                    "    vWorldPos = pos;\n" +
                    "    vFogWorldXZ = pos.xz;\n" +
                    "}\n";

    private static final String FRAG =
            "#version 120\n" +
                    "uniform sampler2D tex;\n" +
                    "uniform float ambient;\n" +
                    "uniform vec3 uSunTint;\n" +
                    "varying vec4 vColor;\n" +
                    "varying vec3 vWorldPos;\n" +
                    "varying vec3 vUvW;\n" +
                    "varying vec2 vUv;\n" +
                    "varying float vT;\n" +
                    LiquidTinting.GLSL_DECLARATIONS +
                    Fog.GLSL_FRAGMENT_DECLARATIONS +
                    Fog.GLSL_DITHER_DECLARATIONS +

                    "const float AFFINE_FROM = " + f(AFFINE_FROM) + ";\n" +
                    "const float AFFINE_TO = " + f(AFFINE_TO) + ";\n" +
                    "const float AFFINE_GAIN = " + f(AFFINE_GAIN) + ";\n" +

                    "void main() {\n" +
                    "    applyDither();\n" +

                    // Affine mapping. GLSL 120 has no noperspective, but the hardware interpolates a varying perspective-correctly, which means
                    // Q = (sum l_i * q_i / w_i) / (sum l_i / w_i). With q = uv * w that is (sum l_i * uv_i) / D, and for the w in .z it is
                    // (sum l_i) / D = 1 / D, so .xy / .z leaves the plain screen-space (affine) interpolation sum l_i * uv_i: textures that swim like on a PS1
                    "    float k = smoothstep(AFFINE_FROM, AFFINE_TO, vT);\n" +
                    "    vec2 uv = vUv + (vUvW.xy / vUvW.z - vUv) * (k * AFFINE_GAIN);\n" +
                    "    vec4 c = texture2D(tex, uv);\n" +
                    "    if (c.a < 0.02) {\n" +
                    "        discard;\n" +
                    "    }\n" +

                    // the light of the terrain shader without its per-texel patches: r = sky, g = block light, b = the face shade
                    "    float s = pow(clamp(vColor.r, 0.0, 1.0), 1.5) * ambient;\n" +
                    "    float b = pow(clamp(vColor.g, 0.0, 1.0), 1.5);\n" +
                    "    float l = clamp(s + (1.0 - s) * b, 0.0, 1.0);\n" +
                    "    vec3 rgb = c.rgb * (l * vColor.b) * uSunTint;\n" +

                    "    if (vColor.a < 0.9997) {\n" +
                    "        rgb = liquidTint(rgb, vColor.a, vWorldPos.y);\n" +
                    "    }\n" +

                    "    gl_FragColor = vec4(applyFog(rgb), c.a);\n" +
                    "}\n";

    private static void initShader() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);

        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("World border shader link failed: " + GL20.glGetProgramInfoLog(p, 4096));
        }

        GL20.glUseProgram(p);
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
        GL20.glUseProgram(0);

        ambientLocation = GL20.glGetUniformLocation(p, "ambient");
        sunTintLocation = GL20.glGetUniformLocation(p, "uSunTint");
        fogParamsLocation = GL20.glGetUniformLocation(p, "uFogParams");
        fogColorLocation = GL20.glGetUniformLocation(p, "uFogColor");
        fogEyeLocation = GL20.glGetUniformLocation(p, "uFogEye");
        timeLocation = GL20.glGetUniformLocation(p, "uTime");
        worldLocation = GL20.glGetUniformLocation(p, "uWorld");
        fringeLocation = GL20.glGetUniformLocation(p, "uFringe");
        stepLocation = GL20.glGetUniformLocation(p, "uStep");
        tinting = new LiquidTinting.Uniforms(p);
        program = p;
    }

    /** compiles the program now instead of on the first frame that shows it, see ShaderWarmup */
    public static void warmUp() {
        if (program == 0) {
            initShader();
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("World border shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
        }
        return shader;
    }

    // ====================================================================================================================================
    // mesh

    /** what a real edge column looks like from above: its top, and the blocks to copy the textures from */
    private static final class Column {
        final int top;          // y of the surface (the highest suitable block + 1), 0 if the column has none
        final Tile topTile;
        final Tile belowTile;   // the first suitable block under the top one (the top one again if there is none)

        Column(int top, Tile topTile, Tile belowTile) {
            this.top = top;
            this.topTile = topTile;
            this.belowTile = belowTile;
        }
    }

    private static final Column EMPTY_COLUMN = new Column(0, null, null);

    private final Map<Integer, Column> columns = new HashMap<>();

    private static boolean suitable(Tile tile) {
        return tile != null && tile != Tile.AIR && tile.isSolid() && !tile.isBillboard() && !tile.isTransparent()
                && !tile.isMultiTile() && tile.getTexture(Tile.FACE_TOP) != null;
    }

    private Column column(int x, int z) {
        Integer key = z * this.level.width + x;
        Column known = this.columns.get(key);
        if (known != null) {
            return known;
        }

        Column found = EMPTY_COLUMN;
        int y = this.level.depth - 1;
        for (; y >= 0; y--) {
            Tile tile = Tile.byId(this.level.getTile(x, y, z));
            if (suitable(tile)) {
                Tile below = tile;
                for (int by = y - 1; by >= 0; by--) {
                    Tile candidate = Tile.byId(this.level.getTile(x, by, z));
                    if (suitable(candidate)) {
                        below = candidate;
                        break;
                    }
                }
                found = new Column(y + 1, tile, below);
                break;
            }
        }
        this.columns.put(key, found);
        return found;
    }

    /** one block-aligned column of the fringe */
    private static final class Cell {
        int x0, z0, x1, z1;     // footprint, block coordinates, x1 / z1 exclusive
        int top, bottom;
        Column source;          // the edge column it copies
        int edgeX, edgeZ;
    }

    private static long patchKey(int x, int z) {
        return ((long) Math.floorDiv(x, REGION) << 32) | (Math.floorDiv(z, REGION) & 0xFFFFFFFFL);
    }

    /** the cell's height and what it copies, worked out, and written to the one-block resolution map faces use to find the neighbour they face */
    private void store(Cell cell) {
        shape(cell, this.level.width, this.level.height, this.level.depth);
        for (int gz = cell.z0; gz < cell.z1; gz++) {
            for (int gx = cell.x0; gx < cell.x1; gx++) {
                int i = (gx + WorldBorder.FRINGE) + (gz + WorldBorder.FRINGE) * this.gridW;
                this.gridTop[i] = Math.max(cell.top, 0);
                this.gridBottom[i] = cell.bottom;
            }
        }
    }

    /** everything, from scratch: the first frame, and when the whole level changed */
    private void rebuildAll() {
        this.dirty = false;
        this.full = false;
        this.pendingEdits.clear();
        this.patchQueue.clear();
        this.columns.clear();

        int width = this.level.width;
        int height = this.level.height;
        int depth = this.level.depth;
        int fringe = WorldBorder.FRINGE;

        // the cells: a ring of one-block columns, as four strips (the near and far ones carry the corners)
        List<Cell> cells = new ArrayList<>();
        strip(cells, -fringe, 0, -fringe, height + fringe);                    // -x
        strip(cells, width, width + fringe, -fringe, height + fringe);         // +x
        strip(cells, 0, width, -fringe, 0);                                    // -z
        strip(cells, 0, width, height, height + fringe);                       // +z

        for (Patch old : this.patches.values()) {
            if (old.list != 0) {
                glDeleteLists(old.list, 1);
            }
        }
        this.patches.clear();

        this.gridW = width + 2 * fringe;
        this.gridH = height + 2 * fringe;
        this.gridTop = new int[this.gridW * this.gridH];
        this.gridBottom = new int[this.gridW * this.gridH];
        this.cellAt = new Cell[this.gridW * this.gridH];
        this.writer = new MeshWriter(width, height, depth, fringe, this.gridW, this.gridH, this.gridTop, this.gridBottom);

        for (Cell cell : cells) {
            store(cell);
            this.cellAt[(cell.x0 + fringe) + (cell.z0 + fringe) * this.gridW] = cell;
            this.patches.computeIfAbsent(patchKey(cell.x0, cell.z0), k -> new Patch()).cells.add(cell);
        }
        for (Patch patch : this.patches.values()) {
            writePatch(patch);
        }
        refreshDrawArrays();
    }

    /**
     * Only what the edited edge columns feed: the cells that copy them are reshaped, and every patch that touches those cells (or the ring of
     * cells around them, whose walls look at them) is written again. A column on the x = 0 edge feeds the whole row of cells past it, so it is a
     * handful of patches, not the whole ring
     */
    private void rebuildEdited() {
        this.dirty = false;
        if (this.pendingEdits.isEmpty()) {
            return;
        }
        int width = this.level.width;
        int height = this.level.height;
        int fringe = WorldBorder.FRINGE;

        // the columns first: reshaping one cell must not see the old state of a column another edit changed
        for (long edit : this.pendingEdits) {
            int x = (int) (edit >> 32);
            int z = (int) edit;
            this.columns.remove(z * width + x);
        }

        Set<Long> dirtyPatches = new HashSet<>();
        for (long edit : this.pendingEdits) {
            int x = (int) (edit >> 32);
            int z = (int) edit;
            // the cells that copy this column: all the way out to the rim past a side, just the one column along it
            int xLo = x <= 0 ? -fringe : x;
            int xHi = x >= width - 1 ? width + fringe - 1 : x;
            int zLo = z <= 0 ? -fringe : z;
            int zHi = z >= height - 1 ? height + fringe - 1 : z;
            for (int cz = zLo; cz <= zHi; cz++) {
                for (int cx = xLo; cx <= xHi; cx++) {
                    Cell cell = cellAt(cx, cz);
                    if (cell != null && cell.edgeX == Math.max(0, Math.min(width - 1, cx)) && cell.edgeZ == Math.max(0, Math.min(height - 1, cz))) {
                        store(cell);
                    }
                }
            }
            // patches under the cells and the ring around them
            for (int pz = Math.floorDiv(zLo - 1, REGION); pz <= Math.floorDiv(zHi + 1, REGION); pz++) {
                for (int px = Math.floorDiv(xLo - 1, REGION); px <= Math.floorDiv(xHi + 1, REGION); px++) {
                    dirtyPatches.add(((long) px << 32) | (pz & 0xFFFFFFFFL));
                }
            }
        }
        this.pendingEdits.clear();

        this.patchQueue.addAll(dirtyPatches);
    }

    /** writes a few of the queued patches again */
    private void drainPatchQueue() {
        int budget = PATCHES_PER_FRAME;
        java.util.Iterator<Long> it = this.patchQueue.iterator();
        while (it.hasNext() && budget-- > 0) {
            Patch patch = this.patches.get(it.next());
            it.remove();
            if (patch != null) {
                writePatch(patch);
            }
        }
        refreshDrawArrays();
    }

    private Cell cellAt(int x, int z) {
        int gx = x + WorldBorder.FRINGE;
        int gz = z + WorldBorder.FRINGE;
        if (gx < 0 || gz < 0 || gx >= this.gridW || gz >= this.gridH) {
            return null;
        }
        return this.cellAt[gx + gz * this.gridW];
    }

    /** (re)compiles the patch's display list from its cells and works out its bounds. the bounds are grown by what the shader can move a vertex by */
    private void writePatch(Patch patch) {
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean any = false;
        for (Cell cell : patch.cells) {
            if (cell.top > cell.bottom && cell.source.topTile != null) {
                any = true;
                minX = Math.min(minX, cell.x0);
                minZ = Math.min(minZ, cell.z0);
                maxX = Math.max(maxX, cell.x1);
                maxZ = Math.max(maxZ, cell.z1);
                minY = Math.min(minY, Math.max(cell.bottom, cell.top - MAX_WALL));
                maxY = Math.max(maxY, cell.top);
            }
        }
        if (!any) {
            if (patch.list != 0) {
                glDeleteLists(patch.list, 1);
                patch.list = 0;
            }
            return;
        }
        if (patch.list == 0) {
            patch.list = glGenLists(1);
        }
        glNewList(patch.list, GL_COMPILE);
        glBegin(GL_TRIANGLES);
        for (Cell cell : patch.cells) {
            if (cell.top > cell.bottom && cell.source.topTile != null) {
                this.writer.cell(cell);
            }
        }
        glEnd();
        glEndList();

        patch.box[0] = minX - BOUNDS_PAD_XZ;
        patch.box[1] = minY - BOUNDS_PAD_Y;
        patch.box[2] = minZ - BOUNDS_PAD_XZ;
        patch.box[3] = maxX + BOUNDS_PAD_XZ;
        patch.box[4] = maxY + BOUNDS_PAD_Y;
        patch.box[5] = maxZ + BOUNDS_PAD_XZ;
    }

    /** the arrays draw() walks every frame: the lists and bounds of the patches that have something to draw */
    private void refreshDrawArrays() {
        int n = 0;
        for (Patch patch : this.patches.values()) {
            if (patch.list != 0) {
                n++;
            }
        }
        this.lists = new int[n];
        this.bounds = new float[n * 6];
        this.listCount = 0;
        for (Patch patch : this.patches.values()) {
            if (patch.list != 0) {
                System.arraycopy(patch.box, 0, this.bounds, this.listCount * 6, 6);
                this.lists[this.listCount++] = patch.list;
            }
        }
    }

    /** adds the one-block cells of the rectangle [x0, x1) x [z0, z1) */
    private static void strip(List<Cell> out, int x0, int x1, int z0, int z1) {
        for (int cz = z0; cz < z1; cz++) {
            for (int cx = x0; cx < x1; cx++) {
                Cell cell = new Cell();
                cell.x0 = cx;
                cell.z0 = cz;
                cell.x1 = cx + 1;
                cell.z1 = cz + 1;
                out.add(cell);
            }
        }
    }

    /** works out which edge column a cell copies and how high it is */
    private void shape(Cell cell, int width, int height, int depth) {
        float cx = (cell.x0 + cell.x1) * 0.5F;
        float cz = (cell.z0 + cell.z1) * 0.5F;

        float dx = Math.max(Math.max(-cx, cx - width), 0.0F);
        float dz = Math.max(Math.max(-cz, cz - height), 0.0F);
        float t = Math.min(Math.max(dx, dz) / WorldBorder.FRINGE, 1.0F);

        cell.edgeX = Math.max(0, Math.min(width - 1, (int) Math.floor(cx)));
        cell.edgeZ = Math.max(0, Math.min(height - 1, (int) Math.floor(cz)));
        cell.source = column(cell.edgeX, cell.edgeZ);

        if (cell.source.top <= 0) {
            cell.top = 0;
            cell.bottom = 0;
            return;
        }

        // the tops wander, then sink. both are 0 at the seam, so the first cells match the real edge exactly
        float wander = (fbm(cx, cz) - 0.5F) * 2.0F * HEIGHT_NOISE * smoothstep(0.05F, 0.6F, t);
        float sink = DROOP * t * t;
        int top = cell.source.top + Math.round(wander) - Math.round(sink);
        top = Math.min(top, depth + 8);
        cell.top = top;

        // near the edge a column reaches the floor of the world, further out it thins to a slab that hangs in the air
        float thin = smoothstep(0.1F, 0.85F, t);
        int bottom = Math.round(thin * Math.max(top - MIN_THICKNESS, 0));
        cell.bottom = Math.max(0, Math.min(bottom, top - 1));
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0.0F, Math.min(1.0F, (x - a) / (b - a)));
        return t * t * (3.0F - 2.0F * t);
    }

    // ---- noise: the same seed gives the same fringe every time the mesh is rebuilt ----

    private float hash2(int x, int z) {
        long h = x * 374761393L + z * 668265263L + this.level.getSeed() * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / 16777216.0F;
    }

    private float vnoise(float x, float z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        float fx = x - xi;
        float fz = z - zi;
        fx = fx * fx * (3.0F - 2.0F * fx);
        fz = fz * fz * (3.0F - 2.0F * fz);
        float a = hash2(xi, zi);
        float b = hash2(xi + 1, zi);
        float c = hash2(xi, zi + 1);
        float d = hash2(xi + 1, zi + 1);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    private float fbm(float x, float z) {
        return 0.6F * vnoise(x * 0.12F, z * 0.12F) + 0.3F * vnoise(x * 0.31F + 17.0F, z * 0.31F - 9.0F) + 0.1F * vnoise(x * 0.8F - 5.0F, z * 0.8F + 23.0F);
    }

    // ---- writing the triangles ----

    private final class MeshWriter {
        final int width, height, depth, fringe, gw, gh;
        final int[] gridTop, gridBottom;

        // the quad being written: 4 corners of x, y, z and of local texture u, v (0..1 inside the slot)
        final float[] qp = new float[12];
        final float[] qt = new float[8];

        // set by gather(): the lowest top and the highest bottom among the neighbour columns a face looks at
        int nbMinTop, nbMaxBottom;

        MeshWriter(int width, int height, int depth, int fringe, int gw, int gh, int[] gridTop, int[] gridBottom) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.fringe = fringe;
            this.gw = gw;
            this.gh = gh;
            this.gridTop = gridTop;
            this.gridBottom = gridBottom;
        }

        void cell(Cell c) {
            Tile top = c.source.topTile;
            Tile below = c.source.belowTile;

            // lit like the edge column: the sky and block light of the air above it, the sea tint of that cell
            int ly = Math.max(0, Math.min(this.depth - 1, c.source.top));
            float sky = WorldBorderRenderer.this.level.getSkyLight(c.edgeX, ly, c.edgeZ);
            float block = WorldBorderRenderer.this.level.getBlockLight(c.edgeX, ly, c.edgeZ);
            // the sea tint is read from the open cell above the edge column's surface: the fringe's own top is usually lower (it droops), inside the
            // blocks of the edge column, where the level says nothing is ocean. the shader measures the depth per fragment from the real height
            float tint = UnderwaterTint.pack(WorldBorderRenderer.this.level, c.edgeX, Math.max(0, Math.min(this.depth - 1, Math.max(c.top, c.source.top))), c.edgeZ);

            float x0 = c.x0, x1 = c.x1, z0 = c.z0, z1 = c.z1;

            // top: always
            set(0, x0, c.top, z1);
            set(1, x1, c.top, z1);
            set(2, x1, c.top, z0);
            set(3, x0, c.top, z0);
            uv(0, 1, 1, 1, 1, 0, 0, 0);
            emit(top.getAtlasSlot(Tile.FACE_TOP), sky, block, Tile.FACE_SHADE[Tile.FACE_TOP], tint);

            // no bottoms and no walls under an overhang: nobody sees the fringe from under it, so the under parts are not built at all

            // sides: only the part of each that is not hidden by the neighbour column
            for (int side = 0; side < 4; side++) {
                gatherNeighbours(c, side);
                // above the neighbour, and no deeper than MAX_WALL under the top: the rest is only seen from far below
                if (this.nbMinTop < c.top) {
                    wall(c, side, Math.max(Math.max(this.nbMinTop, c.bottom), c.top - MAX_WALL), c.top, top, below, sky, block, tint);
                }
            }
        }

        /** the neighbours a side of the cell faces: 0 = -x, 1 = +x, 2 = -z, 3 = +z */
        void gatherNeighbours(Cell c, int side) {
            this.nbMinTop = Integer.MAX_VALUE;
            this.nbMaxBottom = 0;
            if (side < 2) {
                int nx = side == 0 ? c.x0 - 1 : c.x1;
                for (int z = c.z0; z < c.z1; z++) {
                    neighbour(nx, z);
                }
            } else {
                int nz = side == 2 ? c.z0 - 1 : c.z1;
                for (int x = c.x0; x < c.x1; x++) {
                    neighbour(x, nz);
                }
            }
        }

        void neighbour(int x, int z) {
            int top;
            int bottom;
            if (x >= 0 && z >= 0 && x < this.width && z < this.height) {
                // the real world: a solid column from the floor to its surface
                top = column(x, z).top;
                bottom = 0;
            } else {
                int gx = x + this.fringe;
                int gz = z + this.fringe;
                if (gx < 0 || gz < 0 || gx >= this.gw || gz >= this.gh) {
                    top = 0;   // past the outer rim: nothing hides the face
                    bottom = 0;
                } else {
                    top = this.gridTop[gx + gz * this.gw];
                    bottom = this.gridBottom[gx + gz * this.gw];
                }
            }
            this.nbMinTop = Math.min(this.nbMinTop, top);
            this.nbMaxBottom = Math.max(this.nbMaxBottom, bottom);
            if (top <= bottom) {
                // an empty neighbour hides nothing
                this.nbMinTop = Math.min(this.nbMinTop, 0);
            }
        }

        /**
         * a wall on one side of the cell from y = lo to hi, one quad per block so the texture repeats like the real blocks' do instead of
         * stretching over the whole height. the top block of the column gets the top block's side texture, the rest the block under it
         */
        void wall(Cell c, int side, int lo, int hi, Tile topTile, Tile belowTile, float sky, float block, float tint) {
            if (hi <= lo) {
                return;
            }
            int face = side == 0 ? Tile.FACE_WEST : side == 1 ? Tile.FACE_EAST : side == 2 ? Tile.FACE_NORTH : Tile.FACE_SOUTH;
            float shade = Tile.FACE_SHADE[face];
            int topSlot = topTile.getAtlasSlot(face);
            int belowSlot = belowTile.getAtlasSlot(face);

            for (int y = lo; y < hi; y++) {
                wallQuad(c, side, y, y + 1);
                emit(y == c.top - 1 ? topSlot : belowSlot, sky, block, shade, tint);
            }
        }

        void wallQuad(Cell c, int side, float lo, float hi) {
            float x0 = c.x0, x1 = c.x1, z0 = c.z0, z1 = c.z1;
            switch (side) {
                case 0:
                    set(0, x0, hi, z0);
                    set(1, x0, hi, z1);
                    set(2, x0, lo, z1);
                    set(3, x0, lo, z0);
                    break;
                case 1:
                    set(0, x1, hi, z1);
                    set(1, x1, hi, z0);
                    set(2, x1, lo, z0);
                    set(3, x1, lo, z1);
                    break;
                case 2:
                    set(0, x1, hi, z0);
                    set(1, x0, hi, z0);
                    set(2, x0, lo, z0);
                    set(3, x1, lo, z0);
                    break;
                default:
                    set(0, x0, hi, z1);
                    set(1, x1, hi, z1);
                    set(2, x1, lo, z1);
                    set(3, x0, lo, z1);
                    break;
            }
            // v is 0 at the top of the texture
            uv(0, 0, 1, 0, 1, 1, 0, 1);
        }

        void set(int corner, float x, float y, float z) {
            this.qp[corner * 3] = x;
            this.qp[corner * 3 + 1] = y;
            this.qp[corner * 3 + 2] = z;
        }

        void uv(float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3) {
            this.qt[0] = u0;
            this.qt[1] = v0;
            this.qt[2] = u1;
            this.qt[3] = v1;
            this.qt[4] = u2;
            this.qt[5] = v2;
            this.qt[6] = u3;
            this.qt[7] = v3;
        }

        /** the quad as two triangles. every corner carries its triangle's centroid in texcoord 1 */
        void emit(int slot, float sky, float block, float shade, float tint) {
            triangle(0, 1, 2, slot, sky, block, shade, tint);
            triangle(0, 2, 3, slot, sky, block, shade, tint);
        }

        void triangle(int a, int b, int c, int slot, float sky, float block, float shade, float tint) {
            float cx = (this.qp[a * 3] + this.qp[b * 3] + this.qp[c * 3]) / 3.0F;
            float cy = (this.qp[a * 3 + 1] + this.qp[b * 3 + 1] + this.qp[c * 3 + 1]) / 3.0F;
            float cz = (this.qp[a * 3 + 2] + this.qp[b * 3 + 2] + this.qp[c * 3 + 2]) / 3.0F;
            if (brokenOff(cx, cy, cz)) {
                return;   // not in the mesh at all
            }
            corner(a, slot, sky, block, shade, tint, cx, cy, cz);
            corner(b, slot, sky, block, shade, tint, cx, cy, cz);
            corner(c, slot, sky, block, shade, tint, cx, cy, cz);
        }

        /** the breakdown: does this triangle (named by its centroid) fall away? see BorderWarp */
        boolean brokenOff(float cx, float cy, float cz) {
            return BorderWarp.brokenOff(cx, cy, cz, this.width, this.height);
        }

        void corner(int i, int slot, float sky, float block, float shade, float tint, float cx, float cy, float cz) {
            glColor4f(sky, block, shade, tint);
            glTexCoord2f(Chunk.atlasU(slot, this.qt[i * 2]), Chunk.atlasV(slot, this.qt[i * 2 + 1]));
            GL13.glMultiTexCoord3f(GL13.GL_TEXTURE1, cx, cy, cz);
            glVertex3f(this.qp[i * 3], this.qp[i * 3 + 1], this.qp[i * 3 + 2]);
        }
    }
}