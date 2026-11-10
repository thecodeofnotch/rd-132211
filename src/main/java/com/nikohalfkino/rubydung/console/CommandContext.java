package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.net.CommandTier;

/**
 * everything a command may use of where it runs. in the game on its own that is the player's world, on a server it
 * is the world of whoever typed the command (or the one named with @world). a command written against this runs the
 * same either way, and what differs (telling the players about a change, who can be teleported) is behind it
 */
public interface CommandContext {

    /** the words after the command's name */
    String[] args();

    /** a line for whoever ran the command */
    void say(String line);

    /** what the one who ran it may do, always as the server knows it, never as the client claims */
    CommandTier tier();

    String actorName();

    /** whether this is a server (dedicated, or a LAN host's) as opposed to a game on its own */
    boolean onServer();

    CommandRegistry registry();

    // the world

    boolean hasWorld();

    Level level();

    DayNightCycle clock();

    /** the clock was changed: the players of the world are told (nothing to do in a game on its own) */
    void clockChanged();

    /** the sea was changed: the players of the world are told */
    void seaChanged();

    /** what the sea is made of was changed: the players of the world are told */
    void seaLiquidChanged();

    // people

    /** where the one who ran it stands, {x, y, z}, null if that isn't known */
    double[] position();

    /** whether a player of that name is in the world */
    boolean hasPlayer(String name);

    /** moves a player (null = the one who ran it). false if they can't be moved from here */
    boolean teleport(String player, double x, double y, double z);

    /** the position of a player in the world, null if there is no such player */
    double[] positionOf(String player);

    default String arg(int i) {
        String[] args = args();
        return i < args.length ? args[i] : null;
    }

    /** everything from argument i on, as typed */
    default String rest(int i) {
        String[] args = args();
        if (i >= args.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, i, args.length));
    }

    default boolean isOp() {
        return tier().atLeast(CommandTier.OP);
    }
}
