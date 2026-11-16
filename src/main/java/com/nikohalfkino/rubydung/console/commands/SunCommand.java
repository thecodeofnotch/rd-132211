package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.SunPhase;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** the sun's alchemical phase. anybody may look at it, changing it is for ops (on your own, you are one) */
public class SunCommand implements Command {

    @Override
    public String name() {
        return "sun";
    }

    @Override
    public String description() {
        return "Shows the sun's alchemical phase. Changing it needs op";
    }

    @Override
    public Where where() {
        return Where.WORLD;
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: sun [query | set <phase> | auto | day <n>]",
                "  sun [query]            - print the current phase and when it changes",
                "  sun set <phase>        - pin the sun to albedo, citrinitas, rubedo or nigredo (ops)",
                "  sun auto               - let the days decide the phase again (ops)",
                "  sun day <n>            - jump to game day n, which also picks the phase (ops)",
                "  a phase lasts " + DayNightCycle.DAYS_PER_PHASE + " days: albedo > citrinitas > rubedo > nigredo");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg action = CommandArg.choice("action", "query", "set", "auto", "day")
                .optional()
                .note("query", "print the current phase")
                .note("set", "pin the sun to a phase")
                .note("auto", "follow the days again")
                .note("day", "jump to a game day");

        if (given.length == 0) {
            return Collections.singletonList(action);
        }

        switch (given[0].toLowerCase(Locale.ROOT)) {
            case "set":
                return Arrays.asList(action, CommandArg.choice("phase", SunPhase.names()));
            case "day":
                return Arrays.asList(action, CommandArg.number("day"));
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
            ctx.say("Only ops can change the sun");
            return;
        }

        switch (sub) {
            case "query": {
                SunPhase phase = cycle.getSunPhase();
                if (cycle.getSunPhaseOverride() != null) {
                    ctx.say("Sun: " + phase.displayName() + " (pinned, 'sun auto' to follow the days)");
                } else {
                    ctx.say("Sun: " + phase.displayName() + " | day " + cycle.getDayInPhase() + " of "
                            + DayNightCycle.DAYS_PER_PHASE + ", then " + phase.next().displayName()
                            + " | game day " + cycle.getDayCount());
                }
                return;
            }

            case "set": {
                SunPhase phase = SunPhase.byName(ctx.arg(1));
                if (phase == null) {
                    ctx.say("Usage: sun set <" + String.join("|", SunPhase.names()) + ">");
                    return;
                }
                cycle.setSunPhaseOverride(phase);
                ctx.say("Sun pinned to " + phase.displayName());
                break;
            }

            case "auto":
                cycle.setSunPhaseOverride(null);
                ctx.say("Sun follows the days again: " + cycle.getSunPhase().displayName());
                break;

            case "day": {
                int day;
                try {
                    day = Integer.parseInt(ctx.arg(1));
                } catch (RuntimeException e) {
                    ctx.say("Usage: sun day <0 or more>");
                    return;
                }
                if (day < 0) {
                    ctx.say("The day can't be negative");
                    return;
                }
                cycle.setDayCount(day);
                ctx.say("Game day " + day + ": " + cycle.getSunPhase().displayName());
                break;
            }

            default:
                ctx.say("Usage: sun [query | set <phase> | auto | day <n>]  (type 'sun help' for details)");
                return;
        }

        // the players of the world get the new phase at once
        ctx.clockChanged();
    }
}
