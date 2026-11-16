package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.console.Command;

import java.util.Arrays;
import java.util.List;

/** the commands that are the same on the client and the server: they only use a CommandContext, so a dedicated server has them too */
public final class SharedCommands {

    private SharedCommands() {
    }

    public static List<Command> all() {
        return Arrays.<Command>asList(
                new HelpCommand(),
                new TimeCommand(),
                new SunCommand(),
                new SeaCommand(),
                new SeedCommand(),
                new LiquidCommand(),
                new TeleportCommand());
    }
}
