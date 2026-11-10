package com.nikohalfkino.rubydung;

import com.nikohalfkino.rubydung.interfaces.screen.LoadingScreen;
import com.nikohalfkino.rubydung.interfaces.screen.TitleScreen;
import com.nikohalfkino.rubydung.level.Level;

import com.nikohalfkino.rubydung.level.LevelIO;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;
import com.nikohalfkino.rubydung.level.generation.WorldGenStatus;
import com.nikohalfkino.rubydung.util.SaveQueue;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.player.PlayerInteraction;

import java.util.Random;

public class GameSession {

    private static final LevelGenerator.WorldSize FALLBACK_SIZE = LevelGenerator.WorldSize.DEFAULT;

    private final RubyDung game;

    private volatile Level level;
    private LevelRenderer levelRenderer;
    private Player player;
    private volatile PlayerInteraction playerInteraction;

    public GameSession(RubyDung game) {
        this.game = game;
    }

    public boolean hasWorld() {
        return this.level != null;
    }

    public Level getLevel() {
        return this.level;
    }

    public LevelRenderer getLevelRenderer() {
        return this.levelRenderer;
    }

    public Player getPlayer() {
        return this.player;
    }

    public PlayerInteraction getPlayerInteraction() {
        return this.playerInteraction;
    }

    /**
     * the tile in the player's hand. the HUD asks for it on every frame, including the title screen and the loading
     * screen where there is no world (and so no PlayerInteraction yet), so it falls back instead of failing
     */
    public Tile getSelectedTile() {
        PlayerInteraction interaction = this.playerInteraction;
        // read here and not in a static field: Tile's setup may touch OpenGL, which does not exist yet when this class loads
        return interaction != null ? interaction.getSelectedTile() : Tile.GRASS;
    }

    public void tick() {
        tick(true);
    }

    /** interaction false: a menu has the mouse (multiplayer only, alone the game stands still), the body still moves */
    public void tick(boolean interaction) {
        this.player.tick();
        if (interaction) {
            this.playerInteraction.tick();
        }
        // After the player moved and the hold set where a carried cube goes.
        this.level.getCubeManager().tick(this.player);
    }

    private void enterWorld(String worldName, LevelGenerator.WorldType type, Long seed, int width, int height, int depth,
                            boolean saveWhenReady) {
        // A background save of this world must be on disk before it is read back.
        SaveQueue.flush();

        // The old world (door travel) is dropped now, not when the new one is ready: it has been saved by the caller,
        // and while it lingered the game would keep saving the new world's clock into its files.
        leaveWorld();

        // Creating the level (generating or reading it) takes seconds. It used to run right here, on the thread that
        // draws the frames, so the loading screen was not on screen yet and could not show what the generator was
        // doing ("Carving coastlines..."); it only appeared when everything was done, showing the last step.
        // Now the screen opens first and the level is made on a worker thread while it keeps drawing.
        WorldGenStatus.set("Preparing the world...");
        LoadingScreen loadingScreen = new LoadingScreen(this.game);
        this.game.openScreen(loadingScreen);

        // Tile's static setup registers every tile and may touch OpenGL, which only works on this thread.
        // Do it now so the worker never triggers it.
        Tile.getTiles();

        Thread worker = new Thread(() -> {
            Level built;
            try {
                built = seed != null
                        ? new Level(worldName, width, height, depth, type, seed)
                        : new Level(worldName, width, height, depth, type);
            } catch (Throwable t) {
                t.printStackTrace();
                this.game.runOnMainThread(() -> this.game.openScreen(new TitleScreen(this.game)));
                return;
            }
            Level ready = built;
            this.game.runOnMainThread(() -> finishEnteringWorld(ready, loadingScreen, saveWhenReady));
        }, "world-generation");
        worker.setDaemon(true);
        worker.start();
    }

    /** main thread: the level exists, everything that needs OpenGL is made here, then the screen starts building meshes */
    private void finishEnteringWorld(Level built, LoadingScreen loadingScreen, boolean saveWhenReady) {
        // Initialize everything before marking the world as ready.
        LevelRenderer newRenderer = new LevelRenderer(built);
        Player newPlayer = new Player(built);
        PlayerInteraction newInteraction = new PlayerInteraction(built, newPlayer);

        this.levelRenderer = newRenderer;
        this.player = newPlayer;
        this.playerInteraction = newInteraction;

        // Publish the level last: hasWorld() now means everything is initialized.
        this.level = built;

        // Alone (or hosting, where the server takes this over) a thrown dice that has come to rest tells the chat what it shows.
        built.getCubeManager().setRollListener((dice, value, holderId) ->
                this.game.getChatLog().notice(com.nikohalfkino.rubydung.util.GameSettings.getUsername() + " rolled a " + value + "!"));

        if (this.level.hasSavedPosition) {
            this.player.setPositionAndRotation(
                    this.level.savedPlayerX, this.level.savedPlayerY, this.level.savedPlayerZ,
                    this.level.savedPlayerYRotation, this.level.savedPlayerXRotation
            );
        }

        if (this.level.hasFlightState) {
            this.player.flying = this.level.savedFlying;
            this.player.noClip = this.level.savedNoClip;
            this.player.flySpeedMultiplier = this.level.savedFlySpeedMultiplier;
        }

        if (saveWhenReady) {
            this.level.save();
        }

        loadingScreen.startBuilding(this.levelRenderer);
    }

