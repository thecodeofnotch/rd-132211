package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.player.ChessSelection;
import com.nikohalfkino.rubydung.player.HitResult;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.level.tiles.common.BillboardTile;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.phys.AABB;
import com.nikohalfkino.rubydung.render.*;
import com.nikohalfkino.rubydung.util.GameSettings;

import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glMultiTexCoord2f;

public class LevelRenderer implements LevelListener {

    private static final int CHUNK_SIZE = Chunk.SIZE;

    private final Tessellator tessellator;
    private final Level level;
    private final Chunk[] chunks;
    private final OceanSurface ocean;

    private final int chunkAmountX;
    private final int chunkAmountY;
    private final int chunkAmountZ;

    public LevelRenderer(Level level) {
        level.addListener(this);

        this.tessellator = new Tessellator();
        this.level = level;
        this.ocean = new OceanSurface(level);

        // rounded up, so a world whose size isn't a multiple of CHUNK_SIZE still has a (smaller) chunk on its edge
        this.chunkAmountX = (level.width + CHUNK_SIZE - 1) / CHUNK_SIZE;
        this.chunkAmountY = (level.depth + CHUNK_SIZE - 1) / CHUNK_SIZE;
        this.chunkAmountZ = (level.height + CHUNK_SIZE - 1) / CHUNK_SIZE;

        this.chunks = new Chunk[this.chunkAmountX * this.chunkAmountY * this.chunkAmountZ];

        for (int x = 0; x < this.chunkAmountX; x++) {
            for (int y = 0; y < this.chunkAmountY; y++) {
                for (int z = 0; z < this.chunkAmountZ; z++) {
                    int minChunkX = x * CHUNK_SIZE;
                    int minChunkY = y * CHUNK_SIZE;
                    int minChunkZ = z * CHUNK_SIZE;

                    int maxChunkX = (x + 1) * CHUNK_SIZE;
                    int maxChunkY = (y + 1) * CHUNK_SIZE;
                    int maxChunkZ = (z + 1) * CHUNK_SIZE;

                    maxChunkX = Math.min(level.width, maxChunkX);
                    maxChunkY = Math.min(level.depth, maxChunkY);
                    maxChunkZ = Math.min(level.height, maxChunkZ);

                    Chunk chunk = new Chunk(level, minChunkX, minChunkY, minChunkZ, maxChunkX, maxChunkY, maxChunkZ);
                    this.chunks[(x + y * this.chunkAmountX) * this.chunkAmountZ + z] = chunk;
                }
            }
        }

        this.maxBillboardSize = findMaxBillboardSize();
    }

    private double viewX = Double.NaN;
    private double viewZ = Double.NaN;

    public void setViewPosition(double x, double y, double z) {
        this.viewX = x;
        this.viewZ = z;
    }

    private final List<Chunk> visibleChunks = new ArrayList<>();
    private boolean visibleChunksValid = false;

    private boolean withinRenderDistance(AABB box, double extra) {
        return withinRenderDistance(box.minX, box.minZ, box.maxX, box.maxZ, extra);
    }

    private boolean withinRenderDistance(double minX, double minZ, double maxX, double maxZ, double extra) {
        int distance = GameSettings.getRenderDistance();
        if (distance <= 0 || Double.isNaN(this.viewX)) {
            return true;
        }
        double reach = distance * (double) CHUNK_SIZE + extra;
        double dx = Math.max(Math.max(minX - this.viewX, this.viewX - maxX), 0.0);
        double dz = Math.max(Math.max(minZ - this.viewZ, this.viewZ - maxZ), 0.0);
        return dx * dx + dz * dz <= reach * reach;
    }

    // one instance, so the ocean's per-frame culling doesn't allocate a lambda
    private final OceanSurface.DistanceTest oceanRange = (minX, minZ, maxX, maxZ) -> withinRenderDistance(minX, minZ, maxX, maxZ, 0.0);

    // The chunks worth testing each frame: the ones with something to draw or still to build (most of a big world is air or buried),
    // and the ones with sprites. Rebuilt only when a chunk reports a change (Chunk.activeVersion), not every frame.
    private Chunk[] activeChunks = new Chunk[0];
    private int activeCount = 0;
    private Chunk[] billboardActive = new Chunk[0];
    private int billboardActiveCount = 0;
    private int activeSeenVersion = -1;

    private void refreshActiveChunks() {
        if (this.activeSeenVersion == Chunk.activeVersion) {
            return;
        }
        this.activeSeenVersion = Chunk.activeVersion;
        if (this.activeChunks.length != this.chunks.length) {
            this.activeChunks = new Chunk[this.chunks.length];
            this.billboardActive = new Chunk[this.chunks.length];
        }
        int all = 0;
        int sprites = 0;
        for (Chunk chunk : this.chunks) {
            if (chunk.hasBillboards()) {
                this.billboardActive[sprites++] = chunk;
            }
            if (chunk.isDirty() || chunk.hasGeometry()) {
                this.activeChunks[all++] = chunk;
            }
        }
        this.activeCount = all;
        this.billboardActiveCount = sprites;
    }

