package com.nikohalfkino.rubydung.console;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.console.commands.*;
import com.nikohalfkino.rubydung.interfaces.widget.TextField;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.net.ServerCommands;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.*;

public class Console {

    public static final int MAX_LOG_LINES = 200;

    private final RubyDung game;
    // every command there is: the ones both sides share, the server's, and this game's own (see Command.Where)
    private final CommandRegistry registry = new CommandRegistry();
    private final List<String> log = new ArrayList<>();
    private final List<String> commandHistory = new ArrayList<>();

    private final TextField input = new TextField();
    private final CommandSyntax syntax;
    private final CommandCompleter completer;
    private int historyIndex = -1;
    private boolean open = false;

    // while non-null, println() collects here instead of the log (see execute)
    private List<String> capture;

    public Console(RubyDung game) {
        this.game = game;

        // the same commands a server has, so help, completion and routing know them all
        this.registry.registerAll(SharedCommands.all());
        this.registry.registerAll(ServerCommands.commands());

        // what only this game can do
        register(new ClearCommand());
        register(new NoClipCommand());
        register(new FlyCommand());
        register(new ScreenshotCommand());
        register(new RegenerateCommand());
        register(new FreecamCommand());
        register(new SingCommand());
        register(new VectorsCommand());
        register(new ChunkBordersCommand());

        // the chat's ".command" asks the registry where a command runs before it is run
        Multiplayer.useCommands(this.registry);

        // the input line is colourised and tab-completed like the chat's ".commands"
        this.syntax = new CommandSyntax(this::find, this::commands);
        this.input.setHighlighter(this.syntax::highlight);
        this.completer = new CommandCompleter(this.input, this.syntax, "");

        println("Type 'help' for a list of commands.");
    }

    public void register(Command command) {
        this.registry.register(command);
    }

    public CommandRegistry registry() {
        return this.registry;
    }

    /** case-insensitive, null if there is none */
    public Command find(String name) {
        return this.registry.find(name);
    }

    /** the commands this player may run: on a server that depends on their tier there */
    public List<Command> commands() {
        return this.registry.visibleTo(Multiplayer.localTier());
    }

    public boolean isOpen() {
        return this.open;
    }

    public void setOpen(boolean open) {
        if (this.open == open) {
            return;
        }

        this.open = open;
        Keyboard.enableRepeatEvents(open);

        if (open) {
            this.input.clear();
            this.completer.reset();
            this.historyIndex = -1;
            Mouse.setGrabbed(false);
        } else {
            Mouse.setGrabbed(this.game.getCurrentScreen() == null);
        }
    }

    public void toggle() {
        setOpen(!this.open);
    }

    public void println(String line) {
        if (this.capture != null) {
            this.capture.add(line);
            return;
        }

        // the console can't show clickable parts, so it gets the plain text
        this.log.add(ChatText.strip(line));
        if (this.log.size() > MAX_LOG_LINES) {
            this.log.remove(0);
        }
    }

    public void clear() {
        this.log.clear();
    }

    public List<String> getLog() {
        return this.log;
    }

    public TextField getInput() {
        return this.input;
    }

    /** matches for the word being typed, for the console screen to draw */
    public CommandCompleter getCompleter() {
        return this.completer;
    }

    public void keyTyped(int key, char character) {
        if (key == KeyMap.ESC) {
            setOpen(false);
            return;
        }

        if (key == KeyMap.ENTER || key == KeyMap.NUMPAD_ENTER) {
            submit();
            return;
        }

        if (key == KeyMap.TAB) {
            boolean back = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                    || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            this.completer.tab(back ? -1 : 1);
            return;
        }

        if (key == KeyMap.UP) {
            navigateHistory(-1);
            return;
        }

        if (key == KeyMap.DOWN) {
            navigateHistory(1);
            return;
        }

        this.input.keyTyped(key, character);
    }

    private void navigateHistory(int direction) {
        if (this.commandHistory.isEmpty()) {
            return;
        }

        if (direction < 0) {
            if (this.historyIndex < this.commandHistory.size() - 1) {
                this.historyIndex++;
            }
        } else if (direction > 0) {
            if (this.historyIndex >= 0) {
                this.historyIndex--;
            }
        }

        if (this.historyIndex >= 0) {
            this.input.setText(this.commandHistory.get(this.commandHistory.size() - 1 - this.historyIndex));
        } else {
            this.input.clear();
        }
    }

    private void submit() {
        String line = this.input.getText().trim();
        this.input.clear();
        this.completer.reset();
        this.historyIndex = -1;

        if (line.isEmpty()) {
            return;
        }

        this.commandHistory.add(line);
        println("] " + line);

        // typed here or in the chat, a line goes where it belongs: to the server when one is involved
        List<String> routed = Multiplayer.runCommand(line);
        if (routed == null) {
            dispatch(line);
        } else {
            for (String reply : routed) {
                println(reply);
            }
        }
    }

    /** runs a line and returns what it printed instead of logging it (for the chat screen's ".command") */
    public List<String> execute(String line) {
        List<String> output = new ArrayList<>();
        List<String> previous = this.capture;
        this.capture = output;
        try {
            dispatch(line.trim());
        } finally {
            this.capture = previous;
        }
        return output;
    }

    private void dispatch(String line) {
        String[] words = CommandRegistry.split(line);
        this.registry.execute(words, args -> new LocalContext(this.game, this, args, Multiplayer.localTier()));
    }
}
