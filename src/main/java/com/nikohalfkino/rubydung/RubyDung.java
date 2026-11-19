package com.nikohalfkino.rubydung;

import com.nikohalfkino.rubydung.audio.tts.JTalkSettings;
import com.nikohalfkino.rubydung.audio.tts.JTalkSpeaker;
import com.nikohalfkino.rubydung.audio.MusicPlayer;
import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.console.ChatLog;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.interfaces.*;
import com.nikohalfkino.rubydung.interfaces.hud.CenterIndicator;
import com.nikohalfkino.rubydung.interfaces.hud.ChatHud;
import com.nikohalfkino.rubydung.interfaces.hud.CrosshairHud;
import com.nikohalfkino.rubydung.interfaces.hud.DebugHud;
import com.nikohalfkino.rubydung.interfaces.hud.HandHud;
import com.nikohalfkino.rubydung.interfaces.hud.InteractPromptHud;
import com.nikohalfkino.rubydung.interfaces.hud.SelectedTileHud;
import com.nikohalfkino.rubydung.interfaces.widget.Anchor;
import com.nikohalfkino.rubydung.interfaces.screen.LoadingScreen;
import com.nikohalfkino.rubydung.interfaces.screen.FlashingLightsScreen;
import com.nikohalfkino.rubydung.interfaces.screen.SplashScreen;
import com.nikohalfkino.rubydung.interfaces.screen.TitleScreen;
import com.nikohalfkino.rubydung.level.*;
import com.nikohalfkino.rubydung.level.tiles.special.DoorColor;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.entity.player.DoorTravel;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.player.interaction.PlayerInteraction;
import com.nikohalfkino.rubydung.entity.player.input.InputHandler;
import com.nikohalfkino.rubydung.render.Font;
import com.nikohalfkino.rubydung.render.GameRenderer;
import com.nikohalfkino.rubydung.render.ScreenshotManager;
import com.nikohalfkino.rubydung.render.Textures;
import com.nikohalfkino.rubydung.render.WindowManager;
import com.nikohalfkino.rubydung.util.AutosaveSettings;
import com.nikohalfkino.rubydung.util.GameLog;
import com.nikohalfkino.rubydung.util.GameSettings;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.SaveQueue;
import com.nikohalfkino.rubydung.util.Timer;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import javax.swing.*;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.opengl.GL11.*;

public class RubyDung implements Runnable {

    public static final String GAME_NAME = "Strata";
    public static final String GAME_VERSION = "v0.2.2";

    public static final String GAME_TITLE = GAME_NAME + " " + GAME_VERSION;

    private static final String PAUSED_TITLE = GAME_NAME + " - Paused";

    private static final int TICKS_PER_SECOND = 60;

    private final com.nikohalfkino.rubydung.util.Timer timer = new Timer(TICKS_PER_SECOND);

    private final WindowManager windowManager = new WindowManager();
    private final Console console = new Console(this);
    private final ChatLog chatLog = new ChatLog();
    private final InputHandler inputHandler = new InputHandler(this);
    private final GameSession session = new GameSession(this);
    private final GameRenderer gameRenderer = new GameRenderer(this);
    private final ScreenshotManager screenshotManager = new ScreenshotManager(this, this.gameRenderer);
    private final MusicPlayer musicPlayer = new MusicPlayer();
    private final JTalkSpeaker speaker = new JTalkSpeaker();
    private final Hud hud = new Hud();
    private final DebugHud debugHud = new DebugHud(this);
    private final CenterIndicator centerIndicator = new CenterIndicator(this);
    private final DayNightCycle dayNightCycle = new DayNightCycle();

    private static final int TIME_SAVE_INTERVAL_TICKS = 60 * 10;
    private int ticksSinceTimeSave = 0;

    // only counts while the game is running
    private int ticksSinceAutosave = 0;
    private boolean running = true;

    /** frames per second of a window that is not the active one */
    private static final int INACTIVE_FPS = 20;

