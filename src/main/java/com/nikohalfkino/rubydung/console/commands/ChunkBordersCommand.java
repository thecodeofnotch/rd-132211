package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.LocalCommand;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.render.DebugRenderer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ChunkBordersCommand extends LocalCommand {

    @Override
    public String name() {
        return "chunkborders";
    }

    @Override
    public String description() {
        return "Shows the borders of the chunks around the camera";
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: chunkborders [on|off|range <chunks>]",
                "  chunkborders              - toggle the chunk borders",
                "  chunkborders on / off     - turn them on / off",
                "  chunkborders range <n>    - chunks drawn on each side of the camera's chunk, 1 to 8 (default "
                        + DebugRenderer.DEFAULT_CHUNK_RANGE + ")",
                "Chunks are " + Chunk.SIZE + " x " + Chunk.SIZE + " x " + Chunk.SIZE + " blocks.",
                "Yellow = the chunk the camera is in, blue = the other borders, red = the edge of the world.");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg action = CommandArg.choice("action", "on", "off", "toggle", "range")
                .optional()
                .note("on", "show the borders")
                .note("off", "hide the borders")
                .note("toggle", "switch them on or off")
                .note("range", "chunks drawn around the camera (1-8)");

        if (given.length > 0 && given[0].equalsIgnoreCase("range")) {
            return Arrays.asList(action, CommandArg.integer("chunks"));
        }

        return Collections.singletonList(action);
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        DebugRenderer debug = game.getGameRenderer().getDebugRenderer();
        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase();

        switch (sub) {
            case "toggle":
                setBorders(console, debug, !debug.isChunkBorders());
                break;

            case "on":
                setBorders(console, debug, true);
                break;

            case "off":
                setBorders(console, debug, false);
                break;

            case "range": {
                if (args.length < 2) {
                    console.println("Chunk border range: " + debug.getChunkRange() + " chunks");
                    return;
                }
                try {
                    debug.setChunkRange(Integer.parseInt(args[1]));
                    console.println("Chunk border range set to " + debug.getChunkRange() + " chunks");
                } catch (NumberFormatException e) {
                    console.println("Range must be a whole number");
                }
                break;
            }

            default:
                console.println("Usage: chunkborders [on|off|range <chunks>]");
        }
    }

    private static void setBorders(Console console, DebugRenderer debug, boolean on) {
        debug.setChunkBorders(on);
        if (!on) {
            console.println("Chunk borders off");
            return;
        }
        console.println("Chunk borders on (" + Chunk.SIZE + "-block chunks, " + debug.getChunkRange() + " around the camera)");
        console.println("  yellow = the chunk the camera is in, blue = other borders, red = edge of the world");
    }
}