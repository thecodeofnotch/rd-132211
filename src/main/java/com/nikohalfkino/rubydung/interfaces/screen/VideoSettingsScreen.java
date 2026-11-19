package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.UiStyle;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Button;
import com.nikohalfkino.rubydung.interfaces.widget.ChoiceRow;
import com.nikohalfkino.rubydung.interfaces.widget.Column;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.interfaces.widget.Panel;
import com.nikohalfkino.rubydung.interfaces.widget.Row;
import com.nikohalfkino.rubydung.interfaces.widget.Slider;
import com.nikohalfkino.rubydung.interfaces.widget.Toggle;
import com.nikohalfkino.rubydung.util.GameSettings;
import com.nikohalfkino.rubydung.util.GameSettings.DisplayType;
import com.nikohalfkino.rubydung.util.GameSettings.LayoutMode;
import org.lwjgl.opengl.Display;

import java.util.function.Function;

/** video settings in two columns like the Voice screen: Display + Frame Rate | World + Overlay */
public class VideoSettingsScreen extends MenuScreen {

    private static final float PANEL_PADDING = 20.0f;
    private static final float PANEL_TOP     = 90.0f;

    private static final float ITEM_GAP    = 8.0f;
    private static final float HEADER_GAP  = 6.0f;
    private static final float SECTION_GAP = 16.0f;

    private static final float COLUMN_WIDTH = 300.0f;
    private static final float COLUMN_GAP   = 20.0f;
    private static final float FULL_WIDTH   = COLUMN_WIDTH * 2 + COLUMN_GAP;

    private static final float MODE_GAP = 4.0f;

    // fps cap slider: FPS_MIN..(FPS_UNLIMITED_AT - FPS_STEP) in steps, "Unlimited" at the end
    private static final int FPS_MIN = 30;
    private static final int FPS_STEP = 5;
    private static final int FPS_UNLIMITED_AT = 245;

    // pixel size slider: each world pixel is PIXEL_MIN..PIXEL_MAX screen pixels wide
    private static final int PIXEL_MIN = GameSettings.MIN_PIXEL_SIZE;
    private static final int PIXEL_MAX = GameSettings.MAX_PIXEL_SIZE;

    // render distance slider: RENDER_MIN..RENDER_MAX chunks of 16 blocks, "Unlimited" at the end
    private static final int RENDER_MIN = GameSettings.MIN_RENDER_DISTANCE;
    private static final int RENDER_MAX = GameSettings.MAX_RENDER_DISTANCE;
    private static final int RENDER_UNLIMITED_AT = RENDER_MAX + 1;

    private Toggle vsyncToggle;
    private Toggle pixelationToggle;

    public VideoSettingsScreen(RubyDung game) {
        super(game);

        setSwallowKeys(true);
        onEscape(this::goBack);

        addTitle("Video Settings");

        Panel panel = add(new Panel(ITEM_GAP), Anchor.TOP_CENTER, 0, PANEL_TOP);
        panel.setPadding(PANEL_PADDING);
        panel.setShadowAlpha(1.0f);
        panel.setCrossAlign(0.0f);

        Row columns = panel.add(new Row(COLUMN_GAP), SECTION_GAP);
        columns.setCrossAlign(0.0f);   // both columns hang from the top

        columns.add(buildLeftColumn());
        columns.add(buildRightColumn());

        panel.add(new Button("Done", FULL_WIDTH, UiStyle.BUTTON_HEIGHT, true)
                .onClick(this::goBack));
    }

    // left column: Display + Frame Rate

