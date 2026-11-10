package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.special.DoorColor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * dimensional doors of each world, saved next to the level file (doors.dat, one "x y z COLOR" line per door)
 *
 * written on every level save so the world list and door screen can show another world's doors without loading it
 */
public final class DoorRegistry {

    private static final String FILE_NAME = "doors.dat";

    private DoorRegistry() {
    }

    public static final class Entry {
        public final int x;
        public final int y;
        public final int z;
        public final DoorColor color;

        public Entry(int x, int y, int z, DoorColor color) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
        }
    }

    private static File file(String worldName) {
        return WorldManager.worldFile(worldName, FILE_NAME);
    }

    public static List<Entry> load(String worldName) {
        List<Entry> doors = new ArrayList<>();

        File file = file(worldName);
        if (file == null || !file.isFile()) {
            return doors;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 4) {
                    continue;
                }
                try {
                    doors.add(new Entry(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]), DoorColor.valueOf(parts[3])));
                } catch (IllegalArgumentException e) {
                    // Bad line: skip just this door.
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return doors;
    }

    public static void save(String worldName, List<Entry> doors) {
        File file = file(worldName);
        if (file == null) {
            return;
        }

        File folder = file.getParentFile();
        if (folder != null) {
            folder.mkdirs();
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            for (Entry door : doors) {
                writer.println(door.x + " " + door.y + " " + door.z + " " + door.color.name());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** distinct door colours a world has (empty if none, or saved before doors existed) */
    public static Set<DoorColor> colors(String worldName) {
        Set<DoorColor> colors = EnumSet.noneOf(DoorColor.class);
        for (Entry door : load(worldName)) {
            colors.add(door.color);
        }
        return colors;
    }
}