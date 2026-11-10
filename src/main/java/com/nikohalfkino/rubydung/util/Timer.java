package com.nikohalfkino.rubydung.util;

public class Timer {

    private static final long NS_PER_SECOND = 1000000000L;
    private static final long MAX_NS_PER_UPDATE = 1000000000L;
    // how many ticks one frame may run to catch up. a long frame must not make the next one longer (a tick that costs more than its
    // share of time would then never be caught up with): past this the backlog is dropped and the game just runs slower for a moment
    private static final int MAX_TICKS_PER_UPDATE = 10;

    private final float ticksPerSecond;

    private long lastTime = System.nanoTime();

    public float timeScale = 1.0F;

    public float fps = 0.0F;

    private static final float FPS_UPDATE_INTERVAL = 0.4F;

    private int framesSinceLastFpsUpdate = 0;
    private float secondsSinceLastFpsUpdate = 0.0F;

    public float passedTime = 0.0F;

    public int ticks;

    public float partialTicks;

    public Timer(float ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
    }

    public void advanceTime() {
        long now = System.nanoTime();
        long passedNs = now - this.lastTime;

        this.lastTime = now;

        passedNs = Math.max(0, passedNs);
        passedNs = Math.min(MAX_NS_PER_UPDATE, passedNs);

        this.framesSinceLastFpsUpdate++;
        this.secondsSinceLastFpsUpdate += (float) passedNs / NS_PER_SECOND;

        if (this.secondsSinceLastFpsUpdate >= FPS_UPDATE_INTERVAL) {
            this.fps = this.framesSinceLastFpsUpdate / this.secondsSinceLastFpsUpdate;
            this.framesSinceLastFpsUpdate = 0;
            this.secondsSinceLastFpsUpdate = 0.0F;
        }

        this.passedTime += passedNs * this.timeScale * this.ticksPerSecond / NS_PER_SECOND;
        this.ticks = (int) this.passedTime;

        if (this.ticks > MAX_TICKS_PER_UPDATE) {
            this.ticks = MAX_TICKS_PER_UPDATE;
            this.passedTime = 0.0F;   // the backlog is given up, not carried to the next frame
        } else {
            this.passedTime -= this.ticks;
        }
        this.partialTicks = this.passedTime;
    }
}
