package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.level.LiquidSimulator;
import com.nikohalfkino.rubydung.net.CommandTier;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** the liquid simulation. it is one setting for the whole game or server, so it is for ops */
public class LiquidCommand implements Command {

    @Override
    public String name() {
        return "liquid";
    }

    @Override
    public String description() {
        return "Controls the liquid simulation";
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
                "Usage: liquid [sim|finite|speed|step|status]",
                "  liquid sim <on|off|toggle>    - turn the liquid simulation on or off",
                "  liquid finite <on|off|toggle> - finite: liquid is conserved, no infinite sources",
                "  liquid speed <ticks/sec>      - how often liquid moves (default 10)",
                "  liquid step [n]               - run n simulation ticks now, even while off",
                "  liquid [status]               - print whether it is on, its speed and busy cells",
                "  the sea is the 'sea' command");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg action = CommandArg.choice("action", "sim", "finite", "speed", "step", "status")
                .optional()
                .note("sim", "turn the liquid simulation on or off")
                .note("finite", "conserve liquid, no infinite sources")
                .note("speed", "how often liquid moves")
                .note("step", "run simulation ticks now")
                .note("status", "print the simulation state");

        if (given.length == 0) {
            return Collections.singletonList(action);
        }

        switch (given[0].toLowerCase(Locale.ROOT)) {
            case "sim":
            case "finite":
                return Arrays.asList(action, CommandArg.choice("state", "on", "off", "toggle").optional());
            case "speed":
                return Arrays.asList(action, CommandArg.number("ticks/sec"));
            case "step":
                return Arrays.asList(action, CommandArg.integer("n").optional());
            default:
                return Collections.singletonList(action);
        }
    }

    @Override
    public void execute(CommandContext ctx) {
        String sub = ctx.arg(0) == null ? "status" : ctx.arg(0).toLowerCase(Locale.ROOT);

        switch (sub) {
            case "sim": {
                Boolean on = parseState(ctx.arg(1), LiquidSimulator.isEnabled());
                if (on == null) {
                    ctx.say("Usage: liquid sim <on|off|toggle>");
                    return;
                }
                LiquidSimulator.setEnabled(on);
                ctx.say("Liquid simulation " + (on ? "on" : "off"));
                break;
            }

            case "finite": {
                Boolean on = parseState(ctx.arg(1), LiquidSimulator.isFinite());
                if (on == null) {
                    ctx.say("Usage: liquid finite <on|off|toggle>");
                    return;
                }
                LiquidSimulator.setFinite(on);
                ctx.say("Finite liquid " + (on ? "on (liquid is conserved)" : "off (sources are infinite)"));
                break;
            }

            case "speed": {
                if (ctx.arg(1) == null) {
                    ctx.say("Usage: liquid speed <ticks/sec>");
                    return;
                }
                try {
                    float tps = Float.parseFloat(ctx.arg(1));
                    if (!(tps > 0.0F) || tps > 200.0F) {
                        ctx.say("Speed must be above 0 and at most 200");
                        return;
                    }
                    LiquidSimulator.setTicksPerSecond(tps);
                    ctx.say("Liquid speed set to " + tps + " ticks/sec");
                } catch (NumberFormatException e) {
                    ctx.say("Speed must be a number");
                }
                break;
            }

            case "step": {
                if (!ctx.hasWorld()) {
                    ctx.say("No world loaded");
                    return;
                }
                int n = 1;
                if (ctx.arg(1) != null) {
                    try {
                        n = Integer.parseInt(ctx.arg(1));
                    } catch (NumberFormatException e) {
                        ctx.say("Tick count must be a whole number");
                        return;
                    }
                }
                n = Math.max(1, Math.min(n, 1000));
                ctx.level().getLiquids().step(n);
                ctx.say("Ran " + n + " liquid tick" + (n == 1 ? "" : "s"));
                break;
            }

            case "status": {
                String busy = ctx.hasWorld() ? ctx.level().getLiquids().getActiveCells() + " busy cells" : "no world";
                ctx.say("Liquid simulation: " + (LiquidSimulator.isEnabled() ? "on" : "off")
                        + " | " + (LiquidSimulator.isFinite() ? "finite" : "infinite")
                        + " | " + LiquidSimulator.getTicksPerSecond() + " ticks/sec | " + busy);
                break;
            }

            default:
                ctx.say("Usage: liquid [sim|finite|speed|step|status]  (the sea is the 'sea' command)  (type 'liquid help' for details)");
        }
    }

    /** "on", "off" or "toggle" (also no word at all, which toggles). null for anything else */
    private static Boolean parseState(String word, boolean current) {
        if (word == null || word.equalsIgnoreCase("toggle")) {
            return !current;
        }
        if (word.equalsIgnoreCase("on") || word.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (word.equalsIgnoreCase("off") || word.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
