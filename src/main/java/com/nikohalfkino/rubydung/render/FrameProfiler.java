package com.nikohalfkino.rubydung.render;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.glFinish;

/**
 * finds where a frame's time goes. off by default. any ONE of these turns it on:
 *   - the JVM argument -Dstrata.profile=true (a Gradle run task needs it in build.gradle: run { jvmArgs '-Dstrata.profile=true' }, the IDE's VM options field is ignored by it)
 *   - the environment variable STRATA_PROFILE=1
 *   - an empty file called strata-profile.txt in the game's working directory (the folder it is started from)
 * it prints one line when it first runs saying whether it is on, so you can tell the new build is the one running, and when on it
 * also appends every report to strata-profile.log in the working directory, in case the console isn't visible
 *
 * GameRenderer calls lap("name") after each stage. the time since the previous lap is added to that stage, and once a second the
 * average per frame is printed to the console. while it is on, every lap calls glFinish(), so GPU work is counted in the stage that
 * issued it instead of piling up in whichever call finally waits (the buffer swap). that makes frames a bit slower than normal,
 * compare the stages with each other rather than the total with your usual fps
 *
 * for the other view, add -Dstrata.profile.cpu=true (or the environment variable STRATA_PROFILE_CPU=1): the laps then do NOT call glFinish(), so each stage shows only the time the CPU spent issuing it and the GPU's time piles up in "swap". compare it with the synced report: a stage that is big in both is CPU-bound, one that is big only when synced is waiting for the GPU
 */
public final class FrameProfiler {

    private static final String FLAG_FILE = "strata-profile.txt";
    private static final File LOG = new File("strata-profile.log");
    private static final boolean ENABLED;
    private static final boolean CPU_ONLY = Boolean.getBoolean("strata.profile.cpu") || System.getenv("STRATA_PROFILE_CPU") != null;

    static {
        String how = null;
        if (Boolean.getBoolean("strata.profile")) {
            how = "JVM argument";
        } else if (System.getenv("STRATA_PROFILE") != null) {
            how = "environment variable";
        } else if (new File(FLAG_FILE).exists()) {
            how = "file " + FLAG_FILE;
        }
        ENABLED = how != null;
        System.out.println(ENABLED
                ? "[profile] ON (" + how + "), reports go to the console and " + LOG.getAbsolutePath()
                : "[profile] off. to turn it on: JVM arg -Dstrata.profile=true, or create an empty file "
                + new File(FLAG_FILE).getAbsolutePath());
    }

    private static final Map<String, long[]> TOTALS = new LinkedHashMap<>();
    private static long mark;
    private static long lastPrint = System.nanoTime();
    private static int frames;

    private FrameProfiler() {
    }

    public static void begin() {
        if (ENABLED) {
            mark = System.nanoTime();
        }
    }

    public static void lap(String stage) {
        if (!ENABLED) {
            return;
        }
        if (!CPU_ONLY) {
            glFinish();
        }
        long now = System.nanoTime();
        TOTALS.computeIfAbsent(stage, k -> new long[1])[0] += now - mark;
        mark = System.nanoTime();
    }

    public static void endFrame() {
        if (!ENABLED) {
            return;
        }
        frames++;
        long now = System.nanoTime();
        if (now - lastPrint < 1_000_000_000L) {
            return;
        }
        StringBuilder out = new StringBuilder("[profile] ").append(frames).append(" frames, ms per frame:");
        double sum = 0.0;
        for (Map.Entry<String, long[]> entry : TOTALS.entrySet()) {
            double ms = entry.getValue()[0] / 1.0E6 / frames;
            sum += ms;
            out.append(String.format("  %s=%.2f", entry.getKey(), ms));
            entry.getValue()[0] = 0L;
        }
        out.append(String.format("  | total=%.2f", sum)).append(CPU_ONLY ? " (cpu only, no glFinish)" : " (synced)");
        System.out.println(out);
        try (FileWriter log = new FileWriter(LOG, true)) {
            log.write(out + System.lineSeparator());
        } catch (IOException ignored) {
        }
        frames = 0;
        lastPrint = now;
    }
}