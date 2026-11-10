package com.nikohalfkino.rubydung.interfaces.screen;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.audio.tts.JTalkSettings;
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
import com.nikohalfkino.rubydung.interfaces.widget.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Open JTalk voice settings laid out like Options, one panel with two columns
 *
 * left: chat voice switches and the voice itself (type button + its moods), right: tuning sliders, each with a small reset button
 *
 * picking a type or mood plays a sample. no sing-mode toggle, chat lines with notes ([bah<235,30>] or [0]do [2]re) are sung automatically
 */
public class VoiceSettingsScreen extends MenuScreen {

    private static final float PANEL_PADDING = 20.0f;
    private static final float PANEL_TOP     = 90.0f;

    private static final float ITEM_GAP    = 8.0f;
    private static final float HEADER_GAP  = 6.0f;
    private static final float SECTION_GAP = 16.0f;

    private static final float COLUMN_WIDTH = 300.0f;
    private static final float COLUMN_GAP   = 20.0f;
    private static final float FULL_WIDTH   = COLUMN_WIDTH * 2 + COLUMN_GAP;

    private static final float BUTTON_HEIGHT = UiStyle.BUTTON_HEIGHT;
    private static final float ROW_HEIGHT    = UiStyle.ROW_HEIGHT;

    private static final int MOODS_PER_ROW = 3;
    private static final float MOOD_GAP = 4.0f;

    // the reset button is a square the height of a slider row, the slider gives up that width
    private static final float RESET_GAP = 4.0f;
    private static final float RESET_SIZE = ROW_HEIGHT;
    private static final float SLIDER_WIDTH = COLUMN_WIDTH - RESET_SIZE - RESET_GAP;

    // one ChoiceRow per line of mood buttons, they share a selection
    private final List<ChoiceRow<String>> moodRows = new ArrayList<>();

    public VoiceSettingsScreen(RubyDung game) {
        super(game);

        setSwallowKeys(true);
        onEscape(this::goBack);

        addTitle("Voice Settings");

        Panel panel = add(new Panel(ITEM_GAP), Anchor.TOP_CENTER, 0, PANEL_TOP);
        panel.setPadding(PANEL_PADDING);
        panel.setShadowAlpha(1.0f);
        panel.setCrossAlign(0.0f);

        Row columns = panel.add(new Row(COLUMN_GAP), SECTION_GAP);
        columns.setCrossAlign(0.0f);   // both columns hang from the top

        columns.add(buildVoiceColumn());
        columns.add(buildTuningColumn());

        // bottom buttons
        float buttonWidth = (FULL_WIDTH - UiStyle.BUTTON_GAP) / 2.0f;
        Row bottom = panel.add(new Row(UiStyle.BUTTON_GAP));
        bottom.add(new Button("Test Voice", buttonWidth, BUTTON_HEIGHT, true).onClick(this::sample));
        bottom.add(new Button("Done", buttonWidth, BUTTON_HEIGHT, true).onClick(this::goBack));
    }

    // left column: chat voice + the voice itself

