package com.nikohalfkino.rubydung.net;

/**
 * who may run a command on a server. a higher tier can do everything the lower ones can
 *
 *   PLAYER  everybody who is in the game
 *   OP      players on the op list: change the time, teleport, kick, say things as the server
 *   HOST    the owner: whoever runs the server (the LAN host in their own game, the console of a dedicated server). also the only one who can change the sea
 *           the only tier that can change who may join, who is op, save and stop. nobody can be given it over the network
 */
public enum CommandTier {
    PLAYER,
    OP,
    HOST;

    public boolean atLeast(CommandTier other) {
        return this.ordinal() >= other.ordinal();
    }

    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
