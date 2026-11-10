package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.LocalCommand;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

import java.util.Arrays;
import java.util.List;

public class RegenerateCommand extends LocalCommand {

    @Override
    public String name() {
        return "regenerate";
    }

    @Override
    public String description() {
        return "Regenerates the map";
    }

    @Override
    public Where where() {
        return Where.OWN_WORLD;
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: regenerate [seed|-] [flat|natural] [size]",
                "  seed  - a number or text; '-' skips this argument",
                "  type  - flat or natural; '-' skips this argument",
                "  size  - tiny, default, large or insane");
    }

    @Override
    public List<CommandArg> arguments() {
        return Arrays.asList(
                CommandArg.word("seed").optional().skippable(),
                CommandArg.choice("type", "flat", "natural").optional().skippable(),
                CommandArg.choice("size", "tiny", "default", "large", "insane").optional());
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        Long seed = null;
        if (args.length > 0 && !args[0].equals("-")) {
            seed = LevelGenerator.parseSeed(args[0]);
        }

        LevelGenerator.WorldType type = null;
        if (args.length > 1 && !args[1].equals("-")) {
            String typeArg = args[1].toLowerCase();
            if (typeArg.equals("flat")) {
                type = LevelGenerator.WorldType.FLAT;
            } else if (typeArg.equals("natural")) {
                type = LevelGenerator.WorldType.NATURAL;
            } else {
                console.println("Unknown world type '" + args[1] + "'. Use 'flat' or 'natural' - keeping current type.");
            }
        }

        LevelGenerator.WorldSize size = null;
        if (args.length > 2) {
            try {
                size = LevelGenerator.WorldSize.valueOf(args[2].toUpperCase());
            } catch (IllegalArgumentException e) {
                console.println("Unknown world size '" + args[2] + "'. Use tiny, default, large, or insane.");
            }
        }

        game.regenerateLevel(seed, type, size);
        // the loading screen takes over from here, the new world is ready when it closes
        console.println("Regenerating the world...");
    }
}