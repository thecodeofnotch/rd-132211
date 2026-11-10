package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.console.Command;
import com.nikohalfkino.rubydung.console.CommandArg;
import com.nikohalfkino.rubydung.console.CommandContext;
import com.nikohalfkino.rubydung.console.CommandRegistry;
import com.nikohalfkino.rubydung.console.commands.SharedCommands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * the commands that only a server can run (kick, ban, op, say, createworld...), and the one way a server runs a command line.
 * the commands both sides have (time, sea, tp, help...) are SharedCommands: this registry holds them too, so a server
 * answers exactly what the game answers. who may use what is each command's tier (see CommandTier) and is checked by the
 * registry; the tier of a player is looked up on the server (the op list), a client cannot claim one
 *
 * who runs what: Command.where() says it, and net/Multiplayer routes by it
 *   a joined player's commands are NEVER run on their own computer, except the LOCAL ones (they only touch their own
 *   screen). everything else is sent here, so a client can't change the time, the sea, its position or the world by
 *   running a command the server never saw
 *   the LAN host's WORLD and SERVER commands are run here at tier HOST
 *   the console of a dedicated server runs everything here at tier HOST
 */
public final class ServerCommands {

    private interface Handler {
        void run(ServerContext c);
    }

    /** a command that is about the server itself, so it needs a ServerContext (the registry refuses it anywhere else) */
    private static final class ServerCommand implements Command {
        final String name;
        final CommandTier tier;
        final String usage;
        final String about;
        final Handler handler;
        // what to tell about the arguments, when the usage line can't say it (null = read the usage line)
        Function<String[], List<CommandArg>> spec;
        List<String> details = Collections.emptyList();

        ServerCommand(String name, CommandTier tier, String usage, String about, Handler handler) {
            this.name = name;
            this.tier = tier;
            this.usage = usage;
            this.about = Character.toUpperCase(about.charAt(0)) + about.substring(1);
            this.handler = handler;
        }

        ServerCommand args(Function<String[], List<CommandArg>> spec) {
            this.spec = spec;
            return this;
        }

        ServerCommand details(String... lines) {
            this.details = Arrays.asList(lines);
            return this;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public String description() {
            return this.about;
        }

        @Override
        public CommandTier tier() {
            return this.tier;
        }

        @Override
        public Where where() {
            return Where.SERVER;
        }

        @Override
        public List<String> help() {
            List<String> lines = new ArrayList<>();
            lines.add("Usage: " + this.usage);
            lines.add(this.about);
            lines.addAll(this.details);
            return lines;
        }

        @Override
        public List<CommandArg> arguments(String[] given) {
            return this.spec == null ? null : this.spec.apply(given);
        }

        @Override
        public void execute(CommandContext ctx) {
            this.handler.run((ServerContext) ctx);
        }
    }

    private static final List<Command> ADMIN = new ArrayList<>();
    private static final CommandRegistry REGISTRY = new CommandRegistry();

    private static ServerCommand add(String name, CommandTier tier, String usage, String about, Handler handler) {
        ServerCommand command = new ServerCommand(name, tier, usage, about, handler);
        ADMIN.add(command);
        return command;
    }

    private static List<CommandArg> fixed(CommandArg... args) {
        return Arrays.asList(args);
    }

    private static CommandArg player() {
        return CommandArg.word("player");
    }

