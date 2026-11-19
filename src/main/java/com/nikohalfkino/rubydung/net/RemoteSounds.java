package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.audio.BodySounds;
import com.nikohalfkino.rubydung.audio.FootstepSounds;
import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.special.WoodenDoorTile;
import com.nikohalfkino.rubydung.entity.player.Player;

/**
 * the sounds of what other players do. the player's own block sounds are played where they act (the breaker and placer),
 * a change that comes in from the network has no such caller, so a joined player heard nothing of what the others built.
 * main thread only
 */
final class RemoteSounds {

    /** farther than this (blocks) nothing is heard */
    static final double RANGE = 32.0D;

    /** a block placed or broken more than once in this many nanoseconds is not played again, a big fill would be a roar */
    private static final long MIN_GAP_NANOS = 40_000_000L;

    private static long lastPlayed = 0L;

    private RemoteSounds() {
    }

    /**
     * a block changed because somebody else changed it. oldId / newId are the tile ids before and after, listener is
     * where the player is (null = no player yet)
     */
    static void blockChanged(Player listener, int x, int y, int z, int oldId, int newId) {
        if (oldId == newId || listener == null) {
            return;
        }

        Tile oldTile = Tile.byId(oldId & 0xFF);
        Tile newTile = Tile.byId(newId & 0xFF);
        if (oldTile instanceof LiquidTile || newTile instanceof LiquidTile) {
            return;
        }

        float volume = volumeAt(listener, x, y, z);
        if (volume <= 0.0F) {
            return;
        }

        // a wooden door opening or closing is a door sound, not the sound of a block being placed. both halves of a
        // door change, the sound's own gap (audio.toml) keeps it to one
        if (oldTile instanceof WoodenDoorTile && newTile instanceof WoodenDoorTile) {
            boolean opened = ((WoodenDoorTile) newTile).isOpen();
            if (opened != ((WoodenDoorTile) oldTile).isOpen()) {
                SoundEffects.play(opened || !SoundEffects.exists("door_close") ? "door_open" : "door_close", volume);
            }
            return;
        }

        long now = System.nanoTime();
        if (now - lastPlayed < MIN_GAP_NANOS) {
            return;
        }
        lastPlayed = now;

        if (newId == 0) {
            SoundEffects.playTile(oldTile, "break", volume);
        } else {
            SoundEffects.playTile(newTile, "place", volume);
        }
    }

    /** a sound somebody else made at (x, y, z), or one the server made for this player: a knock, a pat, jump / land / water, a cube picked up, put down, thrown, or hitting somebody */
    static void sound(Player listener, int event, float strength, int x, int y, int z) {
        if (listener == null || event < 0 || event >= Protocol.SOUND_COUNT) {
            return;
        }
        if (event == Protocol.SOUND_PHYS_IMPACT) {
            // this player was hit: heard at full, wherever the cube is
            SoundEffects.play("phys_impact_player", Math.max(0.3F, strength));
            return;
        }

        float volume = volumeAt(listener, x, y, z);
        if (volume <= 0.0F) {
            return;
        }
        switch (event) {
            case Protocol.SOUND_KNOCK:
                SoundEffects.play("door_knock", volume);
                break;
            case Protocol.SOUND_PAT:
                BodySounds.pat(volume);
                break;
            case Protocol.SOUND_PICK_UP:
                SoundEffects.play("phys_pick_up", volume);
                break;
            case Protocol.SOUND_PUT_DOWN:
                SoundEffects.play("phys_put_down", volume);
                break;
            case Protocol.SOUND_THROW:
                SoundEffects.play("phys_throw", volume);
                break;
            case Protocol.SOUND_FOOTSTEP:
                FootstepSounds.playAt(listener.getLevel(), x, y, z, volume);
                break;
            case Protocol.SOUND_CUBE_BUMP:
                SoundEffects.play("cube_bump", strength * volume);
                break;
            case Protocol.SOUND_CUBE_HIT:
                SoundEffects.play("cube_impact_player", strength * volume);
                break;
            case Protocol.SOUND_RAGDOLL_HIT:
            case Protocol.SOUND_RAGDOLL_BUMP:
                BodySounds.ragdollImpact(strength * volume);
                break;
            case Protocol.SOUND_RAGDOLL_SPLASH:
                BodySounds.ragdollSplash(strength * volume);
                break;
            default:
                BodySounds.play(event - Protocol.SOUND_BODY, strength, volume);
                break;
        }
    }

    /** how loud a sound at (x, y, z) is for the listener, 0 = out of range */
    private static float volumeAt(Player listener, int x, int y, int z) {
        return BodySounds.volumeAt(x + 0.5D - listener.x, y + 0.5D - listener.y, z + 0.5D - listener.z, RANGE);
    }
}
