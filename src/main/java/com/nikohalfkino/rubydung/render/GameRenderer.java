package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.level.Chunk;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.level.DayNightCycle;
import com.nikohalfkino.rubydung.entity.piece.ChessPiece;
import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.player.hold.CubeHold;
import com.nikohalfkino.rubydung.entity.player.interaction.HitResult;
import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.FrameScreen;
import com.nikohalfkino.rubydung.interfaces.GameFrame;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.interfaces.hud.FpsHud;
import com.nikohalfkino.rubydung.interfaces.screen.ConsoleScreen;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.LevelRenderer;
import com.nikohalfkino.rubydung.level.LiquidRaycaster;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.VoxelRaycaster;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.player.interaction.PlayerInteraction;
import com.nikohalfkino.rubydung.util.GameSettings;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;

public class GameRenderer {

    private final RubyDung game;

    private final FloatBuffer fogColor = BufferUtils.createFloatBuffer(4);

    private final CloudRenderer cloudRenderer = new CloudRenderer();
    private final SkyboxRenderer skyboxRenderer = new SkyboxRenderer();
    private final GameFrame frame = new GameFrame();
    private final PixelationFilter pixelation = new PixelationFilter();
    private final PlayerCamera playerCamera = new PlayerCamera();
    private final PlayerRenderer playerRenderer = new PlayerRenderer();
    private final DebugRenderer debugRenderer = new DebugRenderer();
    private final CubeRenderer cubeRenderer = new CubeRenderer();

    private ScreenshotManager screenshots;

    private long lastFrameNanos = System.nanoTime();

    private static final float FOG_START = -10.0F;
    private static final float FOG_END = 120.0F;

    private static final double REACH_DISTANCE = 5.0;

    // pixels of a billboard or a player's sprite that are less opaque than this are not drawn at all, depth included.
    // the liquids are drawn after them, and a see-through pixel that still wrote depth hid the liquid behind it
    private static final float CUTOUT_ALPHA = 0.5F;

    public GameRenderer(RubyDung game) {
        this.game = game;
    }

    /** called by the ScreenshotManager when it is made, the queue is run from render() */
    void setScreenshotManager(ScreenshotManager screenshots) {
        this.screenshots = screenshots;
    }

    public PlayerCamera getPlayerCamera() {
        return this.playerCamera;
    }

    public CloudRenderer getCloudRenderer() {
        return this.cloudRenderer;
    }

    public SkyboxRenderer getSkyboxRenderer() {
        return this.skyboxRenderer;
    }

    public PlayerRenderer getPlayerRenderer() {
        return this.playerRenderer;
    }

    public DebugRenderer getDebugRenderer() {
        return this.debugRenderer;
    }

    public FloatBuffer getFogColor() {
        return this.fogColor;
    }

    public float getFogStart() {
        return FOG_START;
    }

    public float getFogEnd() {
        return FOG_END;
    }

    public float getCloudY() {
        return CloudRenderer.HEIGHT_OFFSET;
    }

    // Start the game with -Dstrata.profile.sync=true to make every profiler stage wait for the GPU (glFinish), so a stage's
    // time includes the GPU work it queued. Slower overall, but it shows which stage is GPU-bound. Off by default.
    private static final boolean SYNC_PROFILE = Boolean.getBoolean("strata.profile.sync");

    private static void lap(String stage) {
        if (SYNC_PROFILE) {
            glFinish();
        }
        FrameProfiler.lap(stage);
    }

