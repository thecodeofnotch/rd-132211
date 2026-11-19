package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.EntityTypes;
import com.nikohalfkino.rubydung.entity.WorldEntity;
import com.nikohalfkino.rubydung.level.SubTiles;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** packet ids, limits and the small codecs both sides share. every packet is [int length][byte type][payload] */
public final class Protocol {

    private Protocol() {
    }

    /** bump when a packet changes, a client with another version is refused */
    public static final int VERSION = 14;   // 14: dice have colours: C_CUBE_SPAWN kinds 3 (red dice) and 4 (blue dice), the piece codes of the red dice are -32 .. -38 and of the blue ones -40 .. -46, and the ragdolls' are -16 .. -31. 13: C_CUBE_FREEZE; C_CUBE_HOLD's orientation is, for a body made of parts (a ragdoll), the turn of the carried limb since the last one; the pose has the mask of the frozen particles; C_CUBE_PICKUP carries the part and where along it (a ragdoll is taken by a limb), C_CUBE_RELEASE a thrown byte, and a cube entry in S_CUBES has the extra state (a ragdoll's pose) after it. 12: C_CUBE_SPAWN says what to spawn with a byte (cube, dice, ragdoll) instead of a boolean, and the piece codes -16 and below are ragdolls. 11: S_TIME carries the day count and the pinned sun phase. 10: SOUND_FOOTSTEP shifted the server's sound events

    public static final int DEFAULT_PORT = 25566;

    public static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
    public static final int LEVEL_CHUNK_BYTES = 32 * 1024;

    public static final int SOCKET_TIMEOUT_MS = 30_000;
    /** the biggest packet a server accepts from a player (chat, commands, block edits: all tiny). a frame is allocated before the sender has proved anything, so this is far below MAX_FRAME_BYTES */
    public static final int MAX_CLIENT_FRAME_BYTES = 256 * 1024;
    /** at most this many connections from one address (the host's own computer included) */
    public static final int MAX_CONNECTIONS_PER_IP = 8;
    public static final int KEEPALIVE_MS = 5_000;
    public static final int CONNECT_TIMEOUT_MS = 8_000;

    public static final int MAX_NAME_LENGTH = 16;
    public static final int MAX_CHAT_LENGTH = 256;
    /** longest voice profile string that goes with a chat line (see VoiceProfile) */
    public static final int MAX_VOICE_LENGTH = 160;
    public static final int MAX_PASSWORD_LENGTH = 64;
    public static final int MAX_WORLD_NAME_LENGTH = 64;
    public static final int MAX_SUBTILES_PER_CELL = 16;

    /** how far from its last reported position a player may change a block (blocks, generous for lag and flying) */
    public static final double MAX_REACH = 24.0;
    public static final int MAX_ACTIONS_PER_SECOND = 400;

    /** the events of C_SOUND / S_SOUND */
    public static final int SOUND_KNOCK = 0;
    public static final int SOUND_PAT = 1;
    /** BodySounds events (jump, land, water enter, water tread) are SOUND_BODY + the event */
    public static final int SOUND_BODY = 2;
    public static final int SOUND_PICK_UP = 6;    // carrying a cube: picked up (E)
    public static final int SOUND_PUT_DOWN = 7;   // let go of it (E)
    public static final int SOUND_THROW = 8;      // thrown (left click)
    /** a client may send the events below this, the ones from it on only the server makes */
    public static final int SOUND_FOOTSTEP = 9;   // a step: x y z is the cell under the feet, the material is the receiver's to look up there
    public static final int SOUND_CLIENT_COUNT = 10;
    public static final int SOUND_CUBE_HIT = 10;    // a cube hit a player: the sound of the cube, heard by those near it
    public static final int SOUND_PHYS_IMPACT = 11; // sent only to the player who was hit: the sound of taking it
    public static final int SOUND_CUBE_BUMP = 12;   // a cube hit a wall, the floor or another cube
    public static final int SOUND_RAGDOLL_HIT = 13;    // a ragdoll hit a player: a dull thud instead of a cube's knock
    public static final int SOUND_RAGDOLL_BUMP = 14;   // a ragdoll hit a wall, the floor or something else
    public static final int SOUND_RAGDOLL_SPLASH = 15; // a ragdoll went into water: heavier than a cube's splash
    public static final int SOUND_COUNT = 16;