    static {
        // PLAYER
        add("list", CommandTier.PLAYER, "list", "who is playing", c -> {
            c.say(c.server.playerCount() + " in the game: " + String.join(", ", c.server.playerNames()));
        });
        add("world", CommandTier.PLAYER, "world", "which world you are in, and the others", c -> {
            c.say("You are in " + c.server.worldOf(c.who) + ". Worlds: " + String.join(", ", c.server.worldNames()));
        });

        // OP
        add("door", CommandTier.OP, "door [list | set <world> | set <x> <y> <z> <world> | clear [<x> <y> <z>]]",
                "sets where a coloured dimensional door leads (the nearest one to you, or the one at x y z)", ServerCommands::door)
                .details(
                        "  door                             - list the coloured doors of this world and where they lead",
                        "  door set <world>                 - make the nearest coloured door lead to a world",
                        "  door set <x> <y> <z> <world>     - make the coloured door at x y z lead to a world",
                        "  door clear [x y z]               - the nearest (or the given) door leads nowhere",
                        "  a coloured door shows its destination above it and has no menu. regular doors keep theirs")
                .args(given -> {
                    CommandArg first = CommandArg.choice("action", "list", "set", "clear")
                            .optional()
                            .note("list", "the coloured doors and where they lead")
                            .note("set", "point a door at a world")
                            .note("clear", "make a door lead nowhere");
                    if (given.length == 0) {
                        return Collections.singletonList(first);
                    }
                    switch (given[0].toLowerCase(Locale.ROOT)) {
                        case "set":
                            // the world's name is free text (it may be several words), the coordinates are optional
                            return fixed(first, CommandArg.word("world or x"), CommandArg.word("y").optional(),
                                    CommandArg.word("z").optional(), CommandArg.word("world").optional());
                        case "clear":
                            return fixed(first, CommandArg.number("x").optional(),
                                    CommandArg.number("y").optional(), CommandArg.number("z").optional());
                        default:
                            return Collections.singletonList(first);
                    }
                });
        add("say", CommandTier.OP, "say <text...>", "says something to everybody as the server", c -> {
            if (c.rest(0).isEmpty()) {
                c.say("Usage: say <text>");
                return;
            }
            c.server.announce("[" + c.who.name() + "] " + c.rest(0));
        });
        add("kick", CommandTier.OP, "kick <player> [reason...]", "removes a player (they may come back)", ServerCommands::kick);

        // HOST
        add("ban", CommandTier.HOST, "ban <player> [reason...]", "bans a name and kicks it", ServerCommands::ban);
        add("pardon", CommandTier.HOST, "pardon <player>", "lifts a name ban", c -> {
            c.say(c.arg(0) != null && c.server.getSettings().pardonName(c.arg(0))
                    ? "Pardoned " + c.arg(0) : "That name is not banned");
        });
        add("banip", CommandTier.HOST, "banip <address|player> [reason...]", "bans an address (or where a player is connected from)",
                ServerCommands::banIp)
                .args(given -> fixed(CommandArg.word("address or player"), CommandArg.word("reason").optional().rest()));
        add("pardonip", CommandTier.HOST, "pardonip <address>", "lifts an address ban", c -> {
            c.say(c.arg(0) != null && c.server.getSettings().pardonIp(c.arg(0))
                    ? "Pardoned " + c.arg(0) : "That address is not banned");
        }).args(given -> fixed(CommandArg.word("address")));
        add("banlist", CommandTier.HOST, "banlist", "everything that is banned", c -> {
            List<String> bans = c.server.getSettings().bans();
            c.say(bans.isEmpty() ? "Nothing is banned" : "Banned: " + String.join(", ", bans));
        });
        add("op", CommandTier.HOST, "op <player>", "lets a player use the op commands", c -> {
            if (c.arg(0) == null) {
                c.say("Usage: op <player>");
                return;
            }
            boolean added = c.server.getSettings().addOp(c.arg(0));
            c.server.opsChanged(c.arg(0));
            c.say(added ? c.arg(0) + " is an op now" : c.arg(0) + " already is an op");
        });
        add("deop", CommandTier.HOST, "deop <player>", "takes the op commands away", c -> {
            if (c.arg(0) == null) {
                c.say("Usage: deop <player>");
                return;
            }
            boolean removed = c.server.getSettings().removeOp(c.arg(0));
            c.server.opsChanged(c.arg(0));
            c.say(removed ? c.arg(0) + " is not an op any more" : c.arg(0) + " is not an op");
        });
        add("ops", CommandTier.HOST, "ops", "the op list", c -> {
            List<String> ops = c.server.getSettings().ops();
            c.say(ops.isEmpty() ? "There are no ops" : "Ops: " + String.join(", ", ops));
        });
        add("whitelist", CommandTier.HOST, "whitelist <on|off|add <player>|remove <player>|list>",
                "who may join when the whitelist is on (ops always may)", ServerCommands::whitelist)
                .args(given -> {
                    CommandArg action = CommandArg.choice("action", "on", "off", "add", "remove", "list");
                    if (given.length > 0 && (given[0].equalsIgnoreCase("add") || given[0].equalsIgnoreCase("remove"))) {
                        return fixed(action, player());
                    }
                    return Collections.singletonList(action);
                });
        add("password", CommandTier.HOST, "password <text | off>", "the password to join with", c -> {
            String value = c.rest(0);
            if (value.isEmpty()) {
                c.say("The server " + (c.server.getSettings().hasPassword() ? "has" : "has no") + " password. Usage: password <text | off>");
                return;
            }
            if (value.equalsIgnoreCase("off")) {
                c.server.getSettings().setPassword("");
                c.say("Anybody may join now");
            } else {
                c.server.getSettings().setPassword(value);
                c.say("Password set");
            }
        }).args(given -> fixed(CommandArg.word("text or off").optional().rest()));
        add("allow", CommandTier.HOST, "allow <links|embeds|doors> <on|off>",
                "what players may do: open links, see pictures from links, use doors", ServerCommands::allow)
                .args(given -> fixed(CommandArg.choice("what", "links", "embeds", "doors"), CommandArg.choice("state", "on", "off")));
        add("reloadsettings", CommandTier.HOST, "reloadsettings", "reads the settings files again (dedicated servers)", c -> {
            c.server.getSettings().reload();
            c.server.settingsChanged();
            c.say("Settings reloaded");
        });
        add("save", CommandTier.HOST, "save", "saves the worlds (dedicated servers)", c -> {
            if (!c.server.canSave()) {
                c.say("Save from the pause menu instead");
                return;
            }
            c.server.requestSave();
            c.say("Saving...");
        });
        add("createworld", CommandTier.HOST, "createworld <name> [type] [size] [seed]",
                "adds a new world to the server (type: flat, natural, island, archipelago; size: tiny, default, large, insane, xxl). big worlds pause the server while they generate",
                ServerCommands::createWorld)
                .args(given -> fixed(CommandArg.word("name"),
                        CommandArg.choice("type", "flat", "natural", "island", "archipelago").optional(),
                        CommandArg.choice("size", "tiny", "default", "large", "insane", "xxl").optional(),
                        CommandArg.integer("seed").optional()));
        add("stop", CommandTier.HOST, "stop", "closes the server (dedicated servers)", c -> {
            if (!c.server.canStop()) {
                c.say("Use Open to LAN's button in the pause menu to close it");
                return;
            }
            c.server.requestStop();
        });

        REGISTRY.registerAll(SharedCommands.all());
        REGISTRY.registerAll(ADMIN);
    }

