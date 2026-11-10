package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.level.WorldManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * where the coloured dimensional doors of a world lead on a server, set by the server's owner (.door). saved next to the
 * world (door-targets.dat, one "x y z" + tab + world name line per door) so it survives restarts
 */
final class DoorTargets {

    private static final String FILE_NAME = "door-targets.dat";

    private DoorTargets() {
    }

    static long pack(int x, int y, int z) {
        return (x & 0x1FFFFFL) | ((y & 0x1FFFFFL) << 21) | ((z & 0x1FFFFFL) << 42);
    }

    static int x(long key) {
        return (int) (key << 43 >> 43);
    }

    static int y(long key) {
        return (int) (key << 22 >> 43);
    }

    static int z(long key) {
        return (int) (key << 1 >> 43);
    }

    static Map<Long, String> load(String worldName) {
        Map<Long, String> targets = new HashMap<>();
        File file = WorldManager.worldFile(worldName, FILE_NAME);
        if (file == null || !file.isFile()) {
            return targets;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                String[] pos = line.substring(0, tab).trim().split("\\s+");
                String world = line.substring(tab + 1).trim();
                if (pos.length != 3 || world.isEmpty()) {
                    continue;
                }
                try {
                    targets.put(pack(Integer.parseInt(pos[0]), Integer.parseInt(pos[1]), Integer.parseInt(pos[2])), world);
                } catch (NumberFormatException e) {
                    // a bad line: skip just this door
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return targets;
    }

    static void save(String worldName, Map<Long, String> targets) {
        File file = WorldManager.worldFile(worldName, FILE_NAME);
        if (file == null) {
            return;
        }
        File folder = file.getParentFile();
        if (folder != null) {
            folder.mkdirs();
        }
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            for (Map.Entry<Long, String> e : targets.entrySet()) {
                long k = e.getKey();
                writer.println(x(k) + " " + y(k) + " " + z(k) + "\t" + e.getValue());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