    private void collectVisibleChunks() {
        Frustum frustum = Frustum.getFrustum();
        this.visibleChunks.clear();
        refreshActiveChunks();
        for (int i = 0; i < this.activeCount; i++) {
            Chunk chunk = this.activeChunks[i];
            if (withinRenderDistance(chunk.boundingBox, 0.0) && frustum.cubeInFrustum(chunk.boundingBox)) {
                this.visibleChunks.add(chunk);
            }
        }
        this.visibleChunksValid = true;
    }

    public void render(int layer) {
        if (layer == 1) {
            // terrain is one display list per chunk now (layer 0 holds the lit and the shadowed faces, see Tile.LAYER_ALL),
            // so there is no second pass. Kept so existing callers don't have to change.
            return;
        }
        Textures.enableAlphaTest();

        Chunk.rebuiltThisFrame = 0;

        if (layer == 0 || !this.visibleChunksValid) {
            collectVisibleChunks();
        }
        if (layer == 0) {
            FrameProfiler.lap("chunk-collect");
        }

        // the terrain shader tints what is under the sea from these, so a new sea height needs no chunk rebuild
        LiquidLayer seas = this.level.getLiquids();
        boolean sea = seas.hasOcean() && seas.getOceanLiquid() != null;
        TerrainShader.setOcean(sea ? seas.getSurfaceHeight() : -1000.0F, sea ? seas.getOceanLiquid().getLiquidIndex() : 0);

        // every chunk's display list for this layer goes out in one glCallLists call, with the atlas bound once
        this.terrainIds = fillLists(this.terrainIds, layer, false);

        TerrainShader.bind(this.level.getSkyAmbient());
        try {
            if (this.terrainIds.limit() > 0) {
                glEnable(GL_TEXTURE_2D);
                Textures.bind(Chunk.getAtlasTexture());
                glCallLists(this.terrainIds);
                glDisable(GL_TEXTURE_2D);
            }
        } finally {
            TerrainShader.unbind();
        }
    }

    private IntBuffer terrainIds = BufferUtils.createIntBuffer(4096);
    private IntBuffer liquidIds = BufferUtils.createIntBuffer(1024);

    // the display list ids of a layer for every visible chunk that has something in it (rebuilding dirty chunks on the way)
    private IntBuffer fillLists(IntBuffer ids, int layer, boolean liquidOnly) {
        ids.clear();
        for (int i = 0; i < this.visibleChunks.size(); i++) {
            Chunk chunk = this.visibleChunks.get(i);
            if (liquidOnly && !chunk.hasLiquid()) {
                continue;
            }
            int list = chunk.getList(layer);
            if (list < 0) {
                continue;
            }
            if (!ids.hasRemaining()) {
                IntBuffer bigger = BufferUtils.createIntBuffer(ids.capacity() * 2);
                ids.flip();
                bigger.put(ids);
                ids = bigger;
            }
            ids.put(list);
        }
        ids.flip();
        return ids;
    }

    public void renderLiquids() {
        if (!this.visibleChunksValid) {
            collectVisibleChunks();
        }

        boolean blend = glIsEnabled(GL_BLEND);
        boolean alphaTest = glIsEnabled(GL_ALPHA_TEST);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_CULL_FACE);

        this.liquidIds = fillLists(this.liquidIds, Chunk.LAYER_LIQUID, true);

        float seaSurface = this.level.getLiquids().getSurfaceHeight();
        this.ocean.prepare(Frustum.getFrustum(), this.oceanRange);
        FrameProfiler.lap("ocean-prepare");   // dirty tile rebuilds + culling

