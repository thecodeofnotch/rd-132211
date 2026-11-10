package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.level.LiquidLayer;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** the ocean of a world. anybody may look at it, changing it is for ops (on your own, you are one) */
public class SeaCommand implements Command {

    @Override
    public String name() {
        return "sea";
    }

    @Override
    public String description() {
        return "Shows the sea. Changing its height or liquid needs op";
    }

    @Override
    public Where where() {
        return Where.WORLD;
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: sea [height] [blocks/sec] | set | rise | stop | liquid | status",
                "  sea [status]                     - print the sea height, target, rate and liquid",
                "  sea <height>                     - move the whole sea to this height at once (blocks) (ops)",
                "  sea set <height>                 - same as above",
                "  sea <height> <blocks/sec>        - let the sea rise or fall to a height by itself (eases in and out) (ops)",
                "  sea rise <height> [blocks/sec]   - same as above, 1 block a second if no rate is given",
                "  sea stop                         - freeze the sea where it is (ops)",
                "  sea liquid                       - print the sea's liquid and the ones you can pick",
                "  sea liquid <name>                - make the sea out of another liquid (water, lava...) (ops)",
                "  on a server, add @world to change the sea of another world");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg first = CommandArg.choice("height", "set", "rise", "stop", "liquid", "status")
                .orNumber()
                .optional()
                .note("set", "move the sea to a height at once")
                .note("rise", "ease the sea to a height at a rate")
                .note("stop", "freeze the sea where it is")
                .note("liquid", "show or change what the sea is made of")
                .note("status", "print the sea height and rate");

        if (given.length == 0) {
            return Collections.singletonList(first);
        }

        switch (given[0].toLowerCase(Locale.ROOT)) {
            case "set":
                return Arrays.asList(first, CommandArg.number("height"));
            case "rise":
                return Arrays.asList(first, CommandArg.number("height"), CommandArg.number("blocks/sec").optional());
            case "liquid": {
                return Arrays.asList(first, CommandArg.choice("liquid", liquidNames()).optional());
            }
            case "stop":
            case "status":
                return Collections.singletonList(first);
            default:
                // "sea <height> [blocks/sec]"
                return Arrays.asList(first, CommandArg.number("blocks/sec").optional());
        }
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.hasWorld()) {
            ctx.say("No world loaded");
            return;
        }
        LiquidLayer liquids = ctx.level().getLiquids();
        if (!liquids.hasOcean()) {
            ctx.say("This world has no sea");
            return;
        }

        String sub = ctx.arg(0) == null ? "status" : ctx.arg(0).toLowerCase(Locale.ROOT);

        if (sub.equals("status")) {
            printStatus(ctx, liquids);
            return;
        }
        if (sub.equals("liquid") && ctx.arg(1) == null) {
            ctx.say("Sea liquid: " + liquids.getOceanLiquid().name + " | available: " + String.join(", ", liquidNames()));
            return;
        }
        if (!ctx.isOp()) {
            ctx.say("Only ops can change the sea");
            return;
        }

        try {
            if (sub.equals("stop")) {
                liquids.riseTo(liquids.getSeaHeight(), 0.0F);
                ctx.say("Sea stopped at " + liquids.getSeaHeight());

            } else if (sub.equals("liquid")) {
                if (!changeLiquid(ctx, liquids, ctx.arg(1))) {
                    return;
                }

            } else if (sub.equals("set")) {
                if (ctx.arg(1) == null) {
                    ctx.say("Usage: sea set <height>");
                    return;
                }
                setHeight(ctx, liquids, number(ctx.arg(1)));

            } else if (sub.equals("rise")) {
                if (ctx.arg(1) == null) {
                    ctx.say("Usage: sea rise <height> [blocks/sec]");
                    return;
                }
                if (!rise(ctx, liquids, number(ctx.arg(1)), ctx.arg(2) == null ? 1.0F : number(ctx.arg(2)))) {
                    return;
                }

            } else if (ctx.arg(1) != null) {
                if (!rise(ctx, liquids, number(ctx.arg(0)), number(ctx.arg(1)))) {
                    return;
                }

            } else {
                setHeight(ctx, liquids, number(ctx.arg(0)));
            }
        } catch (NumberFormatException e) {
            ctx.say("Height and rate must be numbers (type 'sea help')");
            return;
        }

        // the players of the world see the sea move at once
        ctx.seaChanged();
    }

    private static float number(String text) {
        float value = Float.parseFloat(text);
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new NumberFormatException(text);
        }
        return value;
    }

    private static void setHeight(CommandContext ctx, LiquidLayer liquids, float height) {
        liquids.riseTo(height, 0.0F);
        liquids.setSeaHeight(height);
        ctx.say("Sea height set to " + liquids.getSeaHeight());
    }

    private static boolean rise(CommandContext ctx, LiquidLayer liquids, float target, float rate) {
        if (rate <= 0) {
            ctx.say("Rate must be above 0");
            return false;
        }
        liquids.riseTo(target, rate);
        ctx.say("Sea moving to " + liquids.getSeaTarget() + " at " + rate + " blocks/sec");
        return true;
    }

    private static boolean changeLiquid(CommandContext ctx, LiquidLayer liquids, String name) {
        LiquidTile liquid = findLiquid(name);
        if (liquid == null) {
            ctx.say("Unknown liquid '" + name + "'. Available: " + String.join(", ", liquidNames()));
            return false;
        }
        if (liquid == liquids.getOceanLiquid()) {
            ctx.say("The sea is already " + liquid.name);
            return false;
        }
        liquids.setOceanLiquid(liquid);
        ctx.say("The sea is now " + liquid.name);
        ctx.seaLiquidChanged();
        return true;
    }

    private static LiquidTile findLiquid(String name) {
        for (LiquidTile liquid : LiquidTile.all()) {
            if (liquid.name.equalsIgnoreCase(name)) {
                return liquid;
            }
        }
        return null;
    }

    private static String[] liquidNames() {
        List<String> names = new ArrayList<>();
        for (LiquidTile liquid : LiquidTile.all()) {
            names.add(liquid.name);
        }
        return names.toArray(new String[0]);
    }

    private static void printStatus(CommandContext ctx, LiquidLayer liquids) {
        ctx.say("Sea of " + liquids.getOceanLiquid().name + " at height " + liquids.getSeaHeight()
                + ", drawn at " + liquids.getSurfaceHeight() + " (top cell y=" + liquids.getSeaTop() + ")"
                + (liquids.isSeaMoving()
                ? " | moving to " + liquids.getSeaTarget() + " at " + liquids.getSeaRate() + " blocks/sec"
                : " | still"));
    }
}
