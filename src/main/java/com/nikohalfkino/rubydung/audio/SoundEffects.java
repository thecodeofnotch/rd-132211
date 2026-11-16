package com.nikohalfkino.rubydung.audio;

import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.util.GameSettings;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * fire-and-forget sound effects, defined in /assets/rubydung/audio.toml ([sounds.*] and [tile_sounds.*])
 *
 * play("door_open") looks the name up in audio.toml. a name that is not in the file still works the old way:
 * "door_open" is sfx/tiles/door_open.ogg and "ui/menu_open" is sfx/ui/menu_open.ogg, so existing calls keep working
 *
 * every file is decoded once and kept as pcm. each playing sound is a "voice"; ONE mixer thread adds all voices
 * together and writes them to ONE line, so any number of sounds overlap and nothing depends on how many lines
 * the OS allows
 *
 * what keeps it from hiccuping:
 * - the line is never left to run dry: when nothing plays the mixer writes silence. a drained line has to be
 *   restarted by the driver on the next sound, which is what made the first sound after a pause late or crackly
 * - the line buffer is sized in milliseconds from the toml (mixer.buffer_ms). it is the cushion against the
 *   mixer thread being delayed (GC pause, a busy core). too small = crackles, too big = sounds feel late
 * - all sounds are decoded in the background at startup, a first play never decodes on the hot path
 * - the hot path does not box, lock or build strings; gap limiting is a field on the sound
 * - many voices at once are soft clipped instead of hard clipped, and voices cut for MAX_VOICES fade out
 *   over one block instead of stopping mid wave (a click)
 */
public final class SoundEffects {

    private static final String PATH = "/assets/rubydung/sfx/";
    private static final String LEGACY_FOLDER = "tiles/";

    /** pass as the variation to use the sound's own pitch_variation */
    private static final float OWN_VARIATION = Float.NaN;

    /** pass as the fraction to pick a random pitch, otherwise 0..1 = where in the pitch range to sit */
    private static final float RANDOM_PITCH = Float.NaN;

    // spreads small ints (key codes) evenly over 0..1, so neighbouring keys end up far apart
    private static final double GOLDEN = 0.6180339887498949;

    private static final Sample MISSING = new Sample(null, 0, 0, 0);

    private static volatile AudioConfig config = AudioConfig.load();

    // mixer parameters are fixed once the line is open, reload() leaves them alone
    private static final int OUT_RATE = config.outputRate;
    private static final int OUT_CHANNELS = 2;
    private static final int BLOCK_FRAMES = config.blockFrames;
    private static final int MAX_VOICES = config.maxVoices;

    // path -> decoded pcm. MISSING is cached too so a bad file is only looked for (and reported) once
    private static final Map<String, Sample> CACHE = new ConcurrentHashMap<>();

    // name -> runtime sound. holds the gap timer, so it has to survive between plays
    private static volatile Map<String, Sound> sounds = buildSounds(config);
    private static final Map<String, Sound> LEGACY = new ConcurrentHashMap<>();

    // decoding only, never touches the mixer. two threads: one for startup preloading, one for plays that
    // arrive before their file is ready, so a long preload can't hold a sound back
    private static final ExecutorService PRELOADER = daemonPool("SoundEffects-Preload", Thread.NORM_PRIORITY - 1);
    private static final ExecutorService ON_DEMAND = daemonPool("SoundEffects-Loader", Thread.NORM_PRIORITY);

    // new voices wait here until the mixer picks them up
    private static final BlockingQueue<Voice> INCOMING = new LinkedBlockingQueue<>();
    private static volatile boolean mixerFailed;

    static {
        Thread mixer = new Thread(SoundEffects::mixLoop, "SoundEffects-Mixer");
        mixer.setDaemon(true);
        if (config.threadPriorityHigh) {
            // the mixer only wakes every few ms and does very little, giving it priority costs the game nothing
            // and stops a render thread running at thousands of fps from delaying it
            mixer.setPriority(Thread.MAX_PRIORITY);
        }
        mixer.start();

        preloadAll();
    }

