package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.entity.CubeEntity;
import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.DoorRegistry;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LevelEditListener;
import com.nikohalfkino.rubydung.level.LiquidLayer;
import com.nikohalfkino.rubydung.level.MultiTileInstance;
import com.nikohalfkino.rubydung.level.NetworkSnapshot;
import com.nikohalfkino.rubydung.level.SubTiles;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile;
import com.nikohalfkino.rubydung.level.tiles.special.DoorColor;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.player.DoorTravel;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.player.PlayerPhysics;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * the authoritative copy of one or more worlds. used two ways: a LAN host runs it inside the game (the host's own
 * Level is the one world, the host plays on it and tick() is called from the game loop), a dedicated server runs it
 * headless (ServerMain) and may host several worlds that dimensional doors lead between.
 *
 * every world is a "slot": its level, its clock, the liquid and sea changes waiting to be sent. a player is in exactly
 * one slot and only hears about what happens there. the first slot is the main one: the host plays in it and new
 * players start in it.
 *
 * THREADING: sockets live on their own threads, but every packet is queued and handled inside tick(), so the levels
 * are only ever touched by the one thread that calls tick() (and edits the levels)
 */
public final class GameServer implements Connection.Listener {

    private static final int INBOX_LIMIT = 8192;
    private static final long TIME_SYNC_NANOS = 1_000_000_000L;
    private static final long SEA_RESYNC_NANOS = 100_000_000L;
    private static final double DOOR_COMMAND_REACH = 6.0D;
    private static final long DOORS_MIN_NANOS = 500_000_000L;
    private static final long CHAT_MIN_GAP_NANOS = 200_000_000L;
    // breaking blocks marks the world list dirty too (a door may have gone), it is rebuilt at most this often
    private static final long WORLD_LIST_MIN_NANOS = 1_000_000_000L;
    private static final int LIQUIDS_PER_PACKET = 4096;
    private static final int MAX_FAILED_PASSWORDS = 5;

    /** whoever runs a command: a connected player, the LAN host or the console of a dedicated server */
    public interface Actor {
        String name();

        CommandTier tier();
    }

    /** one connected player */
    /** told about every chat line of a player (sender, text, voice profile or "") so the host's own game can show it */
    public interface ChatSink {
        void said(String sender, String text, String voice);
    }

    private static final class Session implements Actor {
        final int id;
        final Connection connection;
        final String ip;
        String name = "";
        WorldSlot slot;
        // joined = in the world: gets the broadcasts. set when the world copy for this player starts, the download itself finishes later
        boolean joined = false;
        // where the player appears, set when they go through a door (null = the world's own spawn point)
        double[] arrival = null;
        double spawnX, spawnY, spawnZ;
        float spawnYaw;

        boolean hasState = false;
        boolean dirty = false;
        double x, y, z;
        float yaw, pitch, bodyYaw;
        int flags;

        // how fast the player is moving, worked out from the positions they report (blocks/s): it is what pushes a cube
        double velX, velY, velZ;
        long lastStateNanos = 0L;

        long actionWindowStart = 0L;
        int actionsInWindow = 0;

        CommandTier tier = CommandTier.PLAYER;

        // typing a chat message (told to the others, see onTyping)
        boolean typing = false;
        long lastChatNanos = 0L;

        Session(int id, Connection connection) {
            this.id = id;
            this.connection = connection;
            this.ip = connection.remoteIp();
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public CommandTier tier() {
            return this.tier;
        }
    }

    /** one world: its level and clock, and what has changed in it since the last broadcast */
    private final class WorldSlot implements LevelEditListener {
        final String name;
        final Level level;
        final DayNightCycle clock;
        final Map<Long, int[]> pendingLiquids = new LinkedHashMap<>();
        float clockCarry = 0.0F;
        long lastTimeSync = 0L;

        // what the players were last told about the sea
        float seaHeight = Float.NaN, seaTarget = Float.NaN, seaRate = Float.NaN;
        long lastSeaPush = 0L;

        // where the coloured doors of this world lead (packed door origin -> world name), set with .door
        final Map<Long, String> doorTargets;
        boolean doorsDirty = false;
        long lastDoorPush = 0L;

        // weighted cubes: the ids the players have been told about (to tell them when one is gone), and the time not yet stepped (dedicated server)
        final Set<Integer> knownCubes = new HashSet<>();
        float cubeCarry = 0.0F;

        WorldSlot(String name, Level level, DayNightCycle clock) {
            this.name = name;
            this.level = level;
            this.clock = clock;
            this.doorTargets = DoorTargets.load(name);
            // the other players are solid for the cubes and push them
            level.getCubeManager().setBodyProvider(() -> bodiesOf(this));
            // a thrown dice that has come to rest: "<name> rolled a <n>!" for everybody in this world
            level.getCubeManager().setRollListener((dice, value, holderId) -> diceRolled(this, holderId, value));
        }

        @Override
        public void tileEdited(int x, int y, int z, int id, int data) {
            sendAll(this, Connection.frame(Protocol.S_BLOCK_CHANGE, out -> writeBlock(out, x, y, z, id, data)));
            // a door placed or (id 0) removed changes which colours this world has, the players' door screens show them
            if (id == 0 || Tile.byId(id) instanceof DimensionalDoorTile) {
                worldListDirty = true;
                this.doorsDirty = true;
            }
        }

        @Override
        public void liquidPlaced(int x, int y, int z, int tileId) {
            // the liquid layer reports the cell itself (liquidChanged)
        }

        @Override
        public void liquidChanged(int x, int y, int z, int tileId, int fill) {
            long key = ((long) y << 40) | ((long) z << 20) | x;
            this.pendingLiquids.put(key, new int[]{x, y, z, tileId, fill});
        }

        @Override
        public void subTilesEdited(int x, int y, int z) {
            List<SubTiles.Entry> entries = this.level.getSubTiles().cellEntries(x, y, z);
            sendAll(this, Connection.frame(Protocol.S_SUBTILES, out -> {
                out.writeInt(x);
                out.writeInt(y);
                out.writeInt(z);
                Protocol.writeEntries(out, entries);
            }));
        }
    }

    private final int port;
    private final ServerSettings settings;
    private final Consumer<String> log;
    // what a player said, for the host's own chat (sender, text); null on a dedicated server, which just logs it
    private final ChatSink chatSink;

    // LAN host: its name, its player and the registry that draws the others. all null on a dedicated server
    private final String hostName;
    private final Player hostPlayer;
    private final RemotePlayers mirror;

    private final Map<String, WorldSlot> slots = new LinkedHashMap<>();
    private final WorldSlot mainSlot;

    // dedicated server: nobody else steps the liquids and the clocks
    private boolean ownsSimulation = false;

    // set by whoever owns the server, what the host commands save and stop do
    private Runnable saveAction;
    private Runnable stopAction;

    private final Queue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Session> sessions = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, Integer> failedPasswords = new HashMap<>();

    // players waiting for their copy of a world, one is copied at a time (a copy of a big world is hundreds of MB)
    private final Deque<Session> joinQueue = new ArrayDeque<>();
    private Session capturingFor;
    private WorldSlot capturingSlot;
    private NetworkSnapshot.Capture capture;
    // compressing and sending a copy runs here, never on the tick thread
    private ExecutorService encoder;
    private final AtomicInteger encoding = new AtomicInteger(0);

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    private long lastKeepalive = 0L;
    private long lastTick = 0L;

    // the list of worlds and their doors changed (a door was placed or removed), the players are sent it again
    private boolean worldListDirty = false;
    private long lastWorldListPush = 0L;

    // what the host looked like when the others were last told
    private double sentHostX, sentHostY, sentHostZ;
    private float sentHostYaw, sentHostPitch, sentHostBody;
    private int sentHostFlags;
    private boolean hostSent = false;

    public GameServer(String worldName, Level level, DayNightCycle clock, ServerSettings settings, int port,
                      String hostName, Player hostPlayer, RemotePlayers mirror, Consumer<String> log,
                      ChatSink chatSink) {
        this.port = port;
        this.settings = settings;
        this.hostName = hostName;
        this.hostPlayer = hostPlayer;
        this.mirror = mirror;
        this.log = log;
        this.chatSink = chatSink;
        this.mainSlot = new WorldSlot(worldName, level, clock);
        this.slots.put(key(worldName), this.mainSlot);
    }

    /** a dedicated server steps the liquids and the clocks itself */
    public void setOwnsSimulation(boolean ownsSimulation) {
        this.ownsSimulation = ownsSimulation;
    }

    public void setSaveAction(Runnable saveAction) {
        this.saveAction = saveAction;
    }

    public void setStopAction(Runnable stopAction) {
        this.stopAction = stopAction;
    }

    /** another world players can go to through a door. before start() only, and never on a LAN server */
    public void addWorld(String name, Level level, DayNightCycle clock) {
        if (this.running || this.hostPlayer != null) {
            throw new IllegalStateException("worlds are added to a dedicated server before it starts");
        }
        this.slots.put(key(name), new WorldSlot(name, level, clock));
    }

    /** makes (or loads) the level of a new world for a running dedicated server, and remembers it for saving */
    public interface WorldCreator {
        Level create(String name, com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldType type,
                     com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldSize size, Long seed, DayNightCycle clock);
    }

    private WorldCreator worldCreator;

    public void setWorldCreator(WorldCreator creator) {
        this.worldCreator = creator;
    }

