package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.ProgressBar;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.render.ShaderWarmup;
import com.nikohalfkino.rubydung.level.generation.WorldGenStatus;

public class LoadingScreen extends Screen {

    // each frame builds chunks for this long, so the loading screen stays responsive however fast or slow the chunks are
    // (a fixed number per frame made a big world take hundreds of frames no matter how cheap they were)
    private static final long BUILD_BUDGET_NANOS = 30_000_000L;

    private static final float BAR_WIDTH = 300.0f;
    private static final float BAR_HEIGHT = 4.0f;

    private final RubyDung game;
    private final long startNanos = System.nanoTime();

    // null while the level is still being generated (the step text shows what the generator is doing),
    // set by startBuilding once it exists and the chunk meshes can be built
    private LevelRenderer levelRenderer;

    public LoadingScreen(RubyDung game) {
        super(game.getFont());
        this.game = game;
        // no reset here: the generator and Level set the step as they go, and Level ends on "Building terrain..."

        // "Loading..." just above the middle, the bar in the middle, the percentage right under it.
        Label title = add(new Label("Loading..."), Anchor.TOP_CENTER, 0, 0);
        title.setOffsetFraction(0.0f, 0.45f);

        ProgressBar bar = add(new ProgressBar(BAR_WIDTH, BAR_HEIGHT, this::progress),
                Anchor.TOP_CENTER, 0, 0);
        bar.setOffsetFraction(0.0f, 0.5f);

        Label percent = add(new Label(() -> this.levelRenderer == null ? "" : (int) (progress() * 100.0f) + "%")
                .setColor(UiStyle.TEXT_DIM), Anchor.TOP_CENTER, 0, BAR_HEIGHT + 6.0f);
        percent.setOffsetFraction(0.0f, 0.5f);

        // what is going on right now ("Carving coastlines...", "Building terrain...")
        Label step = add(new Label(WorldGenStatus::get).setColor(UiStyle.TEXT_DIM), Anchor.TOP_CENTER, 0, BAR_HEIGHT + 24.0f);
        step.setOffsetFraction(0.0f, 0.5f);
    }

    private float progress() {
        return this.levelRenderer == null ? 0.0f : this.levelRenderer.getBuildProgress();
    }

    /** main thread: the level is ready, from now on every frame builds chunk meshes until all are done */
    public void startBuilding(LevelRenderer levelRenderer) {
        this.levelRenderer = levelRenderer;
        levelRenderer.startParallelBuild();
    }

    @Override
    public boolean pausesGame() {
        return false;
    }

    @Override
    protected void renderBackground() {
        Gui2D.fillRect(0, 0, this.width, this.height, 0.0f, 0.0f, 0.0f, 1.0f);
    }

    @Override
    public void render(int mouseX, int mouseY) {
        // one shader program per frame, so none is compiled in the middle of the first world frame
        ShaderWarmup.step();

        if (this.levelRenderer == null) {
            super.render(mouseX, mouseY);   // the level is still being made, only the step text moves
            return;
        }

        // workers build the meshes, this thread only turns finished ones into display lists
        boolean more = this.levelRenderer.uploadBuiltChunks(Integer.MAX_VALUE, System.nanoTime() + BUILD_BUDGET_NANOS);
        if (!more) {
            ShaderWarmup.finish();   // whatever is left (a fast load), before the first world frame
            System.out.println("[loading] built all chunks in " + (System.nanoTime() - this.startNanos) / 1_000_000L + " ms");
            this.game.finishedLoadingWorld();
            return;
        }

        super.render(mouseX, mouseY);
    }
}
