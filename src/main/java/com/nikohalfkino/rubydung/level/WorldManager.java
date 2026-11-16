package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WorldManager {

    private static final String SAVES_DIR = "saves";
    private static final String LEVEL_FILE_NAME = "level.dat";
    private static final String SIZE_FILE_NAME = "size.dat";
    private static final String TIME_FILE_NAME = "time.dat";
    private static final String PALETTE_FILE_NAME = "palette.dat";
    private static final String MULTI_TILES_FILE_NAME = "multitiles.dat";
    private static final String TYPE_FILE_NAME = "type.dat";
    private static final String SETTINGS_FILE_NAME = "settings.dat";
    private static final String SUB_TILES_FILE_NAME = "subtiles.dat";
    private static final int MAX_NAME_LENGTH = 32;

    private WorldManager() {
    }

    public static final class WorldInfo {

        public final String name;
        public final long lastPlayed;
        /** null for saves from before settings.dat existed */
        public final LevelGenerator.WorldType worldType;
        /** null for saves from before settings.dat existed */
        public final LevelGenerator.WorldSize worldSize;
        /** null for saves from before settings.dat existed */
        public final DayNightCycle.TimeMode timeMode;
        /** total bytes of all files in the world's save directory */
        public final long sizeBytes;

        public WorldInfo(String name, long lastPlayed,
                         LevelGenerator.WorldType worldType,
                         LevelGenerator.WorldSize worldSize,
                         DayNightCycle.TimeMode timeMode,
                         long sizeBytes) {
            this.name = name;
            this.lastPlayed = lastPlayed;
            this.worldType = worldType;
            this.worldSize = worldSize;
            this.timeMode = timeMode;
            this.sizeBytes = sizeBytes;
        }
    }

    public static List<WorldInfo> listWorlds() {
        List<WorldInfo> worlds = new ArrayList<>();

        File[] entries = new File(SAVES_DIR).listFiles();
        if (entries == null) {
            return worlds;
        }

        for (File entry : entries) {
            File levelFile = new File(entry, LEVEL_FILE_NAME);
            if (entry.isDirectory() && levelFile.isFile()) {
                String name = entry.getName();
                LevelGenerator.WorldType worldType = loadWorldType(name);
                int[] settings = loadWorldSettings(name);
                LevelGenerator.WorldSize worldSize = settings != null
                        ? worldSizeFromOrdinal(settings[0]) : null;
                DayNightCycle.TimeMode timeMode = settings != null
                        ? timeModeFromOrdinal(settings[1]) : null;
                long sizeBytes = worldDiskSize(entry);
                worlds.add(new WorldInfo(name, levelFile.lastModified(),
                        worldType, worldSize, timeMode, sizeBytes));
            }
        }

        worlds.sort(Comparator.comparingLong((WorldInfo world) -> world.lastPlayed).reversed());
        return worlds;
    }

    public static boolean exists(String worldName) {
        return new File(new File(SAVES_DIR, worldName), LEVEL_FILE_NAME).isFile();
    }

    /** unlike levelFile this never creates the folder, so it's safe for reading (e.g. peeking at another world's doors.dat), writers call mkdirs on the parent */
    public static File worldFile(String worldName, String fileName) {
        return new File(new File(SAVES_DIR, worldName), fileName);
    }

    public static File levelFile(String worldName) {
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();
        return new File(dir, LEVEL_FILE_NAME);
    }

    public static void saveWorldSize(String worldName, int width, int height, int depth) {
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(new File(dir, SIZE_FILE_NAME)))) {
            dos.writeInt(width);
            dos.writeInt(height);
            dos.writeInt(depth);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static int[] loadWorldSize(String worldName) {
        File file = new File(new File(SAVES_DIR, worldName), SIZE_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            return new int[]{dis.readInt(), dis.readInt(), dis.readInt()};
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /** next to level.dat, written to a temp file and moved into place so a crash mid-write can't leave half a palette */
    public static void saveWorldPalette(String worldName, TilePalette palette) {
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();

        File target = new File(dir, PALETTE_FILE_NAME);
        File temp = new File(dir, PALETTE_FILE_NAME + ".tmp");

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(temp))) {
            palette.write(dos);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** null if the save has none (from before palettes existed) */
    public static TilePalette loadWorldPalette(String worldName) {
        File file = new File(new File(SAVES_DIR, worldName), PALETTE_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            return TilePalette.read(dis);
        } catch (IOException e) {
            System.out.println("Warning: could not read tile palette for world \"" + worldName + "\": " + e.getMessage());
            return null;
        }
    }

    /** origin cell {x, y, z} of every multi-tile object, the cells themselves (tile id, rotation) are already in level.dat, this just says which are origins */
    public static void saveWorldMultiTiles(String worldName, List<int[]> origins) {
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();

        File target = new File(dir, MULTI_TILES_FILE_NAME);
        File temp = new File(dir, MULTI_TILES_FILE_NAME + ".tmp");

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(temp))) {
            dos.writeInt(origins.size());
            for (int[] origin : origins) {
                dos.writeInt(origin[0]);
                dos.writeInt(origin[1]);
                dos.writeInt(origin[2]);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** null if the save has none (older saves, or no objects placed yet) */
    public static List<int[]> loadWorldMultiTiles(String worldName) {
        File file = new File(new File(SAVES_DIR, worldName), MULTI_TILES_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int count = dis.readInt();
            List<int[]> origins = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                origins.add(new int[]{dis.readInt(), dis.readInt(), dis.readInt()});
            }
            return origins;
        } catch (IOException e) {
            System.out.println("Warning: could not read multi-tiles for world \"" + worldName + "\": " + e.getMessage());
            return null;
        }
    }

    /** SubTiles by tile name, written to a temp file and moved into place */
    public static void saveWorldSubTiles(String worldName, List<SubTiles.Entry> entries) {
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();

        File target = new File(dir, SUB_TILES_FILE_NAME);
        if (entries.isEmpty()) {
            target.delete();
            return;
        }
        File temp = new File(dir, SUB_TILES_FILE_NAME + ".tmp");

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(temp))) {
            dos.writeInt(entries.size());
            for (SubTiles.Entry e : entries) {
                dos.writeInt(e.x);
                dos.writeInt(e.y);
                dos.writeInt(e.z);
                dos.writeUTF(e.tileName);
                dos.writeInt(e.data);
                dos.writeFloat(e.ox);
                dos.writeFloat(e.oy);
                dos.writeFloat(e.oz);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** null if the save has none */
    public static List<SubTiles.Entry> loadWorldSubTiles(String worldName) {
        File file = new File(new File(SAVES_DIR, worldName), SUB_TILES_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int count = dis.readInt();
            List<SubTiles.Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int x = dis.readInt();
                int y = dis.readInt();
                int z = dis.readInt();
                String name = dis.readUTF();
                int data = dis.readInt();
                float ox = dis.readFloat();
                float oy = dis.readFloat();
                float oz = dis.readFloat();
                entries.add(new SubTiles.Entry(x, y, z, name, data, ox, oy, oz));
            }
            return entries;
        } catch (IOException e) {
            System.out.println("Warning: could not read sub-tiles for world \"" + worldName + "\": " + e.getMessage());
            return null;
        }
    }

    /** tiny file so loading never has to decompress level.dat just to read the type */
    public static void saveWorldType(String worldName, LevelGenerator.WorldType type) {
        if (type == null) {
            return;
        }

        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(new File(dir, TYPE_FILE_NAME)))) {
            dos.writeInt(type.ordinal());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** null for saves from before type.dat existed */
    public static LevelGenerator.WorldType loadWorldType(String worldName) {
        File file = new File(new File(SAVES_DIR, worldName), TYPE_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int ordinal = dis.readInt();
            LevelGenerator.WorldType[] types = LevelGenerator.WorldType.values();
            return ordinal >= 0 && ordinal < types.length ? types[ordinal] : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** time of day, speed, frozen and real-time flags */
    public static void saveWorldTime(String worldName, DayNightCycle cycle) {
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(new File(dir, TIME_FILE_NAME)))) {
            dos.writeFloat(cycle.getTime());
            dos.writeFloat(cycle.getSpeed());
            dos.writeBoolean(cycle.isFrozen());
            dos.writeBoolean(cycle.isRealTime());
            dos.writeInt(cycle.getDayCount());
            dos.writeByte(cycle.getSunPhaseOverrideIndex());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** loads the clock into the cycle, false (cycle untouched) if nothing usable is saved */
    public static boolean loadWorldTime(String worldName, DayNightCycle cycle) {
        File file = new File(new File(SAVES_DIR, worldName), TIME_FILE_NAME);
        if (!file.isFile()) {
            return false;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            float time = dis.readFloat();
            float speed = dis.readFloat();
            boolean frozen = dis.readBoolean();
            boolean realTime = dis.readBoolean();
            // saves from before the sun phases end here: day 0, not pinned
            int dayCount = 0;
            int phaseOverride = -1;
            if (dis.available() >= 5) {
                dayCount = dis.readInt();
                phaseOverride = dis.readByte();
            }
            cycle.restoreState(time, speed, frozen, realTime, dayCount, phaseOverride);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** size enum and time mode, called once at world creation, read back by listWorlds() into WorldInfo */
    public static void saveWorldSettings(String worldName,
                                         LevelGenerator.WorldSize size,
                                         DayNightCycle.TimeMode timeMode) {
        if (size == null || timeMode == null) {
            return;
        }
        File dir = new File(SAVES_DIR, worldName);
        dir.mkdirs();
        try (DataOutputStream dos = new DataOutputStream(
                new FileOutputStream(new File(dir, SETTINGS_FILE_NAME)))) {
            dos.writeInt(size.ordinal());
            dos.writeInt(timeMode.ordinal());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // {sizeOrdinal, timeModeOrdinal}, null if settings.dat is absent (old save)
    private static int[] loadWorldSettings(String worldName) {
        File file = new File(new File(SAVES_DIR, worldName), SETTINGS_FILE_NAME);
        if (!file.isFile()) {
            return null;
        }
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            return new int[]{ dis.readInt(), dis.readInt() };
        } catch (IOException e) {
            return null;
        }
    }

    private static LevelGenerator.WorldSize worldSizeFromOrdinal(int ordinal) {
        LevelGenerator.WorldSize[] values = LevelGenerator.WorldSize.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    private static DayNightCycle.TimeMode timeModeFromOrdinal(int ordinal) {
        DayNightCycle.TimeMode[] values = DayNightCycle.TimeMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }

    // total bytes of every file directly in the world's save directory
    private static long worldDiskSize(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File f : files) {
            if (f.isFile()) {
                total += f.length();
            }
        }
        return total;
    }

    public static void pruneIncompleteWorlds() {
        File[] entries = new File(SAVES_DIR).listFiles();
        if (entries == null) {
            return;
        }

        for (File entry : entries) {
            if (entry.isDirectory() && !new File(entry, LEVEL_FILE_NAME).isFile()) {
                deleteRecursively(entry);
            }
        }
    }

    public static void delete(String worldName) {
        deleteRecursively(new File(SAVES_DIR, worldName));
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    public static String sanitize(String rawName) {
        String cleaned = rawName.trim()
                .replaceAll("[^a-zA-Z0-9 _-]", "")
                .replaceAll("\\s+", " ");

        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH).trim();
        }

        return cleaned;
    }
}