package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.net.CommandTier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * the commands, and the one place that runs a line: finds the command, checks who may use it, answers "<command> help"
 * and catches what goes wrong. the game's console and chat and the server all go through execute()
 */
public final class CommandRegistry {

    /** makes the context for a run, once the words are split (the context holds the arguments) */
    public interface ContextFactory {
        CommandContext create(String[] args);
    }

    private final Map<String, Command> commands = new HashMap<>();

    public void register(Command command) {
        this.commands.put(command.name().toLowerCase(Locale.ROOT), command);
    }

    public void registerAll(Collection<? extends Command> list) {
        for (Command command : list) {
            register(command);
        }
    }

    /** case-insensitive, null if there is none */
    public Command find(String name) {
        return name == null ? null : this.commands.get(name.toLowerCase(Locale.ROOT));
    }

    /** every command, by name */
    public List<Command> all() {
        List<Command> list = new ArrayList<>(this.commands.values());
        list.sort(Comparator.comparing(Command::name));
        return list;
    }

    /** the commands somebody of this tier may run, by name */
    public List<Command> visibleTo(CommandTier tier) {
        List<Command> list = new ArrayList<>();
        for (Command command : all()) {
            if (tier.atLeast(command.tier())) {
                list.add(command);
            }
        }
        return list;
    }

    /** the words of a line, without the dot a chat line starts with. empty for a blank line */
    public static String[] split(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    /** the command name of a line, lower case, "" for a blank one */
    public static String firstWord(String line) {
        String[] words = split(line);
        return words.length == 0 ? "" : words[0].toLowerCase(Locale.ROOT);
    }

    /** runs a line that has been split (words[0] is the command). whatever there is to say goes through the context */
    public void execute(String[] words, ContextFactory factory) {
        if (words.length == 0) {
            return;
        }
        String name = words[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(words, 1, words.length);
        CommandContext ctx = factory.create(args);

        Command command = find(name);
        if (command == null) {
            ctx.say("Unknown command: " + name + " (try 'help')");
            return;
        }

        // the tier is the context's, which a server reads from its own lists, whatever the player says about themselves
        if (!ctx.tier().atLeast(command.tier())) {
            ctx.say("You are not allowed to use " + name
                    + (command.tier() == CommandTier.HOST ? " (only the owner of the server can)" : " (ops only)"));
            return;
        }

        // "<command> help" prints that command's detailed help instead of running it
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            for (String line : command.help()) {
                ctx.say(line);
            }
            return;
        }

        if (command.where() == Command.Where.SERVER && !ctx.onServer()) {
            ctx.say("'" + name + "' needs a server: open your world to LAN, or join one");
            return;
        }

        try {
            command.execute(ctx);
        } catch (RuntimeException e) {
            if (ctx.onServer()) {
                e.printStackTrace();
            }
            ctx.say("Error running '" + name + "': " + e.getMessage());
        }
    }
}
