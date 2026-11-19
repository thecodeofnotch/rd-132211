package com.nikohalfkino.rubydung.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * copies everything the game prints (System.out, System.err, stack traces) into log.txt in the data folder, the run before
 * it is kept as log-previous.txt. also catches exceptions that end a thread, and a watchdog writes every thread's stack
 * into the log when the game stops drawing frames, so a freeze (no exception, nothing printed) can still be diagnosed
 *
 * call init() first thing in main, and heartbeat() once per frame
 */
public final class GameLog {

    private static final long STALL_MS = 8000L;
    private static final long CHECK_MS = 2000L;

    private static volatile long lastBeat = System.nanoTime();
    private static volatile boolean started = false;

    private GameLog() {
    }

    /** called once per frame by the game loop */
    public static void heartbeat() {
        lastBeat = System.nanoTime();
    }

    public static void init() {
        PrintStream console = System.out;
        try {
            File log = GameDirectory.file("log.txt");
            File previous = GameDirectory.file("log-previous.txt");
            if (log.isFile()) {
                Files.move(log.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            OutputStream file = new FileOutputStream(log);
            System.setOut(new PrintStream(new Tee(console, file), true));
            System.setErr(new PrintStream(new Tee(System.err, file), true));
        } catch (IOException | RuntimeException e) {
            console.println("Warning: could not open the log file: " + e);
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println(stamp() + " uncaught exception in thread \"" + thread.getName() + "\":");
            throwable.printStackTrace();
        });

        Runtime rt = Runtime.getRuntime();
        System.out.println(stamp() + " log started");
        System.out.println("java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("os " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        System.out.println("cpus " + rt.availableProcessors() + ", max heap " + (rt.maxMemory() >> 20) + " MB");
        System.out.println("data folder " + GameDirectory.root());

        startWatchdog();
    }

    /** a line with the time in front, for things worth finding later (autosaves, world loads) */
    public static void log(String message) {
        System.out.println(stamp() + " " + message);
    }

    /** heap and GC totals, written with a message */
    public static void memory(String message) {
        System.out.println(stamp() + " " + message + " | " + memoryLine());
    }

    private static String memoryLine() {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long gcCount = 0;
        long gcMs = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, bean.getCollectionCount());
            gcMs += Math.max(0, bean.getCollectionTime());
        }
        return "heap " + used + " / " + (rt.maxMemory() >> 20) + " MB used, " + gcCount + " collections took " + gcMs + " ms in total";
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static void startWatchdog() {
        if (started) {
            return;
        }
        started = true;

        Thread watchdog = new Thread(() -> {
            boolean stalled = false;
            while (true) {
                try {
                    Thread.sleep(CHECK_MS);
                } catch (InterruptedException e) {
                    return;
                }

                long silentMs = (System.nanoTime() - lastBeat) / 1000000L;
                if (silentMs >= STALL_MS && !stalled) {
                    stalled = true;
                    System.err.println(stamp() + " the game has not drawn a frame for " + (silentMs / 1000) + " s | " + memoryLine());
                    dumpThreads();
                } else if (silentMs < STALL_MS && stalled) {
                    stalled = false;
                    System.err.println(stamp() + " the game is drawing frames again");
                }
            }
        }, "log-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void dumpThreads() {
        StringBuilder out = new StringBuilder("thread dump:\n");
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            out.append("  \"").append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
            for (StackTraceElement frame : entry.getValue()) {
                out.append("      at ").append(frame).append('\n');
            }
        }
        System.err.print(out);
    }

    /** writes to two streams, a failing file never stops the console */
    private static final class Tee extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        Tee(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            this.first.write(b);
            try {
                this.second.write(b);
            } catch (IOException ignored) {
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            this.first.write(b, off, len);
            try {
                this.second.write(b, off, len);
            } catch (IOException ignored) {
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            this.first.flush();
            try {
                this.second.flush();
            } catch (IOException ignored) {
            }
        }
    }
}
