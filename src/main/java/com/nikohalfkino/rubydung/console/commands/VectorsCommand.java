package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.LocalCommand;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.entity.player.PlayerPhysics;
import com.nikohalfkino.rubydung.render.DebugRenderer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class VectorsCommand extends LocalCommand {

    @Override
    public String name() {
        return "vectors";
    }

    @Override
    public String description() {
        return "Draws the player's wish velocity, velocity and movement as arrows";
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: vectors [on|off|scale <seconds>|info]",
                "  vectors                  - toggle the arrows",
                "  vectors on / off         - turn them on / off",
                "  vectors scale <seconds>  - arrow length: how many seconds of movement an arrow shows (default "
                        + DebugRenderer.DEFAULT_VECTOR_SECONDS + ")",
                "  vectors info             - print the numbers (blocks per second)",
                "Green = wish velocity (what the keys ask for), cyan = velocity (what the physics holds),",
                "magenta = how far the player really moved in the last tick (differs from the velocity when it hits something).",
                "The box is orange on the ground and white in the air. Best watched in third person: in first person",
                "the box is hidden, and in the freecam the player is locked so the arrows stay at zero.");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg action = CommandArg.choice("action", "on", "off", "toggle", "scale", "info")
                .optional()
                .note("on", "show the arrows")
                .note("off", "hide the arrows")
                .note("toggle", "switch them on or off")
                .note("scale", "set the arrow length in seconds")
                .note("info", "print the numbers");

        if (given.length > 0 && given[0].equalsIgnoreCase("scale")) {
            return Arrays.asList(action, CommandArg.number("seconds"));
        }

        return Collections.singletonList(action);
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        DebugRenderer debug = game.getGameRenderer().getDebugRenderer();
        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase();

        switch (sub) {
            case "toggle":
                setVectors(console, debug, !debug.isVectors());
                break;

            case "on":
                setVectors(console, debug, true);
                break;

            case "off":
                setVectors(console, debug, false);
                break;

            case "scale": {
                if (args.length < 2) {
                    console.println("Vector scale: " + debug.getVectorSeconds() + " s");
                    return;
                }
                try {
                    debug.setVectorSeconds(Float.parseFloat(args[1]));
                    console.println("Vector scale set to " + debug.getVectorSeconds() + " s");
                } catch (NumberFormatException e) {
                    console.println("Scale must be a number");
                }
                break;
            }

            case "info": {
                if (!game.hasWorld()) {
                    console.println("Load a world first");
                    return;
                }
                PlayerPhysics physics = game.getPlayer().getPhysics();
                console.println("wish     " + describe(physics.getWishVelocity()));
                console.println("velocity " + describe(physics.velocity));
                console.println("moved    " + describe(physics.getMovedVelocity()));
                console.println("on ground: " + (physics.isOnGround() ? "yes" : "no")
                        + ", water level: " + physics.getWaterLevel());
                break;
            }

            default:
                console.println("Usage: vectors [on|off|scale <seconds>|info]");
        }
    }

    private static void setVectors(Console console, DebugRenderer debug, boolean on) {
        debug.setVectors(on);
        if (!on) {
            console.println("Vectors off");
            return;
        }
        console.println("Vectors on (an arrow is " + debug.getVectorSeconds() + " s of movement long)");
        console.println("  green   = wish velocity (what the keys ask for)");
        console.println("  cyan    = velocity (what the physics holds)");
        console.println("  magenta = movement over the last tick");
    }

    private static String describe(double[] v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)  flat speed %.2f", v[0], v[1], v[2], Math.hypot(v[0], v[2]));
    }
}