package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.net.CommandTier;

import java.util.Collections;
import java.util.List;

public class HelpCommand implements Command {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Lists the commands you may use, or shows help for one: help [command]";
    }

    @Override
    public List<CommandArg> arguments() {
        return Collections.singletonList(CommandArg.word("command").optional());
    }

    @Override
    public void execute(CommandContext ctx) {
        if (ctx.args().length > 0) {
            Command command = ctx.registry().find(ctx.arg(0));
            if (command == null) {
                ctx.say("Unknown command: " + ctx.arg(0));
                return;
            }
            for (String line : command.help()) {
                ctx.say(line);
            }
            return;
        }

        // only what this player may run: an op sees more than a player, and on a server the tier is the server's
        List<Command> commands = ctx.registry().visibleTo(ctx.tier());

        int maxNameLength = 0;
        for (Command command : commands) {
            maxNameLength = Math.max(maxNameLength, command.name().length());
        }

        String formatSpecifier = "%-" + maxNameLength + "s - %s%s";
        for (Command command : commands) {
            String tag = command.tier() == CommandTier.PLAYER ? "" : " [" + command.tier().label() + "]";
            ctx.say(String.format(formatSpecifier, command.name(), command.description(), tag));
        }
        ctx.say("Type '<command> help' for details on a command.");
    }
}
