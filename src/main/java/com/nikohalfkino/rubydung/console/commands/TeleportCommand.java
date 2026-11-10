package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.net.CommandTier;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** moves you or another player within the world. ~ is relative to where the one who typed the command stands */
public class TeleportCommand implements Command {

    @Override
    public String name() {
        return "tp";
    }

    @Override
    public String description() {
        return "Teleports you or another player within this world";
    }

    @Override
    public CommandTier tier() {
        return CommandTier.OP;
    }

    @Override
    public Where where() {
        return Where.WORLD;
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: tp <x> <y> <z> | tp <player> | tp <player> <x> <y> <z>",
                "  tp <x> <y> <z>            - teleport yourself",
                "  tp <player>               - go to a player (in this world)",
                "  tp <player> <x> <y> <z>   - move a player",
                "  Use ~ for your current coordinate, or ~<offset> for relative,",
                "  e.g. tp ~ ~10 ~ goes 10 blocks up.");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg first = CommandArg.word("x or player");

        if (given.length == 0) {
            return Collections.singletonList(first);
        }

        if (looksLikeCoordinate(given[0])) {
            return Arrays.asList(first, CommandArg.word("y"), CommandArg.word("z"));
        }
        return Arrays.asList(CommandArg.word("player"), CommandArg.word("x").optional(),
                CommandArg.word("y").optional(), CommandArg.word("z").optional());
    }

    private static boolean looksLikeCoordinate(String word) {
        return word.startsWith("~") || word.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)");
    }

    @Override
    public void execute(CommandContext ctx) {
        int count = ctx.args().length;
        String player = null;
        int at;

        if (count == 3) {
            at = 0;
        } else if (count == 1) {
            // "tp Alex" goes to them
            double[] spot = ctx.positionOf(ctx.arg(0));
            if (spot == null) {
                ctx.say("No player called " + ctx.arg(0) + " in your world");
                return;
            }
            if (!ctx.teleport(null, spot[0], spot[1], spot[2])) {
                ctx.say("You can't be teleported from here");
                return;
            }
            ctx.say("Teleported to " + ctx.arg(0));
            return;
        } else if (count == 4) {
            // "tp Alex x y z" moves them
            player = ctx.arg(0);
            if (!ctx.hasPlayer(player)) {
                ctx.say("No player called " + player);
                return;
            }
            at = 1;
        } else {
            ctx.say("Usage: tp <x> <y> <z> | tp <player> | tp <player> <x> <y> <z>");
            return;
        }

        double[] here = ctx.position();
        double x, y, z;
        try {
            x = coordinate(ctx.arg(at), here, 0);
            y = coordinate(ctx.arg(at + 1), here, 1);
            z = coordinate(ctx.arg(at + 2), here, 2);
        } catch (IllegalArgumentException e) {
            ctx.say("Coordinates must be numbers (or ~ / ~<offset> for relative)");
            return;
        }
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)
                || Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(z)) {
            ctx.say("Those are not coordinates");
            return;
        }

        if (!ctx.teleport(player, x, y, z)) {
            ctx.say(player == null ? "You can't be teleported from here" : "This player can't be teleported from here");
            return;
        }
        ctx.say("Teleported " + (player == null ? "" : player + " ") + "to " + x + ", " + y + ", " + z);
    }

    /** a number, or ~ / ~offset against the position of the one who ran the command (axis 0 = x, 1 = y, 2 = z) */
    private static double coordinate(String word, double[] here, int axis) {
        if (word.startsWith("~")) {
            if (here == null) {
                throw new IllegalArgumentException("no position to be relative to");
            }
            String offset = word.substring(1);
            return offset.isEmpty() ? here[axis] : here[axis] + Double.parseDouble(offset);
        }
        return Double.parseDouble(word);
    }
}
