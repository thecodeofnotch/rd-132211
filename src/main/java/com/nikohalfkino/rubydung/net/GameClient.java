package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.entity.CubeEntity;
import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LevelEditListener;
import com.nikohalfkino.rubydung.level.NetworkSnapshot;
import com.nikohalfkino.rubydung.level.SubTiles;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.level.LiquidLayer;
import com.nikohalfkino.rubydung.level.generation.WorldGenStatus;
import com.nikohalfkino.rubydung.level.tiles.special.DoorColor;
import com.nikohalfkino.rubydung.player.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * the player's side of a connection to a GameServer: joins, downloads the world, then keeps it in step.
 *
 * packets are read on the network thread, parsed there, and everything that touches the game runs on the main thread
 * through RubyDung.runOnMainThread. while the world is still downloading those tasks wait in "pending"
 */
public final class GameClient implements Connection.Listener, LevelEditListener {

    public enum State { CONNECTING, DOWNLOADING, PLAYING, CLOSED }

    private static final int STATE_EVERY_TICKS = 3;

    // liquid cells from the server are applied this many per tick, so a flood does not stall a frame
    private static final int LIQUIDS_PER_TICK = 16384;
    // up to this many waiting cells are applied the moment they arrive
    private static final int LIQUIDS_AT_ONCE = 2048;

    /** a world the server hosts and the colours of the dimensional doors in it */
    public static final class WorldInfo {
        public final String name;
        public final Set<DoorColor> doors;

        WorldInfo(String name, Set<DoorColor> doors) {
            this.name = name;
            this.doors = doors;
        }
    }

    private final RubyDung game;
    private final String host;
    private final int port;
    private final String name;
    private final String password;
    private final RemotePlayers remotes;

    private volatile State state = State.CONNECTING;
    private Connection connection;

    // download (network thread)
    private int welcomeId;
    private int levelWidth, levelHeight, levelDepth;
    private int levelType;
    // the name the server gave us (it makes a name unique, so it may not be the one in the options)
    private String assignedName;
    private long levelSeed;
    private double spawnX, spawnY, spawnZ;
    private float timeOfDay;
    private ByteArrayOutputStream download;
    private int downloadLength;
    private float spawnYaw;

    // what the server allows and who we are on it (written on the network thread, read on the main thread)
    private volatile int allowed = 0;
    private volatile CommandTier tier = CommandTier.PLAYER;
    private volatile String worldName = "";
    private volatile String serverName = "";
    private volatile String chatTopic = "";
    private volatile List<WorldInfo> worlds = Collections.emptyList();

    /** a coloured door of the world the player is in and where the server says it leads ("" = nowhere) */
    public static final class DoorInfo {
        public final int x, y, z;
        public final String destination;