    public void loadWorld(String worldName) {
        // A background save of this world must be on disk before it is read back.
        SaveQueue.flush();

        int[] savedSize = WorldManager.loadWorldSize(worldName);
        int width = savedSize != null ? savedSize[0] : FALLBACK_SIZE.width;
        int height = savedSize != null ? savedSize[1] : FALLBACK_SIZE.height;
        int depth = savedSize != null ? savedSize[2] : FALLBACK_SIZE.depth;

        LevelGenerator.WorldType worldType = WorldManager.loadWorldType(worldName);
        if (worldType == null) {
            // Save from before type.dat existed: read the type out of level.dat once. The next save
            // writes type.dat, so this slow path only ever runs for old worlds.
            worldType = LevelGenerator.WorldType.FLAT;
            java.io.File levelFile = WorldManager.levelFile(worldName);
            if (levelFile.isFile()) {
                byte[] dummyBlocks = new byte[width * height * depth];
                byte[] dummyData = new byte[width * height * depth];
                LevelIO.SavedPosition pos = LevelIO.load(levelFile, dummyBlocks, dummyData);
                if (pos.hasWorldType) {
                    worldType = pos.worldType;
                }
            }
        }

        enterWorld(worldName, worldType, null, width, height, depth, false);
    }

    public void startNewWorld(String worldName, LevelGenerator.WorldType type, LevelGenerator.WorldSize size) {
        WorldManager.saveWorldSize(worldName, size.width, size.height, size.depth);
        enterWorld(worldName, type, null, size.width, size.height, size.depth, true);
    }

    public void startNewWorld(String worldName, LevelGenerator.WorldType type, long seed, LevelGenerator.WorldSize size) {
        WorldManager.saveWorldSize(worldName, size.width, size.height, size.depth);
        enterWorld(worldName, type, seed, size.width, size.height, size.depth, true);
    }

    public void regenerateLevel(Long customSeed, LevelGenerator.WorldType overrideType, LevelGenerator.WorldSize overrideSize) {
        if (!hasWorld()) {
            return;
        }

        long seed = customSeed != null ? customSeed : new Random().nextLong();
        String worldName = this.level.getWorldName();
        LevelGenerator.WorldType type = overrideType != null ? overrideType : this.level.getWorldType();

        int width = overrideSize != null ? overrideSize.width : this.level.width;
        int height = overrideSize != null ? overrideSize.height : this.level.height;
        int depth = overrideSize != null ? overrideSize.depth : this.level.depth;

        if (overrideSize != null) {
            WorldManager.saveWorldSize(worldName, width, height, depth);
        }

        // Goes through the loading screen like any other world: generating while the old world is still being
        // drawn made both slower, and the screen shows what the generator is doing. The new level is saved
        // as soon as it is ready.
        enterWorld(worldName, type, seed, width, height, depth, true);
    }

    /** saves on the calling thread (quit, door travel) */
    public void saveLevel() {
        if (!hasWorld()) {
            return;
        }
        captureState();
        this.level.save();
    }

    /** saves with the gzip + disk write on the save thread, onDone runs there */
    public void saveLevelAsync(Runnable onDone) {
        if (!hasWorld()) {
            return;
        }
        captureState();
        this.level.saveAsync(onDone);
    }

    private void captureState() {
        this.level.setPlayerPosition(this.player.x, this.player.y, this.player.z, this.player.yRotation, this.player.xRotation);
        this.level.setPlayerFlightState(this.player.flying, this.player.noClip, this.player.flySpeedMultiplier);
    }

    /** drops the world, the caller saves first */
    // multiplayer client: the loading screen that is open while the world downloads
    private LoadingScreen networkLoading;

    /** a joined game starts: the old world goes, the loading screen opens and shows the download */
    public void beginNetworkWorld() {
        SaveQueue.flush();
        leaveWorld();
        WorldGenStatus.set("Connecting...");
        Tile.getTiles();
        this.networkLoading = new LoadingScreen(this.game);
        this.game.openScreen(this.networkLoading);
    }

    /** main thread: the world from the server is built, play it (it is never saved) */
    public void finishNetworkWorld(Level built) {
        LoadingScreen screen = this.networkLoading;
        this.networkLoading = null;
        if (screen == null) {
            screen = new LoadingScreen(this.game);
            this.game.openScreen(screen);
        }
        finishEnteringWorld(built, screen, false);
    }

    public void leaveWorld() {
        // a LAN server would be left holding a world that is gone
        Multiplayer.worldLeft();

        if (this.levelRenderer != null) {
            this.levelRenderer.dispose();
        }

        this.level = null;
        this.levelRenderer = null;
        this.playerInteraction = null;
        this.player = null;
    }

    public void dispose() {
        if (this.levelRenderer != null) {
            this.levelRenderer.dispose();
        }
    }
}