    public void render(float partialTicks) {
        FrameProfiler.begin();
        boolean playing = this.game.hasWorld() && this.game.getCurrentScreen() == null
                && !this.game.getConsole().isOpen() && Display.isActive();

        // The frame opens while a FrameScreen (inventory, pause menu) is current and animates closed otherwise.
        Screen screen = this.game.getCurrentScreen();
        FrameScreen frameScreen = screen instanceof FrameScreen ? (FrameScreen) screen : null;
        this.frame.setTarget(frameScreen != null, frameScreen);
        this.frame.update();

        if (playing) {
            float motionX = Mouse.getDX();
            float motionY = Mouse.getDY();

            // Holding the place button while carrying a cube locks the camera: the mouse turns the cube instead.
            if (this.game.getPlayerInteraction().isRotatingCube() && !this.playerCamera.isFreecam()) {
                this.game.getPlayerInteraction().rotateCube(motionX, motionY);
            } else {
                this.playerCamera.turn(this.game.getPlayer(), motionX, motionY);
            }

            if (this.playerCamera.isFreecam()) {
                // The player isn't aimed in freecam, so nothing is targeted.
                this.game.getPlayerInteraction().setHitResult(null);
                this.game.getPlayerInteraction().clearCubeTarget();
            } else {
                pick(partialTicks);
            }
        }

        int width = this.game.getWidth();
        int height = this.game.getHeight();

        WindowManager wm = this.game.getWindowManager();

        // Window mouse position -> game UI coordinates (differs from raw in Fit / Bordered fullscreen).
        int mouseX = wm.toLogicalX(Mouse.getX());
        int mouseY = wm.toLogicalY(Mouse.getY());

        this.game.getInputHandler().handle(playing, mouseX, mouseY);
        lap("input+pick");

        // Black everywhere, then everything below is clipped to the game area.
        wm.beginFrame();

        if (this.game.hasWorld()) {
            Player player = this.game.getPlayer();
            LevelRenderer levelRenderer = this.game.getLevelRenderer();
            DayNightCycle cycle = this.game.getDayNightCycle();

            // Ambient is applied to terrain by TerrainShader at draw time (and to billboards per frame).
            this.game.getLevel().setSkyAmbient(cycle.getAmbient());
            WorldTint.set(cycle.getWorldTint());

            // Liquids flow in real time, except while a screen (pause menu, inventory) is open.
            long now = System.nanoTime();
            float frameSeconds = Math.min((now - this.lastFrameNanos) * 1.0E-9F, 0.25F);
            this.lastFrameNanos = now;
            // (a joined player doesn't simulate them, the server sends what they did)
            if (!this.game.isGameFrozen() && !Multiplayer.isClient()) {
                this.game.getLevel().getLiquids().update(frameSeconds);
            }
            lap("liquid-sim");

            // The world is drawn into the frame's viewport; without a frame (closed, or window
            // too small) it fills the whole window.
            int viewWidth = width;
            int viewHeight = height;
            int viewGlX = 0;
            int viewGlY = 0;

            GameFrame.Layout layout = this.frame.layout(width, height);
            if (layout != null) {
                this.frame.render(layout, width, height, this.game.getFont());

                viewWidth = layout.viewWidth;
                viewHeight = layout.viewHeight;
                viewGlX = layout.viewX;
                viewGlY = height - layout.viewY - layout.viewHeight;
                wm.viewport(viewGlX, viewGlY, viewWidth, viewHeight);

                // Let the screen know where the panel is right now (it slides in).
                if (frameScreen != null) {
                    frameScreen.setLayout(layout);
                }
            }

            // Pixelation draws the world into a smaller viewport; setupCamera still gets the full
            // view size so the aspect ratio is right.
            boolean pixelate = GameSettings.isPixelationEnabled() && GameSettings.getPixelSize() > 1;
            if (pixelate) {
                // The filter works in real window pixels; the pixel size grows with the Fit scale.
                this.pixelation.begin(wm.glX(viewGlX), wm.glY(viewGlY), wm.size(viewWidth), wm.size(viewHeight),
                        Math.max(1, Math.round(GameSettings.getPixelSize() * wm.getScale())));
            }

            float cameraPartialTicks = playing ? partialTicks : 1.0F;
            this.playerCamera.update(player, this.game.getLevel(), cameraPartialTicks, playing);
            lap("frame+camera");
            WorldView view = this.playerCamera.snapshot(player, cameraPartialTicks);
            renderWorld(view, viewWidth, viewHeight, cameraPartialTicks, true, playing);

            // Stretch the low-res world back over the view (the HUD and frame stay full resolution).
            if (pixelate) {
                this.pixelation.end();
            }

            // screenshots: the world is on screen and the HUD and the frame are not, so they get the same view without them
            if (this.screenshots != null && this.screenshots.hasPending()) {
                this.screenshots.processPending(this, view, cameraPartialTicks);
            }

            // Dither the world while the frame is open (checkerboard of black pixels).
            if (layout != null) {
                this.frame.renderDarken(viewWidth, viewHeight);
            }

            // The HUD lays itself out inside the viewport (crosshair centred on the world view).
            this.game.getHud().render(this.game.getFont(), viewWidth, viewHeight);
            Multiplayer.renderNameTags(this.game.getFont(), viewWidth, viewHeight);
            this.cubeRenderer.renderDiceTags(this.game.getFont(), viewWidth, viewHeight);

            wm.viewport(0, 0, width, height);
        }

        if (this.game.getCurrentScreen() != null) {
            this.game.getCurrentScreen().render(mouseX, mouseY);
        }

        ConsoleScreen.render(this.game.getConsole(), this.game.getFont(), width, height);
        FpsHud.render(this.game, this.game.getFont(), width, height);

        wm.endFrame();
        lap("hud+ui");

        Display.update();
        lap("swap");
        FrameProfiler.endFrame();
    }