    private ServerCommands() {
    }

    /** every command a server runs, shared ones included */
    static CommandRegistry registry() {
        return REGISTRY;
    }

    /** the commands that need a server, for the game to know them too (help, completion, where they run) */
    public static List<Command> commands() {
        return Collections.unmodifiableList(ADMIN);
    }

    /** runs a command line (with or without the dot) for somebody and returns what to show them */
    public static List<String> execute(GameServer server, GameServer.Actor who, String line) {
        String[] words = CommandRegistry.split(line);
        if (words.length == 0) {
            return Collections.singletonList("Type .help for a list of commands.");
        }

        // "@world" anywhere after the command name: run it in that world (the console is in none, so it has no other way
        // to reach a world but the main one, and an op may change another world than the one they stand in)
        for (int i = 1; i < words.length; i++) {
            if (words[i].length() > 1 && words[i].charAt(0) == '@') {
                String world = server.worldNamed(words[i].substring(1));
                if (world == null) {
                    return Collections.singletonList("This server has no world called " + words[i].substring(1));
                }
                who = new GameServer.InWorld(who, world);
                String[] rest = new String[words.length - 1];
                System.arraycopy(words, 0, rest, 0, i);
                System.arraycopy(words, i + 1, rest, i, words.length - i - 1);
                words = rest;
                break;
            }
        }

        final GameServer.Actor actor = who;
        final List<String> out = new ArrayList<>();
        REGISTRY.execute(words, args -> new ServerContext(server, actor, args, out));
        return out;
    }