    // tasks handed back to the game thread (e.g. from the save thread)
    private final Queue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();
    private Font font;
    private Screen currentScreen;

    // set while going through a door: colour to look for in the new world
    private DoorColor pendingArrivalColor = null;

    public static void main(String[] args) {
        GameLog.init();
        new Thread(new RubyDung()).start();
    }

    public WindowManager getWindowManager() {
        return this.windowManager;
    }

    public int getWidth() {
        return this.windowManager.getLogicalWidth();
    }

    public int getHeight() {
        return this.windowManager.getLogicalHeight();
    }

    public GameSession getSession() {
        return this.session;
    }

    public Screen getCurrentScreen() {
        return this.currentScreen;
    }

    public Console getConsole() {
        return this.console;
    }

    public ChatLog getChatLog() {
        return this.chatLog;
    }

    public InputHandler getInputHandler() {
        return this.inputHandler;
    }

    public Font getFont() {
        return this.font;
    }

    public JTalkSpeaker getSpeaker() {
        return this.speaker;
    }

    public Hud getHud() {
        return this.hud;
    }

    public DebugHud getDebugHud() {
        return this.debugHud;
    }

    public float getFps() {
        return this.timer.fps;
    }

    public DayNightCycle getDayNightCycle() {
        return this.dayNightCycle;
    }

    public boolean hasWorld() {
        return this.session.hasWorld();
    }

    public Level getLevel() {
        return this.session.getLevel();
    }

    public LevelRenderer getLevelRenderer() {
        return this.session.getLevelRenderer();
    }

    public Player getPlayer() {
        return this.session.getPlayer();
    }

    public PlayerInteraction getPlayerInteraction() {
        return this.session.getPlayerInteraction();
    }

    public Tile getSelectedTile() {
        return this.session.getSelectedTile();
    }

    public GameRenderer getGameRenderer() {
        return this.gameRenderer;
    }

    public CenterIndicator getCenterIndicator() {
        return this.centerIndicator;
    }

    public void init() throws LWJGLException {
        GameSettings.load();
        KeyBinding.load();
        JTalkSettings.load();
        AutosaveSettings.load();

        Display.setTitle(GAME_TITLE);
        this.windowManager.applyInitialMode();

        Display.create();
        Keyboard.create();
        Mouse.create();
        this.windowManager.recompute();

        Display.setVSyncEnabled(GameSettings.isVsyncEnabled());

        Display.setIcon(Textures.loadIcons("/assets/rubydung/icon.png"));

        glEnable(GL_TEXTURE_2D);
        glShadeModel(GL_SMOOTH);
        glClearColor(0.5F, 0.8F, 1.0F, 1.0F);
        glClearDepth(1.0);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, 0.1F);
        glEnable(GL_CULL_FACE);
        glDepthFunc(GL_LEQUAL);

        this.font = new Font("/assets/rubydung/font/default.png");

        // First, so everything else on the HUD draws on top of the hand.
        this.hud.register(new HandHud(this), Anchor.BOTTOM_RIGHT, 0, 0);
        this.hud.register(new SelectedTileHud(this));
        this.hud.register(this.debugHud);
        this.hud.register(new CrosshairHud());
        this.hud.register(new InteractPromptHud(this), Anchor.CENTER, 0, 28);
        this.hud.register(this.centerIndicator);
        this.hud.register(new ChatHud(this));

        WorldManager.pruneIncompleteWorlds();

        this.musicPlayer.start();
        this.speaker.start();

