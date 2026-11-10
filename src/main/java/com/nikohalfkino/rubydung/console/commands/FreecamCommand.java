package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.LocalCommand;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.render.PlayerCamera;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FreecamCommand extends LocalCommand {

    @Override
    public String name() {
        return "freecam";
    }

    @Override
    public String description() {
        return "Detaches the camera from the player so you can look at yourself";
    }

    @Override
    public List<String> help() {
        return Arrays.asList(
                "Usage: freecam [on|off|speed <blocks/s>]",
                "  freecam                  - toggle the freecam",
                "  freecam on / off         - turn it on / off",
                "  freecam speed <blocks/s> - fly speed (default "
                        + (int) PlayerCamera.DEFAULT_FREECAM_SPEED + ")",
                "While it is on the player stands still; fly the camera with the movement keys.",
                "The sky (skybox, clouds, sun and moon) stays around the player, so you can fly out and look at it from outside.",
                "It is 2000 blocks across: raise the speed to get there faster.");
    }

    @Override
    public List<CommandArg> arguments(String[] given) {
        CommandArg action = CommandArg.choice("action", "on", "off", "toggle", "speed")
                .optional()
                .note("on", "turn the freecam on")
                .note("off", "turn the freecam off")
                .note("toggle", "switch it on or off")
                .note("speed", "set the fly speed in blocks/s");

        if (given.length > 0 && given[0].equalsIgnoreCase("speed")) {
            return Arrays.asList(action, CommandArg.number("blocks/s"));
        }

        return Collections.singletonList(action);
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        if (!game.hasWorld()) {
            console.println("Load a world first");
            return;
        }

        PlayerCamera camera = game.getGameRenderer().getPlayerCamera();
        String sub = args.length == 0 ? "toggle" : args[0].toLowerCase();

        switch (sub) {
            case "toggle":
                setFreecam(console, camera, !camera.isFreecam());
                break;

            case "on":
                setFreecam(console, camera, true);
                break;

            case "off":
                setFreecam(console, camera, false);
                break;

            case "speed": {
                if (args.length < 2) {
                    console.println("Freecam speed: " + camera.getFreecamSpeed() + " blocks/s");
                    return;
                }
                try {
                    camera.setFreecamSpeed(Float.parseFloat(args[1]));
                    console.println("Freecam speed set to " + camera.getFreecamSpeed() + " blocks/s");
                } catch (NumberFormatException e) {
                    console.println("Speed must be a number");
                }
                break;
            }

            default:
                console.println("Usage: freecam [on|off|speed <blocks/s>]");
        }
    }

    private static void setFreecam(Console console, PlayerCamera camera, boolean on) {
        camera.setFreecam(on);
        console.println(on ? "Freecam on (the player stays where it is)" : "Freecam off");
    }
}