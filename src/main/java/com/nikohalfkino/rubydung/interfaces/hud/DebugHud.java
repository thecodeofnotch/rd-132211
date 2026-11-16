package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.widget.Widget;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.util.GameSettings;

import java.util.ArrayList;
import java.util.List;

/** the F3 overlay, right-aligned top-right (under the FPS counter if shown) */
public class DebugHud extends Widget {

    private static final float SCALE = 2.0F;
    private static final int LINE_STEP = (int) (10 * SCALE);
    private static final int MARGIN = 10;

    private final RubyDung game;

    // rebuilt every frame by measure(), drawn by draw()
    private final List<String> lines = new ArrayList<>();

    public DebugHud(RubyDung game) {
        this.game = game;

        setAnchor(Anchor.TOP_RIGHT, -MARGIN, MARGIN);
        setVisible(false);
    }

    public void toggle() {
        setVisible(!isVisible());
    }

    @Override
    public void measure(Font font) {
        // Sit below the FPS counter when that's showing.
        setOffset(-MARGIN, GameSettings.isShowFpsInHud() ? FpsHud.getReservedHeight() : MARGIN);

        buildLines();

        boolean previousMono = font.isMonospace();
        font.setMonospace(true);
        float widest = 0.0f;
        for (String line : this.lines) {
            widest = Math.max(widest, font.width(line));
        }
        font.setMonospace(previousMono);

        this.width = widest;
        this.height = this.lines.size() * LINE_STEP;
    }

    private void buildLines() {
        Player player = this.game.getPlayer();

        this.lines.clear();

        this.lines.add(String.format("x: %7.2f", player.x));
        this.lines.add(String.format("y: %7.2f", player.y));
        this.lines.add(String.format("z: %7.2f", player.z));

        float yaw = player.yRotation % 360;
        if (yaw < 0) yaw += 360;

        String facing = getCardinalDirection(yaw);

        this.lines.add(String.format("dir: %s (%.1f)", facing, yaw));
        this.lines.add(String.format("pitch: %6.1f", player.xRotation));

        if (player.flying || player.noClip) {
            this.lines.add(String.format("speed: %5.2fx", player.flySpeedMultiplier));
        }
    }

    @Override
    protected void draw(Font font, int mouseX, int mouseY) {
        boolean previousMono = font.isMonospace();
        font.setMonospace(true);

        int y = (int) this.y;
        for (String line : this.lines) {
            int x = (int) (this.x + this.width - font.width(line));
            font.drawStringScaled(line, x, y, 0xFFFFFF, SCALE);
            y += LINE_STEP;
        }

        font.setMonospace(previousMono);
    }

    private String getCardinalDirection(float yaw) {
        if (yaw >= 315 || yaw < 45) {
            return "North (+Z)";
        } else if (yaw >= 45 && yaw < 135) {
            return "East (-X)";
        } else if (yaw >= 135 && yaw < 225) {
            return "South (-Z)";
        } else {
            return "West (+X)";
        }
    }
}
