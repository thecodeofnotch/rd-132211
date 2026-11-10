package com.nikohalfkino.rubydung.audio.tts;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * async Open JTalk wrapper: text -> katakana -> open_jtalk -> wav played through java audio
 *
 * one daemon thread works through a bounded queue so the game thread never blocks
 *
 * lines with a note (bah<235,30>) or a pitch marker ([+7], [G4]) switch to sing mode automatically,
 * presets are in JTalkSettings. each marked segment is its own open_jtalk call, played on the same line
 *
 * a note whose "phoneme" is a dot (.<200,00>) is a pause: silence for that many ms, nothing is rendered
 */
public final class JTalkSpeaker {

    // new utterances are dropped when it's full
    private static final int QUEUE_CAPACITY = 6;

    // inline pitch markers: [+5] [-3] [0] [C4] [G#4] [Bb3]
    // (public so the chat input can highlight exactly what the speaker will parse)
    public static final Pattern PITCH_MARKER =
            Pattern.compile("\\[([+\\-]?\\d+(?:\\.\\d+)?|[A-Ga-g][#Bb]?\\d?)\\]");

    // moonbase alpha note: phoneme<duration-ms,pitch>, e.g. bah<235,30>
    // brackets aren't part of the pattern, a whole phrase sits in one [...] group
    public static final Pattern SINGING_NOTE =
            Pattern.compile("([^\\s\\[\\]<>,]+)<(\\d+),([^\\s\\[\\]<>,]+)>");

    // addresses in a message (http://..., https://..., www....): never read out, they would be spelled letter by letter
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    // note letter -> semitones from C
    private static final Map<Character, Integer> NOTE_BASE;
    static {
        Map<Character, Integer> m = new HashMap<>();
        m.put('C', 0); m.put('D', 2); m.put('E', 4); m.put('F', 5);
        m.put('G', 7); m.put('A', 9); m.put('B', 11);
        NOTE_BASE = Collections.unmodifiableMap(m);
    }

    // segments

    // a run of kana spoken at one pitch offset
    private static final class Segment {
        // whose voice this is, null = this player's own settings
        VoiceProfile profile;
        final String kana;
        // half-tones on top of the settings pitch
        final float pitchOffset;
        final boolean sing;
        // -1 for an ordinary segment
        final int durationMs;
        // a pause: no kana, durationMs of silence
        final boolean rest;

        Segment(String kana, float pitchOffset, boolean sing) {
            this(kana, pitchOffset, sing, -1, false);
        }

        Segment(String kana, float pitchOffset, boolean sing, int durationMs) {
            this(kana, pitchOffset, sing, durationMs, false);
        }

        private Segment(String kana, float pitchOffset, boolean sing, int durationMs, boolean rest) {
            this.kana = kana;
            this.pitchOffset = pitchOffset;
            this.sing = sing;
            this.durationMs = durationMs;
            this.rest = rest;
        }

        static Segment rest(int durationMs) {
            return new Segment("", 0.0f, true, durationMs, true);
        }
    }

    // lifecycle

    // what is waiting to be said, and in whose voice
    private static final class Utterance {
        final String text;
        final VoiceProfile profile;

        Utterance(String text, VoiceProfile profile) {
            this.text = text;
            this.profile = profile;
        }
    }

