package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;

/**
 * a dedicated server: no window, no player, just the world and the network.
 *
 *   java -Xmx2g -cp <the game's classes and libraries> com.nikohalfkino.rubydung.net.ServerMain --world MyWorld
 *
 * options: --world NAME (default "server", the world players start in), --worlds A,B,C (more worlds, dimensional doors
 * lead between them), --port N (25566), --max-players N (16), --size tiny|default|large|insane|xxl,
 * --type flat|natural|... (a LevelGenerator.WorldType name), --seed N (the first world only), --autosave MINUTES (5, 0 = off),
 * --password TEXT (join password), --gui (a window with the log and a command line instead of the terminal), --settings DIR (default "server": server.properties, ops.txt, whitelist.txt, banned-players.txt, banned-ips.txt)
 *
 * a world that exists is loaded (its saved size and type win), otherwise a new one is made. the worlds can also be
 * listed in server.properties (worlds=a,b,c)
 *
 * the console is the owner of the server: every command in ServerCommands works here (type "help"), at tier HOST.
 * a line without a dot is a command, same as with one
 */
public final class ServerMain {

    private static final int TICKS_PER_SECOND = 20;

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        boolean gui = false;
        for (String arg : args) {
            if (arg.equals("--gui")) {
                gui = true;
            }
        }
        if (!gui) {
            System.setProperty("java.awt.headless", "true");
        }

