package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.level.DayNightCycle;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** the day-night cycle. anybody may look at it, changing it is for ops (on your own, you are one) */
public class TimeCommand implements Command {

    /** the fastest the clock may run, so one command can't make a server's day a blur */
    private static final float MAX_SPEED = 100.0F;

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "Shows the time of day. Changing it needs op";
    }

    @Override
    public Where where() {
        return Where.WORLD;
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: time [query | set <time> | speed <multiplier> | freeze | unfreeze]",
                "  time [query]                                - print the current time & speed",
                "  time set <dawn|noon|dusk|midnight|0.0-1.0>  - jump to a time (ops)",
                "  time speed <multiplier>                     - e.g. 2 = 2x faster, 0 = freeze (ops, at most " + (int) MAX_SPEED + ")",
                "  time freeze / unfreeze                      - stop / resume the cycle (ops)",
                "  sunrise and sunset also work for dawn and dusk, day and night for noon and midnight");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg action = CommandArg.choice("action", "query", "set", "speed", "freeze", "unfreeze")
                .optional()
                .note("query", "print the current time and speed")
                .note("set", "jump to a time")
                .note("speed", "change how fast time passes")
                .note("freeze", "stop the cycle")
                .note("unfreeze", "resume the cycle");

        if (given.length == 0) {
            return Collections.singletonList(action);
        }

        switch (given[0].toLowerCase(Locale.ROOT)) {
            case "set":
                return Arrays.asList(action, CommandArg.choice("time", "dawn", "noon", "dusk", "midnight",
                                "sunrise", "sunset", "day", "night")
                        .orNumber()
                        .note("dawn", "0.25")
                        .note("noon", "0.5")
                        .note("dusk", "0.75")
                        .note("midnight", "0.0"));
            case "speed":
                return Arrays.asList(action, CommandArg.number("multiplier"));
            default:
                return Collections.singletonList(action);
        }
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.hasWorld()) {
            ctx.say("No world loaded");
            return;
        }

        DayNightCycle cycle = ctx.clock();
        String sub = ctx.arg(0) == null ? "query" : ctx.arg(0).toLowerCase(Locale.ROOT);

        if (!sub.equals("query") && !ctx.isOp()) {
            ctx.say("Only ops can change the time");
            return;
        }

        switch (sub) {
            case "query": {
                float t = cycle.getTime();
                ctx.say("Time: " + String.format(Locale.ROOT, "%.4f", t)
                        + " (" + phaseName(t) + ")"
                        + " | speed: " + cycle.getSpeed() + "x"
                        + (cycle.isFrozen() ? " [FROZEN]" : ""));
                return;
            }

            case "set": {
                float t = ctx.arg(1) == null ? Float.NaN : parseTimeValue(ctx.arg(1));
                if (Float.isNaN(t)) {
                    ctx.say("Usage: time set <dawn|noon|dusk|midnight|0.0-1.0>");
                    return;
                }
                cycle.setTime(t);
                ctx.say("Time set to " + ctx.arg(1) + " (" + String.format(Locale.ROOT, "%.3f", t) + ")");
                break;
            }

            case "speed": {
                float speed;
                try {
                    speed = Float.parseFloat(ctx.arg(1));
                } catch (RuntimeException e) {
                    ctx.say("Usage: time speed <0-" + (int) MAX_SPEED + ">");
                    return;
                }
                if (Float.isNaN(speed) || speed < 0.0F || speed > MAX_SPEED) {
                    ctx.say("Speed must be between 0 and " + (int) MAX_SPEED);
                    return;
                }
                cycle.setSpeed(speed);
                ctx.say(speed == 0.0F ? "Time frozen (speed 0)" : "Time speed set to " + speed + "x");
                break;
            }

            case "freeze":
                cycle.setFrozen(true);
                ctx.say("Time frozen at " + String.format(Locale.ROOT, "%.3f", cycle.getTime())
                        + " (" + phaseName(cycle.getTime()) + ")");
                break;

            case "unfreeze":
                cycle.setFrozen(false);
                ctx.say("Time unfrozen");
                break;

            default:
                ctx.say("Usage: time [query | set <time> | speed <n> | freeze | unfreeze]  (type 'time help' for details)");
                return;
        }

        // the players of the world get the new time at once (nothing happens in a game on its own)
        ctx.clockChanged();
    }

    private static float parseTimeValue(String s) {
        switch (s.toLowerCase(Locale.ROOT)) {
            case "midnight":
            case "night":
                return 0.0F;
            case "dawn":
            case "sunrise":
                return 0.25F;
            case "noon":
            case "day":
                return 0.5F;
            case "dusk":
            case "sunset":
                return 0.75F;
            default:
                try {
                    float v = Float.parseFloat(s);
                    return (v >= 0.0F && v <= 1.0F) ? v : Float.NaN;
                } catch (NumberFormatException e) {
                    return Float.NaN;
                }
        }
    }

    private static String phaseName(float t) {
        if (t < 0.125F || t >= 0.875F) return "night";
        if (t < 0.375F)                return "dawn";
        if (t < 0.625F)                return "day";
        return "dusk";
    }
}