        DoorInfo(int x, int y, int z, String destination) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.destination = destination;
        }
    }

    private volatile List<DoorInfo> doors = Collections.emptyList();

    // main thread
    private Level level;
    private final List<Runnable> pending = new ArrayList<>();
    private int ticks = 0;
    private boolean closedHandled = false;
    private final Deque<int[]> liquidQueue = new ArrayDeque<>();
    private long lastLiquidTick = 0L;

    public GameClient(RubyDung game, String host, int port, String name, String password, RemotePlayers remotes) {
        this.game = game;
        this.host = host;
        this.port = port;
        this.name = name;
        this.password = password == null ? "" : password;
        this.remotes = remotes;
    }

    public State getState() {
        return this.state;
    }

    public String getAddress() {
        return this.host + ":" + this.port;
    }

    /** main thread: opens the loading screen, connects in the background */
    public void connect() {
        this.game.getSession().beginNetworkWorld();
        WorldGenStatus.set("Connecting to " + getAddress() + "...");

        Thread thread = new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(this.host, this.port), Protocol.CONNECT_TIMEOUT_MS);
                this.connection = new Connection(socket, this, "server");
                this.connection.start();
                this.connection.send(Protocol.C_HELLO, out -> {
                    out.writeInt(Protocol.VERSION);
                    out.writeUTF(this.name);
                    out.writeUTF(this.password);
                });
            } catch (IOException | RuntimeException e) {
                failed("Could not connect to " + getAddress() + ": " + e.getMessage());
            }
        }, "net-connect");
        thread.setDaemon(true);
        thread.start();
    }

    /** main thread: leaves the server */
    public void disconnect() {
        this.state = State.CLOSED;
        this.closedHandled = true;
        if (this.level != null && this.level.getEditListener() == this) {
            this.level.setEditListener(null);
        }
        if (this.connection != null) {
            this.connection.close("Disconnected");
        }
        this.remotes.clear();
    }

    /** main thread, every game tick */
    public void tick() {
        if (this.state != State.PLAYING || this.connection == null) {
            return;
        }

        stepLiquids();

        Player player = this.game.getPlayer();
        if (player == null) {
            return;
        }

        if (++this.ticks % STATE_EVERY_TICKS == 0) {
            double x = player.x, y = player.y, z = player.z;
            float yaw = player.yRotation, pitch = player.xRotation, body = player.bodyYaw;
            int flags = (player.flying ? Protocol.FLAG_FLYING : 0) | (player.noClip ? Protocol.FLAG_NOCLIP : 0);
            this.connection.send(Protocol.C_STATE, out -> {
                out.writeDouble(x);
                out.writeDouble(y);
                out.writeDouble(z);
                out.writeFloat(yaw);
                out.writeFloat(pitch);
                out.writeFloat(body);
                out.writeByte(flags);
            });
        }
        if (this.ticks % (Protocol.KEEPALIVE_MS * 60 / 1000) == 0) {
            this.connection.send(Protocol.C_KEEPALIVE, out -> {
            });
        }
    }

    /**
     * the liquids of a joined game run here as well as on the server, so water flows smoothly between the server's
     * updates instead of jumping in lumps. what the server says always wins (applyLiquids), this only fills the gaps.
     * runs even while a menu is open, like the rest of the connection
     */
    private void stepLiquids() {
        Level current = this.level;
        if (current == null) {
            return;
        }

        drainLiquids(LIQUIDS_PER_TICK);

        long now = System.nanoTime();
        float seconds = this.lastLiquidTick == 0L ? 0.0F : Math.min((now - this.lastLiquidTick) * 1.0E-9F, 0.25F);
        this.lastLiquidTick = now;
        if (seconds > 0.0F) {
            current.getLiquids().update(seconds);
        }
    }

    private void drainLiquids(int budget) {
        Level current = this.level;
        if (current == null) {
            this.liquidQueue.clear();
            return;
        }
        int[] c;
        while (budget-- > 0 && (c = this.liquidQueue.poll()) != null) {
            current.applyLiquidFromNetwork(c[0], c[1], c[2], c[3], c[4]);
        }
    }

    private void applyLiquids(int[][] cells) {
        Collections.addAll(this.liquidQueue, cells);
        // a small update goes in right now, a big one (a flood) is spread over the next ticks
        if (this.liquidQueue.size() <= LIQUIDS_AT_ONCE) {
            drainLiquids(LIQUIDS_AT_ONCE);
        }
    }

    public void sendChat(String text) {
        if (this.connection != null && this.state == State.PLAYING) {
            // the voice goes along so the others hear it in this player's voice (empty if text to speech is off)
            String voice = Multiplayer.localVoice();
            this.connection.send(Protocol.C_CHAT, out -> {
                out.writeUTF(text);
                out.writeUTF(voice);
            });
        }
    }

    /** tells the server this player started or stopped typing a chat message */
    public void sendTyping(boolean on) {
        if (this.connection != null && this.state == State.PLAYING) {
            this.connection.send(Protocol.C_TYPING, out -> out.writeByte(on ? 1 : 0));
        }
    }

    /** a dot-command for the server to run (without the dot). what it answers comes back as chat */
    public void sendCommand(String line) {
        if (this.connection != null && this.state == State.PLAYING) {
            this.connection.send(Protocol.C_COMMAND, out -> out.writeUTF(line));
        }
    }

    /** asks to go through the dimensional door whose origin cell is (x, y, z) to another world, with the colour picked */
    public void sendDoor(int x, int y, int z, String targetWorld, DoorColor picked) {
        if (this.connection != null && this.state == State.PLAYING) {
            this.connection.send(Protocol.C_DOOR, out -> {
                out.writeInt(x);
                out.writeInt(y);
                out.writeInt(z);
                out.writeUTF(targetWorld);
                out.writeInt(picked.ordinal());
            });
        }
    }

    // weighted cubes: the server moves them, this player asks to put one down, pick one up, carries it (says where it is) and lets go of it

    /** asks the server to put a weighted cube into the empty cell */
    public void sendChessMove(int id, int x, int z) {
        if (this.connection != null && this.state == State.PLAYING) {
            this.connection.send(Protocol.C_CHESS_MOVE, out -> {
                out.writeInt(id);
                out.writeInt(x);
                out.writeInt(z);
            });
        }
    }

    public void sendCubeSpawn(int x, int y, int z, boolean dice) {
        if (this.connection != null && this.state == State.PLAYING) {
            this.connection.send(Protocol.C_CUBE_SPAWN, out -> {
                out.writeInt(x);
                out.writeInt(y);
                out.writeInt(z);
                out.writeBoolean(dice);
            });
        }
    }

    public void sendCubePickup(int id) {
        if (this.connection != null && this.state == State.PLAYING) {
            this.connection.send(Protocol.C_CUBE_PICKUP, out -> out.writeInt(id));
        }
    }

    /** where the carried cube should be and how it is turned */
    public void sendCubeHold(CubeEntity cube) {
        if (this.connection != null && this.state == State.PLAYING) {
            int id = cube.id;
            double x = cube.targetX, y = cube.targetY, z = cube.targetZ;
            float qw = (float) cube.qw, qx = (float) cube.qx, qy = (float) cube.qy, qz = (float) cube.qz;
            this.connection.send(Protocol.C_CUBE_HOLD, out -> {
                out.writeInt(id);
                out.writeDouble(x);
                out.writeDouble(y);
                out.writeDouble(z);
                out.writeFloat(qw);
                out.writeFloat(qx);
                out.writeFloat(qy);
                out.writeFloat(qz);
            });
        }
    }

    /** the cube was let go of or thrown: its speed and spin are what it left the hands with */
    public void sendCubeRelease(CubeEntity cube) {
        if (this.connection != null && this.state == State.PLAYING) {
            int id = cube.id;
            float vx = (float) cube.vx, vy = (float) cube.vy, vz = (float) cube.vz;
            float avx = (float) cube.avx, avy = (float) cube.avy, avz = (float) cube.avz;
            this.connection.send(Protocol.C_CUBE_RELEASE, out -> {
                out.writeInt(id);
                out.writeFloat(vx);
                out.writeFloat(vy);
                out.writeFloat(vz);
                out.writeFloat(avx);
                out.writeFloat(avy);
                out.writeFloat(avz);
            });
        }
    }

    public void sendCubeRemove(int id) {
        if (this.connection != null && this.state == State.PLAYING) {
            this.connection.send(Protocol.C_CUBE_REMOVE, out -> out.writeInt(id));
        }
    }

    // main thread: what the server says about the cubes. the one carried here keeps its own state, it is this machine that says where it is
    private void applyCubes(boolean full, List<Protocol.CubeState> states) {
        Level current = this.level;
        if (current == null) {
            return;
        }
        CubeManager cubes = current.getCubeManager();
        cubes.setReplica(true);

        int me = this.welcomeId;
        Set<Integer> ids = new HashSet<>();
        for (Protocol.CubeState st : states) {
            ids.add(st.id);
            CubeEntity cube = cubes.byId(st.id);
            boolean fresh = false;
            if (cube == null) {
                cube = cubes.addReplica(st.id, st.x, st.y, st.z);
                if (cube == null) {
                    continue;
                }
                fresh = true;
            }
            cubes.applyPieceCode(cube, st.piece);
            cube.remoteHeld = !cube.isPiece() && st.holder != -1 && st.holder != me;
            if (cube.held) {
                continue;
            }
            cube.setNetState(st.x, st.y, st.z, st.qw, st.qx, st.qy, st.qz, st.vx, st.vy, st.vz, fresh);
        }
        if (full) {
            cubes.retainOnly(ids);
        }
    }

    // what the server allows

    public boolean doorsAllowed() {
        return (this.allowed & Protocol.WELCOME_DOORS) != 0;
    }

    public boolean linksAllowed() {
        return (this.allowed & Protocol.WELCOME_LINKS) != 0;
    }

    public boolean embedsAllowed() {
        return (this.allowed & Protocol.WELCOME_EMBEDS) != 0;
    }

    public CommandTier getTier() {
        return this.tier;
    }

    /** the name the server's owner gave it, empty when there is none */
    public String getServerName() {
        return this.serverName;
    }

    /** the chat screen's topic for the world the player is in, empty when the server sets none */
    public String getChatTopic() {
        return this.chatTopic;
    }

    public String getWorldName() {
        return this.worldName;
    }

    /** the coloured doors of the world the player is in, with where each leads (never null) */
    public List<DoorInfo> getDoors() {
        return this.doors;
    }

    /** the worlds of the server with their door colours, as the server last said (never null) */
    public List<WorldInfo> getWorlds() {
        return this.worlds;
    }

    // Connection.Listener (network thread)

    @Override
    public void onPacket(Connection connection, int type, DataInputStream in) throws IOException {
        switch (type) {
            case Protocol.S_WELCOME:
                onWelcome(in);
                break;
            case Protocol.S_LEVEL_CHUNK:
                onLevelChunk(in);
                break;
            case Protocol.S_LEVEL_END:
                onLevelEnd();
                break;
            case Protocol.S_BLOCK_CHANGE: {
                int x = in.readInt(), y = in.readInt(), z = in.readInt(), id = in.readInt(), data = in.readInt();
                later(() -> {
                    // older liquid updates still waiting must not land after this block change
                    drainLiquids(Integer.MAX_VALUE);
                    int before = this.level.getTile(x, y, z);
                    this.level.applyTileFromNetwork(x, y, z, id, data);
                    // what we did ourselves is already applied (and heard) here, so only a real change makes a sound
                    RemoteSounds.blockChanged(this.game.getPlayer(), x, y, z, before, this.level.getTile(x, y, z));
                });
                break;
            }
            case Protocol.S_SUBTILES: {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                List<SubTiles.Entry> entries = Protocol.readEntries(in);
                later(() -> this.level.applySubTilesFromNetwork(x, y, z, entries));
                break;
            }
            case Protocol.S_LIQUIDS: {
                int count = in.readInt();
                if (count < 0 || count > Protocol.MAX_FRAME_BYTES / 14) {
                    throw new IOException("bad liquid count " + count);
                }
                int[][] cells = new int[count][5];
                for (int i = 0; i < count; i++) {
                    cells[i][0] = in.readInt();
                    cells[i][1] = in.readInt();
                    cells[i][2] = in.readInt();
                    cells[i][3] = in.readUnsignedByte();
                    cells[i][4] = in.readUnsignedByte();
                }
                later(() -> applyLiquids(cells));
                break;
            }
            case Protocol.S_CUBES: {
                boolean full = in.readUnsignedByte() != 0;
                List<Protocol.CubeState> states = Protocol.readCubes(in);
                later(() -> applyCubes(full, states));
                break;
            }
            case Protocol.S_CUBE_REMOVE: {
                int id = in.readInt();
                later(() -> {
                    CubeManager cubes = this.level.getCubeManager();
                    CubeEntity cube = cubes.byId(id);
                    if (cube != null) {
                        this.game.getPlayerInteraction().cubeDenied(id);
                        cubes.remove(cube);
                    }
                });
                break;
            }
            case Protocol.S_CUBE_DENY: {
                int id = in.readInt();
                later(() -> this.game.getPlayerInteraction().cubeDenied(id));
                break;
            }
            case Protocol.S_PLAYER_JOIN: {
                int id = in.readInt();
                String playerName = in.readUTF();
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                later(() -> this.remotes.add(new RemotePlayer(id, playerName, x, y, z)));
                break;
            }
            case Protocol.S_PLAYER_LEAVE: {
                int id = in.readInt();
                later(() -> this.remotes.remove(id));
                break;
            }
            case Protocol.S_PLAYER_MOVES: {
                int count = in.readInt();
                double[][] moves = new double[count][];
                for (int i = 0; i < count; i++) {
                    int id = in.readInt();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    float yaw = in.readFloat(), pitch = in.readFloat(), body = in.readFloat();
                    int flags = in.readUnsignedByte();
                    moves[i] = new double[]{id, x, y, z, yaw, pitch, body, flags};
                }
                later(() -> {
                    for (double[] m : moves) {
                        RemotePlayer p = this.remotes.get((int) m[0]);
                        if (p != null) {
                            p.setState(m[1], m[2], m[3], (float) m[4], (float) m[5], (float) m[6], (int) m[7]);
                        }
                    }
                });
                break;
            }
            case Protocol.S_CHAT: {
                String sender = in.readUTF();
                String line = in.readUTF();
                String voice = Protocol.sanitizeVoice(in.readUTF());
                later(() -> {
                    if (sender.isEmpty()) {
                        this.game.getChatLog().notice(line);
                    } else {
                        this.game.getChatLog().sayAs(sender, com.nikohalfkino.rubydung.interfaces.ChatInputSyntax.displayed(line));
                        // a chat bubble over them and their voice in the world, if they are near
                        Multiplayer.heard(this.game, sender, line, voice);
                    }
                });
                break;
            }
            case Protocol.S_TYPING: {
                String who = in.readUTF();
                boolean on = in.readUnsignedByte() != 0;
                later(() -> Multiplayer.typingChanged(who, on));
                break;
            }
            case Protocol.S_TIME: {
                float time = in.readFloat();
                float speed = in.readFloat();
                boolean frozen = in.readBoolean();
                later(() -> {
                    this.game.getDayNightCycle().setTime(time);
                    this.game.getDayNightCycle().setSpeed(speed);
                    this.game.getDayNightCycle().setFrozen(frozen);
                });
                break;
            }
            case Protocol.S_SEA: {
                float height = in.readFloat();
                float target = in.readFloat();
                float rate = in.readFloat();
                later(() -> applySea(height, target, rate));
                break;
            }
            case Protocol.S_SEA_LIQUID: {
                String liquidName = in.readUTF();
                later(() -> applySeaLiquid(liquidName));
                break;
            }
            case Protocol.S_WORLD_CHANGE: {
                String newWorld = in.readUTF();
                onWorldChange(newWorld);
                break;
            }
            case Protocol.S_WORLDS: {
                int count = in.readInt();
                if (count < 0 || count > 1024) {
                    throw new IOException("bad world count " + count);
                }
                List<WorldInfo> list = new ArrayList<>(count);
                DoorColor[] colors = DoorColor.values();
                for (int i = 0; i < count; i++) {
                    String worldName = in.readUTF();
                    int doorCount = in.readInt();
                    if (doorCount < 0 || doorCount > colors.length) {
                        throw new IOException("bad door count " + doorCount);
                    }
                    Set<DoorColor> doors = EnumSet.noneOf(DoorColor.class);
                    for (int k = 0; k < doorCount; k++) {
                        int ordinal = in.readInt();
                        if (ordinal >= 0 && ordinal < colors.length) {
                            doors.add(colors[ordinal]);
                        }
                    }
                    list.add(new WorldInfo(worldName, doors));
                }
                this.worlds = Collections.unmodifiableList(list);
                break;
            }
            case Protocol.S_DOORS: {
                int count = in.readInt();
                if (count < 0 || count > 65536) {
                    throw new IOException("bad door count " + count);
                }
                List<DoorInfo> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int x = in.readInt();
                    int y = in.readInt();
                    int z = in.readInt();
                    list.add(new DoorInfo(x, y, z, in.readUTF()));
                }
                this.doors = Collections.unmodifiableList(list);
                break;
            }
            case Protocol.S_TIER: {
                int ordinal = in.readUnsignedByte();
                CommandTier[] tiers = CommandTier.values();
                CommandTier newTier = tiers[Math.min(ordinal, tiers.length - 1)];
                this.tier = newTier;
                later(() -> this.game.getChatLog().notice(newTier == CommandTier.PLAYER
                        ? "You are not an op any more" : "You are an op now"));
                break;
            }
            case Protocol.S_SETTINGS:
                this.allowed = in.readUnsignedByte();
                break;
            case Protocol.S_TELEPORT: {
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                float yaw = in.readFloat(), pitch = in.readFloat();
                later(() -> {
                    Player player = this.game.getPlayer();
                    if (player != null) {
                        player.setPositionAndRotation(x, y, z, yaw, pitch);
                    }
                });
                break;
            }
            case Protocol.S_KICK: {
                String reason = in.readUTF();
                failed(reason);
                break;
            }
            case Protocol.S_KEEPALIVE:
                break;
            default:
                throw new IOException("unknown packet " + type);
        }
    }

    @Override
    public void onClosed(Connection connection, String reason) {
        failed(reason);
    }

    /** the server moved us to another world (a door): what is left of the old one is dropped and the new copy is awaited */
    private void onWorldChange(String newWorld) {
        // from this moment everything that arrives belongs to the new world, so it waits like it does at the first join
        this.state = State.DOWNLOADING;
        this.game.runOnMainThread(() -> {
            if (this.state == State.CLOSED) {
                return;
            }
            this.pending.removeIf(task -> true);
            this.liquidQueue.clear();
            this.lastLiquidTick = 0L;
            this.remotes.clear();
            this.doors = Collections.emptyList();
            if (this.level != null && this.level.getEditListener() == this) {
                this.level.setEditListener(null);
            }
            this.level = null;
            this.game.getSession().beginNetworkWorld();
            WorldGenStatus.set("Going to " + newWorld + "...");
        });
    }

    /** the sea as the server has it: where it is, where it is going and how fast */
    private void applySeaLiquid(String name) {
        if (this.level == null || !this.level.getLiquids().hasOcean()) {
            return;
        }
        for (com.nikohalfkino.rubydung.level.tiles.special.LiquidTile liquid : com.nikohalfkino.rubydung.level.tiles.special.LiquidTile.all()) {
            if (liquid.name.equalsIgnoreCase(name)) {
                if (liquid != this.level.getLiquids().getOceanLiquid()) {
                    this.level.getLiquids().setOceanLiquid(liquid);
                }
                return;
            }
        }
    }

    private void applySea(float height, float target, float rate) {
        if (this.level == null) {
            return;
        }
        LiquidLayer layer = this.level.getLiquids();
        if (!layer.hasOcean()) {
            return;
        }
        if (Math.abs(layer.getSeaHeight() - height) > 0.001F) {
            layer.setSeaHeight(height);
        }
        layer.riseTo(target, rate);
    }

    // download

    private void onWelcome(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != Protocol.VERSION) {
            throw new IOException("protocol version " + version);
        }
        this.welcomeId = in.readInt();
        this.levelWidth = in.readInt();
        this.levelHeight = in.readInt();
        this.levelDepth = in.readInt();
        this.levelType = in.readInt();
        this.levelSeed = in.readLong();
        this.spawnX = in.readDouble();
        this.spawnY = in.readDouble();
        this.spawnZ = in.readDouble();
        this.timeOfDay = in.readFloat();
        this.downloadLength = in.readInt();
        this.assignedName = in.readUTF();
        // the server's name for the world: shown to the player and used by doors. the Level itself keeps a fixed name
        // of its own, a world from a server must never share a save folder name with one of the player's worlds
        this.worldName = in.readUTF();
        this.allowed = in.readUnsignedByte();
        int tierOrdinal = in.readUnsignedByte();
        CommandTier[] tiers = CommandTier.values();
        this.tier = tiers[Math.min(tierOrdinal, tiers.length - 1)];
        this.spawnYaw = in.readFloat();
        this.serverName = in.readUTF();
        this.chatTopic = in.readUTF();
        this.download = new ByteArrayOutputStream(Math.max(1024, this.downloadLength));
        this.state = State.DOWNLOADING;
        WorldGenStatus.set("Downloading the world...");
    }

    private void onLevelChunk(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] chunk = new byte[length];
        in.readFully(chunk);
        this.download.write(chunk);
        int percent = this.downloadLength == 0 ? 100 : (int) (this.download.size() * 100L / this.downloadLength);
        WorldGenStatus.set("Downloading the world... " + percent + "%");
    }

    private void onLevelEnd() {
        byte[] compressed = this.download.toByteArray();
        this.download = null;
        WorldGenStatus.set("Unpacking the world...");

        // building the level takes a while, the network thread must keep reading meanwhile
        Thread builder = new Thread(() -> {
            try {
                LevelGenerator.WorldType[] types = LevelGenerator.WorldType.values();
                LevelGenerator.WorldType type = types[Math.max(0, Math.min(types.length - 1, this.levelType))];
                Level built = NetworkSnapshot.read(compressed, "Multiplayer", this.levelWidth, this.levelHeight,
                        this.levelDepth, type, this.levelSeed);
                built.setPlayerPosition(this.spawnX, this.spawnY, this.spawnZ, this.spawnYaw, 0.0F);
                this.game.runOnMainThread(() -> worldReady(built));
            } catch (IOException | RuntimeException e) {
                e.printStackTrace();
                failed("Could not read the world from the server: " + e.getMessage());
            }
        }, "net-level-build");
        builder.setDaemon(true);
        builder.start();
    }

    /** main thread */
    private void worldReady(Level built) {
        if (this.state == State.CLOSED) {
            return;
        }
        this.level = built;
        this.game.getSession().finishNetworkWorld(built);
        built.setEditListener(this);
        // the cubes and the chess pieces of a joined world are the server's: placing a piece is only asked of it
        built.getCubeManager().setReplica(true);

        // a joined world gets a fresh chat like a loaded one does (RubyDung.loadWorld), named after the server. the
        // name in the chat has to be the one the server gave us or our own lines would not be recognised as ours
        this.game.getChatLog().reset(this.host);
        this.game.getChatLog().setNick(this.assignedName);
        this.game.getDayNightCycle().setTime(this.timeOfDay);
        this.state = State.PLAYING;

        List<Runnable> waiting = new ArrayList<>(this.pending);
        this.pending.clear();
        for (Runnable task : waiting) {
            runGuarded(task);
        }
    }

    /** runs a task on the main thread once the world exists (tasks that arrive earlier wait, in order) */
    private void later(Runnable task) {
        this.game.runOnMainThread(() -> {
            if (this.state == State.PLAYING) {
                runGuarded(task);
            } else if (this.state != State.CLOSED) {
                this.pending.add(task);
            }
        });
    }

    // an exception that got out of a main thread task would end the game loop, one bad packet must not do that
    private static void runGuarded(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void failed(String reason) {
        this.game.runOnMainThread(() -> {
            if (this.closedHandled) {
                return;
            }
            this.closedHandled = true;
            this.state = State.CLOSED;
            if (this.connection != null) {
                this.connection.close(reason);
            }
            Multiplayer.clientClosed(this.game, this, reason);
        });
    }

    // LevelEditListener: what this player does to the world is asked of the server (and shown at once)

    @Override
    public void tileEdited(int x, int y, int z, int id, int data) {
        sendBlock(x, y, z, id, data);
    }

    @Override
    public void liquidPlaced(int x, int y, int z, int tileId) {
        sendBlock(x, y, z, tileId, 0);
    }

    @Override
    public void liquidChanged(int x, int y, int z, int tileId, int fill) {
        // the server runs the liquids
    }

    @Override
    public void subTilesEdited(int x, int y, int z) {
        if (this.connection == null || this.level == null) {
            return;
        }
        List<SubTiles.Entry> entries = this.level.getSubTiles().cellEntries(x, y, z);
        this.connection.send(Protocol.C_SUBTILES, out -> {
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            Protocol.writeEntries(out, entries);
        });
    }

    private void sendBlock(int x, int y, int z, int id, int data) {
        if (this.connection == null || this.state != State.PLAYING) {
            return;
        }
        this.connection.send(Protocol.C_BLOCK_ACTION, out -> {
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            out.writeInt(id);
            out.writeInt(data);
        });
    }

    public int getPlayerId() {
        return this.welcomeId;
    }

    /** the name this player has on the server (null until the welcome has arrived) */
    public String getAssignedName() {
        return this.assignedName;
    }
}
