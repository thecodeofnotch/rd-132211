package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.audio.tts.JTalkSettings;
import com.nikohalfkino.rubydung.audio.tts.JTalkSpeaker;
import com.nikohalfkino.rubydung.audio.tts.VoiceProfile;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.NameTags;
import com.nikohalfkino.rubydung.render.PlayerCamera;
import com.nikohalfkino.rubydung.render.PlayerRenderer;
import com.nikohalfkino.rubydung.util.GameSettings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** the other players in the world being played: updated from packets, drawn every frame. main thread only */
public final class RemotePlayers {

    // kept for the callers that read them here, the values live with the tag drawing
    public static final double NAME_TAG_RANGE = NameTags.RANGE;
    public static final int NAME_TAG_GAP = NameTags.GAP;
    public static final float TAG_REFERENCE_DISTANCE = NameTags.REFERENCE_DISTANCE;
    public static final float TAG_BASE_SCALE = NameTags.BASE_SCALE;
    public static final float TAG_MIN_SCALE = NameTags.MIN_SCALE;
    public static final float TAG_MAX_SCALE = NameTags.MAX_SCALE;

    // a voice is heard up to this far (blocks), loud within VOICE_FULL_RANGE and fading out to nothing at VOICE_RANGE
    private static final double VOICE_RANGE = 40.0D;
    private static final double VOICE_FULL_RANGE = 3.0D;

    // chat bubble: how long it stays (a base plus a bit per character), the drawing is NameTags'
    private static final long BUBBLE_BASE_NANOS = 3_500_000_000L;
    private static final long BUBBLE_PER_CHAR_NANOS = 60_000_000L;
    private static final long BUBBLE_MAX_NANOS = 12_000_000_000L;
    private static final int BUBBLE_MAX_CHARS = 140;

    // how far above the feet the tag is anchored (the head is at about 1.8)
    private static final double NAME_TAG_HEIGHT = 2.35D;

    // name tags are projected to the screen while the 3D matrices are active and drawn later, with the HUD
    private final NameTags nameTags = new NameTags();

    // who is typing a chat message (lower case name -> display name and when it runs out unless they are told again).
    // entries run out by themselves, so a player who left or lost their connection cannot stay typing
    private static final long TYPING_TIMEOUT_NANOS = 6_000_000_000L;
    private final Map<String, Object[]> typing = new LinkedHashMap<>();

    private final Map<Integer, RemotePlayer> players = new LinkedHashMap<>();
    private long lastFrameNanos = 0L;

    /** the tags of this frame: other things (a dice) add theirs after render() and before renderTags() */
    public NameTags tags() {
        return this.nameTags;
    }

    public void add(RemotePlayer player) {
        this.players.put(player.id, player);
    }

    public RemotePlayer get(int id) {
        return this.players.get(id);
    }

    public void setTyping(String name, boolean on) {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        if (on) {
            this.typing.put(key, new Object[]{name, System.nanoTime() + TYPING_TIMEOUT_NANOS});
        } else {
            this.typing.remove(key);
        }
    }

    public boolean isTyping(String name) {
        Object[] entry = this.typing.get(name.toLowerCase(java.util.Locale.ROOT));
        if (entry == null) {
            return false;
        }
        if (System.nanoTime() > (Long) entry[1]) {
            this.typing.remove(name.toLowerCase(java.util.Locale.ROOT));
            return false;
        }
        return true;
    }

    /** the names of the players typing now, oldest first */
    public List<String> typingNames() {
        long now = System.nanoTime();
        List<String> names = new ArrayList<>();
        java.util.Iterator<Object[]> it = this.typing.values().iterator();
        while (it.hasNext()) {
            Object[] entry = it.next();
            if (now > (Long) entry[1]) {
                it.remove();
            } else {
                names.add((String) entry[0]);
            }
        }
        return names;
    }

    public RemotePlayer remove(int id) {
        RemotePlayer gone = this.players.remove(id);
        if (gone != null) {
            this.typing.remove(gone.name.toLowerCase(java.util.Locale.ROOT));
        }
        silence(gone);
        return gone;
    }

    public void clear() {
        for (RemotePlayer p : this.players.values()) {
            silence(p);
        }
        this.players.clear();
        this.typing.clear();
        this.nameTags.clear();
    }

    private static void silence(RemotePlayer p) {
        if (p != null && p.voice != null) {
            p.voice.stop();
            p.voice = null;
        }
    }

