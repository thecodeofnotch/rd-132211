package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.net.CommandTier;

import java.util.Collections;
import java.util.List;

/**
 * a command, the same kind of object on the client and on the server. there is one registry of them (CommandRegistry),
 * so "help", highlighting and tab completion know every command, and the same code answers wherever it runs.
 *
 * nothing in here may mention RubyDung (the game): a dedicated server has no game. a command that needs the game's own
 * screen or body extends LocalCommand, which is the only kind that is never registered on a server
 */
public interface Command {

    /** where a command runs, which is what decides if a line typed in the game is handled here or sent to the server */
    enum Where {
        /** this player's own screen, sound and body (clear, fly, freecam...). always runs in their own game, never on a server */
        LOCAL,
        /** changes a world the player runs themselves (regenerate): alone or hosting, but not while joined to somebody else's */
        OWN_WORLD,
        /** acts on the world through a CommandContext: the server runs it when one is involved (joined or hosting), else the game itself does */
        WORLD,
        /** administers a server (kick, ban, say, op...): only a server can run it */
        SERVER
    }

    String name();

    String description();

    default List<String> help() {
        return Collections.singletonList(description());
    }

    /** who may run it, see CommandTier. a command may still keep some of its subcommands for a higher tier (ctx.isOp()) */
    default CommandTier tier() {
        return CommandTier.PLAYER;
    }

    default Where where() {
        return Where.LOCAL;
    }

    /**
     * typed arguments, used by the chat to colour wrong ones red and to tab-complete values
     *
     * null (the default) reads them from a single "Usage: name <a> [b]" line of help() when there is
     * one, and otherwise doesn't check them. return an empty list for a command that takes none
     */
    default List<CommandArg> arguments() {
        return null;
    }

    /**
     * the whole argument list, as far as the words typed so far decide it, for commands whose later
     * arguments depend on an earlier one ("time set" takes a time, "time speed" a number). given holds the
     * finished words after the command name, so it is empty while the first argument is being typed.
     * the list always starts at the first argument: for given = {"set"} that is [action, time]
     *
     * the default ignores given and uses arguments(). return null to fall back to that as well
     */
    default List<CommandArg> arguments(String[] given) {
        return arguments();
    }

    /** runs it. the permission (tier) and where it runs have been checked by the registry already */
    void execute(CommandContext ctx);
}