        String worldName = "server";
        int port = Protocol.DEFAULT_PORT;
        int autosaveMinutes = 5;
        LevelGenerator.WorldSize size = LevelGenerator.WorldSize.DEFAULT;
        LevelGenerator.WorldType type = null;
        Long seed = null;
        Integer maxPlayersOverride = null;
        String settingsDir = "server";
        String passwordOverride = null;
        List<String> extraWorlds = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (flag) {
                case "--world":
                    worldName = WorldManager.sanitize(need(flag, value));
                    i++;
                    break;
                case "--port":
                    port = Integer.parseInt(need(flag, value));
                    i++;
                    break;
                case "--max-players":
                    maxPlayersOverride = Integer.parseInt(need(flag, value));
                    i++;
                    break;
                case "--worlds":
                    for (String name : need(flag, value).split(",")) {
                        if (!name.trim().isEmpty()) {
                            extraWorlds.add(WorldManager.sanitize(name.trim()));
                        }
                    }
                    i++;
                    break;
                case "--settings":
                    settingsDir = need(flag, value);
                    i++;
                    break;
                case "--autosave":
                    autosaveMinutes = Integer.parseInt(need(flag, value));
                    i++;
                    break;
                case "--size":
                    size = parseSize(need(flag, value));
                    i++;
                    break;
                case "--type":
                    type = LevelGenerator.WorldType.valueOf(need(flag, value).toUpperCase(Locale.ROOT));
                    i++;
                    break;
                case "--gui":
                    break;
                case "--password":
                    passwordOverride = need(flag, value);
                    i++;
                    break;
                case "--seed":
                    seed = Long.parseLong(need(flag, value));
                    i++;
                    break;
                default:
                    System.err.println("unknown option " + flag);
                    System.exit(2);
            }
        }

        final boolean[] stopRequested = {false};
        Queue<String> commands = new ConcurrentLinkedQueue<>();
        if (gui) {
            // a window with the log and a command line, instead of the terminal
            ServerPanel.open("Strata server", line -> commands.add(line.trim()), () -> stopRequested[0] = true);
        }

        // the tile registry has to exist before any level does (the game does this on its main thread too)
        Tile.getTiles();

        ServerSettings settings = ServerSettings.load(new File(settingsDir));
        if (maxPlayersOverride != null) {
            settings.setMaxPlayers(maxPlayersOverride);
        }
        if (passwordOverride != null && !passwordOverride.isEmpty()) {
            settings.setPassword(passwordOverride);
        }

        // the main world first, then the ones from --worlds and server.properties
        List<String> names = new ArrayList<>();
        names.add(worldName);
        for (String name : extraWorlds) {
            addUnique(names, name);
        }
        for (String name : settings.getWorlds()) {
            addUnique(names, WorldManager.sanitize(name));
        }

        Map<String, Level> levels = new LinkedHashMap<>();
        Map<String, DayNightCycle> clocks = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            DayNightCycle clock = new DayNightCycle();
            // --type and --seed only apply to the first world, the others are made with the defaults
            Level level = openWorld(name, size, i == 0 ? type : null, i == 0 ? seed : null, clock);
            levels.put(name, level);
            clocks.put(name, clock);
        }

        GameServer server = new GameServer(worldName, levels.get(worldName), clocks.get(worldName), settings, port,
                null, null, null, System.out::println, null);
        for (int i = 1; i < names.size(); i++) {
            server.addWorld(names.get(i), levels.get(names.get(i)), clocks.get(names.get(i)));
        }
        server.setOwnsSimulation(true);
        // .createworld: the new world is kept in the same maps the autosave and the final save go through
        server.setWorldCreator((name, wtype, wsize, wseed, clock) -> {
            Level level = openWorld(name, wsize, wtype, wseed, clock);
            levels.put(name, level);
            clocks.put(name, clock);
            return level;
        });

        server.setStopAction(() -> stopRequested[0] = true);
        server.setSaveAction(() -> saveAll(levels, clocks));

        server.start();
        System.out.println("[server] listening on port " + port + " with " + names.size() + " world(s): "
                + String.join(", ", names) + (settings.hasPassword() ? ", password on" : "")
                + (settings.isWhitelistEnabled() ? ", whitelist on" : "")
                + (server.doorsEnabled() ? ", dimensional doors on" : "")
                + ". type \"help\" for commands");

        Thread console = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = in.readLine()) != null) {
                    commands.add(line.trim());
                }
            } catch (IOException ignored) {
            }
        }, "console");
        console.setDaemon(true);
        console.start();

        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopRequested[0] = true;
            try {
                mainThread.join(15_000);
            } catch (InterruptedException ignored) {
            }
        }));

        long tickNanos = 1_000_000_000L / TICKS_PER_SECOND;
        long nextSave = System.nanoTime() + autosaveMinutes * 60L * 1_000_000_000L;
        long next = System.nanoTime();

        int failedTicks = 0;
        while (!stopRequested[0]) {
            // one bad tick or command must not end the loop: it would skip the final save and lose the world since the last autosave
            try {
                server.tick();

                String command;
                while ((command = commands.poll()) != null) {
                    for (String reply : ServerCommands.execute(server, server.hostActor(), command)) {
                        System.out.println(reply);
                    }
                }

                if (autosaveMinutes > 0 && System.nanoTime() >= nextSave) {
                    saveAll(levels, clocks);
                    nextSave = System.nanoTime() + autosaveMinutes * 60L * 1_000_000_000L;
                }
                failedTicks = 0;
            } catch (RuntimeException | OutOfMemoryError e) {
                e.printStackTrace();
                // something that fails every tick is not going to get better: save and stop instead of spinning
                if (++failedTicks >= 100) {
                    System.out.println("[server] the tick failed " + failedTicks + " times in a row, stopping");
                    stopRequested[0] = true;
                }
            }

            next += tickNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
            } else if (sleep < -1_000_000_000L) {
                next = System.nanoTime();   // far behind (a long save): don't try to catch up
            }
        }

        System.out.println("[server] stopping...");
        server.stop("Server closed");
        saveAll(levels, clocks);
        System.out.println("[server] bye");
        if (gui) {
            ServerPanel.close();
        }
    }

    private static void addUnique(List<String> names, String name) {
        for (String existing : names) {
            if (existing.equalsIgnoreCase(name)) {
                return;
            }
        }
        names.add(name);
    }

    /** loads the world of that name, or makes it (a world that exists keeps its saved size and type) */
    private static Level openWorld(String name, LevelGenerator.WorldSize size, LevelGenerator.WorldType type, Long seed,
                                   DayNightCycle clock) {
        if (WorldManager.exists(name) && WorldManager.levelFile(name).isFile()) {
            System.out.println("[server] loading world \"" + name + "\"");
            int[] savedSize = WorldManager.loadWorldSize(name);
            LevelGenerator.WorldSize fallback = LevelGenerator.WorldSize.DEFAULT;
            int width = savedSize != null ? savedSize[0] : fallback.width;
            int height = savedSize != null ? savedSize[1] : fallback.height;
            int depth = savedSize != null ? savedSize[2] : fallback.depth;
            LevelGenerator.WorldType savedType = WorldManager.loadWorldType(name);
            Level level = new Level(name, width, height, depth, savedType != null ? savedType : LevelGenerator.WorldType.FLAT);
            if (!WorldManager.loadWorldTime(name, clock)) {
                clock.applyCreationSettings(DayNightCycle.TimeMode.NORMAL, DayNightCycle.DEFAULT_START_TIME);
            }
            return level;
        }

        LevelGenerator.WorldType made = type != null ? type : LevelGenerator.WorldType.NATURAL;
        long madeSeed = seed != null ? seed : new Random().nextLong();
        System.out.println("[server] creating world \"" + name + "\" (" + made + ", " + size + ", seed " + madeSeed + ")");
        clock.applyCreationSettings(DayNightCycle.TimeMode.NORMAL, DayNightCycle.DEFAULT_START_TIME);
        WorldManager.saveWorldSettings(name, size, DayNightCycle.TimeMode.NORMAL);
        Level level = new Level(name, size.width, size.height, size.depth, made, madeSeed);
        level.save();
        WorldManager.saveWorldTime(name, clock);
        return level;
    }

    private static void saveAll(Map<String, Level> levels, Map<String, DayNightCycle> clocks) {
        System.out.println("[server] saving...");
        for (Map.Entry<String, Level> e : levels.entrySet()) {
            e.getValue().save();
            WorldManager.saveWorldTime(e.getKey(), clocks.get(e.getKey()));
        }
        System.out.println("[server] saved");
    }

    private static LevelGenerator.WorldSize parseSize(String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "tiny":
                return LevelGenerator.WorldSize.TINY;
            case "default":
                return LevelGenerator.WorldSize.DEFAULT;
            case "large":
                return LevelGenerator.WorldSize.LARGE;
            case "insane":
            case "xl":
                return LevelGenerator.WorldSize.INSANE;
            case "xxl":
                return LevelGenerator.WorldSize.XXL;
            default:
                throw new IllegalArgumentException("size must be tiny, default, large, insane or xxl");
        }
    }

    private static String need(String flag, String value) {
        if (value == null) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return value;
    }
}
