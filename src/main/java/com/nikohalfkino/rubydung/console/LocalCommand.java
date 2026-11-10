package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.RubyDung;

/**
 * a command that works on the player's own game: their screen, sound, body or a world they run. it runs in the game
 * (Console) and is never registered on a dedicated server, which has no RubyDung. by default it is Where.LOCAL
 */
public abstract class LocalCommand implements Command {

    public abstract void execute(RubyDung game, Console console, String[] args);

    @Override
    public final void execute(CommandContext ctx) {
        LocalContext local = (LocalContext) ctx;
        execute(local.game(), local.console(), ctx.args());
    }
}