    private SoundEffects() {}

    // ---- volume ----

    // the SFX slider in options, 1.0 = as recorded
    public static void setMasterVolume(float volume) {
        GameSettings.setSfxVolume(volume);
    }

    public static float getMasterVolume() {
        return GameSettings.getSfxVolume();
    }

    // ---- playing ----

    public static void play(String name) {
        play(name, 1.0F, 1.0F);
    }

    public static void play(String name, float volume) {
        play(name, volume, 1.0F);
    }

    /** volume and pitch multiply what audio.toml says for the sound. pitch 1.0 = as defined, higher = higher and shorter */
    public static void play(String name, float volume, float pitch) {
        trigger(find(name), volume, pitch, OWN_VARIATION, RANDOM_PITCH);
    }

    /**
     * kept for old call sites. if audio.toml defines "prefix" that definition is used (and variants is ignored),
     * otherwise it picks prefix_1 .. prefix_variants at random with a slight pitch change
     */
    public static void playRandom(String prefix, int variants) {
        playRandom(prefix, variants, config.defaultPitchVariation);
    }

    public static void playRandom(String prefix, int variants, float pitchVariation) {
        Sound defined = sounds.get(prefix);
        if (defined != null) {
            trigger(defined, 1.0F, 1.0F, OWN_VARIATION, RANDOM_PITCH);
            return;
        }

        Sound legacy = LEGACY.get(prefix);
        if (legacy == null || legacy.spec.files.length != variants) {
            String[] files = new String[Math.max(1, variants)];
            for (int i = 0; i < files.length; i++) {
                files[i] = legacyFile(prefix + "_" + (i + 1));
            }
            legacy = new Sound(prefix, files, config);
            LEGACY.put(prefix, legacy);
        }
        trigger(legacy, 1.0F, 1.0F, pitchVariation, RANDOM_PITCH);
    }