    // client -> server
    public static final int C_HELLO = 1;         // int version, UTF name
    public static final int C_STATE = 2;         // double x y z, float yaw pitch bodyYaw, byte flags
    public static final int C_BLOCK_ACTION = 3;  // int x y z id data
    public static final int C_SUBTILES = 4;      // int x y z, entries
    public static final int C_CHAT = 5;          // UTF text, UTF voice profile ("" = this player does not speak it aloud)
    public static final int C_KEEPALIVE = 6;
    public static final int C_COMMAND = 7;       // UTF line (without the dot). the server runs it at the player's tier
    public static final int C_TYPING = 9;        // byte 1 = typing a chat message, 0 = stopped (repeated every few seconds while typing)
    public static final int C_DOOR = 8;          // int x y z of the door, UTF target world, int picked colour ordinal
    public static final int C_CUBE_SPAWN = 10;   // int x y z of the empty cell to put a weighted cube in, byte kind (0 a cube, 1 a dice, 2 a ragdoll, 3 a red dice, 4 a blue dice, see CubeManager.KIND_*)
    public static final int C_CUBE_PICKUP = 11;  // int cube id, byte part, byte frac (0..255 = 0..1 along the part, see GrabPick): asks to carry it
    public static final int C_CUBE_HOLD = 12;    // int cube id, double x y z where it should be, float w x y z orientation (every few ticks while carrying)
    public static final int C_CUBE_RELEASE = 13; // int cube id, float vx vy vz (blocks/s), float avx avy avz (radians/s), byte thrown (1 = thrown, 0 = let go of): let go or thrown
    public static final int C_CUBE_REMOVE = 14;  // int cube id
    public static final int C_CUBE_FREEZE = 17;  // int cube id, byte mode (0 = freeze the part carried now where it is and let go of it, 1 = free every frozen part of it)
    public static final int C_SOUND = 16;        // byte event (SOUND_*), byte strength (0..255), int x y z: a sound this player made that the others should hear (a knock)
    public static final int C_CHESS_MOVE = 15;   // int piece id, int x z of the square to send it to (the server checks the move)

    // server -> client
    public static final int S_WELCOME = 20;      // int version, int id, int w h d, int worldType, long seed, double x y z, float time, int snapshotLength, UTF assignedName, UTF worldName, byte flags, byte tier, float spawnYaw, UTF serverName, UTF chatTopic
    public static final int S_LEVEL_CHUNK = 21;  // int length, bytes
    public static final int S_LEVEL_END = 22;
    public static final int S_BLOCK_CHANGE = 23; // int x y z id data
    public static final int S_SUBTILES = 24;     // int x y z, entries
    public static final int S_LIQUIDS = 25;      // int count, then count * (int x y z, byte id, byte fill)
    public static final int S_PLAYER_JOIN = 26;  // int id, UTF name, double x y z
    public static final int S_PLAYER_LEAVE = 27; // int id
    public static final int S_PLAYER_MOVES = 28; // int count, then count * (int id, double x y z, float yaw pitch bodyYaw, byte flags)
    public static final int S_CHAT = 29;         // UTF sender (empty = a notice), UTF text, UTF voice profile (empty = not spoken)
    public static final int S_TIME = 30;         // float time of day, float speed, byte frozen, int day count, byte pinned sun phase (-1 = none)
    public static final int S_TELEPORT = 31;     // double x y z, float yaw pitch
    public static final int S_KICK = 32;         // UTF reason
    public static final int S_KEEPALIVE = 33;
    public static final int S_SEA = 34;          // float sea height, float target, float rate (blocks per second, 0 = still)
    public static final int S_WORLD_CHANGE = 35; // UTF new world: the player goes through a door, a S_WELCOME and a new copy of the world follow
    public static final int S_WORLDS = 36;       // int count, then count * (UTF world, int doorColours, then that many int colour ordinals)
    public static final int S_TIER = 37;         // byte tier: the player was made an op (or stopped being one)
    public static final int S_TYPING = 39;       // UTF name, byte 1 = typing, 0 = stopped
    public static final int S_DOORS = 40;        // int count, then count * (int x y z of a coloured door's origin, UTF world it leads to, empty = nowhere): the doors of the world the player is in
    public static final int S_SEA_LIQUID = 41;   // UTF name of the liquid the sea is made of
    public static final int S_CUBES = 42;        // byte full (1 = the whole list, cubes not in it are gone), int count, then count * cube (see writeCube, a ragdoll's pose follows its entry). chess pieces are in it too
    public static final int S_CUBE_REMOVE = 43;  // int cube id
    public static final int S_CUBE_DENY = 44;    // int cube id: the server did not let this player carry it, or no longer does
    public static final int S_SOUND = 45;        // byte event (SOUND_*), byte strength (0..255), int x y z: somebody else made a sound there (sent only to players in range)
    public static final int S_SETTINGS = 38;     // byte flags (WELCOME_*): the owner changed what the server allows

    public static final int FLAG_FLYING = 1;
    /** walking through blocks: drawn dithered, and blocks may be placed where they stand */
    public static final int FLAG_NOCLIP = 2;

    // S_WELCOME flags: what this server allows
    public static final int WELCOME_DOORS = 1;
    public static final int WELCOME_LINKS = 2;
    public static final int WELCOME_EMBEDS = 4;

    /** the most a carried cube may be from its player when a client says where it is, in blocks */
    public static final double MAX_CUBE_HOLD_DISTANCE = 6.5;
    /** how far from the player a cube may be to be picked up or removed (the hold distance, a bit of lag and its own size) */
    public static final double MAX_CUBE_PICKUP_DISTANCE = 10.0;
    /** the fastest a cube may be sent off, blocks/s and radians/s */
    public static final double MAX_CUBE_SPEED = 30.0;
    public static final double MAX_CUBE_SPIN = 12.0;

