package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandRegistry;
import com.nikohalfkino.rubydung.interfaces.screen.JoinServerScreen;
import com.nikohalfkino.rubydung.audio.tts.JTalkSettings;
import com.nikohalfkino.rubydung.audio.tts.VoiceProfile;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.PlayerCamera;
import com.nikohalfkino.rubydung.render.PlayerRenderer;
import com.nikohalfkino.rubydung.util.GameSettings;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * the game's one door to multiplayer: at most one LAN server (this game hosts the world it is playing) or one client
 * connection (this game plays on somebody else's server) at a time. everything here runs on the main thread
 */
public final class Multiplayer {

    private static GameServer server;
    private static GameClient client;
    private static ServerSettings lanSettings;
    private static final RemotePlayers REMOTES = new RemotePlayers();

    private Multiplayer() {
    }

    // state

    /** playing on somebody else's server (the world is a copy, liquids are not simulated here, nothing is saved) */
    public static boolean isClient() {
        return client != null;
    }

    public static boolean isHosting() {
        return server != null && server.isRunning();
    }

    /** hosting or joined */
    public static boolean isActive() {
        return isClient() || isHosting();
    }

    /**
     * whether dimensional doors work: alone yes, on a LAN game never (there is only the host's world), on a server only
     * when it hosts more than one world and its owner has left doors on
     */
    public static boolean doorsAllowed() {
        if (client != null) {
            return client.doorsAllowed();
        }
        return !isHosting();
    }

    /** whether links in chat can be opened: alone always, otherwise what the server allows (off unless the owner turned it on) */
    public static boolean linksAllowed() {
        if (client != null) {
            return client.linksAllowed();
        }
        return !isHosting() || server.getSettings().isAllowLinks();
    }

    /** whether pictures linked in chat are downloaded and shown: same rule as links */
    public static boolean embedsAllowed() {
        if (client != null) {
            return client.embedsAllowed();
        }
        return !isHosting() || server.getSettings().isAllowEmbeds();
    }

    /** the worlds a server hosts, for the door screen (empty when not joined) */
    public static List<GameClient.WorldInfo> serverWorlds() {
        return client != null ? client.getWorlds() : java.util.Collections.<GameClient.WorldInfo>emptyList();
    }

    /** the coloured doors of the world the player is in and where they lead, empty when not joined */
    public static List<GameClient.DoorInfo> serverDoors() {
        return client != null ? client.getDoors() : java.util.Collections.<GameClient.DoorInfo>emptyList();
    }

    /**
     * true when the door at (x, y, z) is a coloured one on a server: it has no menu, the server knows where it leads.
     * where the player uses a door, call travelThroughDoor(x, y, z, "", color) instead of opening DimensionalDoorScreen
     */
    public static boolean leadsByServer(Level level, int x, int y, int z) {
        if (client == null) {
            return false;
        }
        com.nikohalfkino.rubydung.level.tiles.Tile tile = com.nikohalfkino.rubydung.level.tiles.Tile.byId(level.getTile(x, y, z));
        return tile instanceof com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile
                && ((com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile) tile).getColor()
                != com.nikohalfkino.rubydung.level.tiles.special.DoorColor.REGULAR;
    }

    /** the name the server gives the world the player is in, null when not joined */
    public static String serverWorldName() {
        return client != null ? client.getWorldName() : null;
    }

    /** what the pause screen says about where the player is, null when not joined: the server's name, else its address */
    public static String connectedTo() {
        if (client == null) {
            return null;
        }
        String name = client.getServerName();
        return name != null && !name.isEmpty() ? "Connected to " + name : "Connected to: " + client.getAddress();
    }

    /** the topic the server sets for the chat screen in this world, empty = use the game's own */
    public static String chatTopic() {
        return client != null ? client.getChatTopic() : "";
    }

    /** asks the server to take the player through the door at (x, y, z) to another world */
    public static void travelThroughDoor(int x, int y, int z, String world, com.nikohalfkino.rubydung.level.tiles.special.DoorColor color) {
        if (client != null) {
            client.sendDoor(x, y, z, world, color);
        }
    }

    // weighted cubes: alone and on a LAN host the game changes them itself (a host's server tells the others), on a server they are asked of it

    /** asks the server to put a cube into the cell (only when joined, a host's own game does it itself) */
    public static void cubeSpawn(int x, int y, int z, boolean dice) {
        if (client != null) {
            client.sendCubeSpawn(x, y, z, dice);
        }
    }

    public static void cubePickup(com.nikohalfkino.rubydung.entity.CubeEntity cube) {
        if (client != null) {
            client.sendCubePickup(cube.id);
        }
    }

    /** says where the carried cube should be, called every few ticks while carrying */
    public static void cubeHold(com.nikohalfkino.rubydung.entity.CubeEntity cube) {
        if (client != null) {
            client.sendCubeHold(cube);
        }
    }

    public static void cubeRelease(com.nikohalfkino.rubydung.entity.CubeEntity cube) {
        if (client != null) {
            client.sendCubeRelease(cube);
        }
    }

    /** asks the server to send the chess piece to the square (only when joined, see ChessPieceTile.moveTo) */
    public static void chessMove(com.nikohalfkino.rubydung.entity.CubeEntity piece, int x, int z) {
        if (client != null) {
            client.sendChessMove(piece.id, x, z);
        }
    }

    public static void cubeRemove(com.nikohalfkino.rubydung.entity.CubeEntity cube) {
        if (client != null) {
            client.sendCubeRemove(cube.id);
        }
    }

    /**
     * the name this player goes by in the game: on a server it is the name the server gave (it makes names unique, so
     * it can differ from the one in the options), otherwise the one in the options
     */
    public static String selfName() {
        if (client != null && client.getAssignedName() != null) {
            return client.getAssignedName();
        }
        return GameSettings.getUsername();
    }

    public static RemotePlayers remotes() {
        return REMOTES;
    }

    // LAN host

    /** opens the world being played to the network. returns an error message, or null when it worked */
    public static String hostLan(RubyDung game, int port) {
        if (isActive()) {
            return isHosting() ? "Already open on port " + server.getPort() : "You are playing on a server";
        }
        if (!game.hasWorld()) {
            return "There is no world to open";
        }

        Level level = game.getLevel();
        String hostName = GameSettings.getUsername();
        // a LAN game starts with the same defaults as any server (links, embeds off, no doors) and keeps what the host
        // changed with .allow, .op, .ban ... until the game is closed
        if (lanSettings == null) {
            lanSettings = ServerSettings.memory();
        }
        GameServer created = new GameServer(level.getWorldName(), level, game.getDayNightCycle(), lanSettings, port,
                hostName, game.getPlayer(), REMOTES, line -> game.getChatLog().notice(line),
                (sender, text, voice) -> {
                    game.getChatLog().sayAs(sender, com.nikohalfkino.rubydung.interfaces.ChatInputSyntax.displayed(text));
                    heard(game, sender, text, voice);
                });
        try {
            created.start();
        } catch (IOException e) {
            return "Could not open port " + port + ": " + e.getMessage();
        }
        created.setTypingSink(Multiplayer::typingChanged);
        server = created;
        REMOTES.clear();
        localTyping = false;

        game.getChatLog().notice("Open to LAN on port " + port + ". Friends can join with:");
        for (String address : localAddresses()) {
            game.getChatLog().notice("  " + address + ":" + port);
        }
        return null;
    }

    /** closes the LAN server, kicking everyone (the world stays open and playable) */
    public static void stopHosting(String reason) {
        if (server != null) {
            server.stop(reason);
            server = null;
            REMOTES.clear();
            localTyping = false;
        }
    }

    // client

    public static void connect(RubyDung game, String address, String password) {
        if (isActive()) {
            return;
        }

        String host = address.trim();
        int port = Protocol.DEFAULT_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
                host = host.substring(0, colon).trim();
            } catch (NumberFormatException e) {
                game.openScreen(new JoinServerScreen(game, "That port is not a number"));
                return;
            }
        }
        if (host.isEmpty()) {
            game.openScreen(new JoinServerScreen(game, "Enter the server's address"));
            return;
        }

        REMOTES.clear();
        client = new GameClient(game, host, port, GameSettings.getUsername(), password, REMOTES);
        client.connect();
    }

    /** main thread: the connection ended (kicked, lost, or never made). back to the join screen with the reason */
    static void clientClosed(RubyDung game, GameClient closed, String reason) {
        if (client != closed) {
            return;
        }
        client = null;
        REMOTES.clear();
        game.quitToTitle();
        game.openScreen(new JoinServerScreen(game, reason));
    }

    // both

    /** called from the game loop every tick, whether or not a screen is open */
    public static void tick(RubyDung game) {
        if (server != null) {
            server.tick();
        }
        if (client != null) {
            client.tick();
        }
    }

    /** leaving the world (quit to title, closing the game) */
    public static void shutdown(String reason) {
        stopHosting(reason);
        if (client != null) {
            GameClient closing = client;
            client = null;
            closing.disconnect();
        }
        REMOTES.clear();
    }

    /** the world being played is about to be replaced: a LAN server would be left holding a dead level */
    public static void worldLeft() {
        stopHosting("The host left the world");
    }

    // typing

    private static boolean localTyping = false;
    private static long localTypingSentAt = 0L;
    private static final long TYPING_REFRESH_NANOS = 3_000_000_000L;

    /**
     * the chat screen calls this every frame with whether this player is typing a chat message (not a command). the
     * others are told when it changes and again every few seconds while it lasts, so a lost packet or a closed
     * game never leaves a typing indicator stuck
     */
    public static void setTyping(boolean typing) {
        if (!isActive()) {
            localTyping = false;
            return;
        }
        long now = System.nanoTime();
        if (typing == localTyping && (!typing || now - localTypingSentAt < TYPING_REFRESH_NANOS)) {
            return;
        }
        localTyping = typing;
        localTypingSentAt = now;
        if (client != null) {
            client.sendTyping(typing);
        } else if (server != null) {
            server.hostTyping(typing);
        }
    }

    /** somebody else started or stopped typing (main thread) */
    public static void typingChanged(String name, boolean typing) {
        if (name != null && !name.equalsIgnoreCase(selfName())) {
            REMOTES.setTyping(name, typing);
        }
    }

    /** who is typing right now, for the chat screen */
    public static List<String> typingNames() {
        return REMOTES.typingNames();
    }

    /** this player's voice for the chat lines they send: "" when text to speech is off (the others then just see a bubble) */
    public static String localVoice() {
        return JTalkSettings.isEnabled() ? VoiceProfile.current().encode() : "";
    }

    /**
     * somebody else said something (main thread): a chat bubble goes over their head and, if they are near enough and
     * sent a voice, they are heard from where they stand. nothing happens for a player who is not in this world
     */
    public static void heard(RubyDung game, String sender, String text, String voice) {
        Player self = game.getPlayer();
        if (isActive() && self != null) {
            REMOTES.hear(sender, text, voice, self.x, self.y, self.z, self.yRotation);
        }
    }

    /** chat typed in the chat screen. true if it was handled here (a client), false if the caller should show it itself */
    public static boolean sendChat(String text) {
        if (client != null) {
            client.sendChat(text);
            return true;
        }
        if (server != null) {
            server.hostSays(text, localVoice());
        }
        return false;
    }

    // the commands of the game's console, which know where each one runs (Command.Where). set by the Console
    private static volatile CommandRegistry commands;

    public static void useCommands(CommandRegistry registry) {
        commands = registry;
    }

    /** what this player may do: the tier the server gave them when joined, the owner (HOST) in a game of their own or a LAN game they host */
    public static CommandTier localTier() {
        GameClient joined = client;
        return joined != null ? joined.getTier() : CommandTier.HOST;
    }

    /**
     * a dot-command typed in the chat screen or the console. returns the lines to show, or null if the caller should run it
     * itself (the game's own console). where a command runs is its own business (Command.where), so there is no list of
     * names in here:
     *
     *   LOCAL      always the game's own console: it only changes what this player sees and hears
     *   OWN_WORLD  the game's own console, but not while joined: it would change a copy the server never sees
     *   WORLD, SERVER
     *              joined: sent to the server, which runs it at the player's tier and answers in the chat
     *              hosting: run on the server at tier HOST
     *              alone: the game's own console (a WORLD command acts on the world itself, a SERVER one says it needs a server)
     *
     * a line the registry doesn't know is sent along when joined (the server may know it) and is the console's problem otherwise
     */
    public static List<String> runCommand(String line) {
        CommandRegistry registry = commands;
        String name = CommandRegistry.firstWord(line);
        Command command = registry == null ? null : registry.find(name);
        Command.Where where = command == null ? null : command.where();

        if (client != null) {
            if (where == Command.Where.LOCAL) {
                return null;
            }
            if (where == Command.Where.OWN_WORLD) {
                List<String> refused = new ArrayList<>();
                refused.add("'" + name + "' only works in a world of your own, not on a server");
                return refused;
            }
            client.sendCommand(line);
            return new ArrayList<>();
        }
        if (server != null && server.isRunning() && (where == Command.Where.WORLD || where == Command.Where.SERVER)) {
            return ServerCommands.execute(server, server.hostActor(), line);
        }
        return null;
    }

    /** everybody in the world for the chat screen's user list: yourself first, then the others */
    public static List<String> playerNames(String self) {
        List<String> names = new ArrayList<>();
        names.add(self);
        if (isActive()) {
            for (RemotePlayer p : REMOTES.all()) {
                if (!p.name.equalsIgnoreCase(self)) {
                    names.add(p.name);
                }
            }
        }
        return names;
    }

    // drawing

    public static void renderPlayers(RubyDung game, Level level, PlayerRenderer renderer, PlayerCamera camera) {
        if (isActive()) {
            REMOTES.render(level, renderer, camera);
        }
    }

    /** the other players' sprites only (no name tags), for a viewer that is not the PlayerCamera: screenshots */
    public static void renderPlayersSeenFrom(Level level, PlayerRenderer renderer, double viewX, double viewZ, float viewYaw) {
        if (isActive()) {
            REMOTES.renderModels(level, renderer, viewX, viewZ, viewYaw);
        }
    }

    public static void renderNameTags(Font font, int width, int height) {
        if (isActive()) {
            REMOTES.renderTags(font, width, height);
        }
    }

    // the addresses this computer has on the local network, for telling friends

    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        out.add(address.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
        }
        if (out.isEmpty()) {
            out.add("(your local IP address)");
        }
        return out;
    }
}