        try {
            // pass 1: depth only, with the cheap program. it discards exactly what the colour pass discards
            LiquidShader.bindDepth(seaSurface);
            glDepthMask(true);
            glColorMask(false, false, false, false);
            drawLiquidChunks("ocean-depth", "chunks-depth");

            // pass 2: the full shader, only where the nearest water layer is
            LiquidShader.bind(this.level.getSkyAmbient(), seaSurface);
            glColorMask(true, true, true, true);
            glDepthMask(false);
            glDepthFunc(GL_LEQUAL);
            drawLiquidChunks("ocean-color", "chunks-color");
        } finally {
            glColorMask(true, true, true, true);
            glDepthFunc(depthFunc);
            glDepthMask(depthMask);
            LiquidShader.unbind();
            if (cull) {
                glEnable(GL_CULL_FACE);
            }
            if (alphaTest) {
                glEnable(GL_ALPHA_TEST);
            }
            if (!blend) {
                glDisable(GL_BLEND);
            }
        }
    }

    private void drawLiquidChunks(String oceanStage, String chunksStage) {
        this.ocean.draw();
        FrameProfiler.lap(oceanStage);

        if (this.liquidIds.limit() > 0) {
            // the ocean's display lists leave their last "sea height" as the current texcoord 1, the chunk liquids must not fade
            glMultiTexCoord2f(GL_TEXTURE1, OceanSurface.NO_FADE, 0.0F);
            glCallLists(this.liquidIds);
        }
        FrameProfiler.lap(chunksStage);
    }

    private int buildCursor = 0;

    public boolean buildNextChunks(int maxChunks) {
        if (this.parallel) {
            return uploadBuiltChunks(maxChunks, Long.MAX_VALUE);
        }
        int built = 0;
        while (built < maxChunks && this.buildCursor < this.chunks.length) {
            Chunk chunk = this.chunks[this.buildCursor++];
            if (chunk.isDirty()) {
                chunk.rebuildNow();
                built++;
            }
        }
        return this.buildCursor < this.chunks.length;
    }

    private static final int MAX_FINISHED_WAITING = 256;

    private static final class Result {
        final Chunk chunk;
        final Chunk.Built built;

        Result(Chunk chunk, Chunk.Built built) {
            this.chunk = chunk;
            this.built = built;
        }
    }

    private boolean parallel = false;
    private volatile boolean cancelled = false;
    // bumped by every startParallelBuild: workers of an earlier build notice it and stop instead of carrying on into the new one
    private volatile int buildGeneration = 0;
    private int[] pending;
    private int uploaded = 0;
    private final AtomicInteger claimed = new AtomicInteger();
    private final Semaphore waitingSlots = new Semaphore(MAX_FINISHED_WAITING);
    private final ConcurrentLinkedQueue<Result> finished = new ConcurrentLinkedQueue<>();

    public void startParallelBuild() {
        int count = 0;
        int[] list = new int[this.chunks.length];
        for (int i = 0; i < this.chunks.length; i++) {
            if (this.chunks[i].isDirty()) {
                list[count++] = i;
            }
        }
        this.pending = java.util.Arrays.copyOf(list, count);
        this.uploaded = 0;
        this.claimed.set(0);
        this.finished.clear();
        this.waitingSlots.drainPermits();
        this.waitingSlots.release(MAX_FINISHED_WAITING);
        int generation = ++this.buildGeneration;
        this.cancelled = false;
        this.parallel = true;

        // leave a core for the render thread (and one more for the OS / GC), otherwise frames stall while chunks build
        // -Dstrata.chunkThreads=N sets it by hand (two games on one PC should not each take every core)
        int threads = Integer.getInteger("strata.chunkThreads", Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        threads = Math.max(1, threads);
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> buildWorker(generation), "chunk-build-" + t);
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private void buildWorker(int generation) {
        Tessellator tessellator = new Tessellator();
        try {
            while (!this.cancelled && generation == this.buildGeneration) {
                this.waitingSlots.acquire();
                if (this.cancelled || generation != this.buildGeneration) {
                    this.waitingSlots.release();
                    return;
                }
                int slot = this.claimed.getAndIncrement();
                if (slot >= this.pending.length) {
                    this.waitingSlots.release();
                    return;
                }
                Chunk chunk = this.chunks[this.pending[slot]];
                Chunk.Built built = null;
                try {
                    built = chunk.computeAll(tessellator);
                } catch (Throwable t) {
                    System.err.println("[loading] worker failed on a chunk, building it on the main thread: " + t);
                    tessellator = new Tessellator();
                }
                this.finished.add(new Result(chunk, built));
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean uploadBuiltChunks(int maxChunks, long deadlineNanos) {
        int done = 0;
        while (done < maxChunks && System.nanoTime() < deadlineNanos) {
            Result result = this.finished.poll();
            if (result == null) {
                break;
            }
            if (result.built != null) {
                result.chunk.upload(result.built);
            } else {
                result.chunk.rebuildNow();
            }
            this.waitingSlots.release();
            this.uploaded++;
            done++;
        }
        boolean more = this.uploaded < this.pending.length;
        if (!more) {
            this.parallel = false;
            this.buildCursor = this.chunks.length;
        }
        return more;
    }

    public void cancelParallelBuild() {
        this.cancelled = true;
        this.waitingSlots.release(MAX_FINISHED_WAITING);
    }

    public float getBuildProgress() {
        if (this.parallel) {
            return this.pending.length == 0 ? 1.0F : (float) this.uploaded / (float) this.pending.length;
        }
        if (this.chunks.length == 0) {
            return 1.0F;
        }
        return (float) this.buildCursor / (float) this.chunks.length;
    }

    public void setDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        setDirty(minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    private void setDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean urgent) {
        minX /= CHUNK_SIZE;
        minY /= CHUNK_SIZE;
        minZ /= CHUNK_SIZE;
        maxX /= CHUNK_SIZE;
        maxY /= CHUNK_SIZE;
        maxZ /= CHUNK_SIZE;

        minX = Math.max(minX, 0);
        minY = Math.max(minY, 0);
        minZ = Math.max(minZ, 0);

        maxX = Math.min(maxX, this.chunkAmountX - 1);
        maxY = Math.min(maxY, this.chunkAmountY - 1);
        maxZ = Math.min(maxZ, this.chunkAmountZ - 1);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Chunk chunk = this.chunks[(x + y * this.chunkAmountX) * this.chunkAmountZ + z];

                    if (urgent) {
                        chunk.setDirtyNow();
                    } else {
                        chunk.setDirty();
                    }
                }
            }
        }
    }

    private static final float MAX_BILLBOARD_OVERHANG = 1.0F;
    private float maxBillboardSize;
    // sprite sizes come from the texture atlas, which is built after this renderer, so they are read on the first frame
    private boolean billboardSizeResolved = false;

    private static float findMaxBillboardSize() {
        float max = 1.0F;
        for (Tile tile : Tile.getTiles()) {
            if (tile instanceof BillboardTile) {
                BillboardTile billboard = (BillboardTile) tile;
                max = Math.max(max, Math.max(billboard.getSpriteSize(), billboard.getHeight()));
            }
        }
        return max;
    }

    public void renderBillboards(float cameraYaw, float cameraPitch) {
        renderBillboards(cameraYaw, cameraPitch, Double.NaN, Double.NaN, Double.NaN);
    }

    // How far a sprite stays visible, per block of sprite size: the reach is size * this, so big sprites (trees) stay
    // visible far out and small ones (plants, a few pixels from afar) are dropped earlier. The cut-off is made per
    // sprite in StaticBillboardShader, so it costs nothing on the CPU. A finite render distance applies on top.
    private static final float BILLBOARD_REACH_PER_BLOCK = 400.0F;

    // hard upper bound whatever the size
    private static final float BILLBOARD_MAX_REACH = 4096.0F;

    // Sprites whose texture has only fully opaque or fully clear pixels (see BillboardTile.isTranslucent) are cut-outs: drawn first,
    // without blending, near to far, so the depth test throws away the pixels hidden behind nearer sprites early. Only the sprites with
    // partly transparent pixels are blended, afterwards and far to near. Those cut-outs keep pixels from half alpha up.
    private static final float CUTOUT_ALPHA = 0.5F;
    private static final float BLENDED_ALPHA = 0.01F;

    // float -> int whose signed order matches the float order
    private static int sortableBits(float value) {
        int bits = Float.floatToIntBits(value);
        return bits ^ ((bits >> 31) & 0x7FFFFFFF);
    }

    private boolean withinReach(AABB box, double reach) {
        if (Double.isNaN(this.viewX)) {
            return true;
        }
        double dx = Math.max(Math.max(box.minX - this.viewX, this.viewX - box.maxX), 0.0);
        double dz = Math.max(Math.max(box.minZ - this.viewZ, this.viewZ - box.maxZ), 0.0);
        return dx * dx + dz * dz <= reach * reach;
    }

    private long[] billboardChunkKeys;
    private Chunk[] billboardChunkRefs;
    private IntBuffer billboardIds = BufferUtils.createIntBuffer(1024);

    /**
     * The sprites are static geometry built with their chunk (see Chunk.LAYER_BILLBOARD and BillboardMesh), drawn as
     * display lists with StaticBillboardShader, which turns them towards the camera on the GPU. Per frame the CPU only
     * picks the chunks and sorts them far to near.
     */
    public void renderBillboards(float cameraYaw, float cameraPitch, double eyeX, double eyeY, double eyeZ) {
        this.billboardAuraChunkCount = 0;
        if (Double.isNaN(eyeX)) {
            // the shader needs the eye for the growth with distance
            CameraRay.setAngles(cameraYaw, cameraPitch);
            return;
        }
        CameraRay.set(eyeX, eyeY, eyeZ, cameraYaw, cameraPitch);

        if (!this.billboardSizeResolved) {
            // sprite sizes come from the atlas, which exists by the first frame
            this.maxBillboardSize = findMaxBillboardSize();
            this.billboardSizeResolved = true;
        }

        Frustum frustum = Frustum.getFrustum();

        float maxBillboardHeight = this.maxBillboardSize;
        float maxScaleFactor = 2.0F;
        float maxOverhang = Math.max(MAX_BILLBOARD_OVERHANG, (maxBillboardHeight * maxScaleFactor) / 2.0F);

        int renderDistance = GameSettings.getRenderDistance();
        double reach = Math.min(BILLBOARD_MAX_REACH, maxBillboardHeight * BILLBOARD_REACH_PER_BLOCK);
        if (renderDistance > 0) {
            reach = Math.min(reach, renderDistance * (double) CHUNK_SIZE + maxOverhang);
        }

        if (this.billboardChunkKeys == null) {
            this.billboardChunkKeys = new long[this.chunks.length];
            this.billboardChunkRefs = new Chunk[this.chunks.length];
        }

        int count = 0;
        refreshActiveChunks();   // a chunk rebuilt by the terrain pass may have gained or lost its sprites since
        for (int i = 0; i < this.billboardActiveCount; i++) {
            Chunk chunk = this.billboardActive[i];
            if (!withinReach(chunk.boundingBox, reach)) {
                continue;
            }
            // a sprite can stick out of its chunk (a tree's crown), so the chunk is tested with a grown box
            AABB cullBox = chunk.boundingBox.grow(maxOverhang, maxBillboardHeight * maxScaleFactor, maxOverhang);
            if (!frustum.cubeInFrustum(cullBox)) {
                continue;
            }

            double cx = (chunk.boundingBox.minX + chunk.boundingBox.maxX) * 0.5 - eyeX;
            double cy = (chunk.boundingBox.minY + chunk.boundingBox.maxY) * 0.5 - eyeY;
            double cz = (chunk.boundingBox.minZ + chunk.boundingBox.maxZ) * 0.5 - eyeZ;
            float distanceSq = (float) (cx * cx + cy * cy + cz * cz);

            // far to near (the negated distance sorts ascending), the index rides in the low bits. the cut-outs walk the same order backwards
            this.billboardChunkKeys[count] = ((long) sortableBits(-distanceSq) << 32) | count;
            this.billboardChunkRefs[count] = chunk;
            count++;
        }
        FrameProfiler.lap("bb-collect");

        this.billboardAuraChunkCount = count;
        if (count == 0) {
            return;
        }
        Arrays.sort(this.billboardChunkKeys, 0, count);

        // chunks that were sorted are looked up through their index, so the refs array stays in collection order.
        // cut-outs are listed near to far, blended sprites far to near
        this.billboardCutoutIds = fillBillboardIds(this.billboardCutoutIds, Chunk.LAYER_BILLBOARD_CUTOUT, count, true);
        this.billboardIds = fillBillboardIds(this.billboardIds, Chunk.LAYER_BILLBOARD, count, false);
        FrameProfiler.lap("bb-sort");

        if (this.billboardCutoutIds.limit() == 0 && this.billboardIds.limit() == 0) {
            return;
        }

        // the quad is built by the shader, one per sprite, and is visible from either side: face culling is off
        boolean cull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_CULL_FACE);

        float rotRad = (float) Math.toRadians(-cameraYaw);
        float rightX = (float) Math.cos(rotRad);
        float rightZ = (float) -Math.sin(rotRad);
        StaticBillboardShader.bind(eyeX, eyeY, eyeZ, rightX, rightZ, BILLBOARD_REACH_PER_BLOCK, BILLBOARD_MAX_REACH,
                this.level.getSkyAmbient(), CUTOUT_ALPHA);
        try {
            glEnable(GL_TEXTURE_2D);
            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_GREATER, 0.01F);

            // drawn before the liquids and writing depth: the water surface is blended over whatever is behind it, so a sprite under
            // the sea is seen through the water, and one above the sea is not covered by it (the water's depth would hide it otherwise)
            glDepthMask(true);

            Textures.bind(Chunk.getAtlasTexture());

            if (this.billboardCutoutIds.limit() > 0) {
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glCallLists(this.billboardCutoutIds);
                FrameProfiler.lap("bb-cutout");
            }

            if (this.billboardIds.limit() > 0) {
                // over the cut-outs, which are already in the depth buffer
                StaticBillboardShader.setCutoff(BLENDED_ALPHA);
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glCallLists(this.billboardIds);
            }

            glDepthMask(true);
            glDisable(GL_BLEND);

            glEnable(GL_ALPHA_TEST);
            glAlphaFunc(GL_GREATER, 0.1F);
        } finally {
            StaticBillboardShader.unbind();
            glDisable(GL_TEXTURE_2D);
            if (cull) {
                glEnable(GL_CULL_FACE);
            }
        }
        FrameProfiler.lap("bb-draw");
    }

    private void drawBillboardAuraLists() {
        if (this.billboardAuraCutoutIds.limit() > 0) {
            glCallLists(this.billboardAuraCutoutIds);
        }
        if (this.billboardAuraIds.limit() > 0) {
            glCallLists(this.billboardAuraIds);
        }
    }

    private IntBuffer billboardAuraCutoutIds = BufferUtils.createIntBuffer(1024);
    private IntBuffer billboardAuraIds = BufferUtils.createIntBuffer(1024);
    private int billboardAuraChunkCount = 0;

    /**
     * The glow of the sprites that have one (BillboardAura). Call it after renderLiquids: it is blended over everything
     * already drawn, far to near, and it reads the depth buffer without writing it, so the water, the sprites and the glows
     * behind it still show through, which they did not when the glow was drawn with its sprite and wrote depth.
     * Uses the chunk order renderBillboards just sorted.
     */
    public void renderBillboardAuras(float cameraYaw, double eyeX, double eyeY, double eyeZ) {
        int count = this.billboardAuraChunkCount;
        if (count == 0 || Double.isNaN(eyeX)) {
            return;
        }
        this.billboardAuraCutoutIds = fillBillboardIds(this.billboardAuraCutoutIds, Chunk.LAYER_BILLBOARD_CUTOUT, count, false);
        this.billboardAuraIds = fillBillboardIds(this.billboardAuraIds, Chunk.LAYER_BILLBOARD, count, false);
        if (this.billboardAuraCutoutIds.limit() == 0 && this.billboardAuraIds.limit() == 0) {
            return;
        }

        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean alphaTest = glIsEnabled(GL_ALPHA_TEST);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        glDisable(GL_CULL_FACE);

        float rotRad = (float) Math.toRadians(-cameraYaw);
        float rightX = (float) Math.cos(rotRad);
        float rightZ = (float) -Math.sin(rotRad);
        StaticBillboardShader.bind(eyeX, eyeY, eyeZ, rightX, rightZ, BILLBOARD_REACH_PER_BLOCK, BILLBOARD_MAX_REACH,
                this.level.getSkyAmbient(), CUTOUT_ALPHA);
        try {
            glEnable(GL_TEXTURE_2D);
            glDisable(GL_ALPHA_TEST);
            glEnable(GL_BLEND);
            glDepthMask(false);
            Textures.bind(Chunk.getAtlasTexture());

            // the colour is premultiplied by the shader: src + dst * (1 - alpha) is NORMAL (alpha = the opacity) and ADD (alpha 0)
            StaticBillboardShader.setAuraPass(1);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            drawBillboardAuraLists();

            // SCREEN: src + dst * (1 - src), per channel
            StaticBillboardShader.setAuraPass(2);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR);
            drawBillboardAuraLists();
        } finally {
            glDepthMask(depthMask);
            if (!blend) {
                glDisable(GL_BLEND);
            }
            if (alphaTest) {
                glEnable(GL_ALPHA_TEST);
            }
            StaticBillboardShader.unbind();
            glDisable(GL_TEXTURE_2D);
            if (cull) {
                glEnable(GL_CULL_FACE);
            }
        }
        FrameProfiler.lap("bb-aura");
    }

    private IntBuffer billboardCutoutIds = BufferUtils.createIntBuffer(1024);

    // the display lists of one sprite layer for the culled chunks, in the sorted far-to-near order or (nearToFar) the reverse
    private IntBuffer fillBillboardIds(IntBuffer ids, int layer, int count, boolean nearToFar) {
        ids.clear();
        for (int n = 0; n < count; n++) {
            int i = nearToFar ? count - 1 - n : n;
            Chunk chunk = this.billboardChunkRefs[(int) this.billboardChunkKeys[i]];
            int list = chunk.getList(layer);
            if (list < 0) {
                continue;
            }
            if (!ids.hasRemaining()) {
                IntBuffer bigger = BufferUtils.createIntBuffer(ids.capacity() * 2);
                ids.flip();
                bigger.put(ids);
                ids = bigger;
            }
            ids.put(list);
        }
        ids.flip();
        return ids;
    }

    public void renderHit(HitResult hitResult) {
        renderHit(hitResult, CameraRay.yaw());
    }

    public void renderHit(HitResult hitResult, float cameraYaw) {
        if (hitResult.liquid) {
            renderLiquidHit(hitResult);
            return;
        }
        TilePicker.refine(this.level, hitResult);

        Tile tile = Tile.byId(this.level.getTile(hitResult.x, hitResult.y, hitResult.z));
        if (hitResult.overlayId >= 0) {
            SubTile shared = this.level.getSubTiles().find(hitResult.x, hitResult.y, hitResult.z, hitResult.overlayId);
            if (shared != null) {
                tile = Tile.byId(shared.tileId);
            }
        }
        if (tile == null || tile == Tile.AIR) {
            return;
        }

        float pulse = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2f + 0.4f;

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_CURRENT_BIT);
        glColor4f(1.0f, 1.0f, 1.0f, pulse);

        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0F, -4.0F);
        glDepthMask(false);

        if (tile instanceof BillboardTile && !Float.isNaN(cameraYaw)) {
            renderBillboardBlink((BillboardTile) tile, hitResult, cameraYaw, pulse);
        } else {
            renderFaceOverlay(tile, hitResult);
        }

        glDepthMask(true);
        glDisable(GL_POLYGON_OFFSET_FILL);

        glDisable(GL_BLEND);
    }

    /**
     * lights up the squares the picked chess piece may go to and the square it stands on, as pulsing tiles lying on the floor of the piece's level,
     * blue for a white piece and red for a black one (the square it stands on is paler, the squares where a piece would be taken are stronger).
     * fixed function, no texture, call with texturing off like renderHit
     */
    public void renderChessSelection(ChessSelection selection) {
        if (selection == null || !selection.isActive()) {
            return;
        }

        float pulse = (float) Math.sin(System.currentTimeMillis() / 150.0) * 0.12f + 0.5f;
        int y = selection.getY();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0F, -4.0F);
        glDepthMask(false);

        float[] side = selection.isWhite() ? new float[]{0.2f, 0.45f, 1.0f} : new float[]{1.0f, 0.2f, 0.15f};

        glColor4f(side[0] * 0.5f + 0.5f, side[1] * 0.5f + 0.5f, side[2] * 0.5f + 0.5f, pulse);
        this.tessellator.init();
        addChessSquare(selection.getX(), y, selection.getZ());
        this.tessellator.flush();

        glColor4f(side[0], side[1], side[2], pulse);
        this.tessellator.init();
        for (ChessPieceTile.Move move : selection.getMoves()) {
            if (!move.capture) {
                addChessSquare(move.x, y, move.z);
            }
        }
        this.tessellator.flush();

        glColor4f(side[0], side[1], side[2], Math.min(1.0f, pulse + 0.3f));
        this.tessellator.init();
        for (ChessPieceTile.Move move : selection.getMoves()) {
            if (move.capture) {
                addChessSquare(move.x, y, move.z);
            }
        }
        this.tessellator.flush();

        glDepthMask(true);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_BLEND);
    }

    // one lit square on the floor of the cell, a little inside its edges so neighbouring squares stay apart
    private void addChessSquare(int x, int y, int z) {
        float inset = 0.04F;
        float top = y - this.level.getBillboardDrop(x, y, z) + 0.02F;
        float minX = x + inset;
        float maxX = x + 1.0F - inset;
        float minZ = z + inset;
        float maxZ = z + 1.0F - inset;

        this.tessellator.vertex(maxX, top, maxZ);
        this.tessellator.vertex(maxX, top, minZ);
        this.tessellator.vertex(minX, top, minZ);
        this.tessellator.vertex(minX, top, maxZ);
    }

    private void renderLiquidHit(HitResult hit) {
        LiquidTile liquid = this.level.getLiquids().get(hit.x, hit.y, hit.z);
        if (liquid == null) {
            return;
        }
        float top = liquid.getSurfaceY(this.level, hit.x, hit.y, hit.z);
        float pulse = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2f + 0.4f;

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_CURRENT_BIT);
        glColor4f(1.0f, 1.0f, 1.0f, pulse);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0F, -4.0F);
        glDepthMask(false);

        int[] normal = HIT_FACE_NORMALS[hit.face];
        glPushMatrix();
        glTranslatef(normal[0] * HIT_FACE_NUDGE, normal[1] * HIT_FACE_NUDGE, normal[2] * HIT_FACE_NUDGE);
        this.tessellator.init();
        renderBoxFace(hit.x, hit.y, hit.z, hit.x + 1.0F, top, hit.z + 1.0F, hit.face);
        this.tessellator.flush();
        glPopMatrix();

        glDepthMask(true);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_BLEND);
    }

    private void renderFaceOverlay(Tile tile, HitResult hitResult) {
        int[] normal = HIT_FACE_NORMALS[hitResult.face];
        glPushMatrix();
        glTranslatef(normal[0] * HIT_FACE_NUDGE, normal[1] * HIT_FACE_NUDGE, normal[2] * HIT_FACE_NUDGE);

        this.tessellator.init();

        MultiTileInstance object = this.level.getMultiTile(hitResult.x, hitResult.y, hitResult.z);
        if (object != null && !tile.hasCustomOutline()) {
            renderBoxFace(object.x, object.y, object.z,
                    object.x + object.sizeX, object.y + object.sizeY, object.z + object.sizeZ, hitResult.face);
        } else {
            AABB box = tile.hasCustomOutline() ? null : hitResult.hitBox;
            if (box == null) {
                box = tile.getOutlineAABB(this.level, hitResult.x, hitResult.y, hitResult.z);
            }
            if (box != null) {
                renderBoxFace((float) box.minX, (float) box.minY, (float) box.minZ,
                        (float) box.maxX, (float) box.maxY, (float) box.maxZ, hitResult.face);
            } else {
                tile.renderFace(this.tessellator, hitResult.x, hitResult.y, hitResult.z, hitResult.face);
            }
        }

        this.tessellator.flush();
        glPopMatrix();
    }

    private void renderBillboardBlink(BillboardTile billboard, HitResult hitResult, float cameraYaw, float pulse) {
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.01F);
        Textures.bind(Chunk.getAtlasTexture());

        float rotRad = (float) Math.toRadians(-cameraYaw);
        float cosYaw = (float) Math.cos(rotRad);
        float sinYaw = (float) Math.sin(rotRad);

        float worldX = hitResult.x + 0.5F + billboard.getPivotOffsetX();
        float worldY = hitResult.y - this.level.getBillboardDrop(hitResult.x, hitResult.y, hitResult.z);
        float worldZ = hitResult.z + 0.5F + billboard.getPivotOffsetZ();

        this.tessellator.init();
        billboard.renderBillboardBatched(this.tessellator, this.level, hitResult.x, hitResult.y, hitResult.z,
                Chunk.getTotalAtlasSlots(), worldX, worldY, worldZ, cosYaw, sinYaw, 1.0F, 1.0F, pulse);
        this.tessellator.flush();

        glAlphaFunc(GL_GREATER, 0.1F);
        glDisable(GL_TEXTURE_2D);
    }

    private static final int[][] HIT_FACE_NORMALS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    private static final float HIT_FACE_NUDGE = 0.002F;

    private void renderBoxFace(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int face) {
        if (face == Tile.FACE_BOTTOM) {
            this.tessellator.vertex(minX, minY, maxZ);
            this.tessellator.vertex(minX, minY, minZ);
            this.tessellator.vertex(maxX, minY, minZ);
            this.tessellator.vertex(maxX, minY, maxZ);
        } else if (face == Tile.FACE_TOP) {
            this.tessellator.vertex(maxX, maxY, maxZ);
            this.tessellator.vertex(maxX, maxY, minZ);
            this.tessellator.vertex(minX, maxY, minZ);
            this.tessellator.vertex(minX, maxY, maxZ);
        } else if (face == Tile.FACE_NORTH) {
            this.tessellator.vertex(minX, maxY, minZ);
            this.tessellator.vertex(maxX, maxY, minZ);
            this.tessellator.vertex(maxX, minY, minZ);
            this.tessellator.vertex(minX, minY, minZ);
        } else if (face == Tile.FACE_SOUTH) {
            this.tessellator.vertex(minX, maxY, maxZ);
            this.tessellator.vertex(minX, minY, maxZ);
            this.tessellator.vertex(maxX, minY, maxZ);
            this.tessellator.vertex(maxX, maxY, maxZ);
        } else if (face == Tile.FACE_WEST) {
            this.tessellator.vertex(minX, maxY, maxZ);
            this.tessellator.vertex(minX, maxY, minZ);
            this.tessellator.vertex(minX, minY, minZ);
            this.tessellator.vertex(minX, minY, maxZ);
        } else if (face == Tile.FACE_EAST) {
            this.tessellator.vertex(maxX, minY, maxZ);
            this.tessellator.vertex(maxX, minY, minZ);
            this.tessellator.vertex(maxX, maxY, minZ);
            this.tessellator.vertex(maxX, maxY, maxZ);
        }
    }

    public void dispose() {
        this.ocean.dispose();
        if (this.chunks != null) {
            for (Chunk chunk : this.chunks) {
                if (chunk != null) {
                    chunk.dispose();
                }
            }
        }
    }

    @Override
    public void lightColumnChanged(int x, int z, int minY, int maxY) {
        this.ocean.lightChanged(x, z, minY, maxY);
        setDirty(x - 1, minY - 1, z - 1, x + 1, maxY + 1, z + 1);
    }

    @Override
    public void tileChanged(int x, int y, int z) {
        this.ocean.cellChanged(x, y, z);
        setDirty(x - 1, y - 2, z - 1, x + 1, y + 2, z + 1, true);
    }

    @Override
    public void allChanged() {
        setDirty(0, 0, 0, this.level.width, this.level.depth, this.level.height);
    }
}