    /**
     * somebody in this world said something: a bubble over their head for a while and, if they sent a voice and are close
     * enough, their voice from where they stand. listener is where this player is (eye position) and which way they look
     */
    public void hear(String sender, String text, String voice, double listenerX, double listenerY, double listenerZ,
                     float listenerYaw) {
        RemotePlayer speaker = null;
        for (RemotePlayer p : this.players.values()) {
            if (p.name.equalsIgnoreCase(sender)) {
                speaker = p;
                break;
            }
        }
        if (speaker == null) {
            return;   // not in this world (chat reaches every world, bubbles and voices only the one they are in)
        }

        this.typing.remove(speaker.name.toLowerCase(java.util.Locale.ROOT));
        // a song shows as "[Singing...]" over their head too, the voice below still sings the real text
        String bubbleText = com.nikohalfkino.rubydung.interfaces.ChatInputSyntax.displayed(text);
        String shown = bubbleText.length() > BUBBLE_MAX_CHARS ? bubbleText.substring(0, BUBBLE_MAX_CHARS - 3) + "..." : bubbleText;
        speaker.bubble = shown;
        speaker.bubbleUntilNanos = System.nanoTime()
                + Math.min(BUBBLE_MAX_NANOS, BUBBLE_BASE_NANOS + BUBBLE_PER_CHAR_NANOS * shown.length());

        VoiceProfile profile = VoiceProfile.decode(voice);
        if (profile == null || !JTalkSettings.isEnabled()) {
            return;
        }
        double dx = speaker.drawX - listenerX;
        double dy = speaker.drawY - listenerY;
        double dz = speaker.drawZ - listenerZ;
        if (dx * dx + dy * dy + dz * dz >= VOICE_RANGE * VOICE_RANGE) {
            return;
        }

        if (speaker.voice == null) {
            // one thread to render, one to play: enough for somebody who talks in turns
            speaker.voice = new JTalkSpeaker(true, 1);
            speaker.voice.start();
        }
        updateVoice(speaker, listenerX, listenerY, listenerZ, listenerYaw);
        speaker.voice.speak(text, profile);
    }

    // sets how loud and from which side a speaking player is heard
    private static void updateVoice(RemotePlayer p, double listenerX, double listenerY, double listenerZ, float listenerYaw) {
        if (p.voice == null) {
            return;
        }
        double dx = p.drawX - listenerX;
        double dy = p.drawY - listenerY;
        double dz = p.drawZ - listenerZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double closeness = distance <= VOICE_FULL_RANGE ? 1.0D
                : Math.max(0.0D, (VOICE_RANGE - distance) / (VOICE_RANGE - VOICE_FULL_RANGE));
        float gain = (float) (closeness * closeness) * GameSettings.getSfxVolume() * JTalkSettings.getVolume();

        // right of the listener is (cos yaw, sin yaw), forward is (sin yaw, -cos yaw)
        double yaw = Math.toRadians(listenerYaw);
        double flat = Math.sqrt(dx * dx + dz * dz);
        float pan = flat < 0.5D ? 0.0F : (float) ((dx * Math.cos(yaw) + dz * Math.sin(yaw)) / flat);
        p.voice.setPosition(gain, pan);
    }

    public Collection<RemotePlayer> all() {
        return this.players.values();
    }

    public int size() {
        return this.players.size();
    }

    /** 3D pass, after the terrain and before the billboards, like the local player's sprite */
    public void render(Level level, PlayerRenderer renderer, PlayerCamera camera) {
        this.nameTags.begin();
        List<GameClient.DoorInfo> doors = Multiplayer.serverDoors();
        if (this.players.isEmpty() && doors.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        float seconds = this.lastFrameNanos == 0L ? 0.0F : Math.min((now - this.lastFrameNanos) * 1.0E-9F, 0.25F);
        this.lastFrameNanos = now;

        addDoorTags(level, doors, camera);

        for (RemotePlayer p : this.players.values()) {
            p.step(seconds);
            updateVoice(p, camera.getX(), camera.getY(), camera.getZ(), camera.getYaw());

            double feetY = p.drawY - Player.EYE_HEIGHT;
            renderer.renderRemote(level, p.drawX, feetY, p.drawZ, p.drawBodyYaw, p.isNoClip(), camera);

            String bubble = null;
            if (p.bubble != null) {
                if (now < p.bubbleUntilNanos) {
                    bubble = p.bubble;
                } else {
                    p.bubble = null;
                }
            }
            this.nameTags.add(p.drawX, feetY + NAME_TAG_HEIGHT, p.drawZ, camera, p.name, PlayerColors.of(p.name),
                    bubble, bubble == null && isTyping(p.name));
        }
    }

    // a coloured door's tag floats this far above its top (doors are DimensionalDoorTile.HEIGHT tall)
    private static final double DOOR_TAG_GAP = 0.4D;
    private static final int NO_DESTINATION_COLOR = 0xAAAAAA;

    /** a tag over every coloured door in range saying where it leads, like a player's name above their head (matrices are active) */
    private void addDoorTags(Level level, List<GameClient.DoorInfo> doors, PlayerCamera camera) {
        for (GameClient.DoorInfo door : doors) {
            Tile tile = Tile.byId(level.getTile(door.x, door.y, door.z));
            if (!(tile instanceof DimensionalDoorTile)) {
                continue;   // the door was broken, the server's next list leaves it out
            }
            boolean set = !door.destination.isEmpty();
            this.nameTags.add(door.x + 0.5D, door.y + DimensionalDoorTile.HEIGHT + DOOR_TAG_GAP, door.z + 0.5D, camera,
                    set ? door.destination : "No destination",
                    set ? ((DimensionalDoorTile) tile).getColor().getTint() : NO_DESTINATION_COLOR);
        }
    }

    /** the sprites at where they are drawn now, nothing else (no smoothing step, no tags): for screenshots */
    public void renderModels(Level level, PlayerRenderer renderer, double viewX, double viewZ, float viewYaw) {
        for (RemotePlayer p : this.players.values()) {
            renderer.renderRemoteSeenFrom(level, p.drawX, p.drawY - Player.EYE_HEIGHT, p.drawZ, p.drawBodyYaw, p.isNoClip(),
                    viewX, viewZ, viewYaw);
        }
    }

    /** HUD pass: the name tags found by render(), width and height are the HUD's size */
    public void renderTags(Font font, int width, int height) {
        this.nameTags.render(font, width, height);
    }
}