    /** id the host of a LAN game has among the players (a dedicated server has no such player) */
    public static final int HOST_ID = 0;

    /** writes the body of a packet */
    public interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    public static String sanitizeName(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                out.append(c);
            }
            if (out.length() >= MAX_NAME_LENGTH) {
                break;
            }
        }
        return out.length() == 0 ? "Player" : out.toString();
    }

    public static String sanitizeChat(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= ' ' && c != 0x7F) {
                out.append(c);
            }
            if (out.length() >= MAX_CHAT_LENGTH) {
                break;
            }
        }
        return out.toString().trim();
    }

    /** a voice profile from a client: control characters dropped, cut to MAX_VOICE_LENGTH. it is only ever parsed with VoiceProfile.decode */
    public static String sanitizeVoice(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= ' ' && c != 0x7F) {
                out.append(c);
            }
            if (out.length() >= MAX_VOICE_LENGTH) {
                break;
            }
        }
        return out.toString().trim();
    }

    /** replaces anything that looks like an address with a note, for servers that do not allow links */
    public static String stripLinks(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("http://") && !lower.contains("https://") && !lower.contains("www.")) {
            return text;
        }
        return text.replaceAll("(?i)(https?://|www\\.)\\S+", "[hyperlink blocked]");
    }

    public static void writeEntries(DataOutputStream out, List<SubTiles.Entry> entries) throws IOException {
        out.writeInt(entries.size());
        for (SubTiles.Entry e : entries) {
            out.writeInt(e.x);
            out.writeInt(e.y);
            out.writeInt(e.z);
            out.writeUTF(e.tileName);
            out.writeInt(e.data);
            out.writeFloat(e.ox);
            out.writeFloat(e.oy);
            out.writeFloat(e.oz);
        }
    }

    public static List<SubTiles.Entry> readEntries(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_SUBTILES_PER_CELL) {
            throw new IOException("bad sub-tile count " + count);
        }
        List<SubTiles.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            String name = in.readUTF();
            int data = in.readInt();
            float ox = in.readFloat();
            float oy = in.readFloat();
            float oz = in.readFloat();
            entries.add(new SubTiles.Entry(x, y, z, name, data, ox, oy, oz));
        }
        return entries;
    }

    /** what the server says about one cube */
    public static final class CubeState {
        public int id;
        public double x, y, z;
        public double qw, qx, qy, qz;
        public double vx, vy, vz;
        /** id of the player carrying it, HOST_ID for the LAN host, -1 for nobody */
        public int holder;
        /** -1 for a plain cube, else the chess piece (see WorldEntity.pieceCode) */
        public int piece = -1;
        /** the extra state that follows the entry (see WorldEntity.writeExtra), null if the piece code has none */
        public byte[] extra;
    }

    /** the part byte and the fraction byte of C_CUBE_PICKUP */
    public static int fracToByte(double frac) {
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, frac)) * 255.0D);
    }

    public static double byteToFrac(int b) {
        return (b & 0xFF) / 255.0D;
    }

    public static void writeCube(DataOutputStream out, WorldEntity cube, int holder) throws IOException {
        out.writeInt(cube.id);
        out.writeDouble(cube.x);
        out.writeDouble(cube.y);
        out.writeDouble(cube.z);
        out.writeFloat((float) cube.qw);
        out.writeFloat((float) cube.qx);
        out.writeFloat((float) cube.qy);
        out.writeFloat((float) cube.qz);
        out.writeFloat((float) cube.vx);
        out.writeFloat((float) cube.vy);
        out.writeFloat((float) cube.vz);
        out.writeInt(holder);
        out.writeByte(cube.pieceCode());
        if (EntityTypes.extraSizeOf(cube.pieceCode()) > 0) {
            cube.writeExtra(out);
        }
    }

    public static CubeState readCube(DataInputStream in) throws IOException {
        CubeState c = new CubeState();
        c.id = in.readInt();
        c.x = in.readDouble();
        c.y = in.readDouble();
        c.z = in.readDouble();
        c.qw = in.readFloat();
        c.qx = in.readFloat();
        c.qy = in.readFloat();
        c.qz = in.readFloat();
        c.vx = in.readFloat();
        c.vy = in.readFloat();
        c.vz = in.readFloat();
        c.holder = in.readInt();
        c.piece = in.readByte();
        int extraSize = EntityTypes.extraSizeOf(c.piece);
        if (extraSize > 0) {
            c.extra = new byte[extraSize];
            in.readFully(c.extra);
        }
        if (!Double.isFinite(c.x + c.y + c.z + c.qw + c.qx + c.qy + c.qz + c.vx + c.vy + c.vz)) {
            throw new IOException("bad cube state");
        }
        return c;
    }

    public static List<CubeState> readCubes(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > CubeManager.MAX_ENTITIES) {
            throw new IOException("bad cube count " + count);
        }
        List<CubeState> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(readCube(in));
        }
        return list;
    }
}
