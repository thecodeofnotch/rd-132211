package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.Gui2D;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.GameSettings;

public final class FpsHud {

    private static final float SCALE = 2.0F;
    private static final int MARGIN = 10;

    private static final int LINE_HEIGHT = (int) (10 * SCALE);

    // the text is only built again when the rounded fps changes (String.format every frame is slow and makes garbage)
    private static int lastFps = Integer.MIN_VALUE;
    private static String lastText = "";

    private FpsHud() {
    }

    public static int getReservedHeight() {
        return MARGIN + LINE_HEIGHT;
    }

    public static void render(RubyDung game, Font font, int width, int height) {
        if (!GameSettings.isShowFpsInHud()) {
            return;
        }

        int fps = (int) Math.round(game.getFps());
        if (fps != lastFps) {
            lastFps = fps;
            lastText = fps + " fps";
        }
        String text = lastText;

        Gui2D.begin(width, height);

        boolean previousMono = font.isMonospace();
        font.setMonospace(true);

        int x = width - font.width(text) - MARGIN;
        font.drawStringScaled(text, x, MARGIN, 0xFFFFFF, SCALE);

        font.setMonospace(previousMono);

        Gui2D.end();
    }
}