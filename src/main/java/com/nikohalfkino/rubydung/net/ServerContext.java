package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.console.CommandRegistry;
import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.level.Level;

import java.util.List;

/**
 * a command run by a server for somebody (a player, the LAN host, the console of a dedicated server): the world is the
 * one they are in, or the one they named with @world. the replies are collected and handed back to be told to them
 */
final class ServerContext implements CommandContext {

    final GameServer server;
    final GameServer.Actor who;
    final String[] args;
    private final List<String> out;

    ServerContext(GameServer server, GameServer.Actor who, String[] args, List<String> out) {
        this.server = server;
        this.who = who;
        this.args = args;
        this.out = out;
    }

    @Override
    public String[] args() {
        return this.args;
    }

    @Override
    public void say(String line) {
        this.out.add(line);
    }

    @Override
    public CommandTier tier() {
        // read again from the server's lists each time, whatever the actor said earlier
        return this.who.tier();
    }

    @Override
    public String actorName() {
        return this.who.name();
    }

    @Override
    public boolean onServer() {
        return true;
    }

    @Override
    public CommandRegistry registry() {
        return ServerCommands.registry();
    }

    @Override
    public boolean hasWorld() {
        return true;
    }

    @Override
    public Level level() {
        return this.server.levelOf(this.who);
    }

    @Override
    public DayNightCycle clock() {
        return this.server.clockOfActor(this.who);
    }

    @Override
    public void clockChanged() {
        this.server.pushTime(this.who);
    }

    @Override
    public void seaChanged() {
        this.server.pushSea(this.who);
    }

    @Override
    public void seaLiquidChanged() {
        this.server.pushSeaLiquid(this.who);
    }

    @Override
    public double[] position() {
        return this.server.positionOf(this.who.name(), this.who);
    }

    @Override
    public boolean hasPlayer(String name) {
        return this.server.findActor(name) != null;
    }

    @Override
    public boolean teleport(String player, double x, double y, double z) {
        GameServer.Actor target = player == null ? this.who : this.server.findActor(player);
        return target != null && this.server.teleportActor(target, x, y, z);
    }

    @Override
    public double[] positionOf(String player) {
        return this.server.positionOf(player, this.who);
    }
}
