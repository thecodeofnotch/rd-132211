package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class LevelIO {

    private LevelIO() {
    }

    public static final class SavedPosition {

        public final boolean present;
        public final double x, y, z;
        public final float yRotation, xRotation;

        public final boolean hasSeed;
        public final long seed;
        public final boolean hasWorldType;
        public final LevelGenerator.WorldType worldType;

        public final boolean hasFlightState;
        public final boolean flying;
        public final boolean noClip;
        public final float flySpeedMultiplier;

        private SavedPosition(boolean present, double x, double y, double z, float yRotation, float xRotation,
                              boolean hasSeed, long seed, boolean hasWorldType, LevelGenerator.WorldType worldType,
                              boolean hasFlightState, boolean flying, boolean noClip, float flySpeedMultiplier) {
            this.present = present;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yRotation = yRotation;
            this.xRotation = xRotation;
            this.hasSeed = hasSeed;
            this.seed = seed;
            this.hasWorldType = hasWorldType;
            this.worldType = worldType;
            this.hasFlightState = hasFlightState;
            this.flying = flying;
            this.noClip = noClip;
            this.flySpeedMultiplier = flySpeedMultiplier;
        }

        public static SavedPosition none() {
            return new SavedPosition(false, 0, 0, 0, 0, 0, false, 0, false, null,
                    false, false, false, 1.0F);
        }

        public static SavedPosition of(double x, double y, double z, float yRotation, float xRotation) {
            return new SavedPosition(true, x, y, z, yRotation, xRotation, false, 0, false, null,
                    false, false, false, 1.0F);
        }

        public SavedPosition withMetadata(long seed, LevelGenerator.WorldType worldType) {
            return new SavedPosition(this.present, this.x, this.y, this.z, this.yRotation, this.xRotation,
                    true, seed, true, worldType, this.hasFlightState, this.flying, this.noClip, this.flySpeedMultiplier);
        }

        private SavedPosition withSeed(long seed) {
            return new SavedPosition(this.present, this.x, this.y, this.z, this.yRotation, this.xRotation,
                    true, seed, this.hasWorldType, this.worldType,
                    this.hasFlightState, this.flying, this.noClip, this.flySpeedMultiplier);
        }

        private SavedPosition withWorldType(LevelGenerator.WorldType worldType) {
            return new SavedPosition(this.present, this.x, this.y, this.z, this.yRotation, this.xRotation,
                    this.hasSeed, this.seed, true, worldType,
                    this.hasFlightState, this.flying, this.noClip, this.flySpeedMultiplier);
        }

        public SavedPosition withFlightState(boolean flying, boolean noClip, float flySpeedMultiplier) {
            return new SavedPosition(this.present, this.x, this.y, this.z, this.yRotation, this.xRotation,
                    this.hasSeed, this.seed, this.hasWorldType, this.worldType,
                    true, flying, noClip, flySpeedMultiplier);
        }
    }

    public static SavedPosition load(File file, byte[] blocks, byte[] data) {
        SavedPosition position = SavedPosition.none();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file), 1 << 16), 1 << 16))) {
            dis.readFully(blocks);

            try {
                if (dis.readBoolean()) {
                    double x = dis.readDouble();
                    double y = dis.readDouble();
                    double z = dis.readDouble();
                    float yRotation = dis.readFloat();
                    float xRotation = dis.readFloat();
                    position = SavedPosition.of(x, y, z, yRotation, xRotation);
                }
            } catch (EOFException ignored) {
            }

            try {
                if (dis.readBoolean()) {
                    dis.readFully(data);
                }
            } catch (EOFException ignored) {
            }

            try {
                if (dis.readBoolean()) {
                    position = position.withSeed(dis.readLong());
                }
                if (dis.readBoolean()) {
                    int ordinal = dis.readInt();
                    LevelGenerator.WorldType[] types = LevelGenerator.WorldType.values();
                    if (ordinal >= 0 && ordinal < types.length) {
                        position = position.withWorldType(types[ordinal]);
                    }
                }
            } catch (EOFException ignored) {
            }

            try {
                if (dis.readBoolean()) {
                    boolean flying = dis.readBoolean();
                    boolean noClip = dis.readBoolean();
                    float flySpeedMultiplier = dis.readFloat();
                    position = position.withFlightState(flying, noClip, flySpeedMultiplier);
                }
            } catch (EOFException ignored) {
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return position;
    }

    /** gzip with a chosen level: the autosave uses the fastest, on a 2 core CPU the default level steals time from the game */
    private static final class LeveledGZIPOutputStream extends GZIPOutputStream {
        LeveledGZIPOutputStream(java.io.OutputStream out, int size, int level) throws IOException {
            super(out, size);
            this.def.setLevel(level);
        }
    }

    public static void save(File file, byte[] blocks, byte[] data, SavedPosition position) {
        save(file, blocks, data, position, false);
    }

    public static void save(File file, byte[] blocks, byte[] data, SavedPosition position, boolean fast) {
        // Write to a temp file and move it into place, so a crash mid-write can't corrupt the world.
        File temp = new File(file.getPath() + ".tmp");
        int level = fast ? java.util.zip.Deflater.BEST_SPEED : java.util.zip.Deflater.DEFAULT_COMPRESSION;

        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new LeveledGZIPOutputStream(new FileOutputStream(temp), 1 << 16, level), 1 << 16))) {
            dos.write(blocks);

            dos.writeBoolean(position.present);
            if (position.present) {
                dos.writeDouble(position.x);
                dos.writeDouble(position.y);
                dos.writeDouble(position.z);
                dos.writeFloat(position.yRotation);
                dos.writeFloat(position.xRotation);
            }

            dos.writeBoolean(true);
            dos.write(data);

            dos.writeBoolean(position.hasSeed);
            if (position.hasSeed) {
                dos.writeLong(position.seed);
            }

            dos.writeBoolean(position.hasWorldType);
            if (position.hasWorldType) {
                dos.writeInt(position.worldType.ordinal());
            }

            dos.writeBoolean(position.hasFlightState);
            if (position.hasFlightState) {
                dos.writeBoolean(position.flying);
                dos.writeBoolean(position.noClip);
                dos.writeFloat(position.flySpeedMultiplier);
            }
        } catch (Exception | OutOfMemoryError e) {
            e.printStackTrace();
            temp.delete();   // don't leave a half written file behind
            return;
        }

        try {
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            e.printStackTrace();
            temp.delete();
        }
    }
}