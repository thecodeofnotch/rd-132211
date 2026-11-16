package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.net.CommandTier;
import com.nikohalfkino.rubydung.entity.player.Player;

/** a command run by the game itself, on the world it has loaded: single player, or the LOCAL commands of any game */
final class LocalContext implements CommandContext {

    private final RubyDung game;
    private final Console console;
    private final String[] args;
    private final CommandTier tier;

    LocalContext(RubyDung game, Console console, String[] args, CommandTier tier) {
        this.game = game;
        this.console = console;
        this.args = args;
        this.tier = tier;
    }

    RubyDung game() {
        return this.game;
    }

    Console console() {
        return this.console;
    }

    @Override
    public String[] args() {
        return this.args;
    }

    @Override
    public void say(String line) {
        this.console.println(line);
    }

    @Override
    public CommandTier tier() {
        return this.tier;
    }

    @Override
    public String actorName() {
        return this.game.getChatLog().getNick();
    }

    @Override
    public boolean onServer() {
        return false;
    }

    @Override
    public CommandRegistry registry() {
        return this.console.registry();
    }

    @Override
    public boolean hasWorld() {
        return this.game.hasWorld();
    }

    @Override
    public Level level() {
        return this.game.getLevel();
    }

    @Override
    public DayNightCycle clock() {
        return this.game.getDayNightCycle();
    }

    @Override
    public void clockChanged() {
    }

    @Override
    public void seaChanged() {
    }

    @Override
    public void seaLiquidChanged() {
    }

    @Override
    public double[] position() {
        Player player = this.game.getPlayer();
        return new double[]{player.x, player.y, player.z};
    }

    @Override
    public boolean hasPlayer(String name) {
        return false;
    }

    @Override
    public boolean teleport(String player, double x, double y, double z) {
        if (player != null) {
            return false;
        }
        Player self = this.game.getPlayer();
        self.setPositionAndRotation(x, y, z, self.yRotation, self.xRotation);
        return true;
    }

    @Override
    public double[] positionOf(String player) {
        return null;
    }
}