    private Column buildLeftColumn() {
        Column col = new Column(ITEM_GAP);
        col.setCrossAlign(0.0f);

        // display
        col.add(new Label("Display").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        // Windowed <-> Fullscreen. F11 jumps in and out of fullscreen.
        col.add(new Button(() -> "Window: " + typeName(GameSettings.getDisplayType()),
                COLUMN_WIDTH, UiStyle.BUTTON_HEIGHT, true)
                .onClick(() -> {
                    GameSettings.setDisplayType(GameSettings.isFullscreen()
                            ? DisplayType.WINDOWED : DisplayType.FULLSCREEN);
                    GameSettings.save();
                    this.game.applyDisplay();
                }), SECTION_GAP);

        // A change applies at once if fullscreen is in use.
        col.add(new Label("Fullscreen Layout").setColor(UiStyle.TEXT_DIM), HEADER_GAP);
        col.add(layoutRow(GameSettings.getFullscreenMode(), m -> {
            GameSettings.setFullscreenMode(m);
            return GameSettings.isFullscreen();
        }), SECTION_GAP);

        // frame rate
        col.add(new Label("Frame Rate").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        this.vsyncToggle = new Toggle("VSync", GameSettings.isVsyncEnabled(), Toggle.LabelSide.LEFT)
                .setRowWidth(COLUMN_WIDTH)
                .onChange(on -> {
                    GameSettings.setVsyncEnabled(on);
                    Display.setVSyncEnabled(on);
                    GameSettings.save();
                });
        col.add(this.vsyncToggle);

        // Greyed out and unclickable while VSync is on, since VSync already caps the frame rate.
        // Steps of FPS_STEP; the last step is "Unlimited".
        Slider fpsCap = new Slider("FPS Cap", COLUMN_WIDTH, UiStyle.ROW_HEIGHT,
                FPS_MIN, FPS_UNLIMITED_AT, initialFpsSlider(), true)
                .setFormatter(v -> {
                    int cap = snapFps(v);
                    return cap == GameSettings.UNCAPPED_FPS ? "Unlimited" : String.valueOf(cap);
                })
                .onChange(v -> GameSettings.setFpsCap(snapFps(v)))
                .onCommit(GameSettings::save);
        fpsCap.setEnabledWhen(() -> !this.vsyncToggle.getValue());
        col.add(fpsCap);

        return col;
    }

    // Fit / Expand / Bordered buttons. apply stores the pick and returns whether the window must be re-laid out now (this layout is the one in use)
    private ChoiceRow<LayoutMode> layoutRow(LayoutMode current, Function<LayoutMode, Boolean> apply) {
        ChoiceRow<LayoutMode> row = new ChoiceRow<LayoutMode>(COLUMN_WIDTH, UiStyle.BUTTON_HEIGHT, MODE_GAP)
                .option("Fit", LayoutMode.FIT)
                .option("Expand", LayoutMode.EXPAND)
                .option("Bordered", LayoutMode.BORDERED)
                .onChange(m -> {
                    boolean active = apply.apply(m);
                    GameSettings.save();
                    if (active) {
                        this.game.applyDisplay();
                    }
                });
        row.select(current);
        return row;
    }

    // right column: World + Overlay

    private Column buildRightColumn() {
        Column col = new Column(ITEM_GAP);
        col.setCrossAlign(0.0f);

        // world
        col.add(new Label("World").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        this.pixelationToggle = new Toggle("Pixelation", GameSettings.isPixelationEnabled(), Toggle.LabelSide.LEFT)
                .setRowWidth(COLUMN_WIDTH)
                .onChange(on -> {
                    GameSettings.setPixelationEnabled(on);
                    GameSettings.save();
                });
        col.add(this.pixelationToggle);

        // Greyed out and unclickable while pixelation is off.
        Slider pixelSize = new Slider("Pixel Size", COLUMN_WIDTH, UiStyle.ROW_HEIGHT,
                PIXEL_MIN, PIXEL_MAX, initialPixelSlider(), true)
                .setFormatter(v -> snapPixelSize(v) + "x")
                .onChange(v -> GameSettings.setPixelSize(snapPixelSize(v)))
                .onCommit(GameSettings::save);
        pixelSize.setEnabledWhen(() -> this.pixelationToggle.getValue());
        col.add(pixelSize, ITEM_GAP);

        // chunks further than this aren't drawn, a big help on weak devices in Large / XL worlds
        Slider renderDistance = new Slider("Render Distance", COLUMN_WIDTH, UiStyle.ROW_HEIGHT,
                RENDER_MIN, RENDER_UNLIMITED_AT, initialRenderSlider(), true)
                .setFormatter(v -> {
                    int chunks = snapRenderDistance(v);
                    return chunks == 0 ? "Unlimited" : chunks + " chunks";
                })
                .onChange(v -> GameSettings.setRenderDistance(snapRenderDistance(v)))
                .onCommit(GameSettings::save);
        col.add(renderDistance, SECTION_GAP);

        // overlay
        col.add(new Label("Overlay").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        col.add(new Toggle("Show FPS", GameSettings.isShowFpsInHud(), Toggle.LabelSide.LEFT)
                .setRowWidth(COLUMN_WIDTH)
                .onChange(on -> {
                    GameSettings.setShowFpsInHud(on);
                    GameSettings.save();
                }), SECTION_GAP);

        // start up
        col.add(new Label("Start Up").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        col.add(new Toggle("Splash Screen", GameSettings.isShowSplashScreen(), Toggle.LabelSide.LEFT)
                .setRowWidth(COLUMN_WIDTH)
                .onChange(on -> {
                    GameSettings.setShowSplashScreen(on);
                    GameSettings.save();
                }));

        col.add(new Toggle("Flashing Lights Warning", GameSettings.isShowFlashingWarning(), Toggle.LabelSide.LEFT)
                .setRowWidth(COLUMN_WIDTH)
                .onChange(on -> {
                    GameSettings.setShowFlashingWarning(on);
                    GameSettings.save();
                }));

        return col;
    }

    // helpers

    // slider position -> fps cap, rounded to FPS_STEP, top of the track = no cap
    private static int snapFps(float value) {
        int snapped = Math.round(value / FPS_STEP) * FPS_STEP;
        return snapped >= FPS_UNLIMITED_AT ? GameSettings.UNCAPPED_FPS : Math.max(FPS_MIN, snapped);
    }

    // where the thumb starts for the saved cap (a value off the steps is clamped onto the track)
    private static float initialFpsSlider() {
        int cap = GameSettings.getFpsCap();
        if (cap == GameSettings.UNCAPPED_FPS) {
            return FPS_UNLIMITED_AT;
        }
        return Math.max(FPS_MIN, Math.min(FPS_UNLIMITED_AT - FPS_STEP, cap));
    }

    // slider position -> whole pixel size within PIXEL_MIN..PIXEL_MAX
    private static int snapPixelSize(float value) {
        return Math.max(PIXEL_MIN, Math.min(PIXEL_MAX, Math.round(value)));
    }

    private static float initialPixelSlider() {
        return Math.max(PIXEL_MIN, Math.min(PIXEL_MAX, GameSettings.getPixelSize()));
    }

    // slider position -> render distance in chunks, top of the track = no limit (0)
    private static int snapRenderDistance(float value) {
        int snapped = Math.round(value);
        return snapped >= RENDER_UNLIMITED_AT ? 0 : Math.max(RENDER_MIN, snapped);
    }

    private static float initialRenderSlider() {
        int chunks = GameSettings.getRenderDistance();
        if (chunks <= 0) {
            return RENDER_UNLIMITED_AT;
        }
        return Math.max(RENDER_MIN, Math.min(RENDER_MAX, chunks));
    }

    private static String typeName(DisplayType type) {
        switch (type) {
            case FULLSCREEN:
                return "Fullscreen (F11)";
            default:
                return "Windowed";
        }
    }

    private void goBack() {
        this.game.openScreen(new OptionsScreen(this.game));
    }
}