package com.nikohalfkino.rubydung.console.commands;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.LocalCommand;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.player.Player;

public class FlyCommand extends LocalCommand {

    @Override
    public String name() {
        return "fly";
    }

    @Override
    public String description() {
        return "Toggles flight";
    }

    @Override
    public void execute(RubyDung game, Console console, String[] args) {
        Player player = game.getPlayer();
        player.flying = !player.flying;
        console.println("flying " + (player.flying ? "enabled" : "disabled"));
    }
}