        openStartupScreens();
    }

    /** splash -> flashing lights warning -> title, each one only if its option is on */
    private void openStartupScreens() {
        Runnable toTitle = () -> openScreen(new TitleScreen(this, true));
        Runnable toWarning = () -> {
            if (GameSettings.isShowFlashingWarning()) {
                openScreen(new FlashingLightsScreen(this, toTitle));
            } else {
                toTitle.run();
            }
        };

        if (GameSettings.isShowSplashScreen()) {
            openScreen(new SplashScreen(this, toWarning));
        } else {
            toWarning.run();
        }
    }

    /** saves the old world (door closed), arrives at a matching door in the new one */
    public void travelThroughDoor(String worldName, DoorColor pickedColor) {
        SoundEffects.play("door_travel");
        closeScreen();
        saveLevel();
        this.pendingArrivalColor = pickedColor;
        loadWorld(worldName);
    }

    public void finishedLoadingWorld() {
        this.ticksSinceAutosave = 0;

        if (this.pendingArrivalColor != null) {
            DoorColor arrivedThrough = this.pendingArrivalColor;
            this.pendingArrivalColor = null;
            DoorTravel.arrive(getLevel(), getPlayer(), arrivedThrough);
        }

        closeScreen();
        Mouse.setGrabbed(true);
    }

    public void loadWorld(String worldName) {
        saveWorldTime();
        this.chatLog.reset(worldName);

        // Each world keeps its own clock; worlds saved before this existed start at noon.
        if (!WorldManager.loadWorldTime(worldName, this.dayNightCycle)) {
            this.dayNightCycle.applyCreationSettings(
                    DayNightCycle.TimeMode.NORMAL,
                    DayNightCycle.DEFAULT_START_TIME
            );
        }

        this.session.loadWorld(worldName);
    }

    public void startNewWorld(String worldName, LevelGenerator.WorldType type, LevelGenerator.WorldSize size) {
        startNewWorld(worldName, type, size, DayNightCycle.TimeMode.NORMAL, DayNightCycle.DEFAULT_START_TIME);
    }

    public void startNewWorld(
            String worldName,
            LevelGenerator.WorldType type,
            LevelGenerator.WorldSize size,
            DayNightCycle.TimeMode timeMode,
            float startTime
    ) {
        beginWorldClock(worldName, timeMode, startTime);
        WorldManager.saveWorldSettings(worldName, size, timeMode);
        this.chatLog.reset(worldName);
        this.session.startNewWorld(worldName, type, size);
    }

    public void startNewWorld(
            String worldName,
            LevelGenerator.WorldType type,
            long seed,
            LevelGenerator.WorldSize size
    ) {
        startNewWorld(
                worldName,
                type,
                seed,
                size,
                DayNightCycle.TimeMode.NORMAL,
                DayNightCycle.DEFAULT_START_TIME
        );
    }

    public void startNewWorld(
            String worldName,
            LevelGenerator.WorldType type,
            long seed,
            LevelGenerator.WorldSize size,
            DayNightCycle.TimeMode timeMode,
            float startTime
    ) {
        beginWorldClock(worldName, timeMode, startTime);
        WorldManager.saveWorldSettings(worldName, size, timeMode);
        this.chatLog.reset(worldName);
        this.session.startNewWorld(worldName, type, seed, size);
    }

    // applies the time settings and saves them right away
    private void beginWorldClock(String worldName, DayNightCycle.TimeMode timeMode, float startTime) {
        saveWorldTime();
        this.dayNightCycle.applyCreationSettings(timeMode, startTime);
        WorldManager.saveWorldTime(worldName, this.dayNightCycle);
    }

    // no-op without a world
    private void saveWorldTime() {
        // a world downloaded from a server is not ours to save
        if (hasWorld() && !Multiplayer.isClient()) {
            WorldManager.saveWorldTime(getLevel().getWorldName(), this.dayNightCycle);
        }
    }

    public void regenerateLevel(
            Long customSeed,
            LevelGenerator.WorldType overrideType,
            LevelGenerator.WorldSize overrideSize
    ) {
        this.session.regenerateLevel(customSeed, overrideType, overrideSize);
    }

    public void saveLevel() {
        this.session.saveLevel();
        saveWorldTime();

        // Any save (manual, travelling through a door, ...) restarts the autosave countdown.
        this.ticksSinceAutosave = 0;
    }

    /** saves and unloads the world, otherwise hasWorld() stays true after quitting */
    public void quitToTitle() {
        Multiplayer.shutdown("Left the game");

        if (hasWorld()) {
            saveLevel();
            this.session.leaveWorld();
        }

        this.pendingArrivalColor = null;
        this.ticksSinceAutosave = 0;
        Display.setTitle(GAME_TITLE);

        openScreen(new TitleScreen(this));
    }

    private void autosave() {
        // Only snapshots happen here; the GZIP + disk write runs on the save thread.
        // An autosave that can't start (memory) is skipped and retried next interval, it must not end the game.
        try {
            GameLog.memory("autosave starting");
            this.session.saveLevelAsync(() -> {
                GameLog.memory("autosave finished");
                runOnMainThread(() -> this.centerIndicator.showMessage("Saved!"));
            });
            saveWorldTime();
        } catch (Throwable t) {
            System.err.println("[autosave] failed: " + t);
            t.printStackTrace();
        }
        this.ticksSinceAutosave = 0;
    }

    /** safe from any thread, runs at the start of the next frame */
    public void runOnMainThread(Runnable task) {
        this.mainThreadTasks.add(task);
    }

    public void quit() {
        this.running = false;
    }

    /**
     * every step runs even if an earlier one fails: the save queue's thread is not a daemon, so one step that throws and skips
     * SaveQueue.shutdownAndWait() used to leave the JVM alive (the window gone, the process still there)
     */
    public void destroy() {
        attempt("leaving the network", () -> Multiplayer.shutdown("Game closed"));
        attempt("saving the level", this::saveLevel);
        attempt("saving the settings", () -> {
            GameSettings.save();
            KeyBinding.save();
            JTalkSettings.save();
            AutosaveSettings.save();
        });
        attempt("finishing the saves", SaveQueue::shutdownAndWait);
        attempt("closing the session", () -> this.session.dispose());
        attempt("stopping the voice", () -> this.speaker.stop());
        attempt("stopping the music", () -> this.musicPlayer.stop());
        attempt("closing the input", () -> {
            Mouse.destroy();
            Keyboard.destroy();
        });
        attempt("closing the window", Display::destroy);
    }

    private static void attempt(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            System.out.println("Shutdown: failed " + what + ": " + t);
            t.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            init();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    null,
                    e,
                    "Failed to start " + GAME_TITLE,
                    JOptionPane.ERROR_MESSAGE
            );
            System.exit(0);
        }

        long lastTime = System.currentTimeMillis();

        try {
            while (!Display.isCloseRequested() && this.running) {
                GameLog.heartbeat();
                Runnable task;
                while ((task = this.mainThreadTasks.poll()) != null) {
                    task.run();
                }

                this.timer.advanceTime();

                for (int i = 0; i < this.timer.ticks; ++i) {
                    tick();
                }

                if (Display.wasResized()) {
                    onWindowResized();
                }

                this.gameRenderer.render(this.timer.partialTicks);

                // a window that is not in front (another instance has the focus, or it is covered or minimized) has no reason to
                // draw at full speed: vsync is often ignored for it, and two games on one PC would fight over the GPU. the ticks
                // do not depend on the frame rate, so a LAN host in the background still serves the others
                int fpsCap = GameSettings.getFpsCap();
                if (!Display.isActive()) {
                    fpsCap = fpsCap == GameSettings.UNCAPPED_FPS ? INACTIVE_FPS : Math.min(fpsCap, INACTIVE_FPS);
                }
                if (fpsCap != GameSettings.UNCAPPED_FPS) {
                    Display.sync(fpsCap);
                }

                while (System.currentTimeMillis() >= lastTime + 1000L) {
                    Chunk.updates = 0;
                    lastTime += 1000L;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            destroy();
            // nothing may keep the process alive once the game is closed (a thread left over, a save still pending)
            System.exit(0);
        }
    }

    private void tick() {
        // The LAN server and the connection to a server keep going while a menu is open.
        Multiplayer.tick(this);

        // Autosave the clock so a crash or a quit path that skips saveLevel() loses at most ~10 s.
        if (hasWorld() && ++this.ticksSinceTimeSave >= TIME_SAVE_INTERVAL_TICKS) {
            this.ticksSinceTimeSave = 0;
            saveWorldTime();
        }

        if (!hasWorld() || isGameFrozen()) {
            return;
        }

        // Alone, a menu / the console / a lost window focus stops the game. In multiplayer the world is shared and
        // keeps running: the player just stops reacting to the keys and mouse (they belong to the menu).
        boolean inputBlocked = this.currentScreen != null || this.console.isOpen() || !Display.isActive();
        if (inputBlocked && !Multiplayer.isActive()) {
            return;
        }
        getPlayer().screenLocked = inputBlocked;

        // The world clock only runs while the game does, so it pauses with menus, the console and loading.
        this.dayNightCycle.tick(this.timer.partialTicks);
        this.session.tick(!inputBlocked);

        // An autosave under way copies the world a slice per tick instead of all at once.
        getLevel().tickAutosave();

        // Counts play time only: menus, the console and loading pause it, like the world clock.
        if (AutosaveSettings.isEnabled()
                && ++this.ticksSinceAutosave >= AutosaveSettings.getIntervalMinutes() * 60 * TICKS_PER_SECOND) {
            autosave();
        }
    }

    /**
     * true while the world must stand still: loading always, and any screen alone. with other players (hosting a LAN
     * game or joined to a server) screens never stop the world, the others keep playing
     */
    public boolean isGameFrozen() {
        if (this.currentScreen == null) {
            return false;
        }
        if (this.currentScreen instanceof LoadingScreen) {
            return true;
        }
        return this.currentScreen.pausesGame() && !Multiplayer.isActive();
    }

    public void openScreen(Screen screen) {
        if (this.currentScreen != null) {
            this.currentScreen.onClose();
        }

        this.currentScreen = screen;
        screen.init(getWidth(), getHeight());

        if (screen.pausesGame()) {
            Mouse.setGrabbed(false);

            if (hasWorld() && isGameFrozen()) {
                Display.setTitle(PAUSED_TITLE);
            }
        }
    }

    public void closeScreen() {
        if (this.currentScreen != null) {
            this.currentScreen.onClose();
        }

        this.currentScreen = null;

        if (!this.console.isOpen()) {
            Mouse.setGrabbed(true);
        }

        Display.setTitle(GAME_TITLE);
    }

    /** queued so it runs at the start of the next frame, never mid-click */
    public void applyDisplay() {
        this.mainThreadTasks.add(() -> {
            try {
                boolean grabbed = Mouse.isGrabbed();
                this.windowManager.applyMode();

                // The window may have been recreated.
                Display.setTitle(hasWorld() && isGameFrozen()
                        ? PAUSED_TITLE : GAME_TITLE);
                Display.setIcon(Textures.loadIcons("/assets/rubydung/icon.png"));

                Mouse.setGrabbed(grabbed);
                Mouse.getDX(); // drop the jump the mode switch causes so the camera doesn't snap
                Mouse.getDY();

                if (this.currentScreen != null) {
                    this.currentScreen.resize(getWidth(), getHeight());
                }
            } catch (LWJGLException e) {
                e.printStackTrace();
            }
        });
    }

    private void onWindowResized() {
        this.windowManager.recompute();
        if (this.currentScreen != null) {
            this.currentScreen.resize(getWidth(), getHeight());
        }
    }

    public void toggleFullscreen() {
        // F11: into fullscreen, or back to the windowed state it came from.
        GameSettings.setDisplayType(GameSettings.isFullscreen()
                ? GameSettings.getLastWindowType() : GameSettings.DisplayType.FULLSCREEN);
        GameSettings.save();
        applyDisplay();
    }

    public void takeScreenshot(int scale) {
        this.screenshotManager.takeScreenshot(scale);
    }

    public void takePanoramaScreenshot(int scale) {
        this.screenshotManager.takePanoramaScreenshot(scale);
    }

    public void takeIsometricScreenshot(String direction, int scale) {
        this.screenshotManager.takeIsometricScreenshot(direction, scale);
    }
}