    private Column buildVoiceColumn() {
        Column col = new Column(ITEM_GAP);
        col.setCrossAlign(0.0f);

        col.add(new Label("Chat Voice").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        col.add(new Toggle("Speak Chat", JTalkSettings.isEnabled(), Toggle.LabelSide.LEFT)
                .setRowWidth(COLUMN_WIDTH)
                .onChange(on -> {
                    JTalkSettings.setEnabled(on);
                    if (!on) {
                        this.game.getSpeaker().clear();
                    }
                    JTalkSettings.save();
                }));

        // Katakana transliteration of the chat text; off sends the raw text to the voice.
        col.add(new Toggle("Transliterate", JTalkSettings.isTransliterate(), Toggle.LabelSide.LEFT)
                        .setRowWidth(COLUMN_WIDTH)
                        .onChange(on -> {
                            JTalkSettings.setTransliterate(on);
                            JTalkSettings.save();
                        }),
                SECTION_GAP);

        col.add(new Label("Voice").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        // Voice type. Switching it changes which moods exist, so the screen is rebuilt.
        col.add(new Button(() -> "Voice: " + typeName(JTalkSettings.getVoiceCharacter()),
                COLUMN_WIDTH, BUTTON_HEIGHT, true)
                .onClick(() -> {
                    JTalkSettings.nextCharacter();
                    JTalkSettings.save();
                    this.game.openScreen(new VoiceSettingsScreen(this.game));
                    sample();
                }));

        // The moods of the current type, a few per line.
        String character = JTalkSettings.getVoiceCharacter();
        String currentMood = JTalkSettings.getVoiceMood();
        List<String> moods = JTalkSettings.listMoods(character);
        for (int i = 0; i < moods.size(); i += MOODS_PER_ROW) {
            int end = Math.min(i + MOODS_PER_ROW, moods.size());
            ChoiceRow<String> moodRow = new ChoiceRow<>(COLUMN_WIDTH, BUTTON_HEIGHT, MOOD_GAP);
            for (String mood : moods.subList(i, end)) {
                moodRow.option(cap(mood), mood);
            }
            moodRow.onChange(m -> pickMood(character, m));
            moodRow.select(currentMood);
            this.moodRows.add(moodRow);
            col.add(moodRow);
        }

        return col;
    }

    // right column: sliders

    private Column buildTuningColumn() {
        Column col = new Column(ITEM_GAP);
        col.setCrossAlign(0.0f);

        col.add(new Label("Tuning").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        // how loud every voice is (yours and the other players')
        col.add(sliderRow(
                new Slider("Volume", SLIDER_WIDTH, ROW_HEIGHT,
                        0.0f, 100.0f, Math.round(JTalkSettings.getVolume() * 100.0f), true)
                        .onChange(percent -> JTalkSettings.setVolume(percent / 100.0f))
                        .onCommit(() -> {
                            JTalkSettings.save();
                            sample();
                        }),
                () -> near(JTalkSettings.getVolume(), JTalkSettings.DEF_VOLUME),
                JTalkSettings::resetVolume));

        // -r  speech speed
        col.add(sliderRow(
                new Slider("Speed", SLIDER_WIDTH, ROW_HEIGHT,
                        JTalkSettings.MIN_SPEED, JTalkSettings.MAX_SPEED, JTalkSettings.getSpeed(), false)
                        .onChange(JTalkSettings::setSpeed)
                        .onCommit(JTalkSettings::save),
                () -> near(JTalkSettings.getSpeed(), JTalkSettings.DEF_SPEED),
                JTalkSettings::resetSpeed));

        // -fm  extra half-tones
        col.add(sliderRow(
                new Slider("Pitch", SLIDER_WIDTH, ROW_HEIGHT,
                        JTalkSettings.MIN_PITCH, JTalkSettings.MAX_PITCH, JTalkSettings.getPitch(), true)
                        .onChange(JTalkSettings::setPitch)
                        .onCommit(JTalkSettings::save),
                () -> near(JTalkSettings.getPitch(), JTalkSettings.DEF_PITCH),
                JTalkSettings::resetPitch));

        // -a  all-pass constant (timbre)
        col.add(sliderRow(
                new Slider("Alpha", SLIDER_WIDTH, ROW_HEIGHT,
                        JTalkSettings.MIN_ALPHA, JTalkSettings.MAX_ALPHA, JTalkSettings.getAlpha(), false)
                        .onChange(JTalkSettings::setAlpha)
                        .onCommit(JTalkSettings::save),
                () -> near(JTalkSettings.getAlpha(), JTalkSettings.DEF_ALPHA),
                JTalkSettings::resetAlpha));

        // -u  voiced/unvoiced threshold (higher = more unvoiced / whispery)
        col.add(sliderRow(
                new Slider("Unvoiced", SLIDER_WIDTH, ROW_HEIGHT,
                        JTalkSettings.MIN_VOICED, JTalkSettings.MAX_VOICED, JTalkSettings.getVoiced(), false)
                        .onChange(JTalkSettings::setVoiced)
                        .onCommit(JTalkSettings::save),
                () -> near(JTalkSettings.getVoiced(), JTalkSettings.DEF_VOICED),
                JTalkSettings::resetVoiced));

        // -jf  GV weight for F0
        col.add(sliderRow(
                        new Slider("GV F0", SLIDER_WIDTH, ROW_HEIGHT,
                                JTalkSettings.MIN_JF, JTalkSettings.MAX_JF, JTalkSettings.getJf(), false)
                                .onChange(JTalkSettings::setJf)
                                .onCommit(JTalkSettings::save),
                        () -> near(JTalkSettings.getJf(), JTalkSettings.DEF_JF),
                        JTalkSettings::resetJf),
                SECTION_GAP);

        col.add(new Label("Singing").setColor(UiStyle.TEXT_DIM), HEADER_GAP);

        // Base pitch for sung text that has no note of its own (e.g. the first word in "do [4]mi").
        col.add(sliderRow(
                new Slider("Sing Note", SLIDER_WIDTH, ROW_HEIGHT,
                        JTalkSettings.MIN_PITCH, JTalkSettings.MAX_PITCH, JTalkSettings.getSingNote(), true)
                        .onChange(JTalkSettings::setSingNote)
                        .onCommit(JTalkSettings::save),
                () -> near(JTalkSettings.getSingNote(), 0.0f),
                JTalkSettings::resetSingNote));

        return col;
    }

    // slider with a square reset button on its right, greyed out while at the default. clicking it resets and rebuilds the screen so the slider shows the new value
    private Row sliderRow(Widget slider, BooleanSupplier atDefault, Runnable reset) {
        Button resetButton = new Button("R", RESET_SIZE, RESET_SIZE, true)
                .onClick(() -> {
                    reset.run();
                    JTalkSettings.save();
                    this.game.openScreen(new VoiceSettingsScreen(this.game));
                });
        resetButton.setEnabledWhen(() -> !atDefault.getAsBoolean());

        Row row = new Row(RESET_GAP);
        row.setSize(COLUMN_WIDTH, ROW_HEIGHT);
        row.add(slider);
        row.add(resetButton);
        return row;
    }

    // helpers

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.001f;
    }

    private void pickMood(String character, String mood) {
        JTalkSettings.setVoice(character, mood);
        JTalkSettings.save();
        for (ChoiceRow<String> row : this.moodRows) {
            row.select(mood);   // only the row that contains it lights up
        }
        sample();
    }

    // "mei" -> "Type 1", "takumi" -> "Type 2" (numbered in sorted order)
    private static String typeName(String character) {
        int index = JTalkSettings.listCharacters().indexOf(character);
        return index < 0 ? "None" : "Type " + (index + 1);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void sample() {
        this.game.getSpeaker().clear();
        this.game.getSpeaker().speak("Hello, this is a test.");
    }

    private void goBack() {
        JTalkSettings.save();
        this.game.openScreen(new OptionsScreen(this.game));
    }
}