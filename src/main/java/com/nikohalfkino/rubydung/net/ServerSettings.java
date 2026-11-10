package com.nikohalfkino.rubydung.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * what a server lets in and allows: password, whitelist, op list, ban lists, links, embeds, doors.
 *
 * a dedicated server keeps them in a folder (server.properties, ops.txt, whitelist.txt, banned-players.txt,
 * banned-ips.txt) that is read at start and written back when something changes. a LAN game uses {@link #memory()}:
 * the same settings, but nothing is read or written, and dimensional doors are always off there.
 *
 * names are not logged in anywhere (there are no accounts), so an op list and a whitelist by name are only as strong
 * as the password. with a password set, only people who know it can pretend to be somebody
 *
 * links and embeds are OFF unless the owner turns them on. embeds make every player's game download whatever address
 * somebody types in the chat, which shows that player's IP address to whoever hosts the picture
 */
public final class ServerSettings {

    private static final String PROPERTIES = "server.properties";
    private static final String OPS = "ops.txt";
    private static final String WHITELIST = "whitelist.txt";
    private static final String BANNED_PLAYERS = "banned-players.txt";
    private static final String BANNED_IPS = "banned-ips.txt";

    private final File dir;
    private final boolean lan;

    private int maxPlayers = 16;
    private String password = "";
    private boolean whitelistEnabled = false;
    private boolean allowLinks = false;
    private boolean allowEmbeds = false;
    private boolean allowDoors = true;
    private List<String> worlds = new ArrayList<>();
    private String serverName = "";
    private String defaultTopic = "";
    // world name (lower case) -> chat topic
    private final Map<String, String> topics = new LinkedHashMap<>();

    // lower case names / addresses
    private final Set<String> ops = new LinkedHashSet<>();
    private final Set<String> whitelist = new LinkedHashSet<>();
    private final Map<String, String> bannedNames = new LinkedHashMap<>();
    private final Map<String, String> bannedIps = new LinkedHashMap<>();

    private ServerSettings(File dir, boolean lan) {
        this.dir = dir;
        this.lan = lan;
    }

    /** a LAN game: defaults, nothing saved, no dimensional doors */
    public static ServerSettings memory() {
        return new ServerSettings(null, true);
    }

    /** a dedicated server: reads the folder (and writes a server.properties with the defaults if there is none) */
    public static ServerSettings load(File dir) {
        ServerSettings settings = new ServerSettings(dir, false);
        dir.mkdirs();
        settings.read();
        settings.write();
        return settings;
    }

    // reading and writing

    private void read() {
        File file = new File(this.dir, PROPERTIES);
        if (file.isFile()) {
            Properties props = new Properties();
            try (BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("[settings] could not read " + file + ": " + e);
            }
            this.maxPlayers = parseInt(props.getProperty("max-players"), this.maxPlayers, 1, 256);
            this.password = clip(props.getProperty("password", ""), Protocol.MAX_PASSWORD_LENGTH);
            this.whitelistEnabled = parseBool(props.getProperty("whitelist"), false);
            this.allowLinks = parseBool(props.getProperty("allow-links"), false);
            this.allowEmbeds = parseBool(props.getProperty("allow-embeds"), false);
            this.allowDoors = parseBool(props.getProperty("allow-doors"), true);
            this.serverName = clip(props.getProperty("server-name", "").trim(), 64);
            this.defaultTopic = clip(props.getProperty("chat-topic", "").trim(), 200);
            this.topics.clear();
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("topic.") && key.length() > 6) {
                    String topic = clip(props.getProperty(key, "").trim(), 200);
                    if (!topic.isEmpty()) {
                        this.topics.put(key.substring(6).toLowerCase(Locale.ROOT), topic);
                    }
                }
            }
            this.worlds = new ArrayList<>();
            for (String name : props.getProperty("worlds", "").split(",")) {
                if (!name.trim().isEmpty()) {
                    this.worlds.add(name.trim());
                }
            }
        }

        readLines(OPS, line -> this.ops.add(line.toLowerCase(Locale.ROOT)));
        readLines(WHITELIST, line -> this.whitelist.add(line.toLowerCase(Locale.ROOT)));
        readLines(BANNED_PLAYERS, line -> putBan(this.bannedNames, line));
        readLines(BANNED_IPS, line -> putBan(this.bannedIps, line));
    }

    private interface LineSink {
        void accept(String line);
    }

    private void readLines(String fileName, LineSink sink) {
        File file = new File(this.dir, fileName);
        if (!file.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    sink.accept(trimmed);
                }
            }
        } catch (IOException e) {
            System.err.println("[settings] could not read " + file + ": " + e);
        }
    }

    // "name reason words" -> name, reason
    private static void putBan(Map<String, String> into, String line) {
        String[] parts = line.split("\\s+", 2);
        into.put(parts[0].toLowerCase(Locale.ROOT), parts.length > 1 ? parts[1] : "");
    }

    /** writes everything back, no-op for a LAN game */
    public synchronized void write() {
        if (this.dir == null) {
            return;
        }
        List<String> properties = new ArrayList<>(Arrays.asList(
                "# server settings. the ops, whitelist and ban lists are in their own files next to this one",
                "# players at the same time",
                "max-players=" + this.maxPlayers,
                "# empty = anybody may join. keep this file private, the password is stored as it is",
                "password=" + this.password,
                "# true = only the names in whitelist.txt (and ops) may join",
                "whitelist=" + this.whitelistEnabled,
                "# links in chat can be opened. off by default",
                "allow-links=" + this.allowLinks,
                "# pictures linked in chat are downloaded and shown. off by default: it shows every player's IP address to the host of the picture",
                "allow-embeds=" + this.allowEmbeds,
                "# dimensional doors lead to the other worlds below",
                "allow-doors=" + this.allowDoors,
                "# worlds this server hosts, the first one is where players start. empty = the --world option only",
                "worlds=" + String.join(",", this.worlds),
                "# shown on the pause screen instead of the address. empty = the address",
                "server-name=" + this.serverName,
                "# topic at the top of the chat screen in every world that has no topic of its own. empty = the game's welcome",
                "chat-topic=" + this.defaultTopic));
        // one line per world: topic.<world name>=text
        for (Map.Entry<String, String> e : this.topics.entrySet()) {
            properties.add("topic." + e.getKey() + "=" + e.getValue());
        }
        writeLines(PROPERTIES, properties);
        writeLines(OPS, new ArrayList<>(this.ops));
        writeLines(WHITELIST, new ArrayList<>(this.whitelist));
        writeLines(BANNED_PLAYERS, banLines(this.bannedNames));
        writeLines(BANNED_IPS, banLines(this.bannedIps));
    }

    private static List<String> banLines(Map<String, String> bans) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : bans.entrySet()) {
            lines.add(e.getValue().isEmpty() ? e.getKey() : e.getKey() + " " + e.getValue());
        }
        return lines;
    }

    private void writeLines(String fileName, List<String> lines) {
        File file = new File(this.dir, fileName);
        try (BufferedWriter out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                out.write(line);
                out.newLine();
            }
        } catch (IOException e) {
            System.err.println("[settings] could not write " + file + ": " + e);
        }
    }

    /** reads the files again (the owner edited them by hand) */
    public synchronized void reload() {
        if (this.dir == null) {
            return;
        }
        this.ops.clear();
        this.whitelist.clear();
        this.bannedNames.clear();
        this.bannedIps.clear();
        read();
    }

    // names and topics

    /** the name players see on the pause screen, empty = none (they see the address) */
    public synchronized String getServerName() {
        return this.serverName;
    }

    public synchronized void setServerName(String name) {
        this.serverName = clip(name == null ? "" : name.trim(), 64);
        write();
    }

    /** the chat screen's topic in this world: its own, else the server-wide one, else empty (the game's welcome is used) */
    public synchronized String topicFor(String worldName) {
        String own = worldName == null ? null : this.topics.get(worldName.toLowerCase(Locale.ROOT));
        return own != null ? own : this.defaultTopic;
    }

    /** empty text removes the world's own topic */
    public synchronized void setTopic(String worldName, String topic) {
        String clean = clip(topic == null ? "" : topic.trim(), 200);
        if (clean.isEmpty()) {
            this.topics.remove(worldName.toLowerCase(Locale.ROOT));
        } else {
            this.topics.put(worldName.toLowerCase(Locale.ROOT), clean);
        }
        write();
    }

    // joining

    public int getMaxPlayers() {
        return this.maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = Math.max(1, maxPlayers);
        write();
    }

    public boolean hasPassword() {
        return !this.password.isEmpty();
    }

    /** compares in constant time, so how long it takes says nothing about how much of the password was right */
    public boolean passwordMatches(String given) {
        byte[] a = this.password.getBytes(StandardCharsets.UTF_8);
        byte[] b = (given == null ? "" : given).getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }

    public void setPassword(String password) {
        this.password = clip(password == null ? "" : password, Protocol.MAX_PASSWORD_LENGTH);
        write();
    }

    public boolean isWhitelistEnabled() {
        return this.whitelistEnabled;
    }

    public void setWhitelistEnabled(boolean on) {
        this.whitelistEnabled = on;
        write();
    }

    /** null if this player may join, otherwise the reason they may not */
    public synchronized String refusal(String name, String ip) {
        String key = name.toLowerCase(Locale.ROOT);
        if (this.bannedNames.containsKey(key)) {
            return banMessage("You are banned from this server", this.bannedNames.get(key));
        }
        if (this.bannedIps.containsKey(ip)) {
            return banMessage("Your address is banned from this server", this.bannedIps.get(ip));
        }
        if (this.whitelistEnabled && !this.whitelist.contains(key) && !this.ops.contains(key)) {
            return "You are not on the whitelist";
        }
        return null;
    }

    private static String banMessage(String start, String reason) {
        return reason == null || reason.isEmpty() ? start : start + ": " + reason;
    }

    // lists

    public synchronized boolean isOp(String name) {
        return this.ops.contains(name.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean addOp(String name) {
        boolean added = this.ops.add(name.toLowerCase(Locale.ROOT));
        write();
        return added;
    }

    public synchronized boolean removeOp(String name) {
        boolean removed = this.ops.remove(name.toLowerCase(Locale.ROOT));
        write();
        return removed;
    }

    public synchronized boolean whitelistAdd(String name) {
        boolean added = this.whitelist.add(name.toLowerCase(Locale.ROOT));
        write();
        return added;
    }

    public synchronized boolean whitelistRemove(String name) {
        boolean removed = this.whitelist.remove(name.toLowerCase(Locale.ROOT));
        write();
        return removed;
    }

    public synchronized List<String> whitelisted() {
        return new ArrayList<>(this.whitelist);
    }

    public synchronized List<String> ops() {
        return new ArrayList<>(this.ops);
    }

    public synchronized void banName(String name, String reason) {
        this.bannedNames.put(name.toLowerCase(Locale.ROOT), reason == null ? "" : reason);
        write();
    }

    public synchronized boolean pardonName(String name) {
        boolean removed = this.bannedNames.remove(name.toLowerCase(Locale.ROOT)) != null;
        write();
        return removed;
    }

    public synchronized void banIp(String ip, String reason) {
        this.bannedIps.put(ip, reason == null ? "" : reason);
        write();
    }

    public synchronized boolean pardonIp(String ip) {
        boolean removed = this.bannedIps.remove(ip) != null;
        write();
        return removed;
    }

    public synchronized List<String> bans() {
        List<String> all = new ArrayList<>();
        for (String name : this.bannedNames.keySet()) {
            all.add(name);
        }
        for (String ip : this.bannedIps.keySet()) {
            all.add(ip + " (address)");
        }
        return all;
    }

    // what the server allows

    public boolean isAllowLinks() {
        return this.allowLinks;
    }

    public void setAllowLinks(boolean on) {
        this.allowLinks = on;
        // a picture is a link too, so with links off there is nothing to embed
        if (!on) {
            this.allowEmbeds = false;
        }
        write();
    }

    public boolean isAllowEmbeds() {
        return this.allowEmbeds && this.allowLinks;
    }

    public void setAllowEmbeds(boolean on) {
        this.allowEmbeds = on;
        if (on) {
            this.allowLinks = true;
        }
        write();
    }

    /** a LAN game never has doors: there is only the one world, and it is the host's */
    public boolean isAllowDoors() {
        return !this.lan && this.allowDoors;
    }

    public void setAllowDoors(boolean on) {
        this.allowDoors = on;
        write();
    }

    public boolean isLan() {
        return this.lan;
    }

    /** world names from server.properties, in order (may be empty) */
    public List<String> getWorlds() {
        return Collections.unmodifiableList(this.worlds);
    }

    // helpers

    private static boolean parseBool(String text, boolean fallback) {
        if (text == null) {
            return fallback;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.equals("true") || t.equals("on") || t.equals("yes")) {
            return true;
        }
        if (t.equals("false") || t.equals("off") || t.equals("no")) {
            return false;
        }
        return fallback;
    }

    private static int parseInt(String text, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(text.trim())));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String clip(String text, int max) {
        String t = text.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
