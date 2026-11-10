package com.nikohalfkino.rubydung.audio.tts;

import java.util.Locale;

/**
 * how somebody sounds: which voice and the open_jtalk settings. a player's profile travels with what they say, so the
 * others hear the line in the speaker's voice (a voice name that is not installed here falls back to the listener's own)
 *
 * wire form: "voice;speed;pitch;alpha;voiced;jf", e.g. "mei_normal;1.00;0.00;0.55;0.50;1.00"
 */
public final class VoiceProfile {

    public final String voice;
    public final float speed;
    public final float pitch;
    public final float alpha;
    public final float voiced;
    public final float jf;

    private String resolvedPath;

    private VoiceProfile(String voice, float speed, float pitch, float alpha, float voiced, float jf) {
        this.voice = voice;
        this.speed = speed;
        this.pitch = pitch;
        this.alpha = alpha;
        this.voiced = voiced;
        this.jf = jf;
    }

    /** this player's settings right now */
    public static VoiceProfile current() {
        return new VoiceProfile(JTalkSettings.getVoiceName(), JTalkSettings.getSpeed(), JTalkSettings.getPitch(),
                JTalkSettings.getAlpha(), JTalkSettings.getVoiced(), JTalkSettings.getJf());
    }

    public String encode() {
        return String.format(Locale.ROOT, "%s;%.2f;%.2f;%.2f;%.2f;%.2f",
                this.voice, this.speed, this.pitch, this.alpha, this.voiced, this.jf);
    }

    /** null for a blank or broken string. every number is clamped to what the settings allow, whatever the sender claims */
    public static VoiceProfile decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.split(";", -1);
        if (parts.length != 6) {
            return null;
        }
        try {
            return new VoiceProfile(
                    parts[0].replaceAll("[^A-Za-z0-9_\\-]", ""),
                    clamp(Float.parseFloat(parts[1]), JTalkSettings.MIN_SPEED, JTalkSettings.MAX_SPEED),
                    clamp(Float.parseFloat(parts[2]), JTalkSettings.MIN_PITCH, JTalkSettings.MAX_PITCH),
                    clamp(Float.parseFloat(parts[3]), JTalkSettings.MIN_ALPHA, JTalkSettings.MAX_ALPHA),
                    clamp(Float.parseFloat(parts[4]), JTalkSettings.MIN_VOICED, JTalkSettings.MAX_VOICED),
                    clamp(Float.parseFloat(parts[5]), JTalkSettings.MIN_JF, JTalkSettings.MAX_JF));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** the .htsvoice to use here: the sender's voice if it is installed, otherwise this player's own voice */
    public synchronized String voicePath() {
        if (this.resolvedPath == null) {
            String found = this.voice.isEmpty() ? null : JTalkSettings.findVoicePath(this.voice);
            this.resolvedPath = found != null ? found : JTalkSettings.getVoicePath();
        }
        return this.resolvedPath;
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }
}
