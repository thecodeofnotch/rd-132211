package com.nikohalfkino.rubydung.audio.tts;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * settings for the Open JTalk tts engine, saved to jtalk.txt
 *
 * speed -> -r, pitch -> -fm, alpha -> -a, voiced -> -u (lower = more voiced), jf -> -jf (0 = flat pitch)
 *
 * sing mode presets override speed/voiced/jf, and kick in automatically for chat lines with notes
 *
 * singNote shifts the whole utterance in half-tones, inline [N] markers override it per word
 */
public final class JTalkSettings {

    private static final String FILE_NAME = "jtalk.txt";

    // bundled engine location (packaged builds set -Djtalk.home=$APPDIR/jtalk)
    // under it: bin/open_jtalk[.exe], open_jtalk_dic_utf_8/, voice/**.htsvoice
    // anything missing falls back to PATH / empty, jtalk.txt can override all three

    private static final String HOME = System.getProperty("jtalk.home", "jtalk");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final File VOICE_DIR = new File(HOME, "voice");

    private static final String DEFAULT_VOICE_NAME = "mei_normal";

    private static final String DEF_EXEC  = findExec();
    private static final String DEF_DIC   = findDic();
    private static final String DEF_VOICE = findVoice();

    private static String findExec() {
        File f = new File(HOME, "bin/open_jtalk" + (WINDOWS ? ".exe" : ""));
        return f.isFile() ? f.getAbsolutePath() : "open_jtalk";
    }

    private static String findDic() {
        File f = new File(HOME, "open_jtalk_dic_utf_8");
        return f.isDirectory() ? f.getAbsolutePath() : "open_jtalk_dic_utf_8";
    }

    // prefers mei_normal, else the first voice found ("" if none)
    private static String findVoice() {
        File[] voices = listVoices();
        for (File f : voices) {
            if (stem(f).equals(DEFAULT_VOICE_NAME)) return f.getAbsolutePath();
        }
        return voices.length > 0 ? voices[0].getAbsolutePath() : "";
    }

    // voices

    // jtalk/voice if it exists, else the jtalk home
    private static File voiceRoot() {
        return VOICE_DIR.isDirectory() ? VOICE_DIR : new File(HOME);
    }