    private final BlockingDeque<Utterance> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);

    // a positional speaker is somebody else in the world: its audio is stereo and scaled by gain / pan, which the
    // owner updates while it plays (setPosition)
    private final boolean positional;
    private final int workerCount;
    private volatile float gain = 1.0f;
    private volatile float pan = 0.0f;
    private volatile boolean running;
    private boolean warnedNoVoice;
    private Thread thread;

    // bumped by clear() so a playing utterance knows to stop
    private final AtomicInteger generation = new AtomicInteger();

    // polled by getViseme(), null when nothing plays
    private volatile SourceDataLine activeLine;

    // mouth cues for the audio written to activeLine, in its timeline
    private final List<Visemes.Cue> cues = new java.util.concurrent.CopyOnWriteArrayList<>();

    // audio written to the current line so far (worker thread only)
    private long queuedMicros;

    // renders the notes of one utterance in parallel
    private ExecutorService pool;

    // finished sing renders keyed by voice + params + kana
    private static final int CACHE_ENTRIES = 96;
    private final Map<String, Pcm> cache = new LinkedHashMap<String, Pcm>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pcm> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /** the speaker of the local player */
    public JTalkSpeaker() {
        this(false, Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
    }

    /** positional = another player heard in the world, workers = threads that render its notes */
    public JTalkSpeaker(boolean positional, int workers) {
        this.positional = positional;
        this.workerCount = Math.max(1, workers);
    }

    /** where the voice is right now: gain 0..1 (distance) and pan -1 (left) .. 1 (right). only used by positional speakers */
    public void setPosition(float gain, float pan) {
        this.gain = Math.max(0.0f, Math.min(1.0f, gain));
        this.pan = Math.max(-1.0f, Math.min(1.0f, pan));
    }

    public synchronized void start() {
        if (this.thread != null) return;
        this.running = true;
        int workers = this.workerCount;
        this.pool = Executors.newFixedThreadPool(workers, r -> {
            Thread th = new Thread(r, "JTalk-render");
            th.setDaemon(true);
            return th;
        });
        this.thread = new Thread(this::loop, "JTalk");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public synchronized void stop() {
        this.running = false;
        if (this.pool != null) {
            this.pool.shutdownNow();
            this.pool = null;
        }
        if (this.thread != null) {
            this.thread.interrupt();
            this.thread = null;
        }
    }

    // public api

    /** no-op if disabled, blank or the queue is full, safe from any thread */
    public void speak(String text) {
        speak(text, null);
    }

    /** in somebody's voice (null = this player's own settings). same rules as speak(text) */
    public void speak(String text, VoiceProfile profile) {
        if (!JTalkSettings.isEnabled() || text == null) return;
        text = LINK.matcher(text).replaceAll(" ").trim();
        if (text.isEmpty()) return;   // nothing but links
        this.queue.offer(new Utterance(text, profile));   // non-blocking; drops if full
    }

    /** also cuts off the one that's playing */
    public void clear() {
        this.queue.clear();
        this.generation.incrementAndGet();
    }

    /** mouth shape for what is audible right now (CLOSED when nothing plays), safe to call every frame */
    public Visemes.Viseme getViseme() {
        SourceDataLine l = this.activeLine;
        if (l == null) return Visemes.Viseme.CLOSED;
        long pos = l.getMicrosecondPosition();
        for (Visemes.Cue c : this.cues) {
            if (pos >= c.startMicros && pos < c.endMicros) return c.viseme;
        }
        return Visemes.Viseme.CLOSED;
    }

    // worker loop

    private void loop() {
        while (this.running) {
            try {
                Utterance utterance = this.queue.take();
                handleUtterance(utterance);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("JTalkSpeaker: " + e);
            }
        }
    }

    private void handleUtterance(Utterance utterance) throws Exception {
        String raw = utterance.text;
        String voicePath = utterance.profile != null ? utterance.profile.voicePath() : JTalkSettings.getVoicePath();
        if (voicePath.isEmpty()) {
            if (!this.warnedNoVoice) {
                this.warnedNoVoice = true;
                System.err.println("JTalkSpeaker: no voicePath set in jtalk.txt (point it at a .htsvoice file).");
            }
            return;
        }
        List<Segment> segments = buildSegments(raw);
        if (segments.isEmpty()) return;
        for (Segment seg : segments) seg.profile = utterance.profile;

        final int gen = this.generation.get();
        final ExecutorService workers = this.pool;
        if (workers == null) return;

        // Start rendering EVERY note right away, in parallel. Playback below consumes them in
        // order, so by the time note 1 has finished playing, notes 2..n are already waiting.
        List<Future<Pcm>> jobs = new ArrayList<>();
        List<Segment> jobSegs = new ArrayList<>();   // jobSegs.get(i) is the segment of jobs.get(i)
        for (Segment seg : segments) {
            if (seg.rest) {
                // nothing to render, the silence is written in order during playback
                jobSegs.add(seg);
                jobs.add(null);
                continue;
            }
            if (seg.kana.isBlank()) continue;
            jobSegs.add(seg);
            jobs.add(workers.submit((Callable<Pcm>) () -> render(seg)));
        }

        SourceDataLine line = null;
        boolean aborted = false;
        // pause time from before the first sound, played once the line (and so its format) exists
        int pendingRestMs = 0;
        try {
            for (int j = 0; j < jobs.size(); j++) {
                Future<Pcm> job = jobs.get(j);
                if (!this.running || gen != this.generation.get()) { aborted = true; break; }

                Segment current = jobSegs.get(j);
                if (current.rest) {
                    if (line == null) {
                        pendingRestMs += current.durationMs;
                    } else if (writeSilence(line, current.durationMs, gen)) {
                        this.queuedMicros += current.durationMs * 1000L;
                    } else {
                        aborted = true;
                        break;
                    }
                    continue;
                }

                Pcm pcm;
                try {
                    pcm = job.get();
                } catch (ExecutionException e) {
                    System.err.println("JTalkSpeaker: " + e.getCause());
                    continue;
                }
                if (pcm == null || pcm.data.length == 0) continue;

                AudioFormat fmt = pcm.format;
                AudioFormat outFmt = outputFormat(fmt);
                if (line == null || !formatsMatch(line.getFormat(), outFmt)) {
                    if (line != null) { line.drain(); line.close(); line = null; this.activeLine = null; }
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, outFmt);
                    if (!AudioSystem.isLineSupported(info)) {
                        System.err.println("JTalkSpeaker: unsupported format: " + outFmt);
                        continue;
                    }
                    line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(outFmt);
                    line.start();
                    this.cues.clear();
                    this.queuedMicros = 0;
                    this.activeLine = line;
                }

                if (pendingRestMs > 0) {
                    if (!writeSilence(line, pendingRestMs, gen)) { aborted = true; break; }
                    this.queuedMicros += pendingRestMs * 1000L;
                    pendingRestMs = 0;
                }

                // Mouth cues for this segment, placed after everything already written to the line.
                this.cues.addAll(Visemes.build(jobSegs.get(j).kana, pcm.millis(), this.queuedMicros));
                this.queuedMicros += (long) (pcm.millis() * 1000);

                int frame = Math.max(1, fmt.getFrameSize());
                int offset = 0;
                while (offset < pcm.data.length) {
                    if (gen != this.generation.get()) { aborted = true; break; }
                    int count = Math.min(4096, pcm.data.length - offset);
                    count -= count % frame;
                    if (count <= 0) break;
                    if (this.positional && isPcm16(fmt)) {
                        byte[] shaped = shape(pcm.data, offset, count, fmt);
                        line.write(shaped, 0, shaped.length);
                    } else if (isPcm16(fmt) && JTalkSettings.getVolume() < 0.999f) {
                        // the local player's own voice: the voice volume setting, read per chunk so the slider is heard at once
                        byte[] scaled = scaleVolume(pcm.data, offset, count, JTalkSettings.getVolume());
                        line.write(scaled, 0, scaled.length);
                    } else {
                        line.write(pcm.data, offset, count);
                    }
                    offset += count;
                }
                if (aborted) break;
            }
        } finally {
            for (Future<Pcm> job : jobs) if (job != null) job.cancel(true);
            if (line != null) {
                if (aborted || !this.running) line.flush(); else line.drain();
                line.close();
            }
            this.activeLine = null;
            this.cues.clear();
        }
    }

    // the format written to the line: a positional voice is always 16-bit stereo, so it can be panned
    private AudioFormat outputFormat(AudioFormat source) {
        if (!this.positional || !isPcm16(source) || source.getChannels() == 2) return source;
        return new AudioFormat(source.getSampleRate(), 16, 2, true, false);
    }

    // count bytes of source audio as stereo, scaled by the current distance gain and equal-power pan
    private byte[] shape(byte[] data, int offset, int count, AudioFormat source) {
        float g = this.gain;
        double angle = (this.pan + 1.0) * Math.PI / 4.0;
        float left = (float) (g * Math.cos(angle));
        float right = (float) (g * Math.sin(angle));

        int channels = source.getChannels();
        int frames = count / source.getFrameSize();
        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            int o = offset + i * source.getFrameSize();
            int l = (short) ((data[o + 1] << 8) | (data[o] & 0xFF));
            int r = channels > 1 ? (short) ((data[o + 3] << 8) | (data[o + 2] & 0xFF)) : l;
            short sl = (short) Math.max(-32768, Math.min(32767, Math.round(l * left)));
            short sr = (short) Math.max(-32768, Math.min(32767, Math.round(r * right)));
            out[i * 4] = (byte) sl;
            out[i * 4 + 1] = (byte) (sl >> 8);
            out[i * 4 + 2] = (byte) sr;
            out[i * 4 + 3] = (byte) (sr >> 8);
        }
        return out;
    }

    // 16-bit little endian samples times volume
    private static byte[] scaleVolume(byte[] data, int offset, int count, float volume) {
        byte[] out = new byte[count];
        for (int i = 0; i + 1 < count; i += 2) {
            int sample = (short) ((data[offset + i + 1] << 8) | (data[offset + i] & 0xFF));
            short scaled = (short) Math.max(-32768, Math.min(32767, Math.round(sample * volume)));
            out[i] = (byte) scaled;
            out[i + 1] = (byte) (scaled >> 8);
        }
        return out;
    }

    // writes ms of silence (16-bit PCM, which is what open_jtalk writes), false if the utterance was cut off meanwhile
    private boolean writeSilence(SourceDataLine line, int ms, int gen) {
        AudioFormat fmt = line.getFormat();
        int frame = Math.max(1, fmt.getFrameSize());
        int remaining = (int) Math.round(fmt.getFrameRate() * ms / 1000.0) * frame;
        byte[] zeros = new byte[Math.max(frame, 4096 - 4096 % frame)];

        while (remaining > 0) {
            if (!this.running || gen != this.generation.get()) return false;
            int count = Math.min(zeros.length, remaining);
            count -= count % frame;
            if (count <= 0) break;
            line.write(zeros, 0, count);
            remaining -= count;
        }
        return true;
    }

    // text segmentation

    // splits into kana segments, one per pitch marker (plain text and marker-less notes get a single one)
    private List<Segment> buildSegments(String raw) {
        // The duration/pitch note syntax always enables singing, regardless of the settings toggle.
        // Most importantly, only the phoneme (group 1) is passed through the kana converter;
        // the surrounding brackets and <duration,pitch> metadata are never spoken.
        Matcher notes = SINGING_NOTE.matcher(raw);
        List<Segment> noteSegments = new ArrayList<>();
        boolean foundNote = false;
        while (notes.find()) {
            foundNote = true;
            String phoneme = notes.group(1).trim();
            boolean pause = isRest(phoneme);
            String kana = pause ? "" : singingKana(phoneme);
            int durationMs;
            try {
                durationMs = Integer.parseInt(notes.group(2));
            } catch (NumberFormatException ex) {
                continue;
            }
            durationMs = Math.max(1, Math.min(30000, durationMs));
            float pitchOffset = parseSingingPitch(notes.group(3).trim());
            if (pause) {
                noteSegments.add(Segment.rest(durationMs));
            } else if (!kana.isBlank()) {
                noteSegments.add(new Segment(kana, pitchOffset, true, durationMs));
            }
        }
        if (foundNote) return noteSegments;

        boolean sing = containsSingingNotes(raw);
        if (!sing) {
            String kana = toKana(PITCH_MARKER.matcher(raw).replaceAll(" "));
            return kana.isBlank() ? Collections.emptyList()
                    : Collections.singletonList(new Segment(kana, 0.0f, false));
        }

        Matcher m = PITCH_MARKER.matcher(raw);
        if (!m.find()) {
            String kana = toKana(raw);
            return kana.isBlank() ? Collections.emptyList()
                    : Collections.singletonList(new Segment(kana, JTalkSettings.getSingNote(), true));
        }

        List<Segment> out = new ArrayList<>();
        int pos = 0;
        float currentOffset = JTalkSettings.getSingNote();
        m.reset();
        while (m.find()) {
            if (m.start() > pos) addSegment(raw, pos, m.start(), currentOffset, out);
            currentOffset = parseMarker(m.group(1));
            pos = m.end();
        }
        if (pos < raw.length()) addSegment(raw, pos, raw.length(), currentOffset, out);
        return out;
    }

    // converts to katakana, or passes through if "Transliterate Voice" is off
    private static String toKana(String text) {
        return JTalkSettings.isTransliterate() ? Katakana.toKana(text) : text;
    }

    // same switch for the phoneme of a note
    private static String singingKana(String phoneme) {
        return JTalkSettings.isTransliterate() ? Katakana.toSingingKana(phoneme) : phoneme;
    }

    // numeric singing scale (25 = C4) or note name -> C4-relative offset
    private static float parseSingingPitch(String value) {
        try {
            return Float.parseFloat(value) - 25.0f;
        } catch (NumberFormatException ignored) {
            return parseMarker(value);
        }
    }

    // true if there's a real note: a number in range ([0], [+7]) or a note name + octave ([G4]), a bare [a] doesn't count
    private static boolean containsSingingNotes(String raw) {
        if (SINGING_NOTE.matcher(raw).find()) return true;
        Matcher m = PITCH_MARKER.matcher(raw);
        while (m.find()) {
            if (isSingingMarker(m.group(1))) return true;
        }
        return false;
    }

    /** a "phoneme" of only dots is a pause, .<200,00> is 200 ms of silence (the pitch is ignored) */
    public static boolean isRest(String phoneme) {
        return phoneme != null && phoneme.matches("\\.+");
    }

    /** whether the inside of a [..] pitch marker counts as a note: a number in range or a note name + octave, a bare [a] doesn't */
    public static boolean isSingingMarker(String g) {
        if (g == null || g.isEmpty()) return false;
        char first = g.charAt(0);
        if (first == '+' || first == '-' || Character.isDigit(first)) {
            try {
                return Math.abs(Float.parseFloat(g)) <= JTalkSettings.MAX_PITCH;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return Character.isDigit(g.charAt(g.length() - 1));   // letter, optional accidental, octave digit
    }

    private static final Pattern NOTE_NAME = Pattern.compile("[A-Ga-g][#Bb]?\\d?");

    /** whether the pitch of a note (the 30 in bah<235,30>) is something parseSingingPitch understands: a number or a note name */
    public static boolean isValidPitch(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Float.parseFloat(value);
            return true;
        } catch (NumberFormatException ignored) {
            return NOTE_NAME.matcher(value).matches();
        }
    }

    private static void addSegment(String raw, int from, int to, float offset, List<Segment> out) {
        String chunk = raw.substring(from, to).trim();
        if (chunk.isEmpty()) return;
        String kana = toKana(chunk);
        if (!kana.isBlank()) out.add(new Segment(kana, offset, true));
    }

    // "+5" -> 5, "G4" -> 7 (semitones from C4), "G#4" -> 8, "Bb3" -> -2
    private static float parseMarker(String s) {
        // Numeric offset: "+5", "-3", "0", "7.5" …
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException ignored) {
        }

        // Note name: letter + optional accidental + optional octave digit.
        char letter = Character.toUpperCase(s.charAt(0));
        Integer base = NOTE_BASE.get(letter);
        if (base == null) return 0.0f;

        int idx = 1;
        int accidental = 0;
        if (idx < s.length()) {
            char a = s.charAt(idx);
            if (a == '#') { accidental =  1; idx++; }
            else if (a == 'b' || a == 'B') { accidental = -1; idx++; }
        }

        int octave = 4;
        if (idx < s.length() && Character.isDigit(s.charAt(idx))) {
            octave = s.charAt(idx) - '0';
        }

        // Semitone distance from C4.  C4 = 0, D4 = 2, G4 = 7, C5 = 12, G3 = -5 …
        return (octave - 4) * 12.0f + base + accidental;
    }

    // synthesis

    // decoded, silence-trimmed audio for one segment
    private static final class Pcm {
        final AudioFormat format;
        final byte[] data;

        Pcm(AudioFormat format, byte[] data) {
            this.format = format;
            this.data = data;
        }

        double millis() {
            int fs = Math.max(1, format.getFrameSize());
            return 1000.0 * (data.length / fs) / format.getFrameRate();
        }
    }

    // peak below this (of 32768) counts as silence when trimming
    private static final int SILENCE_PEAK = 400;

    // plain segments are synthesised once, notes with a duration get long-vowel marks (ー) appended
    // in as few open_jtalk runs as possible (usually 1-2), then are fitted to the exact length
    private Pcm render(Segment seg) throws Exception {
        Pcm pcm = synthPcm(seg.kana, seg);
        if (pcm == null || seg.durationMs <= 0) return pcm;

        double target = seg.durationMs;
        // First guess for how long one mora lasts, refined after every pass.
        double perMora = Math.max(30.0, pcm.millis() / Math.max(1, seg.kana.length()));
        int extra = 0;

        for (int pass = 0; pass < 3 && pcm.millis() < target; pass++) {
            int add = (int) Math.ceil((target - pcm.millis()) * 1.1 / perMora);   // overshoot is free: it gets trimmed
            add = Math.max(1, Math.min(add, 60));
            extra += add;

            Pcm longer = synthPcm(seg.kana + "ー".repeat(extra), seg);
            if (longer == null || !formatsMatch(pcm.format, longer.format)) break;

            double grown = longer.millis() - pcm.millis();
            if (grown > 0) perMora = Math.max(30.0, grown / add);
            pcm = longer;
        }
        return fitToDuration(pcm, seg.durationMs);
    }

    // cuts (with a short fade-out) or pads with silence to exactly ms
    private static Pcm fitToDuration(Pcm in, int ms) {
        int fs = Math.max(1, in.format.getFrameSize());
        int frames = (int) Math.round(in.format.getFrameRate() * ms / 1000.0);
        byte[] out = new byte[frames * fs];
        System.arraycopy(in.data, 0, out, 0, Math.min(in.data.length, out.length));
        if (in.data.length > out.length) {
            fade(out, in.format, 0, (int) (in.format.getFrameRate() * 0.015));
        }
        return new Pcm(in.format, out);
    }

    // runs open_jtalk (or hits the cache), null on failure
    private Pcm synthPcm(String kana, Segment seg) throws IOException, InterruptedException,
            UnsupportedAudioFileException {
        String key = voicePathOf(seg) + '|' + String.join(",", params(seg)) + '|' + kana;
        if (seg.sing) {
            synchronized (this.cache) {
                Pcm hit = this.cache.get(key);
                if (hit != null) return hit;
            }
        }

        File wav = Files.createTempFile("jtalk_", ".wav").toFile();
        try {
            if (!synthesise(kana, seg, wav)) return null;
            Pcm pcm;
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(wav)) {
                pcm = trimSilence(new Pcm(ais.getFormat(), ais.readAllBytes()));
            }
            if (seg.sing) {
                synchronized (this.cache) {
                    this.cache.put(key, pcm);
                }
            }
            return pcm;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            wav.delete();
        }
    }

    // silence trimming / fades (16-bit little-endian PCM, which is what open_jtalk writes)

    private static boolean isPcm16(AudioFormat f) {
        return f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && f.getSampleSizeInBits() == 16 && !f.isBigEndian() && f.getFrameSize() > 0;
    }

    private static int framePeak(byte[] d, int frame, int frameSize) {
        int peak = 0;
        int base = frame * frameSize;
        for (int o = base; o + 1 < base + frameSize && o + 1 < d.length; o += 2) {
            int s = Math.abs((short) ((d[o + 1] << 8) | (d[o] & 0xFF)));
            if (s > peak) peak = s;
        }
        return peak;
    }

    // open_jtalk pads utterances with silence (stretched ~2x in sing mode), which would become the gap between notes
    private static Pcm trimSilence(Pcm in) {
        AudioFormat f = in.format;
        if (!isPcm16(f)) return in;
        int fs = f.getFrameSize();
        int frames = in.data.length / fs;

        int first = 0;
        while (first < frames && framePeak(in.data, first, fs) < SILENCE_PEAK) first++;
        if (first >= frames) return in;                       // all quiet: leave it alone
        int last = frames - 1;
        while (last > first && framePeak(in.data, last, fs) < SILENCE_PEAK) last--;

        int margin = (int) (f.getFrameRate() * 0.003);
        first = Math.max(0, first - margin);
        last = Math.min(frames - 1, last + margin);

        byte[] out = Arrays.copyOfRange(in.data, first * fs, (last + 1) * fs);
        fade(out, f, (int) (f.getFrameRate() * 0.004), (int) (f.getFrameRate() * 0.006));
        return new Pcm(f, out);
    }

    // linear fade in/out in place so cuts don't click
    private static void fade(byte[] d, AudioFormat f, int inFrames, int outFrames) {
        if (!isPcm16(f)) return;
        int fs = f.getFrameSize();
        int ch = Math.max(1, f.getChannels());
        int frames = d.length / fs;
        for (int i = 0; i < frames; i++) {
            double g = 1.0;
            if (i < inFrames) g = Math.min(g, (double) i / inFrames);
            int fromEnd = frames - 1 - i;
            if (fromEnd < outFrames) g = Math.min(g, (double) fromEnd / outFrames);
            if (g >= 1.0) continue;
            for (int c = 0; c < ch; c++) {
                int o = i * fs + c * 2;
                short s = (short) ((d[o + 1] << 8) | (d[o] & 0xFF));
                s = (short) Math.round(s * g);
                d[o] = (byte) s;
                d[o + 1] = (byte) (s >> 8);
            }
        }
    }

    // process

    // runs open_jtalk, true if it exited cleanly and wrote a non-empty file
    private boolean synthesise(String kana, Segment seg, File out) throws IOException, InterruptedException {
        File textFile = Files.createTempFile("jtalk_in_", ".txt").toFile();
        Process proc = null;
        try {
            try (PrintWriter w = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(textFile), StandardCharsets.UTF_8))) {
                w.println(kana);
            }

            ProcessBuilder pb = buildCommand(out, textFile, seg);
            pb.redirectErrorStream(true);

            proc = pb.start();
            try (InputStream drain = proc.getInputStream()) {
                drain.transferTo(OutputStream.nullOutputStream());
            }

            int exit = proc.waitFor();
            if (exit != 0) {
                System.err.println("JTalkSpeaker: open_jtalk exited " + exit + " for: " + kana);
                return false;
            }
            return out.length() > 0;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();   // cancelled mid-synthesis
            //noinspection ResultOfMethodCallIgnored
            textFile.delete();
        }
    }

    // speed, pitch, alpha, voiced, jf as open_jtalk args
    private static String[] params(Segment seg) {
        VoiceProfile p = seg.profile;
        float speed  = seg.sing ? JTalkSettings.SING_SPEED  : (p != null ? p.speed : JTalkSettings.getSpeed());
        float voiced = seg.sing ? JTalkSettings.SING_VOICED : (p != null ? p.voiced : JTalkSettings.getVoiced());
        float jf     = seg.sing ? JTalkSettings.SING_JF     : (p != null ? p.jf : JTalkSettings.getJf());
        float pitch  = (p != null ? p.pitch : JTalkSettings.getPitch()) + seg.pitchOffset;
        float alpha  = p != null ? p.alpha : JTalkSettings.getAlpha();
        return new String[]{fmt(speed), fmt(pitch), fmt(alpha), fmt(voiced), fmt(jf)};
    }

    private static ProcessBuilder buildCommand(File wavOut, File textIn, Segment seg) {
        String[] p = params(seg);
        ProcessBuilder pb = new ProcessBuilder(
                JTalkSettings.getExecPath(),
                "-x",  JTalkSettings.getDicPath(),
                "-m",  voicePathOf(seg),
                "-ow", wavOut.getAbsolutePath(),
                "-r",  p[0],
                "-fm", p[1],
                "-a",  p[2],
                "-u",  p[3],
                "-jf", p[4],
                textIn.getAbsolutePath()
        );
        pb.environment().put("LANG", "ja_JP.UTF-8");
        return pb;
    }

    // helpers

    private static String voicePathOf(Segment seg) {
        return seg.profile != null ? seg.profile.voicePath() : JTalkSettings.getVoicePath();
    }

    // locale-safe float format (always '.')
    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static boolean formatsMatch(AudioFormat a, AudioFormat b) {
        return a.getSampleRate()    == b.getSampleRate()
                && a.getSampleSizeInBits() == b.getSampleSizeInBits()
                && a.getChannels()      == b.getChannels()
                && a.isBigEndian()      == b.isBigEndian();
    }
}