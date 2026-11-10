package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.console.ChatText;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.net.CommandTier;

import java.util.Collections;
import java.util.List;

public class SeedCommand implements Command {

    @Override
    public String name() {
        return "seed";
    }

    @Override
    public String description() {
        return "Displays the seed of the world you are in";
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
    public List<CommandArg> arguments() {
        return Collections.emptyList();
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.hasWorld()) {
            ctx.say("No world loaded");
            return;
        }
        // in the chat the number can be clicked to copy it
        ctx.say("World seed: " + ChatText.copyable(String.valueOf(ctx.level().getSeed())));
    }
}
