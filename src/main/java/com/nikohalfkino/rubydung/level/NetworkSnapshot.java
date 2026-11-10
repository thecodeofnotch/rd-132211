package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * a whole world as one gzip blob, what a server sends a joining client: the block and data arrays as they are in
 * memory (both sides run the same build, so the tile ids match), the multi-tile origins, the sub-tiles by tile name and
 * the liquid layer in its save-file format
 *
 * making the blob is split in two so the tick thread never pays for it:
 *
 *   1. begin() + Capture.copySlice(): on the tick thread, a few MB per tick (the same idea as the autosave's sliced copy)
 *   2. encode(): on any other thread, the gzip, which is where nearly all the time goes
 *
 * the copy is NOT a single instant of the world: cells that change while it is being copied are not mirrored into it.
 * that is fine because the server starts sending the joining player every change from the moment begin() was called,
 * and the client applies those over the snapshot once it is loaded, so every cell ends up with its latest value
 */
public final class NetworkSnapshot {

    /** bytes of each array copied per call of Capture.copySlice (a couple of MB is a fraction of a millisecond) */
    public static final int DEFAULT_SLICE = 1 << 22;

    private NetworkSnapshot() {
    }

    /** a world being copied for the network. only ever touched by the thread that owns the level until isComplete() */
    public static final class Capture {
        private final Level level;
        private final byte[] blocks;
        private final byte[] data;
        private final List<int[]> origins;
        private final List<SubTiles.Entry> subTiles;
        private final LiquidLayer.Frozen liquids;

        private int copied = 0;
        private boolean liquidsDone = false;

        private Capture(Level level) {
            this.level = level;
            this.blocks = new byte[level.blocksArray().length];
            this.data = new byte[level.dataArray().length];
            // the small parts are taken at once, see the class comment for why that is consistent
            this.origins = level.collectMultiTileOrigins();
            this.subTiles = level.getSubTiles().snapshot();
            this.liquids = level.getLiquids().freeze();
        }

        /** copies the next slice. true once the whole world is copied */
        public boolean copySlice(int bytes) {
            if (this.copied < this.blocks.length) {
                int n = Math.min(bytes, this.blocks.length - this.copied);
                System.arraycopy(this.level.blocksArray(), this.copied, this.blocks, this.copied, n);
                System.arraycopy(this.level.dataArray(), this.copied, this.data, this.copied, n);
                this.copied += n;
            }
            if (!this.liquidsDone) {
                this.liquidsDone = this.level.getLiquids().copyFrozenSlice(this.liquids, bytes);
            }
            return isComplete();
        }

        public boolean isComplete() {
            return this.copied >= this.blocks.length && this.liquidsDone;
        }

        /** 0..1, for a log line or a progress message */
        public float progress() {
            return this.blocks.length == 0 ? 1.0F : this.copied / (float) this.blocks.length;
        }
    }

    /** tick thread: starts copying the level (nothing is copied yet beyond the small parts) */
    public static Capture begin(Level level) {
        return new Capture(level);
    }

    /** any thread, the capture must be complete: compresses it. this is the slow part */
    public static byte[] encode(Capture capture) throws IOException {
        if (!capture.isComplete()) {
            throw new IllegalStateException("snapshot is not fully copied yet");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(bytes, 1 << 16), 1 << 16))) {
            out.write(capture.blocks);
            out.write(capture.data);

            out.writeInt(capture.origins.size());
            for (int[] origin : capture.origins) {
                out.writeInt(origin[0]);
                out.writeInt(origin[1]);
                out.writeInt(origin[2]);
            }

            out.writeInt(capture.subTiles.size());
            for (SubTiles.Entry e : capture.subTiles) {
                out.writeInt(e.x);
                out.writeInt(e.y);
                out.writeInt(e.z);
                out.writeUTF(e.tileName);
                out.writeInt(e.data);
                out.writeFloat(e.ox);
                out.writeFloat(e.oy);
                out.writeFloat(e.oz);
            }

            // last on purpose: the liquid reader treats the end of the stream as "no ocean data" for old saves
            capture.level.getLiquids().writeFrozen(capture.liquids, out);
        }
        return bytes.toByteArray();
    }

    /**
     * the whole thing in one go on the calling thread. only for a level nobody else is changing (a dedicated server
     * that is not running yet, tests): a running server uses begin / copySlice / encode
     */
    public static byte[] write(Level level) throws IOException {
        Capture capture = begin(level);
        while (!capture.copySlice(Integer.MAX_VALUE)) {
            // a single slice is the whole level
        }
        return encode(capture);
    }

    /** client side, may run on any thread: builds the level and lights it. the level is read only (never saved) */
    public static Level read(byte[] compressed, String worldName, int width, int height, int depth,
                             LevelGenerator.WorldType type, long seed) throws IOException {
        int cells = width * height * depth;
        Level level = new Level(worldName, width, height, depth, type, seed, new byte[cells], new byte[cells]);

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(compressed), 1 << 16), 1 << 16))) {
            in.readFully(level.blocksArray());
            in.readFully(level.dataArray());

            int originCount = in.readInt();
            List<int[]> origins = new ArrayList<>(originCount);
            for (int i = 0; i < originCount; i++) {
                origins.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
            }
            level.registerMultiTiles(origins);

            int subTileCount = in.readInt();
            List<SubTiles.Entry> subTiles = new ArrayList<>(subTileCount);
            for (int i = 0; i < subTileCount; i++) {
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                String name = in.readUTF();
                int data = in.readInt();
                float ox = in.readFloat();
                float oy = in.readFloat();
                float oz = in.readFloat();
                subTiles.add(new SubTiles.Entry(x, y, z, name, data, ox, oy, oz));
            }
            level.getSubTiles().restore(subTiles);

            level.getLiquids().load(in);
        }

        level.lighting.calcLightDepths(0, 0, level.width, level.height);
        level.lighting.rebuildBlockLight();
        level.setReadOnly(true);
        return level;
    }
}