    private static final java.util.concurrent.ScheduledExecutorService LATER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SoundEffects-Later");
                thread.setDaemon(true);
                return thread;
            });

    /** plays the sound after a delay (a purr after the pat) */
    public static void playLater(String name, float volume, long delayMillis) {
        LATER.schedule(() -> play(name, volume), delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * a sound that repeats until stop(): a cube dragged over the floor. setVolume() is read by the mixer every block
     * and eased, so it can follow what is happening without clicks. stop() fades it out
     */
    public static final class Loop {
        volatile float target;
        volatile boolean stopped;

        public void setVolume(float volume) {
            this.target = Math.max(0.0F, volume);
        }

        public void stop() {
            this.stopped = true;
        }
    }

    /** starts a looping sound (the first file of the sound's definition) at the given volume, with its pitch from audio.toml */
    public static Loop startLoop(String name, float volume) {
        Loop loop = new Loop();
        loop.target = Math.max(0.0F, volume);
        if (mixerFailed) {
            return loop;
        }

        Sound sound = find(name);
        AudioConfig.SoundSpec spec = sound.spec;
        String file = spec.files[0];
        float gain = spec.volume;
        float pitch = spec.pitch;
        Sample cached = CACHE.get(file);
        if (cached != null) {
            startLoopVoice(cached, gain, pitch, loop);
        } else {
            ON_DEMAND.execute(() -> startLoopVoice(load(file), gain, pitch, loop));
        }
        return loop;
    }

    private static void startLoopVoice(Sample sample, float gain, float pitch, Loop loop) {
        if (sample == MISSING || mixerFailed || loop.stopped) {
            return;
        }
        double step = Math.max(0.01D, pitch) * sample.sampleRate / OUT_RATE;
        INCOMING.offer(new Voice(sample, gain, step, loop));
    }

    /** like playRandom, for a single file */
    public static void playVaried(String name) {
        trigger(find(name), 1.0F, 1.0F, OWN_VARIATION, RANDOM_PITCH);
    }

    /**
     * for typing: every key gets its own fixed pitch inside the sound's range (pitch_min / pitch_max, or
     * pitch +- pitch_variation if no range is set), and the same key always gets the same pitch.
     * give it the key code, e.g. Keyboard.getEventKey()
     */
    public static void playKey(String name, int keyCode) {
        float fraction = (float) ((Math.abs((long) keyCode) * GOLDEN) % 1.0);
        trigger(find(name), 1.0F, 1.0F, OWN_VARIATION, fraction);
    }

    /** whether a sound can be played: defined in audio.toml or a file of that name is there (a missing one only logs and stays silent) */
    public static boolean exists(String name) {
        if (sounds.containsKey(name)) {
            return true;
        }
        return SoundEffects.class.getResource(PATH + legacyFile(name) + ".ogg") != null;
    }

    // ---- tile sounds ----

    /** the place sound of the tile's group in [tile_sounds], or the generic one from [tile_sounds.default] */
    public static void playTilePlace(Tile tile) {
        playTile(tile, "place");
    }

    /** the break sound of the tile's group in [tile_sounds], or the generic one from [tile_sounds.default] */
    public static void playTileBreak(Tile tile) {
        playTile(tile, "break");
    }

    /** any event a [tile_sounds] group defines ("place", "break", or your own such as "step") */
    public static void playTile(Tile tile, String event) {
        playTile(tile != null ? tile.name : null, event);
    }

    public static void playTile(String tileName, String event) {
        playTile(tileName, event, 1.0F);
    }

    /** like playTile, scaled by volume (e.g. a block changed far away by another player) */
    public static void playTile(Tile tile, String event, float volume) {
        playTile(tile != null ? tile.name : null, event, volume);
    }

    public static void playTile(String tileName, String event, float volume) {
        String sound = config.tileSound(tileName, event);
        if (sound != null) {
            play(sound, volume);
        }
    }

    // ---- loading ----

    /** decodes the named sounds ahead of time so the first play has no delay (everything in audio.toml already is) */
    public static void preload(String... names) {
        PRELOADER.execute(() -> {
            for (String name : names) {
                Sound sound = find(name);
                for (String file : sound.spec.files) {
                    load(file);
                }
            }
        });
    }

    /**
     * re-reads audio.toml: sounds, tile sounds and music tracks change, the mixer settings (rate, buffer, voices)
     * only apply after a restart because the line stays open. a broken file is reported and the old one kept
     */
    public static void reload() {
        AudioConfig fresh = AudioConfig.load();
        config = fresh;
        sounds = buildSounds(fresh);
        LEGACY.clear();
        preloadAll();
    }

    private static void preloadAll() {
        List<String> files = new ArrayList<>();
        for (AudioConfig.SoundSpec spec : config.sounds.values()) {
            if (spec.preload) {
                files.addAll(Arrays.asList(spec.files));
            }
        }
        if (files.isEmpty()) {
            return;
        }
        PRELOADER.execute(() -> {
            for (String file : files) {
                load(file);
            }
        });
    }

    private static Map<String, Sound> buildSounds(AudioConfig cfg) {
        Map<String, Sound> map = new ConcurrentHashMap<>();
        for (AudioConfig.SoundSpec spec : cfg.sounds.values()) {
            map.put(spec.name, new Sound(spec));
        }
        return map;
    }

    private static Sound find(String name) {
        Sound defined = sounds.get(name);
        if (defined != null) {
            return defined;
        }
        return LEGACY.computeIfAbsent(name, n -> new Sound(n, new String[]{legacyFile(n)}, config));
    }

    // "door_open" -> "tiles/door_open", "ui/menu_open" stays
    private static String legacyFile(String name) {
        return name.indexOf('/') >= 0 ? name : LEGACY_FOLDER + name;
    }

    private static void trigger(Sound sound, float volume, float pitchScale, float variationOverride, float fraction) {
        if (mixerFailed || sound == null) {
            return;
        }

        AudioConfig.SoundSpec spec = sound.spec;
        float gain = spec.volume * volume;

        // the slider is applied by the mixer, but there is no point queueing what can't be heard
        if (gain * getMasterVolume() <= 0.0001F) {
            return;
        }

        // only a sound that gets through counts as played, so holding a key can't mute itself forever
        if (!sound.tryAcquire(System.nanoTime())) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        float f = Float.isNaN(fraction) ? random.nextFloat() : fraction;
        float pitch;
        if (spec.hasPitchRange && Float.isNaN(variationOverride)) {
            pitch = pitchScale * (spec.pitchMin + (spec.pitchMax - spec.pitchMin) * f);
        } else {
            float variation = Float.isNaN(variationOverride) ? spec.pitchVariation : variationOverride;
            pitch = spec.pitch * pitchScale * (1.0F + (f * 2.0F - 1.0F) * variation);
        }

        String file = spec.files[sound.pick(random)];
        Sample cached = CACHE.get(file);
        if (cached != null) {
            // already decoded: hand straight to the mixer, no thread hop
            startVoice(cached, gain, pitch);
        } else {
            ON_DEMAND.execute(() -> startVoice(load(file), gain, pitch));
        }
    }

    private static void startVoice(Sample sample, float gain, float pitch) {
        if (sample == MISSING || mixerFailed) {
            return;
        }
        double step = Math.max(0.01D, pitch) * sample.sampleRate / OUT_RATE;
        INCOMING.offer(new Voice(sample, gain, step));
    }

    // ---- mixer ----

    private static void mixLoop() {
        SourceDataLine line;
        try {
            AudioFormat format = new AudioFormat(OUT_RATE, 16, OUT_CHANNELS, true, false);
            int bufferFrames = Math.max(BLOCK_FRAMES * 2, (int) (OUT_RATE * (config.bufferMs / 1000.0)));
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, bufferFrames * OUT_CHANNELS * 2);
            line.start();
        } catch (Exception e) {
            System.err.println("SoundEffects: could not open audio line, sfx disabled: " + e);
            mixerFailed = true;
            INCOMING.clear();
            return;
        }

        List<Voice> active = new ArrayList<>();
        float[] mix = new float[BLOCK_FRAMES * OUT_CHANNELS];
        byte[] out = new byte[BLOCK_FRAMES * OUT_CHANNELS * 2];
        byte[] silence = new byte[out.length];
        float lastMaster = clamp01(getMasterVolume());

        try {
            while (true) {
                INCOMING.drainTo(active);

                // over the limit: the oldest fade out over this block (and are gone next block)
                for (int i = 0; i < active.size() - MAX_VOICES; i++) {
                    active.get(i).startFadeOut();
                }

                if (active.isEmpty()) {
                    // keep the line fed so it never drains. blocks while the buffer is full, which paces the loop
                    line.write(silence, 0, silence.length);
                    continue;
                }

                Arrays.fill(mix, 0.0F);
                for (int i = active.size() - 1; i >= 0; i--) {
                    if (active.get(i).mixInto(mix)) {
                        active.remove(i);
                    }
                }

                // master volume is read once per block and ramped across it, so the slider works on sounds
                // that are already playing without zipper noise
                float master = clamp01(getMasterVolume());
                float masterStep = (master - lastMaster) / BLOCK_FRAMES;
                float gain = lastMaster;
                lastMaster = master;

                int o = 0;
                for (int n = 0; n < BLOCK_FRAMES; n++) {
                    gain += masterStep;
                    int left = (int) (softClip(mix[n * 2] * gain) * 32767.0F);
                    int right = (int) (softClip(mix[n * 2 + 1] * gain) * 32767.0F);
                    out[o++] = (byte) left;
                    out[o++] = (byte) (left >> 8);
                    out[o++] = (byte) right;
                    out[o++] = (byte) (right >> 8);
                }

                line.write(out, 0, out.length);
            }
        } catch (Exception e) {
            System.err.println("SoundEffects: mixer stopped: " + e);
            mixerFailed = true;
        } finally {
            line.stop();
            line.close();
        }
    }

    // linear up to 0.7, then eases into +-1 instead of chopping the wave flat. 30 blocks breaking at once
    // used to hard clip, which is the harsh crackle
    private static float softClip(float x) {
        float a = Math.abs(x);
        if (a <= 0.7F) {
            return x;
        }
        float shaped = 0.7F + 0.3F * (float) Math.tanh((a - 0.7F) / 0.3F);
        return x < 0.0F ? -shaped : shaped;
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    // ---- decoding ----

    private static Sample load(String file) {
        Sample cached = CACHE.get(file);
        if (cached != null) {
            return cached;
        }

        Sample sample = decode(file);
        Sample raced = CACHE.putIfAbsent(file, sample);
        return raced != null ? raced : sample;
    }

    private static Sample decode(String file) {
        String resource = PATH + file + ".ogg";
        InputStream raw = SoundEffects.class.getResourceAsStream(resource);
        if (raw == null) {
            // no .ogg of that name: a .wav is fine too
            String wav = PATH + file + ".wav";
            InputStream wavStream = SoundEffects.class.getResourceAsStream(wav);
            if (wavStream != null) {
                return decodeWav(wavStream, wav);
            }
            System.err.println("SoundEffects: missing resource " + resource);
            return MISSING;
        }

        MusicPlayer.Decoder decoder = null;
        try {
            decoder = new MusicPlayer.Decoder(new BufferedInputStream(raw, 1 << 15));
            int channels = decoder.channels;
            float[] chunk = new float[4096 * channels];
            short[] data = new short[1 << 16];
            int length = 0;

            int frames;
            while ((frames = decoder.read(chunk, 4096)) > 0) {
                int count = frames * channels;
                if (length + count > data.length) {
                    data = Arrays.copyOf(data, Math.max(data.length * 2, length + count));
                }
                for (int i = 0; i < count; i++) {
                    data[length++] = (short) Math.max(-32768, Math.min(32767, (int) (chunk[i] * 32767.0F)));
                }
            }

            if (length == 0) {
                System.err.println("SoundEffects: empty sound " + resource);
                return MISSING;
            }
            return new Sample(Arrays.copyOf(data, length), decoder.sampleRate, channels, length / channels);
        } catch (Exception e) {
            System.err.println("SoundEffects: failed to decode " + resource + ": " + e);
            return MISSING;
        } finally {
            if (decoder != null) {
                decoder.close();
            }
        }
    }

    // a .wav file: whatever the file holds is converted to 16 bit pcm, the same form the ogg decoder gives
    private static Sample decodeWav(InputStream raw, String resource) {
        try (javax.sound.sampled.AudioInputStream source = AudioSystem.getAudioInputStream(new BufferedInputStream(raw, 1 << 15))) {
            AudioFormat in = source.getFormat();
            int channels = Math.max(1, in.getChannels());
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, in.getSampleRate(), 16, channels,
                    channels * 2, in.getSampleRate(), false);
            try (javax.sound.sampled.AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, source)) {
                byte[] bytes = converted.readAllBytes();
                int count = bytes.length / 2;
                short[] data = new short[count];
                for (int i = 0; i < count; i++) {
                    data[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
                }
                int frames = count / channels;
                if (frames == 0) {
                    System.err.println("SoundEffects: empty sound " + resource);
                    return MISSING;
                }
                return new Sample(data, Math.round(pcm.getSampleRate()), channels, frames);
            }
        } catch (Exception e) {
            System.err.println("SoundEffects: failed to decode " + resource + ": " + e);
            return MISSING;
        }
    }

    private static ExecutorService daemonPool(String name, int priority) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        });
    }

    // ---- types ----

    /** a named sound at runtime: its definition plus the state that must persist between plays */
    private static final class Sound {
        final AudioConfig.SoundSpec spec;
        private long lastNanos;
        private boolean playedBefore;
        private int lastIndex = -1;

        Sound(AudioConfig.SoundSpec spec) {
            this.spec = spec;
        }

        // sound that is not in audio.toml: default volume/gap, files given by the old naming rules
        Sound(String name, String[] files, AudioConfig cfg) {
            this(new AudioConfig.SoundSpec(name, files, 1.0F, 1.0F, cfg.defaultPitchVariation, false, 1.0F, 1.0F, cfg.defaultGapMs, false));
        }

        synchronized boolean tryAcquire(long now) {
            if (this.playedBefore && now - this.lastNanos < this.spec.gapNanos) {
                return false;
            }
            this.playedBefore = true;
            this.lastNanos = now;
            return true;
        }

        // random file, never the same one twice in a row
        synchronized int pick(ThreadLocalRandom random) {
            int count = this.spec.files.length;
            if (count == 1) {
                return 0;
            }
            int index = random.nextInt(count);
            if (index == this.lastIndex) {
                index = (index + 1 + random.nextInt(count - 1)) % count;
            }
            this.lastIndex = index;
            return index;
        }
    }

    private static final class Sample {
        final short[] pcm;
        final int sampleRate;
        final int channels;
        final int frames;

        Sample(short[] pcm, int sampleRate, int channels, int frames) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.frames = frames;
        }
    }

    // one playing instance of a sample. only the mixer thread touches position and fading
    private static final class Voice {
        final Sample sample;
        final float gain;
        final double step; // source frames per output frame (pitch * rate conversion)
        final Loop loop;   // null = plays once
        double position;
        boolean fadingOut;
        float loopGain;    // where the volume of a loop is now, eased toward loop.target

        Voice(Sample sample, float gain, double step) {
            this(sample, gain, step, null);
        }

        Voice(Sample sample, float gain, double step, Loop loop) {
            this.sample = sample;
            this.gain = gain;
            this.step = step;
            this.loop = loop;
        }

        void startFadeOut() {
            this.fadingOut = true;
        }

        /** adds one block into the buffer, true once the sound has finished */
        boolean mixInto(float[] mix) {
            if (this.loop != null) {
                return mixLoop(mix);
            }
            short[] pcm = sample.pcm;
            int channels = sample.channels;
            int last = sample.frames - 1;
            float scale = this.gain / 32768.0F;
            double pos = this.position;
            boolean fading = this.fadingOut;
            float fadeStep = 1.0F / BLOCK_FRAMES;

            for (int n = 0; n < BLOCK_FRAMES && pos <= last; n++, pos += step) {
                int i0 = (int) pos;
                int i1 = Math.min(i0 + 1, last);
                float fraction = (float) (pos - i0);

                int a = i0 * channels;
                int b = i1 * channels;
                float left = pcm[a] + (pcm[b] - pcm[a]) * fraction;
                float right = left;
                if (channels > 1) {
                    right = pcm[a + 1] + (pcm[b + 1] - pcm[a + 1]) * fraction;
                }

                float s = fading ? scale * (1.0F - n * fadeStep) : scale;
                mix[n * 2] += left * s;
                mix[n * 2 + 1] += right * s;
            }

            this.position = pos;
            return fading || pos > last;
        }

        // a loop wraps round at the end and eases its volume across the block, it ends once it has faded out after stop()
        private boolean mixLoop(float[] mix) {
            short[] pcm = sample.pcm;
            int channels = sample.channels;
            int frames = sample.frames;
            boolean stopping = this.loop.stopped || this.fadingOut;
            float to = stopping ? 0.0F : this.loop.target;
            float g = this.loopGain;
            float gStep = (to - g) / BLOCK_FRAMES;
            this.loopGain = to;
            float scale = this.gain / 32768.0F;
            double pos = this.position;

            for (int n = 0; n < BLOCK_FRAMES; n++, pos += step) {
                if (pos >= frames) {
                    pos -= frames;
                }
                int i0 = (int) pos;
                int i1 = i0 + 1 >= frames ? 0 : i0 + 1;
                float fraction = (float) (pos - i0);

                int a = i0 * channels;
                int b = i1 * channels;
                float left = pcm[a] + (pcm[b] - pcm[a]) * fraction;
                float right = left;
                if (channels > 1) {
                    right = pcm[a + 1] + (pcm[b + 1] - pcm[a + 1]) * fraction;
                }

                g += gStep;
                mix[n * 2] += left * scale * g;
                mix[n * 2 + 1] += right * scale * g;
            }

            this.position = pos;
            return stopping;
        }
    }
}