    public boolean canCreateWorlds() {
        return this.worldCreator != null && this.running && this.hostPlayer == null;
    }

    /** adds a world while the server runs (tick thread). generating a big one pauses the server until it is done. returns an error, or null */
    public String createWorld(String rawName, com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldType type,
                              com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldSize size, Long seed) {
        if (!canCreateWorlds()) {
            return "Worlds can only be created on a running dedicated server";
        }
        String name = com.nikohalfkino.rubydung.level.WorldManager.sanitize(rawName);
        if (name.isEmpty() || this.slots.containsKey(key(name))) {
            return "There is already a world called " + name;
        }
        DayNightCycle clock = new DayNightCycle();
        Level level = this.worldCreator.create(name, type, size, seed, clock);
        WorldSlot slot = new WorldSlot(name, level, clock);
        this.slots.put(key(name), slot);
        level.setEditListener(slot);
        // the second world is what turns doors on: players already in the game were welcomed with the doors flag off and
        // would never get the door screen, so everybody is told what is allowed now (this also sends the world list)
        settingsChanged();
        return null;
    }

    public int getPort() {
        return this.port;
    }

    public boolean isRunning() {
        return this.running;
    }

    public ServerSettings getSettings() {
        return this.settings;
    }

    public Level getLevel() {
        return this.mainSlot.level;
    }

    public List<String> worldNames() {
        List<String> names = new ArrayList<>();
        for (WorldSlot slot : this.slots.values()) {
            names.add(slot.name);
        }
        return names;
    }

    /** every world with the clock that goes with it, for saving */
    public Map<String, Level> levels() {
        Map<String, Level> all = new LinkedHashMap<>();
        for (WorldSlot slot : this.slots.values()) {
            all.put(slot.name, slot.level);
        }
        return all;
    }

    public DayNightCycle clockOf(String worldName) {
        WorldSlot slot = this.slots.get(key(worldName));
        return slot == null ? null : slot.clock;
    }

    /** doors only work on a dedicated server that hosts more than one world, and only if the owner left them on */
    public boolean doorsEnabled() {
        return this.hostPlayer == null && this.slots.size() > 1 && this.settings.isAllowDoors();
    }

    public int playerCount() {
        int count = 0;
        for (Session s : this.sessions.values()) {
            if (s.joined) {
                count++;
            }
        }
        return count;
    }

    public List<String> playerNames() {
        List<String> names = new ArrayList<>();
        if (this.hostName != null) {
            names.add(this.hostName);
        }
        for (Session s : this.sessions.values()) {
            if (s.joined) {
                names.add(s.name);
            }
        }
        return names;
    }

    // lifecycle

