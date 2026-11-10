package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.LocalCommand;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Console;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ScreenshotCommand extends LocalCommand {

    @Override
    public String name() {
        return "screenshot";
    }

    @Override
    public String description() {
        return "Takes a screenshot";
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: screenshot [pano|iso] [scale]",
                "  screenshot [scale]            - normal screenshot",
                "  screenshot pano [scale]       - panorama",
                "  screenshot iso [dir] [scale]  - isometric (dir defaults to NW, scale to 2)",
                "Scale is a number, optionally with an x: 2 or 2x.");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        // the first word is a mode or already the scale ("2" or "2x"), so any word is let through
        CommandArg first = CommandArg.choice("mode", "pano", "iso")
                .loose()
                .optional()
                .note("pano", "panorama")
                .note("iso", "isometric view");

        if (given.length == 0) {
            return Collections.singletonList(first);
        }

        switch (given[0].toLowerCase()) {
            case "pano":
            case "panorama":
                return Arrays.asList(first, CommandArg.word("scale").optional());
            case "iso":
            case "isometric":
                return Arrays.asList(first,
                        CommandArg.choice("dir", "NW", "NE", "SW", "SE").loose().optional(),
                        CommandArg.word("scale").optional());
            default:
                return Collections.singletonList(first);
        }
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        if (game.getLevel() == null) {
            console.println("No world is loaded.");
            return;
        }

        int scale = 1;

        if (args.length == 0) {
            game.takeScreenshot(1);
            console.println("Screenshot saved.");
            return;
        }

        String arg0 = args[0].toLowerCase();

        if (isScaleArgument(arg0)) {
            scale = parseScale(arg0);
            game.takeScreenshot(scale);
            console.println("Screenshot saved (" + scale + "x scale).");
            return;
        }

        if (arg0.equals("pano") || arg0.equals("panorama")) {
            if (args.length > 1 && isScaleArgument(args[1])) {
                scale = parseScale(args[1]);
            }
            game.takePanoramaScreenshot(scale);
            console.println("Panorama screenshot saved (" + scale + "x scale).");
        } else if (arg0.equals("iso") || arg0.equals("isometric")) {
            String dir = "NW";
            if (args.length > 1 && !isScaleArgument(args[1])) {
                dir = args[1].toUpperCase();
                if (args.length > 2 && isScaleArgument(args[2])) {
                    scale = parseScale(args[2]);
                } else {
                    scale = 2;
                }
            } else if (args.length > 1 && isScaleArgument(args[1])) {
                scale = parseScale(args[1]);
            } else {
                scale = 2;
            }
            game.takeIsometricScreenshot(dir, scale);
            console.println("Isometric screenshot saved (" + dir + ", " + scale + "x scale).");
        } else {
            console.println("Unknown screenshot argument '" + args[0] + "'.");
        }
    }

    private boolean isScaleArgument(String arg) {
        return arg.endsWith("x") || arg.matches("\\d+");
    }

    private int parseScale(String arg) {
        try {
            String clean = arg.endsWith("x") ? arg.substring(0, arg.length() - 1) : arg;
            return Math.max(1, Integer.parseInt(clean));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}