    /** all bundled .htsvoice files (incl. subfolders), sorted by path */
    public static File[] listVoices() {
        File root = voiceRoot();
        if (!root.isDirectory()) return new File[0];
        try (Stream<Path> s = Files.walk(root.toPath())) {
            return s.map(Path::toFile)
                    .filter(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".htsvoice"))
                    .sorted()
                    .toArray(File[]::new);
        } catch (IOException | UncheckedIOException e) {
            return new File[0];
        }
    }

    // file name without .htsvoice, lowercase
    private static String stem(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".htsvoice") ? n.substring(0, n.length() - ".htsvoice".length()) : n;
    }

    // mei_happy -> mei
    private static String characterOf(File f) {
        String s = stem(f);
        int u = s.indexOf('_');
        return u > 0 ? s.substring(0, u) : s;
    }

    // mei_happy -> happy, "default" if there is none
    private static String moodOf(File f) {
        String s = stem(f);
        int u = s.indexOf('_');
        return u > 0 && u < s.length() - 1 ? s.substring(u + 1) : "default";
    }

    public static List<String> listCharacters() {
        TreeSet<String> set = new TreeSet<>();
        for (File f : listVoices()) set.add(characterOf(f));
        return new ArrayList<>(set);
    }

    public static List<String> listMoods(String character) {
        List<String> out = new ArrayList<>();
        for (File f : listVoices()) {
            if (characterOf(f).equals(character)) out.add(moodOf(f));
        }
        return out;
    }

    // "none" if no voice
    public static String getVoiceCharacter() {
        return voicePath.isEmpty() ? "none" : characterOf(new File(voicePath));
    }

    // "none" if no voice
    public static String getVoiceMood() {
        return voicePath.isEmpty() ? "none" : moodOf(new File(voicePath));
    }

    /** false if there is no such voice */
    public static boolean setVoice(String character, String mood) {
        for (File f : listVoices()) {
            if (characterOf(f).equals(character) && moodOf(f).equals(mood)) {
                voicePath = f.getAbsolutePath();
                return true;
            }
        }
        return false;
    }

    /** keeps the current mood if the next character has it, else "normal", else its first */
    public static void nextCharacter() {
        List<String> chars = listCharacters();
        if (chars.isEmpty()) return;

        int i = chars.indexOf(getVoiceCharacter());
        String next = chars.get((i + 1) % chars.size());

        List<String> moods = listMoods(next);
        String mood = getVoiceMood();
        if (!moods.contains(mood)) mood = moods.contains("normal") ? "normal" : moods.get(0);
        setVoice(next, mood);
    }

    /** name with or without .htsvoice, false if no bundled voice matches */
    public static boolean setVoiceByName(String name) {
        if (!notBlank(name)) return false;
        String want = name.strip().toLowerCase(Locale.ROOT);
        if (want.endsWith(".htsvoice")) want = want.substring(0, want.length() - ".htsvoice".length());

        for (File f : listVoices()) {
            if (stem(f).equals(want)) {
                voicePath = f.getAbsolutePath();
                return true;
            }
        }
        return false;
    }

    // bounds

    // how loud the voices are, for the local player's and everybody else's (on top of the sound effects volume)
    public static final float MIN_VOLUME = 0.0f;
    public static final float MAX_VOLUME = 1.0f;
    public static final float DEF_VOLUME = 1.0f;

    public static final float MIN_SPEED  = 0.3f;
    public static final float MAX_SPEED  = 3.0f;
    public static final float DEF_SPEED  = 1.0f;

    public static final float MIN_PITCH  = -24.0f;
    public static final float MAX_PITCH  =  24.0f;
    public static final float DEF_PITCH  =   0.0f;

    public static final float MIN_ALPHA  = 0.0f;
    public static final float MAX_ALPHA  = 1.0f;
    public static final float DEF_ALPHA  = 0.55f;

    public static final float MIN_JF     = 0.0f;
    public static final float MAX_JF     = 2.0f;
    public static final float DEF_JF     = 1.0f;

    public static final float MIN_VOICED = 0.0f;
    public static final float MAX_VOICED = 1.0f;
    public static final float DEF_VOICED = 0.5f;

    // sing mode presets (used instead of speed/voiced/jf)
    public static final float SING_SPEED  = 0.45f;
    // low = almost every frame is voiced so the pitch shift is audible
    public static final float SING_VOICED = 0.10f;
    // no GV on F0 = steady pitch per note
    public static final float SING_JF     = 0.00f;

    // state

    private static volatile boolean enabled   = true;

    // convert chat text to katakana before speaking
    private static volatile boolean transliterate = true;

    // "open_jtalk" works if it is on PATH
    private static volatile String  execPath  = DEF_EXEC;

    private static volatile String  dicPath   = DEF_DIC;

    private static volatile String  voicePath = DEF_VOICE;

    private static volatile float   volume    = DEF_VOLUME;
    private static volatile float   speed     = DEF_SPEED;
    private static volatile float   pitch     = DEF_PITCH;
    private static volatile float   alpha     = DEF_ALPHA;
    private static volatile float   jf        = DEF_JF;
    private static volatile float   voiced    = DEF_VOICED;

    private static volatile boolean singMode  = false;

    // half-tones used in sing mode when no marker overrides it (stacks on pitch)
    private static volatile float   singNote  = 0.0f;

    private JTalkSettings() {}

    // accessors

    public static boolean isEnabled()              { return enabled; }
    public static void    setEnabled(boolean v)    { enabled = v; }

    public static boolean isTransliterate()          { return transliterate; }
    public static void    setTransliterate(boolean v) { transliterate = v; }

    public static String  getExecPath()            { return execPath; }
    public static void    setExecPath(String v)    { if (notBlank(v)) execPath  = v.strip(); }

    public static String  getDicPath()             { return dicPath; }
    public static void    setDicPath(String v)     { if (notBlank(v)) dicPath   = v.strip(); }

    public static String  getVoicePath()           { return voicePath; }
    public static void    setVoicePath(String v)   { voicePath = notBlank(v) ? v.strip() : DEF_VOICE; }

    public static float   getVolume()              { return volume; }
    public static void    setVolume(float v)       { volume = clamp(v, MIN_VOLUME, MAX_VOLUME); }

    public static float   getSpeed()               { return speed; }
    public static void    setSpeed(float v)        { speed  = clamp(v, MIN_SPEED,  MAX_SPEED);  }

    public static float   getPitch()               { return pitch; }
    public static void    setPitch(float v)        { pitch  = clamp(v, MIN_PITCH,  MAX_PITCH);  }

    public static float   getAlpha()               { return alpha; }
    public static void    setAlpha(float v)        { alpha  = clamp(v, MIN_ALPHA,  MAX_ALPHA);  }

    public static float   getJf()                  { return jf; }
    public static void    setJf(float v)           { jf     = clamp(v, MIN_JF,     MAX_JF);     }

    public static float   getVoiced()              { return voiced; }
    public static void    setVoiced(float v)       { voiced = clamp(v, MIN_VOICED, MAX_VOICED); }

    public static boolean isSingMode()             { return singMode; }
    public static void    setSingMode(boolean v)   { singMode = v; }

    public static float   getSingNote()            { return singNote; }
    public static void    setSingNote(float v)     { singNote = clamp(v, MIN_PITCH, MAX_PITCH); }

    // resets (for the reset buttons next to the sliders)

    public static void resetVolume()   { volume   = DEF_VOLUME; }
    public static void resetSpeed()    { speed    = DEF_SPEED;  }
    public static void resetPitch()    { pitch    = DEF_PITCH;  }
    public static void resetAlpha()    { alpha    = DEF_ALPHA;  }
    public static void resetVoiced()   { voiced   = DEF_VOICED; }
    public static void resetJf()       { jf       = DEF_JF;     }
    public static void resetSingNote() { singNote = 0.0f;       }

    // load / save

    public static void load() {
        File file = new File(FILE_NAME);
        if (!file.isFile()) {
            save();          // first run: write defaults so jtalk.txt exists to edit
            return;
        }

        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        enabled  = parseBoolean(p.getProperty("enabled"),  enabled);
        transliterate = parseBoolean(p.getProperty("transliterate"), transliterate);
        singMode = parseBoolean(p.getProperty("singMode"), singMode);

        setExecPath(p.getProperty("execPath",  execPath));
        setDicPath(p.getProperty("dicPath",    dicPath));

        // A bare file name ("takumi_normal.htsvoice") means "the bundled voice with that name";
        // anything with a path in it is used as-is. Unknown names keep the default voice.
        String v = p.getProperty("voicePath", "");
        if (notBlank(v) && !v.contains("/") && !v.contains("\\")) {
            voicePath = DEF_VOICE;
            setVoiceByName(v);
        } else {
            setVoicePath(v);
        }

        volume   = clamp(parseFloat(p.getProperty("volume"),   volume),   MIN_VOLUME, MAX_VOLUME);
        speed    = clamp(parseFloat(p.getProperty("speed"),    speed),    MIN_SPEED,  MAX_SPEED);
        pitch    = clamp(parseFloat(p.getProperty("pitch"),    pitch),    MIN_PITCH,  MAX_PITCH);
        alpha    = clamp(parseFloat(p.getProperty("alpha"),    alpha),    MIN_ALPHA,  MAX_ALPHA);
        jf       = clamp(parseFloat(p.getProperty("jf"),       jf),       MIN_JF,     MAX_JF);
        voiced   = clamp(parseFloat(p.getProperty("voiced"),   voiced),   MIN_VOICED, MAX_VOICED);
        singNote = clamp(parseFloat(p.getProperty("singNote"), singNote), MIN_PITCH,  MAX_PITCH);
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("enabled",   Boolean.toString(enabled));
        p.setProperty("transliterate", Boolean.toString(transliterate));
        // Blank = "use the bundled copy", so a moved/updated install never keeps a stale absolute path.
        p.setProperty("execPath",  execPath.equals(DEF_EXEC)   ? "" : execPath);
        p.setProperty("dicPath",   dicPath.equals(DEF_DIC)     ? "" : dicPath);
        p.setProperty("voicePath", voiceForSave());
        p.setProperty("volume",    Float.toString(volume));
        p.setProperty("speed",     Float.toString(speed));
        p.setProperty("pitch",     Float.toString(pitch));
        p.setProperty("alpha",     Float.toString(alpha));
        p.setProperty("jf",        Float.toString(jf));
        p.setProperty("voiced",    Float.toString(voiced));
        p.setProperty("singMode",  Boolean.toString(singMode));
        p.setProperty("singNote",  Float.toString(singNote));

        try (FileOutputStream out = new FileOutputStream(FILE_NAME)) {
            p.store(out, "Open JTalk TTS settings — see JTalkSettings.java");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // blank for the default voice, file name for a bundled one, full path otherwise
    private static String voiceForSave() {
        if (voicePath.equals(DEF_VOICE)) return "";
        File f = new File(voicePath);
        boolean bundled = f.getAbsoluteFile().toPath().normalize()
                .startsWith(voiceRoot().getAbsoluteFile().toPath().normalize());
        return bundled ? f.getName() : voicePath;
    }

    // helpers

    /** file name of the current voice without .htsvoice, "" if there is none: what is sent to the other players */
    public static String getVoiceName() {
        String path = voicePath;
        return path == null || path.isEmpty() ? "" : stem(new File(path));
    }

    /** absolute path of the bundled voice of that name (with or without .htsvoice), null if none matches */
    public static String findVoicePath(String name) {
        if (!notBlank(name)) return null;
        String want = name.strip().toLowerCase(Locale.ROOT);
        if (want.endsWith(".htsvoice")) want = want.substring(0, want.length() - ".htsvoice".length());
        for (File f : listVoices()) {
            if (stem(f).equals(want)) return f.getAbsolutePath();
        }
        return null;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static float parseFloat(String v, float fallback) {
        if (v == null) return fallback;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return fallback; }
    }

    private static boolean parseBoolean(String v, boolean fallback) {
        if ("true".equalsIgnoreCase(v))  return true;
        if ("false".equalsIgnoreCase(v)) return false;
        return fallback;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}