    public void start() throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(this.port));
        this.running = true;
        this.lastTick = System.nanoTime();
        for (WorldSlot slot : this.slots.values()) {
            slot.level.setEditListener(slot);
        }
        this.encoder = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "net-snapshot");
            thread.setDaemon(true);
            return thread;
        });

        Thread accept = new Thread(this::acceptLoop, "net-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** on the thread that ticks the server */
    public void stop(String reason) {
        if (!this.running) {
            return;
        }
        this.running = false;
        for (WorldSlot slot : this.slots.values()) {
            if (slot.level.getEditListener() == slot) {
                slot.level.setEditListener(null);
            }
        }
        try {
            this.serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Session s : new ArrayList<>(this.sessions.values())) {
            s.connection.send(Protocol.S_KICK, out -> out.writeUTF(reason));
            s.connection.closeAfterFlush(reason);
        }
        this.sessions.clear();
        this.joinQueue.clear();
        this.capture = null;
        this.capturingFor = null;
        this.capturingSlot = null;
        if (this.encoder != null) {
            this.encoder.shutdownNow();
        }
        if (this.mirror != null) {
            this.mirror.clear();
        }
    }

    private void acceptLoop() {
        while (this.running) {
            try {
                Socket socket = this.serverSocket.accept();
                if (!admit(socket)) {
                    continue;
                }
                int id = this.nextId.getAndIncrement();
                Connection connection;
                try {
                    connection = new Connection(socket, this, "c" + id, Protocol.MAX_CLIENT_FRAME_BYTES);
                } catch (IOException | RuntimeException e) {
                    release(socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress());
                    continue;
                }
                Session session = new Session(id, connection);
                session.slot = this.mainSlot;
                connection.attachment = session;
                this.inbox.add(() -> this.sessions.put(id, session));
                connection.start();
            } catch (IOException e) {
                if (this.running) {
                    this.log.accept("[server] accept failed: " + e.getMessage());
                }
            }
        }
    }

    // open connections, counted on the accept thread and when a link closes, so a flood of sockets (each one costs two
    // threads and, before it says hello, a spot in the queue) is turned away before anything is allocated for it
    private final java.util.concurrent.atomic.AtomicInteger openConnections = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> connectionsPerIp = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean admit(Socket socket) {
        String ip = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
        int limit = Math.max(32, this.settings.getMaxPlayers() * 2 + 8);
        boolean ok = this.openConnections.get() < limit
                && this.connectionsPerIp.getOrDefault(ip, 0) < Protocol.MAX_CONNECTIONS_PER_IP;
        if (!ok) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return false;
        }
        this.openConnections.incrementAndGet();
        this.connectionsPerIp.merge(ip, 1, Integer::sum);
        return true;
    }

    private void release(String ip) {
        this.openConnections.decrementAndGet();
        this.connectionsPerIp.computeIfPresent(ip, (key, count) -> count <= 1 ? null : count - 1);
    }

    // Connection.Listener (reader threads: only queue)

    @Override
    public void onPacket(Connection connection, int type, DataInputStream in) {
        Session session = (Session) connection.attachment;
        this.inbox.add(() -> {
            try {
                handle(session, type, in);
            } catch (IOException | RuntimeException e) {
                this.log.accept("[server] bad packet " + type + " from " + session.name + ": " + e);
                kick(session, "Bad packet");
            }
        });
    }

    @Override
    public void onClosed(Connection connection, String reason) {
        Session session = (Session) connection.attachment;
        release(session.ip);
        this.inbox.add(() -> {
            if (this.sessions.remove(session.id) != null && session.joined) {
                session.joined = false;
                releaseCubesOf(session);
                if (session.typing) {
                    session.typing = false;
                    broadcastTyping(session.name, false, session);
                }
                sendAll(session.slot, Connection.frame(Protocol.S_PLAYER_LEAVE, out -> out.writeInt(session.id)));
                if (this.mirror != null) {
                    this.mirror.remove(session.id);
                }
                announce(session.name + " left the game");
            }
        });
    }

    // tick

    /** call about 20 to 60 times a second from the thread that owns the levels */
    public void tick() {
        if (!this.running) {
            return;
        }

        for (int i = 0; i < INBOX_LIMIT; i++) {
            Runnable task = this.inbox.poll();
            if (task == null) {
                break;
            }
            task.run();
        }

        advanceJoins();

        long now = System.nanoTime();
        float seconds = Math.min((now - this.lastTick) * 1.0E-9F, 0.25F);
        this.lastTick = now;

        for (WorldSlot slot : this.slots.values()) {
            if (this.ownsSimulation) {
                slot.level.getLiquids().update(seconds);
                slot.clockCarry += seconds * 60.0F;
                while (slot.clockCarry >= 1.0F) {
                    slot.clock.tick(0.0F);
                    slot.clockCarry -= 1.0F;
                }
            }

            // a LAN host's own game steps its cubes with its player, a dedicated server steps them here
            if (this.ownsSimulation) {
                stepCubes(slot, seconds);
            }
            syncCubes(slot);

            // every tick, not every few: liquids reach the players as they flow instead of in lumps
            flushLiquids(slot);
            syncSea(slot, now);

            if (slot.doorsDirty && now - slot.lastDoorPush >= DOORS_MIN_NANOS) {
                slot.doorsDirty = false;
                slot.lastDoorPush = now;
                sendAll(slot, doorsFrame(slot));
            }

            if (now - slot.lastTimeSync >= TIME_SYNC_NANOS) {
                slot.lastTimeSync = now;
                pushTime(slot);
            }
        }

        broadcastMoves();

        if (this.worldListDirty && now - this.lastWorldListPush >= WORLD_LIST_MIN_NANOS) {
            this.worldListDirty = false;
            this.lastWorldListPush = now;
            byte[] frame = worldListFrame();
            for (Session s : this.sessions.values()) {
                if (s.joined) {
                    s.connection.sendFrame(frame);
                }
            }
        }

        if (now - this.lastKeepalive >= Protocol.KEEPALIVE_MS * 1_000_000L) {
            this.lastKeepalive = now;
            for (Session s : this.sessions.values()) {
                s.connection.send(Protocol.S_KEEPALIVE, out -> {
                });
            }
        }
    }

    // packets (all on the tick thread)

    private void handle(Session s, int type, DataInputStream in) throws IOException {
        if (type == Protocol.C_HELLO) {
            if (!s.joined && s.name.isEmpty()) {
                onHello(s, in);
            }
            return;
        }
        if (!s.joined) {
            return;
        }

        switch (type) {
            case Protocol.C_STATE:
                onState(s, in);
                break;
            case Protocol.C_BLOCK_ACTION:
                onBlockAction(s, in);
                break;
            case Protocol.C_SUBTILES:
                onSubTiles(s, in);
                break;
            case Protocol.C_CHAT:
                onChat(s, in);
                break;
            case Protocol.C_COMMAND:
                onCommand(s, in);
                break;
            case Protocol.C_TYPING:
                onTyping(s, in);
                break;
            case Protocol.C_CHESS_MOVE:
                onChessMove(s, in);
                break;
            case Protocol.C_DOOR:
                onDoor(s, in);
                break;
            case Protocol.C_CUBE_SPAWN:
                onCubeSpawn(s, in);
                break;
            case Protocol.C_CUBE_PICKUP:
                onCubePickup(s, in);
                break;
            case Protocol.C_CUBE_HOLD:
                onCubeHold(s, in);
                break;
            case Protocol.C_CUBE_RELEASE:
                onCubeRelease(s, in);
                break;
            case Protocol.C_CUBE_REMOVE:
                onCubeRemove(s, in);
                break;
            case Protocol.C_KEEPALIVE:
                break;
            default:
                throw new IOException("unknown packet " + type);
        }
    }

    private void onHello(Session s, DataInputStream in) throws IOException {
        int version = in.readInt();
        String name = Protocol.sanitizeName(in.readUTF());
        String password = in.readUTF();

        if (version != Protocol.VERSION) {
            kick(s, "Version mismatch (server " + Protocol.VERSION + ", you " + version + ")");
            return;
        }

        // bans and the whitelist go first so that they do not tell a banned player whether the password was right
        String refusal = this.settings.refusal(name, s.ip);
        if (refusal != null) {
            kick(s, refusal);
            return;
        }

        if (this.settings.hasPassword() && !this.settings.passwordMatches(password)) {
            int failures = this.failedPasswords.merge(s.ip, 1, Integer::sum);
            this.log.accept("[server] wrong password from " + s.ip + " as " + name + " (" + failures + ")");
            kick(s, failures >= MAX_FAILED_PASSWORDS ? "Too many wrong passwords" : "Wrong password");
            return;
        }
        this.failedPasswords.remove(s.ip);

        if (occupancy() + (this.hostName != null ? 1 : 0) >= this.settings.getMaxPlayers()) {
            kick(s, "The server is full");
            return;
        }

        // the name is reserved now, the world follows when it is this player's turn (advanceJoins)
        s.name = uniqueName(name);
        s.tier = this.settings.isOp(s.name) ? CommandTier.OP : CommandTier.PLAYER;
        this.joinQueue.add(s);
    }

    // joining: the tick thread only copies the world a few MB per tick, the compressing and sending happen elsewhere

    /** called every tick: starts the next waiting player's copy, or goes on with the one under way */
    private void advanceJoins() {
        if (this.capture != null) {
            if (this.capturingFor.connection.isClosed()) {
                // gave up while waiting: nothing to send, the rest of the copy is not needed
                this.capture = null;
                this.capturingFor = null;
                this.capturingSlot = null;
            } else if (this.capture.copySlice(NetworkSnapshot.DEFAULT_SLICE)) {
                finishCapture();
            }
            return;
        }

        // one copy being compressed at a time as well, two big copies in memory is too much for a big world
        if (this.encoding.get() > 0) {
            return;
        }

        Session next;
        while ((next = this.joinQueue.poll()) != null) {
            if (!next.connection.isClosed() && this.sessions.containsKey(next.id)) {
                beginCapture(next);
                return;
            }
        }
    }

    private void beginCapture(Session s) {
        WorldSlot slot = s.slot;
        double[] spawn = s.arrival != null ? s.arrival : spawnPoint(slot);
        s.arrival = null;
        s.spawnX = spawn[0];
        s.spawnY = spawn[1];
        s.spawnZ = spawn[2];
        s.spawnYaw = spawn.length > 3 ? (float) spawn[3] : 0.0F;
        s.x = spawn[0];
        s.y = spawn[1];
        s.z = spawn[2];
        s.hasState = false;

        // from now on the player is sent everything that changes, the client applies it over the world once it has it
        s.joined = true;
        this.capturingFor = s;
        this.capturingSlot = slot;
        this.capture = NetworkSnapshot.begin(slot.level);

        introduce(s, spawn);
        announce(s.name + " joined " + (this.slots.size() > 1 ? slot.name : "the game"));

        // a small world is done in the first slice
        if (this.capture.copySlice(NetworkSnapshot.DEFAULT_SLICE)) {
            finishCapture();
        }
    }

    private void finishCapture() {
        NetworkSnapshot.Capture done = this.capture;
        Session s = this.capturingFor;
        WorldSlot slot = this.capturingSlot;
        this.capture = null;
        this.capturingFor = null;
        this.capturingSlot = null;

        // everything the worker needs is read here, it never touches the level
        final int width = slot.level.width;
        final int height = slot.level.height;
        final int depth = slot.level.depth;
        final int type = slot.level.getWorldType().ordinal();
        final long seed = slot.level.getSeed();
        final float time = slot.clock.getTime();
        final String worldName = slot.name;
        final int welcomeFlags = welcomeFlags();
        final int tier = s.tier.ordinal();
        final String serverName = this.settings.getServerName();
        final String topic = this.settings.topicFor(slot.name);

        this.encoding.incrementAndGet();
        try {
            this.encoder.execute(() -> {
                try {
                    byte[] snapshot = NetworkSnapshot.encode(done);
                    if (s.connection.isClosed()) {
                        return;
                    }
                    sendWorld(s, snapshot, width, height, depth, type, seed, time, worldName, welcomeFlags, tier, serverName, topic);
                    // what the copy cannot tell the player (the sea's movement, the clock's speed, the other worlds) is
                    // read when it is queued on the tick thread, so it is current and arrives after the end of the copy
                    this.inbox.add(() -> afterWorld(s, slot));
                } catch (IOException | RuntimeException e) {
                    // the log is the host's chat, which only the tick thread may touch
                    String message = "[server] could not send the world to " + s.name + ": " + e;
                    this.inbox.add(() -> this.log.accept(message));
                    kick(s, "The server could not send you the world");
                } finally {
                    this.encoding.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            // the executor is gone: the server was stopped while this was being copied
            this.encoding.decrementAndGet();
        }
    }

    private int welcomeFlags() {
        int flags = 0;
        if (doorsEnabled()) {
            flags |= Protocol.WELCOME_DOORS;
        }
        if (this.settings.isAllowLinks()) {
            flags |= Protocol.WELCOME_LINKS;
        }
        if (this.settings.isAllowEmbeds()) {
            flags |= Protocol.WELCOME_EMBEDS;
        }
        return flags;
    }

    /** worker thread: connection.send only queues, so it is safe from anywhere */
    private void sendWorld(Session s, byte[] snapshot, int width, int height, int depth, int type, long seed, float time,
                           String worldName, int flags, int tier, String serverName, String topic) {
        s.connection.send(Protocol.S_WELCOME, out -> {
            out.writeInt(Protocol.VERSION);
            out.writeInt(s.id);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(depth);
            out.writeInt(type);
            out.writeLong(seed);
            out.writeDouble(s.spawnX);
            out.writeDouble(s.spawnY);
            out.writeDouble(s.spawnZ);
            out.writeFloat(time);
            out.writeInt(snapshot.length);
            out.writeUTF(s.name);
            out.writeUTF(worldName);
            out.writeByte(flags);
            out.writeByte(tier);
            out.writeFloat(s.spawnYaw);
            out.writeUTF(serverName);
            out.writeUTF(topic);
        });
        for (int offset = 0; offset < snapshot.length; offset += Protocol.LEVEL_CHUNK_BYTES) {
            int from = offset;
            int length = Math.min(Protocol.LEVEL_CHUNK_BYTES, snapshot.length - offset);
            s.connection.send(Protocol.S_LEVEL_CHUNK, out -> {
                out.writeInt(length);
                out.write(snapshot, from, length);
            });
        }
        s.connection.send(Protocol.S_LEVEL_END, out -> {
        });
    }

    /** tick thread, right after the copy of a world has been sent to a player */
    private void afterWorld(Session s, WorldSlot slot) {
        if (s.connection.isClosed() || s.slot != slot) {
            return;
        }
        s.connection.sendFrame(timeFrame(slot));
        s.connection.sendFrame(worldListFrame());
        s.connection.sendFrame(doorsFrame(slot));
        s.connection.sendFrame(allCubesFrame(slot));
        if (slot.level.getLiquids().hasOcean()) {
            s.connection.sendFrame(seaFrame(slot.level.getLiquids()));
            s.connection.sendFrame(seaLiquidFrame(slot.level.getLiquids()));
        }
    }

    /** the newcomer learns who is here, the others learn about the newcomer */
    private void introduce(Session s, double[] spawn) {
        WorldSlot slot = s.slot;
        if (this.hostPlayer != null && slot == this.mainSlot) {
            double hx = this.hostPlayer.x, hy = this.hostPlayer.y, hz = this.hostPlayer.z;
            s.connection.send(Protocol.S_PLAYER_JOIN, out -> {
                out.writeInt(Protocol.HOST_ID);
                out.writeUTF(this.hostName);
                out.writeDouble(hx);
                out.writeDouble(hy);
                out.writeDouble(hz);
            });
        }
        for (Session other : this.sessions.values()) {
            if (other != s && other.joined && other.slot == slot) {
                s.connection.send(Protocol.S_PLAYER_JOIN, out -> {
                    out.writeInt(other.id);
                    out.writeUTF(other.name);
                    out.writeDouble(other.x);
                    out.writeDouble(other.y);
                    out.writeDouble(other.z);
                });
            }
        }
        byte[] joinFrame = Connection.frame(Protocol.S_PLAYER_JOIN, out -> {
            out.writeInt(s.id);
            out.writeUTF(s.name);
            out.writeDouble(spawn[0]);
            out.writeDouble(spawn[1]);
            out.writeDouble(spawn[2]);
        });
        for (Session other : this.sessions.values()) {
            if (other != s && other.joined && other.slot == slot) {
                other.connection.sendFrame(joinFrame);
            }
        }
        if (this.mirror != null && slot == this.mainSlot) {
            this.mirror.add(new RemotePlayer(s.id, s.name, spawn[0], spawn[1], spawn[2]));
        }
    }

    private void onState(Session s, DataInputStream in) throws IOException {
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        float yaw = in.readFloat();
        float pitch = in.readFloat();
        float bodyYaw = in.readFloat();
        int flags = in.readUnsignedByte();

        if (!finite(x) || !finite(y) || !finite(z)) {
            return;
        }
        Level level = s.slot.level;

        long nowNanos = System.nanoTime();
        if (s.hasState) {
            double dt = (nowNanos - s.lastStateNanos) * 1.0E-9D;
            if (dt > 0.005D && dt < 0.5D) {
                double k = 0.5D;
                s.velX += ((x - s.x) / dt - s.velX) * k;
                s.velY += ((y - s.y) / dt - s.velY) * k;
                s.velZ += ((z - s.z) / dt - s.velZ) * k;
            }
        } else {
            s.velX = s.velY = s.velZ = 0.0D;
        }
        s.lastStateNanos = nowNanos;

        s.x = clamp(x, -64.0D, level.width + 64.0D);
        s.y = clamp(y, -64.0D, level.depth + 256.0D);
        s.z = clamp(z, -64.0D, level.height + 64.0D);
        s.yaw = yaw;
        s.pitch = pitch;
        s.bodyYaw = bodyYaw;
        s.flags = flags;
        s.hasState = true;
        s.dirty = true;

        if (this.mirror != null && s.slot == this.mainSlot) {
            RemotePlayer p = this.mirror.get(s.id);
            if (p != null) {
                p.setState(s.x, s.y, s.z, yaw, pitch, bodyYaw, flags);
            }
        }
    }

    private void onBlockAction(Session s, DataInputStream in) throws IOException {
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        int id = in.readInt() & 0xFF;
        int data = in.readInt() & 0xFF;
        Level level = s.slot.level;

        if (!allowAction(s) || !inReach(s, x, y, z) || Tile.byId(id) == null) {
            sendCorrection(s, x, y, z);
            return;
        }

        // nobody may build inside another player (the sender's own client checks its own body)
        if (id != 0 && placesInsideSomeone(s, x, y, z, id, data)) {
            sendCorrection(s, x, y, z);
            return;
        }

        // the level reports the change to the slot, which tells everyone in the world (the sender included)
        int before = level.getTile(x, y, z);
        level.setTile(x, y, z, id, data);

        // the LAN host is a player too and should hear what the others do (a dedicated server has no one to hear)
        if (s.slot == this.mainSlot) {
            // a chess piece is not a block in the cell, but it is heard like one
            int after = Tile.byId(id) instanceof ChessPieceTile && level.getCubeManager().pieceAt(x, y, z) != null
                    ? id : level.getTile(x, y, z);
            RemoteSounds.blockChanged(this.hostPlayer, x, y, z, before, after);
        }

        if (!(Tile.byId(id) instanceof LiquidTile)
                && (level.getTile(x, y, z) != id || level.getData(x, y, z) != data)) {
            // what the sender did could not happen here, put its copy right
            sendCorrection(s, x, y, z);
        }
    }

    // ---------------------------------------------------------------- weighted cubes
    // the server owns them: it moves the loose ones (a dedicated server here, a LAN host's own game with its player) and tells the players what changed. a player carrying one says where it should be, the server pulls it there like it does for a local hold

    private static int holderOf(CubeEntity cube) {
        if (cube.heldBy >= 1) {
            return cube.heldBy;
        }
        return cube.held ? Protocol.HOST_ID : -1;
    }

    /** the other players as bodies for the cubes of this world (the LAN host is the one stepping them, so it is not among them) */
    private List<CubeManager.Body> bodiesOf(WorldSlot slot) {
        List<CubeManager.Body> list = new ArrayList<>();
        for (Session s : this.sessions.values()) {
            if (!s.joined || s.slot != slot || !s.hasState || (s.flags & Protocol.FLAG_NOCLIP) != 0) {
                continue;
            }
            double feet = s.y - Player.EYE_HEIGHT;
            double half = PlayerPhysics.HALF_WIDTH;
            list.add(new CubeManager.Body(new com.nikohalfkino.rubydung.phys.AABB(s.x - half, feet, s.z - half,
                    s.x + half, feet + PlayerPhysics.HEIGHT, s.z + half), s.velX, s.velZ));
        }
        return list;
    }

    // dedicated server: the cubes' physics runs at 60 steps a second whatever the tick rate of the server is
    private void stepCubes(WorldSlot slot, float seconds) {
        CubeManager cubes = slot.level.getCubeManager();
        if (cubes.count() == 0) {
            slot.cubeCarry = 0.0F;
            return;
        }
        slot.cubeCarry = Math.min(slot.cubeCarry + seconds * 60.0F, 5.0F);
        while (slot.cubeCarry >= 1.0F) {
            cubes.tick(null);
            slot.cubeCarry -= 1.0F;
        }
    }

    // tells the players about the cubes that changed since the last tick, and about the ones that are gone
    private void syncCubes(WorldSlot slot) {
        CubeManager cubes = slot.level.getCubeManager();
        if (cubes.count() == 0 && slot.knownCubes.isEmpty()) {
            return;
        }

        List<CubeEntity> changed = new ArrayList<>();
        for (CubeEntity cube : cubes.all()) {
            slot.knownCubes.add(cube.id);
            if (cube.takeDirty(holderOf(cube))) {
                changed.add(cube);
            }
        }

        for (Iterator<Integer> it = slot.knownCubes.iterator(); it.hasNext(); ) {
            int id = it.next();
            if (cubes.byId(id) == null) {
                it.remove();
                sendAll(slot, Connection.frame(Protocol.S_CUBE_REMOVE, out -> out.writeInt(id)));
            }
        }

        if (!changed.isEmpty()) {
            sendAll(slot, Connection.frame(Protocol.S_CUBES, out -> {
                out.writeByte(0);
                out.writeInt(changed.size());
                for (CubeEntity cube : changed) {
                    Protocol.writeCube(out, cube, holderOf(cube));
                }
            }));
        }
    }

    private byte[] allCubesFrame(WorldSlot slot) {
        List<CubeEntity> all = new ArrayList<>(slot.level.getCubeManager().all());
        return Connection.frame(Protocol.S_CUBES, out -> {
            out.writeByte(1);
            out.writeInt(all.size());
            for (CubeEntity cube : all) {
                Protocol.writeCube(out, cube, holderOf(cube));
            }
        });
    }

    private void releaseCubesOf(Session s) {
        if (s.slot == null) {
            return;
        }
        for (CubeEntity cube : s.slot.level.getCubeManager().all()) {
            if (cube.heldBy == s.id) {
                cube.held = false;
                cube.heldBy = -1;
                cube.ignoreBodies = true;
                cube.ignorePlayer = true;
            }
        }
    }

    private boolean cubeNear(Session s, CubeEntity cube, double max) {
        if (!s.hasState) {
            return true;
        }
        double dx = cube.x - s.x;
        double dy = cube.y - s.y;
        double dz = cube.z - s.z;
        return dx * dx + dy * dy + dz * dz <= max * max;
    }

    private void denyCube(Session s, int id) {
        s.connection.send(Protocol.S_CUBE_DENY, out -> out.writeInt(id));
    }

    private boolean holdsACube(Session s) {
        for (CubeEntity cube : s.slot.level.getCubeManager().all()) {
            if (cube.heldBy == s.id) {
                return true;
            }
        }
        return false;
    }

    private void onCubeSpawn(Session s, DataInputStream in) throws IOException {
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        boolean dice = in.readBoolean();
        if (!allowAction(s) || !inReach(s, x, y, z)) {
            return;
        }
        // the LAN host's body is checked here, the others' by the manager (its body provider)
        Player host = s.slot == this.mainSlot ? this.hostPlayer : null;
        if (s.slot.level.getCubeManager().spawnIn(x, y, z, host, dice) != null && s.slot == this.mainSlot) {
            RemoteSounds.blockChanged(this.hostPlayer, x, y, z, 0, (dice
                    ? com.nikohalfkino.rubydung.level.tiles.TileRegistry.DICE
                    : com.nikohalfkino.rubydung.level.tiles.TileRegistry.WEIGHTED_CUBE).id);
        }
    }

    private void onCubePickup(Session s, DataInputStream in) throws IOException {
        int id = in.readInt();
        CubeEntity cube = s.slot.level.getCubeManager().byId(id);
        if (!allowAction(s) || cube == null || cube.isPiece() || cube.held || holdsACube(s)
                || !cubeNear(s, cube, Protocol.MAX_CUBE_PICKUP_DISTANCE)) {
            denyCube(s, id);
            return;
        }
        cube.held = true;
        cube.heldBy = s.id;
        cube.ignorePlayer = false;
        cube.ignoreBodies = false;
        cube.onGround = false;
        cube.targetX = cube.x;
        cube.targetY = cube.y;
        cube.targetZ = cube.z;
        cube.followVx = cube.followVy = cube.followVz = 0.0D;
    }

    private void onCubeHold(Session s, DataInputStream in) throws IOException {
        int id = in.readInt();
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        double qw = in.readFloat();
        double qx = in.readFloat();
        double qy = in.readFloat();
        double qz = in.readFloat();

        CubeEntity cube = s.slot.level.getCubeManager().byId(id);
        if (cube == null || cube.heldBy != s.id) {
            denyCube(s, id);
            return;
        }
        if (!finite(x) || !finite(y) || !finite(z) || !finite(qw + qx + qy + qz)) {
            return;
        }

        // a client cannot put the cube farther from itself than a carried one can be
        if (s.hasState) {
            double dx = x - s.x;
            double dy = y - s.y;
            double dz = z - s.z;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > Protocol.MAX_CUBE_HOLD_DISTANCE) {
                double k = Protocol.MAX_CUBE_HOLD_DISTANCE / len;
                x = s.x + dx * k;
                y = s.y + dy * k;
                z = s.z + dz * k;
            }
        }
        cube.targetX = x;
        cube.targetY = y;
        cube.targetZ = z;
        cube.followVx = s.velX;
        cube.followVy = s.velY;
        cube.followVz = s.velZ;
        cube.setOrientation(qw, qx, qy, qz);
    }

    private void onCubeRelease(Session s, DataInputStream in) throws IOException {
        int id = in.readInt();
        double vx = in.readFloat();
        double vy = in.readFloat();
        double vz = in.readFloat();
        double avx = in.readFloat();
        double avy = in.readFloat();
        double avz = in.readFloat();

        CubeEntity cube = s.slot.level.getCubeManager().byId(id);
        if (cube == null || cube.heldBy != s.id) {
            return;
        }
        if (!finite(vx + vy + vz + avx + avy + avz)) {
            vx = vy = vz = avx = avy = avz = 0.0D;
        }

        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed > Protocol.MAX_CUBE_SPEED) {
            double k = Protocol.MAX_CUBE_SPEED / speed;
            vx *= k;
            vy *= k;
            vz *= k;
        }

        cube.held = false;
        cube.heldBy = -1;
        // not an obstacle for whoever it was carried through until it has cleared them
        cube.ignorePlayer = true;
        cube.ignoreBodies = true;
        cube.vx = vx;
        cube.vy = vy;
        cube.vz = vz;
        cube.avx = clamp(avx, -Protocol.MAX_CUBE_SPIN, Protocol.MAX_CUBE_SPIN);
        cube.avy = clamp(avy, -Protocol.MAX_CUBE_SPIN, Protocol.MAX_CUBE_SPIN);
        cube.avz = clamp(avz, -Protocol.MAX_CUBE_SPIN, Protocol.MAX_CUBE_SPIN);
    }

    // a player sends a chess piece to a square: it goes if that is one of the moves of the piece (the pieces are not tied to a side, anybody may move any)
    private void onChessMove(Session s, DataInputStream in) throws IOException {
        int id = in.readInt();
        int toX = in.readInt();
        int toZ = in.readInt();

        Level level = s.slot.level;
        CubeEntity piece = level.getCubeManager().byId(id);
        ChessPieceTile tile = ChessPieceTile.of(piece);
        if (!allowAction(s) || tile == null || piece.gliding || !cubeNear(s, piece, Protocol.MAX_REACH)) {
            return;
        }

        for (ChessPieceTile.Move move : tile.getMoves(level, piece.pieceCellX(), piece.pieceCellY(), piece.pieceCellZ())) {
            if (move.x == toX && move.z == toZ) {
                level.getCubeManager().movePiece(piece, toX, toZ);
                return;
            }
        }
    }

    private void onCubeRemove(Session s, DataInputStream in) throws IOException {
        int id = in.readInt();
        CubeManager cubes = s.slot.level.getCubeManager();
        CubeEntity cube = cubes.byId(id);
        if (!allowAction(s) || cube == null || cube.held || !cubeNear(s, cube, Protocol.MAX_CUBE_PICKUP_DISTANCE)) {
            return;
        }
        int tileId = cube.isPiece() ? ChessPieceTile.of(cube).id
                : (cube.isDice() ? com.nikohalfkino.rubydung.level.tiles.TileRegistry.DICE
                : com.nikohalfkino.rubydung.level.tiles.TileRegistry.WEIGHTED_CUBE).id;
        int cellX = (int) Math.floor(cube.x);
        int cellY = cube.isPiece() ? cube.pieceCellY() : (int) Math.floor(cube.y);
        int cellZ = (int) Math.floor(cube.z);
        cubes.remove(cube);
        if (s.slot == this.mainSlot) {
            RemoteSounds.blockChanged(this.hostPlayer, cellX, cellY, cellZ, tileId, 0);
        }
    }

    // players walking through blocks (noclip) don't count: they can't be trapped in anything
    private static final double BODY_SHRINK = 0.02D;

    /** whether the tile would overlap another player in the same world (the placer's own body is the placer's client's business) */
    private boolean placesInsideSomeone(Session placer, int x, int y, int z, int id, int data) {
        Tile tile = Tile.byId(id);
        if (tile == null) {
            return false;
        }
        java.util.List<com.nikohalfkino.rubydung.phys.AABB> boxes = tile.getCollisionBoxes(x, y, z, data);
        if (boxes.isEmpty() && tile.getAABB(x, y, z) == null) {
            boxes = java.util.Collections.singletonList(new com.nikohalfkino.rubydung.phys.AABB(x, y, z, x + 1, y + 1, z + 1));
        }
        if (boxes.isEmpty()) {
            return false;
        }

        for (Session other : this.sessions.values()) {
            if (other == placer || !other.joined || !other.hasState || other.slot != placer.slot
                    || (other.flags & Protocol.FLAG_NOCLIP) != 0) {
                continue;
            }
            if (overlapsBody(boxes, other.x, other.y, other.z)) {
                return true;
            }
        }
        if (this.hostPlayer != null && placer.slot == this.mainSlot && !this.hostPlayer.noClip
                && overlapsBody(boxes, this.hostPlayer.x, this.hostPlayer.y, this.hostPlayer.z)) {
            return true;
        }
        return false;
    }

    // eyeX/Y/Z is where a player's state puts them (their eyes), the body is the physics box under it
    private static boolean overlapsBody(java.util.List<com.nikohalfkino.rubydung.phys.AABB> boxes, double eyeX, double eyeY, double eyeZ) {
        double feet = eyeY - Player.EYE_HEIGHT;
        com.nikohalfkino.rubydung.phys.AABB body = new com.nikohalfkino.rubydung.phys.AABB(
                eyeX - PlayerPhysics.HALF_WIDTH + BODY_SHRINK, feet + BODY_SHRINK, eyeZ - PlayerPhysics.HALF_WIDTH + BODY_SHRINK,
                eyeX + PlayerPhysics.HALF_WIDTH - BODY_SHRINK, feet + PlayerPhysics.HEIGHT - BODY_SHRINK, eyeZ + PlayerPhysics.HALF_WIDTH - BODY_SHRINK);
        for (com.nikohalfkino.rubydung.phys.AABB box : boxes) {
            if (box.intersects(body)) {
                return true;
            }
        }
        return false;
    }

    private void onSubTiles(Session s, DataInputStream in) throws IOException {
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        List<SubTiles.Entry> entries = Protocol.readEntries(in);

        if (!allowAction(s) || !inReach(s, x, y, z)) {
            sendSubTiles(s, x, y, z);
            return;
        }
        s.slot.level.setSubTilesOfCell(x, y, z, entries);
    }

    private void onChat(Session s, DataInputStream in) throws IOException {
        String text = Protocol.sanitizeChat(in.readUTF());
        String voice = Protocol.sanitizeVoice(in.readUTF());
        if (!this.settings.isAllowLinks()) {
            text = Protocol.stripLinks(text);
        }
        // a chat line is sent to everybody (and spoken aloud by their voices): more than a few a second is spam
        long now = System.nanoTime();
        if (now - s.lastChatNanos < CHAT_MIN_GAP_NANOS) {
            return;
        }
        s.lastChatNanos = now;
        if (!text.isEmpty()) {
            say(s.name, text, voice);
        }
    }

    private void onTyping(Session s, DataInputStream in) throws IOException {
        boolean on = in.readUnsignedByte() != 0;
        if (!s.joined || !allowAction(s)) {
            return;
        }
        // the refresh every few seconds is not a change, but it keeps the others' timers going
        s.typing = on;
        broadcastTyping(s.name, on, s);
    }

    /** the LAN host started or stopped typing a chat message */
    public void hostTyping(boolean on) {
        if (this.hostName != null) {
            broadcastTyping(this.hostName, on, null);
        }
    }

    /** tells everybody but the one it is about (and the host's own game) that somebody is or is not typing */
    private void broadcastTyping(String name, boolean on, Session except) {
        byte[] frame = Connection.frame(Protocol.S_TYPING, out -> {
            out.writeUTF(name);
            out.writeByte(on ? 1 : 0);
        });
        for (Session other : this.sessions.values()) {
            if (other.joined && other != except) {
                other.connection.sendFrame(frame);
            }
        }
        if (except != null && this.typingSink != null) {
            this.typingSink.accept(name, on);
        }
    }

    private java.util.function.BiConsumer<String, Boolean> typingSink;

    /** where the host's own game hears about others typing (called on the tick thread) */
    public void setTypingSink(java.util.function.BiConsumer<String, Boolean> sink) {
        this.typingSink = sink;
    }

    private void onCommand(Session s, DataInputStream in) throws IOException {
        String line = Protocol.sanitizeChat(in.readUTF());
        if (line.startsWith(".")) {
            line = line.substring(1).trim();
        }
        if (line.isEmpty() || !allowAction(s)) {
            return;
        }
        // the tier is decided here from the op list, never from anything the client says
        s.tier = this.settings.isOp(s.name) ? CommandTier.OP : CommandTier.PLAYER;
        for (String reply : ServerCommands.execute(this, s, line)) {
            tell(s, reply);
        }
    }

    // dimensional doors

    private void onDoor(Session s, DataInputStream in) throws IOException {
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        String target = in.readUTF();
        int colorOrdinal = in.readInt();

        if (!doorsEnabled()) {
            tell(s, "Dimensional doors are turned off on this server");
            return;
        }
        DoorColor[] colors = DoorColor.values();
        if (colorOrdinal < 0 || colorOrdinal >= colors.length) {
            return;
        }
        DoorColor picked = colors[colorOrdinal];

        // the door has to be there and in reach
        Level from = s.slot.level;
        Tile tile = Tile.byId(from.getTile(x, y, z));
        if (!(tile instanceof DimensionalDoorTile) || !inReach(s, x, y, z)) {
            return;
        }
        DoorColor doorColor = ((DimensionalDoorTile) tile).getColor();

        if (doorColor != DoorColor.REGULAR) {
            // a coloured door has no menu: it leads where the server's owner pointed it, whatever the client asked for
            picked = doorColor;
            MultiTileInstance origin = from.getMultiTile(x, y, z);
            long key = origin != null ? DoorTargets.pack(origin.x, origin.y, origin.z) : DoorTargets.pack(x, y, z);
            target = s.slot.doorTargets.get(key);
            if (target == null) {
                tell(s, "This door does not lead anywhere yet");
                return;
            }
        }

        WorldSlot to = this.slots.get(key(target));
        if (to == null) {
            tell(s, "This door leads to " + target + ", which this server does not have");
            return;
        }
        if (to == s.slot) {
            return;
        }
        double[] arrival = DoorTravel.findArrival(to.level, picked);
        if (arrival == null) {
            tell(s, "There is no matching door in " + to.name);
            return;
        }

        moveToWorld(s, to, arrival);
    }

    // door destinations (the owner's .door command)

    /** the doors of a world a command can point somewhere: every coloured one */
    private static List<DoorRegistry.Entry> colouredDoors(WorldSlot slot) {
        List<DoorRegistry.Entry> doors = new ArrayList<>();
        for (DoorRegistry.Entry door : slot.level.getDoors()) {
            if (door.color != DoorColor.REGULAR) {
                doors.add(door);
            }
        }
        return doors;
    }

    private DoorRegistry.Entry doorAt(WorldSlot slot, int x, int y, int z) {
        for (DoorRegistry.Entry door : colouredDoors(slot)) {
            if (door.x == x && door.y == y && door.z == z) {
                return door;
            }
        }
        return null;
    }

    /** the coloured door nearest to a player (within a few blocks), null if none */
    private DoorRegistry.Entry doorNear(Actor who) {
        double[] at = who.name() == null ? null : positionOf(who.name(), who);
        if (at == null) {
            return null;
        }
        DoorRegistry.Entry best = null;
        double bestDistance = DOOR_COMMAND_REACH * DOOR_COMMAND_REACH;
        for (DoorRegistry.Entry door : colouredDoors(slotOf(who))) {
            double dx = door.x + 0.5 - at[0];
            double dy = door.y + 1.5 - at[1];
            double dz = door.z + 0.5 - at[2];
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestDistance) {
                bestDistance = d;
                best = door;
            }
        }
        return best;
    }

    /**
     * .door set / clear: points a coloured door at a world (null world = clears it). the door is the one at x y z, or (all
     * null) the nearest one to the player. returns what to tell the one who ran it
     */
    String setDoorDestination(Actor who, Integer x, Integer y, Integer z, String world, boolean clear) {
        WorldSlot slot = slotOf(who);
        DoorRegistry.Entry door = x != null ? doorAt(slot, x, y, z) : doorNear(who);
        if (door == null) {
            return x != null ? "There is no coloured door at " + x + " " + y + " " + z
                    : "No coloured door within " + (int) DOOR_COMMAND_REACH + " blocks of you (stand next to one, or give its x y z)";
        }
        long key = DoorTargets.pack(door.x, door.y, door.z);
        String at = door.x + " " + door.y + " " + door.z;
        if (clear) {
            slot.doorTargets.remove(key);
            DoorTargets.save(slot.name, slot.doorTargets);
            slot.doorsDirty = true;
            return "The " + door.color.getDisplayName().toLowerCase(Locale.ROOT) + " door at " + at + " leads nowhere now";
        }
        WorldSlot to = this.slots.get(key(world));
        if (to == null) {
            return "This server has no world called " + world;
        }
        slot.doorTargets.put(key, to.name);
        DoorTargets.save(slot.name, slot.doorTargets);
        slot.doorsDirty = true;
        return "The " + door.color.getDisplayName().toLowerCase(Locale.ROOT) + " door at " + at + " now leads to " + to.name;
    }

    /** one line per coloured door of the world the actor is in */
    List<String> describeDoors(Actor who) {
        WorldSlot slot = slotOf(who);
        List<String> lines = new ArrayList<>();
        for (DoorRegistry.Entry door : colouredDoors(slot)) {
            String target = slot.doorTargets.get(DoorTargets.pack(door.x, door.y, door.z));
            lines.add(door.color.getDisplayName() + " door at " + door.x + " " + door.y + " " + door.z + " -> "
                    + (target == null ? "(nowhere)" : target));
        }
        if (lines.isEmpty()) {
            lines.add("There are no coloured doors in " + slot.name);
        }
        return lines;
    }

    /** every coloured door of a world with where it leads ("" = nowhere), for the name tags above them */
    private byte[] doorsFrame(WorldSlot slot) {
        List<DoorRegistry.Entry> doors = doorsEnabled() ? colouredDoors(slot) : new ArrayList<DoorRegistry.Entry>();
        return Connection.frame(Protocol.S_DOORS, out -> {
            out.writeInt(doors.size());
            for (DoorRegistry.Entry door : doors) {
                out.writeInt(door.x);
                out.writeInt(door.y);
                out.writeInt(door.z);
                String target = slot.doorTargets.get(DoorTargets.pack(door.x, door.y, door.z));
                out.writeUTF(target == null ? "" : target);
            }
        });
    }

    /** takes a player out of the world they are in and queues them to get a copy of another one */
    private void moveToWorld(Session s, WorldSlot to, double[] arrival) {
        WorldSlot from = s.slot;
        s.joined = false;
        releaseCubesOf(s);
        sendAll(from, Connection.frame(Protocol.S_PLAYER_LEAVE, out -> out.writeInt(s.id)));
        if (this.mirror != null && from == this.mainSlot) {
            this.mirror.remove(s.id);
        }

        s.slot = to;
        s.arrival = arrival;
        // the first thing the player gets about the new world: from here on everything it is sent belongs to it
        s.connection.send(Protocol.S_WORLD_CHANGE, out -> out.writeUTF(to.name));
        this.joinQueue.add(s);
    }

    private byte[] worldListFrame() {
        if (!doorsEnabled()) {
            return Connection.frame(Protocol.S_WORLDS, out -> out.writeInt(0));
        }
        List<String> names = new ArrayList<>();
        List<Set<DoorColor>> doors = new ArrayList<>();
        for (WorldSlot slot : this.slots.values()) {
            names.add(slot.name);
            Set<DoorColor> colors = EnumSet.noneOf(DoorColor.class);
            for (DoorRegistry.Entry door : slot.level.getDoors()) {
                colors.add(door.color);
            }
            doors.add(colors);
        }
        return Connection.frame(Protocol.S_WORLDS, out -> {
            out.writeInt(names.size());
            for (int i = 0; i < names.size(); i++) {
                out.writeUTF(names.get(i));
                out.writeInt(doors.get(i).size());
                for (DoorColor color : doors.get(i)) {
                    out.writeInt(color.ordinal());
                }
            }
        });
    }

    // helpers

    private boolean allowAction(Session s) {
        long now = System.nanoTime();
        if (now - s.actionWindowStart > 1_000_000_000L) {
            s.actionWindowStart = now;
            s.actionsInWindow = 0;
        }
        return ++s.actionsInWindow <= Protocol.MAX_ACTIONS_PER_SECOND;
    }

    private boolean inReach(Session s, int x, int y, int z) {
        Level level = s.slot.level;
        if (x < 0 || y < 0 || z < 0 || x >= level.width || y >= level.depth || z >= level.height) {
            return false;
        }
        if (!s.hasState) {
            return true;
        }
        double dx = x + 0.5D - s.x;
        double dy = y + 0.5D - s.y;
        double dz = z + 0.5D - s.z;
        return dx * dx + dy * dy + dz * dz <= Protocol.MAX_REACH * Protocol.MAX_REACH;
    }

    private void sendCorrection(Session s, int x, int y, int z) {
        Level level = s.slot.level;
        if (x < 0 || y < 0 || z < 0 || x >= level.width || y >= level.depth || z >= level.height) {
            return;
        }
        int id = level.getTile(x, y, z);
        int data = level.getData(x, y, z);
        s.connection.send(Protocol.S_BLOCK_CHANGE, out -> writeBlock(out, x, y, z, id, data));
        sendSubTiles(s, x, y, z);
    }

    private void sendSubTiles(Session s, int x, int y, int z) {
        Level level = s.slot.level;
        if (x < 0 || y < 0 || z < 0 || x >= level.width || y >= level.depth || z >= level.height) {
            return;
        }
        List<SubTiles.Entry> entries = level.getSubTiles().cellEntries(x, y, z);
        s.connection.send(Protocol.S_SUBTILES, out -> {
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            Protocol.writeEntries(out, entries);
        });
    }

    private static void writeBlock(java.io.DataOutputStream out, int x, int y, int z, int id, int data) throws IOException {
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeInt(id);
        out.writeInt(data);
    }

    private void kick(Session s, String reason) {
        s.connection.send(Protocol.S_KICK, out -> out.writeUTF(reason));
        s.connection.closeAfterFlush(reason);
    }

    private String uniqueName(String wanted) {
        String name = wanted;
        int suffix = 2;
        while (nameTaken(name)) {
            String tail = Integer.toString(suffix++);
            name = wanted.substring(0, Math.min(wanted.length(), Protocol.MAX_NAME_LENGTH - tail.length())) + tail;
        }
        return name;
    }

    private boolean nameTaken(String name) {
        if (name.equalsIgnoreCase(this.hostName)) {
            return true;
        }
        for (Session s : this.sessions.values()) {
            // a name is taken from the moment it is chosen, a player waiting for the world already owns theirs
            if (!s.name.isEmpty() && s.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** players in the world plus the ones waiting to get in (they all count towards the limit) */
    private int occupancy() {
        int count = 0;
        for (Session s : this.sessions.values()) {
            if (!s.name.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** the host's position if there is a host, else the saved one, else the nearest dry land to the middle of the map */
    private double[] spawnPoint(WorldSlot slot) {
        Level level = slot.level;
        if (this.hostPlayer != null && slot == this.mainSlot) {
            return new double[]{this.hostPlayer.x, this.hostPlayer.y + 0.1D, this.hostPlayer.z};
        }
        if (level.hasSavedPosition) {
            return new double[]{level.savedPlayerX, level.savedPlayerY, level.savedPlayerZ};
        }

        int cx = level.width / 2;
        int cz = level.height / 2;
        for (int radius = 0; radius < 256; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = cx + dx;
                    int z = cz + dz;
                    int top = surface(level, x, z);
                    if (top >= 0 && !level.getLiquids().has(x, top + 1, z)) {
                        return new double[]{x + 0.5D, top + 1 + Player.EYE_HEIGHT + 0.1D, z + 0.5D};
                    }
                }
            }
        }
        return new double[]{cx + 0.5D, level.depth, cz + 0.5D};
    }

    private static int surface(Level level, int x, int z) {
        if (x < 0 || z < 0 || x >= level.width || z >= level.height) {
            return -1;
        }
        for (int y = level.depth - 1; y >= 0; y--) {
            if (level.isSolidTile(x, y, z)) {
                return y;
            }
        }
        return -1;
    }

    private static String key(String worldName) {
        return worldName.toLowerCase(Locale.ROOT);
    }

    // outgoing

    private void sendAll(WorldSlot slot, byte[] frame) {
        for (Session s : this.sessions.values()) {
            if (s.joined && s.slot == slot) {
                s.connection.sendFrame(frame);
            }
        }
    }

    private void sendEverybody(byte[] frame) {
        for (Session s : this.sessions.values()) {
            if (s.joined) {
                s.connection.sendFrame(frame);
            }
        }
    }

    /** a dice came to rest in this world: the line goes to the players there and to the host's chat / the log */
    private void diceRolled(WorldSlot slot, int holderId, int value) {
        Session thrower = holderId == -1 ? null : this.sessions.get(holderId);
        String name = thrower != null ? thrower.name
                : this.hostName != null ? this.hostName : "Server";
        String line = name + " rolled a " + value + "!";
        sendAll(slot, Connection.frame(Protocol.S_CHAT, out -> {
            out.writeUTF("");
            out.writeUTF(line);
            out.writeUTF("");
        }));
        if (slot == this.mainSlot) {
            this.log.accept(line);
        }
    }

    /** a line for everybody: the clients' chat and the host's chat (or the console of a dedicated server) */
    public void announce(String line) {
        sendEverybody(Connection.frame(Protocol.S_CHAT, out -> {
            out.writeUTF("");
            out.writeUTF(line);
            out.writeUTF("");
        }));
        this.log.accept(line);
    }

    /** a line for one player only */
    void tell(Session s, String line) {
        s.connection.send(Protocol.S_CHAT, out -> {
            out.writeUTF("");
            out.writeUTF(line);
            out.writeUTF("");
        });
    }

    /** a player's chat message: everybody gets it as "<sender> text", the host's chat shows it the same way */
    private void say(String sender, String text, String voice) {
        sendEverybody(Connection.frame(Protocol.S_CHAT, out -> {
            out.writeUTF(sender);
            out.writeUTF(text);
            out.writeUTF(voice);
        }));
        if (this.chatSink != null) {
            this.chatSink.said(sender, text, voice);
        } else {
            this.log.accept("<" + sender + "> " + text);
        }
    }

    /** the LAN host said something: everybody else hears it, the host's own chat shows it already */
    public void hostSays(String text, String voice) {
        String clean = Protocol.sanitizeChat(text);
        if (!this.settings.isAllowLinks()) {
            clean = Protocol.stripLinks(clean);
        }
        if (!clean.isEmpty() && this.hostName != null) {
            String line = clean;
            sendEverybody(Connection.frame(Protocol.S_CHAT, out -> {
                out.writeUTF(this.hostName);
                out.writeUTF(line);
                out.writeUTF(Protocol.sanitizeVoice(voice));
            }));
        }
    }

    // used by the commands (ServerCommands), all on the tick thread

    /** the owner of the server: tier HOST. the LAN host, or the console of a dedicated server */
    public Actor hostActor() {
        return new HostActor(this.hostName != null ? this.hostName : "Server");
    }

    private static final class HostActor implements Actor {
        private final String name;

        HostActor(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public CommandTier tier() {
            return CommandTier.HOST;
        }
    }

    public boolean isLan() {
        return this.hostPlayer != null;
    }

    public void requestSave() {
        if (this.saveAction != null) {
            this.saveAction.run();
        }
    }

    public boolean canSave() {
        return this.saveAction != null;
    }

    public void requestStop() {
        if (this.stopAction != null) {
            this.stopAction.run();
        }
    }

    public boolean canStop() {
        return this.stopAction != null;
    }

    /** the world this actor is in (the console is nowhere: the main one) */
    String worldOf(Actor who) {
        return slotOf(who).name;
    }

    Level levelOf(Actor who) {
        return slotOf(who).level;
    }

    DayNightCycle clockOfActor(Actor who) {
        return slotOf(who).clock;
    }

    private WorldSlot slotOf(Actor who) {
        if (who instanceof InWorld) {
            WorldSlot chosen = this.slots.get(key(((InWorld) who).world));
            return chosen != null ? chosen : slotOf(((InWorld) who).base);
        }
        return who instanceof Session ? ((Session) who).slot : this.mainSlot;
    }

    /** a command given "@world": whoever runs it, in that world instead of the one they are in (the console is in none) */
    static final class InWorld implements Actor {
        final Actor base;
        final String world;

        InWorld(Actor base, String world) {
            this.base = base;
            this.world = world;
        }

        @Override
        public String name() {
            return this.base.name();
        }

        @Override
        public CommandTier tier() {
            return this.base.tier();
        }
    }

    /** the name this server knows a world by (any case), null if it has no such world */
    String worldNamed(String name) {
        WorldSlot slot = this.slots.get(key(name));
        return slot == null ? null : slot.name;
    }

    /** position {x, y, z} of a player by name (the LAN host counts), null if nobody here is called that */
    double[] positionOf(String name, Actor asker) {
        WorldSlot askerSlot = slotOf(asker);
        if (this.hostName != null && this.hostName.equalsIgnoreCase(name) && askerSlot == this.mainSlot) {
            return new double[]{this.hostPlayer.x, this.hostPlayer.y, this.hostPlayer.z};
        }
        Session s = find(name);
        if (s == null || s.slot != askerSlot) {
            return null;
        }
        return new double[]{s.x, s.y, s.z};
    }

    /** moves a player, or the LAN host (their own player is right here in this game). false for the console of a dedicated server */
    boolean teleportActor(Actor who, double x, double y, double z) {
        while (who instanceof InWorld) {
            who = ((InWorld) who).base;
        }
        if (who instanceof HostActor) {
            if (this.hostPlayer == null) {
                return false;
            }
            this.hostPlayer.setPositionAndRotation(x, y, z, this.hostPlayer.yRotation, this.hostPlayer.xRotation);
            return true;
        }
        if (!(who instanceof Session)) {
            return false;
        }
        teleportSession((Session) who, x, y, z);
        return true;
    }

    private void teleportSession(Session s, double x, double y, double z) {
        s.connection.send(Protocol.S_TELEPORT, out -> {
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeFloat(s.yaw);
            out.writeFloat(s.pitch);
        });
    }

    /** moves a player (dedicated server console, or a LAN host command) */
    public void teleport(String name, double x, double y, double z) {
        Session s = find(name);
        if (s != null) {
            teleportSession(s, x, y, z);
        }
    }

    Actor findActor(String name) {
        return find(name);
    }

    private Session find(String name) {
        for (Session s : this.sessions.values()) {
            if (s.joined && s.name.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    /** the tier somebody has now: what the op list says (the host is always HOST) */
    CommandTier tierOf(String name) {
        if (this.hostName != null && this.hostName.equalsIgnoreCase(name)) {
            return CommandTier.HOST;
        }
        return this.settings.isOp(name) ? CommandTier.OP : CommandTier.PLAYER;
    }

    /** the op list changed: the player (if here) is told, and what they may do changes with it */
    void opsChanged(String name) {
        Session s = find(name);
        if (s != null) {
            s.tier = this.settings.isOp(s.name) ? CommandTier.OP : CommandTier.PLAYER;
            byte tier = (byte) s.tier.ordinal();
            s.connection.send(Protocol.S_TIER, out -> out.writeByte(tier));
        }
    }

    public boolean kickByName(String name, String reason) {
        Session s = find(name);
        if (s == null) {
            return false;
        }
        kick(s, reason);
        return true;
    }

    /** the address a connected player came from, null if nobody here is called that */
    String ipOf(String name) {
        Session s = find(name);
        return s == null ? null : s.ip;
    }

    /** kicks everybody who connected from this address */
    int kickAddress(String ip, String reason) {
        int kicked = 0;
        for (Session s : this.sessions.values()) {
            if (s.ip.equals(ip)) {
                kick(s, reason);
                kicked++;
            }
        }
        return kicked;
    }

    /** the owner changed what the server allows (links, embeds, doors): everybody in the game is told */
    void settingsChanged() {
        int flags = welcomeFlags();
        sendEverybody(Connection.frame(Protocol.S_SETTINGS, out -> out.writeByte(flags)));
        this.worldListDirty = true;
    }

    /** the clock of a world was changed by a command: its players get the new time at once */
    void pushTime(Actor who) {
        pushTime(slotOf(who));
    }

    private void pushTime(WorldSlot slot) {
        sendAll(slot, timeFrame(slot));
    }

    private byte[] timeFrame(WorldSlot slot) {
        float time = slot.clock.getTime();
        float speed = slot.clock.getSpeed();
        boolean frozen = slot.clock.isFrozen();
        return Connection.frame(Protocol.S_TIME, out -> {
            out.writeFloat(time);
            out.writeFloat(speed);
            out.writeBoolean(frozen);
        });
    }

    /** the sea of a world was changed by a command: its players get it at once */
    void pushSea(Actor who) {
        WorldSlot slot = slotOf(who);
        // forget what the players were told, so the next check sends the sea whatever it looks like
        slot.seaHeight = Float.NaN;
        slot.seaTarget = Float.NaN;
        slot.seaRate = Float.NaN;
        syncSea(slot, System.nanoTime());
    }

    /** the sea's liquid was changed by a command: the players of the world are told what it is made of now */
    void pushSeaLiquid(Actor who) {
        WorldSlot slot = slotOf(who);
        if (slot.level.getLiquids().hasOcean()) {
            sendAll(slot, seaLiquidFrame(slot.level.getLiquids()));
        }
    }

    private static byte[] seaLiquidFrame(LiquidLayer layer) {
        String name = layer.getOceanLiquid().name;
        return Connection.frame(Protocol.S_SEA_LIQUID, out -> out.writeUTF(name));
    }

    private static byte[] seaFrame(LiquidLayer layer) {
        float height = layer.getSeaHeight();
        float target = layer.getSeaTarget();
        float rate = layer.isSeaMoving() ? layer.getSeaRate() : 0.0F;
        return Connection.frame(Protocol.S_SEA, out -> {
            out.writeFloat(height);
            out.writeFloat(target);
            out.writeFloat(rate);
        });
    }

    /**
     * tells a world's players when its sea starts, stops or is moved (riseTo, setSeaHeight). while it is moving the
     * clients move it by themselves, so a resync ten times a second makes them follow the server's height even if their own stepping misses
     */
    private void syncSea(WorldSlot slot, long now) {
        LiquidLayer layer = slot.level.getLiquids();
        if (!layer.hasOcean()) {
            return;
        }
        float height = layer.getSeaHeight();
        float target = layer.getSeaTarget();
        float rate = layer.isSeaMoving() ? layer.getSeaRate() : 0.0F;
        boolean moving = rate > 0.0F;

        boolean changed = target != slot.seaTarget || rate != slot.seaRate || (!moving && height != slot.seaHeight);
        boolean resync = moving && now - slot.lastSeaPush >= SEA_RESYNC_NANOS;
        if (!changed && !resync) {
            return;
        }
        slot.seaHeight = height;
        slot.seaTarget = target;
        slot.seaRate = rate;
        slot.lastSeaPush = now;
        sendAll(slot, seaFrame(layer));
    }

    private void broadcastMoves() {
        boolean hostChanged = false;
        double hx = 0, hy = 0, hz = 0;
        float hyaw = 0, hpitch = 0, hbody = 0;
        int hflags = 0;
        if (this.hostPlayer != null) {
            hx = this.hostPlayer.x;
            hy = this.hostPlayer.y;
            hz = this.hostPlayer.z;
            hyaw = this.hostPlayer.yRotation;
            hpitch = this.hostPlayer.xRotation;
            hbody = this.hostPlayer.bodyYaw;
            hflags = (this.hostPlayer.flying ? Protocol.FLAG_FLYING : 0) | (this.hostPlayer.noClip ? Protocol.FLAG_NOCLIP : 0);
            hostChanged = !this.hostSent || hx != this.sentHostX || hy != this.sentHostY || hz != this.sentHostZ
                    || hyaw != this.sentHostYaw || hpitch != this.sentHostPitch || hbody != this.sentHostBody
                    || hflags != this.sentHostFlags;
            if (hostChanged) {
                this.hostSent = true;
                this.sentHostX = hx;
                this.sentHostY = hy;
                this.sentHostZ = hz;
                this.sentHostYaw = hyaw;
                this.sentHostPitch = hpitch;
                this.sentHostBody = hbody;
                this.sentHostFlags = hflags;
            }
        }

        List<Session> movers = new ArrayList<>();
        for (Session s : this.sessions.values()) {
            if (s.joined && s.dirty) {
                movers.add(s);
            }
        }
        if (movers.isEmpty() && !hostChanged) {
            return;
        }

        final boolean sendHost = hostChanged;
        final double fx = hx, fy = hy, fz = hz;
        final float fyaw = hyaw, fpitch = hpitch, fbody = hbody;
        final int fflags = hflags;

        for (Session recipient : this.sessions.values()) {
            if (!recipient.joined) {
                continue;
            }
            // only the players of the same world, the host is in the main one
            boolean hostHere = sendHost && recipient.slot == this.mainSlot;
            List<Session> list = new ArrayList<>(movers.size());
            for (Session m : movers) {
                if (m != recipient && m.slot == recipient.slot) {
                    list.add(m);
                }
            }
            if (list.isEmpty() && !hostHere) {
                continue;
            }
            recipient.connection.send(Protocol.S_PLAYER_MOVES, out -> {
                out.writeInt(list.size() + (hostHere ? 1 : 0));
                if (hostHere) {
                    out.writeInt(Protocol.HOST_ID);
                    out.writeDouble(fx);
                    out.writeDouble(fy);
                    out.writeDouble(fz);
                    out.writeFloat(fyaw);
                    out.writeFloat(fpitch);
                    out.writeFloat(fbody);
                    out.writeByte(fflags);
                }
                for (Session m : list) {
                    out.writeInt(m.id);
                    out.writeDouble(m.x);
                    out.writeDouble(m.y);
                    out.writeDouble(m.z);
                    out.writeFloat(m.yaw);
                    out.writeFloat(m.pitch);
                    out.writeFloat(m.bodyYaw);
                    out.writeByte(m.flags);
                }
            });
        }
        for (Session m : movers) {
            m.dirty = false;
        }
    }

    private void flushLiquids(WorldSlot slot) {
        if (slot.pendingLiquids.isEmpty()) {
            return;
        }
        List<int[]> cells = new ArrayList<>(slot.pendingLiquids.values());
        slot.pendingLiquids.clear();

        for (int from = 0; from < cells.size(); from += LIQUIDS_PER_PACKET) {
            List<int[]> part = cells.subList(from, Math.min(cells.size(), from + LIQUIDS_PER_PACKET));
            sendAll(slot, Connection.frame(Protocol.S_LIQUIDS, out -> {
                out.writeInt(part.size());
                for (int[] c : part) {
                    out.writeInt(c[0]);
                    out.writeInt(c[1]);
                    out.writeInt(c[2]);
                    out.writeByte(c[3]);
                    out.writeByte(c[4]);
                }
            }));
        }
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