    /**
     * the whole 3D picture as seen from view, from the sky to the glows: the one place the world is drawn, for the screen and for screenshots. it sets the projection and view matrices itself and does not draw the HUD
     *
     * live = this is the frame on screen: the other players' sprites are drawn the way that also steps their smoothing and collects their name tags (RemotePlayers.render), which must happen once per frame. a screenshot only draws them
     * highlights = the targeted block, cube or piece and the squares a chess piece can go to (only while playing)
     * debug = the overlays of the chunkborders and vectors commands
     */
    void renderWorld(WorldView view, int width, int height, float partialTicks, boolean live, boolean playing) {
        boolean highlights = live && playing;
        boolean debug = live;

        Player player = this.game.getPlayer();
        Level level = this.game.getLevel();
        LevelRenderer levelRenderer = this.game.getLevelRenderer();
        DayNightCycle cycle = this.game.getDayNightCycle();
        CubeManager cubes = level.getCubeManager();

        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        view.applyProjection(width, height);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        view.applyView();

        // Where the camera is (the player's eyes in first person).
        double eyeX = view.eyeX();
        double eyeY = view.eyeY();
        double eyeZ = view.eyeZ();

        // Update fog color from the day-night cycle.
        float[] fc = cycle.getFogColor();
        this.fogColor.clear();
        this.fogColor.put(new float[]{fc[0], fc[1], fc[2], 1.0F}).flip();

        // The sky belongs to the player, not to the camera (see PlayerCamera.snapshot).
        final double skyX = view.skyX();
        final double skyY = view.skyY();
        final double skyZ = view.skyZ();

        // Away from the player the sky can be seen from outside, so it has to write and test depth,
        // otherwise its far side is painted over its near side.
        this.skyboxRenderer.setSolid(view.solidSky());

        this.skyboxRenderer.render(skyX, skyY, skyZ, cycle);
        lap("dome");
        // The sun and moon are drawn between the white (lower) and coloured (upper) cloud layers.
        this.cloudRenderer.render(skyX, skyY, skyZ, cycle,
                () -> this.skyboxRenderer.renderSunAndMoon(skyX, skyY, skyZ, cycle));

        // Distance fog at the edge of the render distance (the terrain and liquid shaders apply it themselves,
        // the fixed function fog is only switched on for the billboards).
        lap("sky+clouds");
        Fog.update(GameSettings.getRenderDistance() * (float) Chunk.SIZE, fc, eyeX, eyeZ);
        glDisable(GL_FOG);

        levelRenderer.setViewPosition(eyeX, eyeY, eyeZ);
        levelRenderer.render(0);

        glEnable(GL_TEXTURE_2D);
        levelRenderer.render(1);
        lap("terrain");

        // The weighted cubes: bodies of their own, so they are drawn here and not as part of the chunk meshes.
        this.cubeRenderer.render(level, cubes, partialTicks);

        // The alpha test is on for the player sprites and the billboards: their transparent pixels draw nothing and
        // write no depth, so the liquids drawn later show through them (a lamppost's texture, a tree's leaves)
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(GL_GREATER, CUTOUT_ALPHA - 0.001F);

        // Before the billboards, so those behind the player are hidden by it.
        if (live) {
            this.playerRenderer.render(level, player, this.playerCamera, partialTicks);
            Multiplayer.renderPlayers(this.game, level, this.playerRenderer, this.playerCamera);
        } else {
            if (view.showsPlayer()) {
                this.playerRenderer.renderSeenFrom(level, player, partialTicks, view.viewX(), view.viewZ(), view.yaw());
            }
            Multiplayer.renderPlayersSeenFrom(level, this.playerRenderer, view.viewX(), view.viewZ(), view.yaw());
        }
        lap("player");

        levelRenderer.renderBillboards(view.yaw(), view.pitch(), eyeX, eyeY, eyeZ);

        // The chess pieces are sprites too, drawn the same way, with their own (not square) textures.
        this.cubeRenderer.renderPieces(level, cubes, partialTicks, view.yaw(), eyeX, eyeY, eyeZ);

        // Where the number over a dice that has rolled is on the screen (it is drawn with the HUD, like the name tags).
        this.cubeRenderer.collectDiceTags(cubes, partialTicks, eyeX, eyeY, eyeZ);

        glDisable(GL_ALPHA_TEST);

        lap("player+billboards");

        // last: the liquids blend over everything already drawn, billboards and the player included, so what is
        // under the water is seen through it and what is above it isn't covered by the water's depth
        // what stands and moves in the liquid: the shader gives it a foam rim and a wake. once per frame, so not for a screenshot
        if (live) {
            LiquidEntities.update(level, player, cubes, partialTicks, eyeX, eyeZ);
        }
        levelRenderer.renderLiquids();
        lap("liquids");

        // the billboard glows go over the liquids: they are blended and write no depth, so the water and the sprites
        // behind them still show through
        levelRenderer.renderBillboardAuras(view.yaw(), eyeX, eyeY, eyeZ);

        glDisable(GL_TEXTURE_2D);
        lap("hit+debug");

        if (highlights) {
            // A cube under the crosshair is highlighted (like a targeted block) instead of the block behind it, and while one is carried
            // nothing is targeted (placing and breaking are locked).
            CubeHold cubeHold = this.game.getPlayerInteraction().getCubeHold();
            HitResult hitResult = this.game.getPlayerInteraction().getHitResult();
            if (!cubeHold.isHolding()) {
                ChessPiece targetedPiece = cubes.getTargetedPiece();
                if (cubeHold.getTargeted() != null) {
                    this.cubeRenderer.renderHighlight(cubeHold.getTargeted(), partialTicks);
                } else if (targetedPiece != null) {
                    // blue for a white piece, red for a black one
                    this.cubeRenderer.renderHighlight(targetedPiece, partialTicks);
                } else if (hitResult != null) {
                    levelRenderer.renderHit(hitResult);
                }
            }

            // the squares the picked chess piece may go to
            levelRenderer.renderChessSelection(this.game.getPlayerInteraction().getChessSelection());
        }

        // Debug overlays (the chunkborders and vectors commands), drawn in world space with the camera's matrices.
        if (debug && this.debugRenderer.isActive()) {
            this.debugRenderer.render(level, player, eyeX, eyeY, eyeZ, partialTicks);
        }

        glDisable(GL_FOG);
    }