    // commands

    private static void door(ServerContext c) {
        String sub = c.arg(0) == null ? "list" : c.arg(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                for (String line : c.server.describeDoors(c.who)) {
                    c.say(line);
                }
                return;
            case "set": {
                // set <world> (the nearest door) or set <x> <y> <z> <world>
                Integer[] at = doorCoords(c, 1);
                int worldAt = at == null ? 1 : 4;
                String world = c.rest(worldAt);
                if (world.isEmpty()) {
                    c.say("Usage: door set <world> | door set <x> <y> <z> <world>");
                    return;
                }
                c.say(c.server.setDoorDestination(c.who, at == null ? null : at[0], at == null ? null : at[1],
                        at == null ? null : at[2], world, false));
                return;
            }
            case "clear": {
                Integer[] at = doorCoords(c, 1);
                c.say(c.server.setDoorDestination(c.who, at == null ? null : at[0], at == null ? null : at[1],
                        at == null ? null : at[2], null, true));
                return;
            }
            default:
                c.say("Usage: door [list | set <world> | set <x> <y> <z> <world> | clear [<x> <y> <z>]]");
        }
    }

    /** three integers from argument i on, null if they are not there (or not numbers) */
    private static Integer[] doorCoords(ServerContext c, int i) {
        if (c.args.length < i + 3) {
            return null;
        }
        try {
            return new Integer[]{Integer.parseInt(c.args[i]), Integer.parseInt(c.args[i + 1]),
                    Integer.parseInt(c.args[i + 2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void createWorld(ServerContext c) {
        String name = c.arg(0);
        if (name == null) {
            c.say("Usage: createworld <name> [type] [size] [seed]");
            return;
        }
        com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldType type =
                com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldType.ISLAND;
        com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldSize size =
                com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldSize.DEFAULT;
        Long seed = null;
        try {
            if (c.arg(1) != null) {
                type = com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldType
                        .valueOf(c.arg(1).toUpperCase(Locale.ROOT));
            }
            if (c.arg(2) != null) {
                String s = c.arg(2).toUpperCase(Locale.ROOT);
                size = com.nikohalfkino.rubydung.level.generation.LevelGenerator.WorldSize.valueOf(s.equals("XL") ? "INSANE" : s);
            }
            if (c.arg(3) != null) {
                seed = Long.parseLong(c.arg(3));
            }
        } catch (IllegalArgumentException e) {
            c.say("Usage: createworld <name> [type: flat|natural|island|archipelago] [size: tiny|default|large|insane|xxl] [seed]");
            return;
        }
        c.say("Creating " + name + " ...");
        String error = c.server.createWorld(name, type, size, seed);
        c.say(error != null ? error : "World " + name + " is ready. Dimensional doors can lead to it");
    }

    private static void kick(ServerContext c) {
        String name = c.arg(0);
        if (name == null) {
            c.say("Usage: kick <player> [reason]");
            return;
        }
        // an op can only kick players, not other ops or the host. the owner can kick anybody but themselves
        CommandTier targetTier = c.server.tierOf(name);
        if (targetTier == CommandTier.HOST || (c.who.tier() != CommandTier.HOST && targetTier.atLeast(CommandTier.OP))) {
            c.say("You can't kick " + name);
            return;
        }
        String reason = c.rest(1).isEmpty() ? "Kicked by " + c.who.name() : c.rest(1);
        c.say(c.server.kickByName(name, reason) ? "Kicked " + name : "No player called " + name);
    }

    private static void ban(ServerContext c) {
        String name = c.arg(0);
        if (name == null) {
            c.say("Usage: ban <player> [reason]");
            return;
        }
        String reason = c.rest(1);
        c.server.getSettings().banName(name, reason);
        c.server.kickByName(name, reason.isEmpty() ? "You are banned from this server" : "You are banned from this server: " + reason);
        c.say("Banned " + name);
    }

    private static void banIp(ServerContext c) {
        String target = c.arg(0);
        if (target == null) {
            c.say("Usage: banip <address|player> [reason]");
            return;
        }
        String ip = c.server.ipOf(target);
        if (ip == null) {
            ip = target;
        }
        String reason = c.rest(1);
        c.server.getSettings().banIp(ip, reason);
        int kicked = c.server.kickAddress(ip, "Your address is banned from this server");
        c.say("Banned " + ip + (kicked > 0 ? " (" + kicked + " kicked)" : ""));
    }

    private static void whitelist(ServerContext c) {
        ServerSettings settings = c.server.getSettings();
        String sub = c.arg(0) == null ? "" : c.arg(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "on":
                settings.setWhitelistEnabled(true);
                c.say("The whitelist is on");
                break;
            case "off":
                settings.setWhitelistEnabled(false);
                c.say("The whitelist is off");
                break;
            case "add":
                if (c.arg(1) == null) {
                    c.say("Usage: whitelist add <player>");
                    return;
                }
                c.say(settings.whitelistAdd(c.arg(1)) ? "Added " + c.arg(1) : c.arg(1) + " is on it already");
                break;
            case "remove":
                if (c.arg(1) == null) {
                    c.say("Usage: whitelist remove <player>");
                    return;
                }
                c.say(settings.whitelistRemove(c.arg(1)) ? "Removed " + c.arg(1) : c.arg(1) + " is not on it");
                break;
            case "list": {
                List<String> names = settings.whitelisted();
                c.say((settings.isWhitelistEnabled() ? "On. " : "Off. ") + (names.isEmpty() ? "Nobody is on it" : String.join(", ", names)));
                break;
            }
            default:
                c.say("Usage: whitelist <on|off|add <player>|remove <player>|list>");
        }
    }

    private static void allow(ServerContext c) {
        String what = c.arg(0) == null ? "" : c.arg(0).toLowerCase(Locale.ROOT);
        String value = c.arg(1) == null ? "" : c.arg(1).toLowerCase(Locale.ROOT);
        boolean on;
        if (value.equals("on") || value.equals("true")) {
            on = true;
        } else if (value.equals("off") || value.equals("false")) {
            on = false;
        } else {
            c.say("Usage: allow <links|embeds|doors> <on|off>");
            return;
        }

        ServerSettings settings = c.server.getSettings();
        switch (what) {
            case "links":
                settings.setAllowLinks(on);
                c.say("Links are " + (on ? "allowed" : "off (and so are embeds)"));
                break;
            case "embeds":
                settings.setAllowEmbeds(on);
                c.say("Embeds are " + (on ? "allowed (and so are links)" : "off"));
                break;
            case "doors":
                if (settings.isLan() && on) {
                    c.say("Dimensional doors don't work in a LAN game");
                    return;
                }
                settings.setAllowDoors(on);
                c.say("Dimensional doors are " + (on ? "on" : "off"));
                break;
            default:
                c.say("Usage: allow <links|embeds|doors> <on|off>");
                return;
        }
        c.server.settingsChanged();
    }
}