    private void pick(float partialTicks) {
        Player player = this.game.getPlayer();
        PlayerInteraction playerInteraction = this.game.getPlayerInteraction();

        if (player == null || playerInteraction == null) {
            return;
        }

        double eyeX = player.prevX + (player.x - player.prevX) * partialTicks;
        double eyeY = player.prevY + (player.y - player.prevY) * partialTicks;
        double eyeZ = player.prevZ + (player.z - player.prevZ) * partialTicks;

        double yawRad = Math.toRadians(player.yRotation);
        double pitchRad = Math.toRadians(player.xRotation);
        double dirX = Math.sin(yawRad) * Math.cos(pitchRad);
        double dirY = -Math.sin(pitchRad);
        double dirZ = -(Math.cos(yawRad) * Math.cos(pitchRad));

        HitResult hitResult = VoxelRaycaster.raycast(
                this.game.getLevel(), eyeX, eyeY, eyeZ, dirX, dirY, dirZ, REACH_DISTANCE);

        // Liquids aren't blocks: holding a liquid's tile makes that liquid targetable (to break it or
        // build against it) when it lies before the block the ray hit.
        Tile selected = playerInteraction.getSelectedTile();
        if (selected instanceof LiquidTile) {
            HitResult liquidHit = LiquidRaycaster.raycast(this.game.getLevel(), eyeX, eyeY, eyeZ,
                    dirX, dirY, dirZ, REACH_DISTANCE, (LiquidTile) selected, hitResult);
            if (liquidHit != null) {
                hitResult = liquidHit;
            }
        }

        playerInteraction.setHitResult(hitResult);

        // After the block hit is known: a cube only counts when nothing is in front of it.
        playerInteraction.updateCubeTarget(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, REACH_DISTANCE);

        // a chess piece counts when no block is in front of it
        double pieceReach = REACH_DISTANCE;
        if (hitResult != null) {
            pieceReach = Math.min(pieceReach, entryDistance(hitResult.x, hitResult.y, hitResult.z,
                    eyeX, eyeY, eyeZ, dirX, dirY, dirZ));
        }
        CubeManager cubes = this.game.getLevel().getCubeManager();
        cubes.setTargetedPiece(cubes.raycastPiece(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, pieceReach, null));
    }

    // how far along the ray (unit direction) it enters the cell, 0 if it starts inside
    private static double entryDistance(int cellX, int cellY, int cellZ,
                                        double ox, double oy, double oz, double dx, double dy, double dz) {
        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] lo = {cellX, cellY, cellZ};
        double near = 0.0D;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1.0E-12D) {
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a];
            double t2 = (lo[a] + 1.0D - o[a]) / d[a];
            near = Math.max(near, Math.min(t1, t2));
        }
        return near;
    }
}