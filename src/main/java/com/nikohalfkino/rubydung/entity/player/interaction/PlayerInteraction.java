package com.nikohalfkino.rubydung.entity.player.interaction;

import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.player.PlayerPhysics;
import com.nikohalfkino.rubydung.entity.player.hold.CubeHold;
import com.nikohalfkino.rubydung.entity.player.chess.ChessSelection;
import com.nikohalfkino.rubydung.audio.BodySounds;
import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.entity.WorldEntity;
import com.nikohalfkino.rubydung.entity.cube.Dice;
import com.nikohalfkino.rubydung.entity.cube.PhysCube;
import com.nikohalfkino.rubydung.entity.piece.ChessPiece;
import com.nikohalfkino.rubydung.interfaces.hud.HandHud;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.net.Protocol;
import com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.TileRegistry;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.level.tiles.special.WoodenDoorTile;
import com.nikohalfkino.rubydung.util.KeyBinding;

import java.util.List;

import static com.nikohalfkino.rubydung.entity.player.interaction.InteractionContext.BREAK_DELAY_TICKS;
import static com.nikohalfkino.rubydung.entity.player.interaction.InteractionContext.DEBUG_CLICKS;

/**
 * public face of the player's block interaction: owns the held-button state machine (tap vs hold, break+place chord, suppression after screens close)
 *
 * delegates the actual work to BlockBreaker, BlockPlacer and BlockReplacer
 */
public class PlayerInteraction {

    private static final int CHORD_GRACE_TICKS = 3;

    private final InteractionContext ctx;
    private final HitTracker hitTracker;
    private final BlockBreaker breaker;
    private final BlockPlacer placer;
    private final BlockReplacer replacer;
    private final CubeHold cubeHold;
    private final ChessSelection chess = new ChessSelection();

    private int heldTicks = 0;
    private boolean chordActive = false;

    // break was pressed and is still waiting out the chord grace period without having acted
    private boolean tapPending = false;

    private boolean breakSuppressedUntilRelease = false;
    private boolean placeSuppressedUntilRelease = false;

    // the break button was down last tick (a cube is only broken by a fresh click, never by sweeping over it)
    private boolean cubeBreakWasDown = false;

    public PlayerInteraction(Level level, Player player) {
        this.ctx = new InteractionContext(level, player);
        this.hitTracker = new HitTracker(level);
        this.breaker = new BlockBreaker(this.ctx, this.hitTracker);
        this.placer = new BlockPlacer(this.ctx);
        this.replacer = new BlockReplacer(this.ctx, this.placer);
        this.cubeHold = new CubeHold(player);
    }

    // Chess

    /** the chess piece picked up with the interact key, and where it may go */
    public ChessSelection getChessSelection() {
        return this.chess;
    }

    // Weighted cubes

    public CubeHold getCubeHold() {
        return this.cubeHold;
    }

    public boolean isHoldingCube() {
        return this.cubeHold.isHolding();
    }

    /** the held cube is being turned by hand: the camera must not follow the mouse */
    public boolean isRotatingCube() {
        return this.cubeHold.isRotating();
    }

    public void rotateCube(float mouseDx, float mouseDy) {
        this.cubeHold.rotate(mouseDx, mouseDy);
    }

    /** mouse wheel while carrying: positive = farther */
    public void scrollCube(int direction) {
        this.cubeHold.scroll(direction);
    }

    /** word for the interact prompt when the key does something with a cube ("Pick up", "Drop"), null when it doesn't */
    public String getCubePrompt() {
        if (this.cubeHold.isHolding()) {
            return "Drop";
        }
        return this.cubeHold.getTargeted() != null ? "Pick up" : null;
    }

    /**
     * works out which cube (if any) the crosshair is on: the nearest thing along the ray, a block in front of it hides it. call after setHitResult, every frame
     */
    public void updateCubeTarget(double eyeX, double eyeY, double eyeZ,
                                 double dirX, double dirY, double dirZ, double reach) {
        if (this.cubeHold.isHolding()) {
            this.cubeHold.setTargeted(null);
            return;
        }

        double[] distance = new double[1];
        PhysCube cube = this.ctx.level.getCubeManager()
                .raycast(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, reach, distance);

        HitResult hit = this.hitTracker.current();
        if (cube != null && hit != null) {
            double hx = hit.hasPoint ? hit.hitX : hit.cellX + 0.5;
            double hy = hit.hasPoint ? hit.hitY : hit.cellY + 0.5;
            double hz = hit.hasPoint ? hit.hitZ : hit.cellZ + 0.5;
            double blockDistance = Math.sqrt((hx - eyeX) * (hx - eyeX) + (hy - eyeY) * (hy - eyeY)
                    + (hz - eyeZ) * (hz - eyeZ));
            if (!hit.hasPoint) {
                blockDistance -= 0.5;   // the cell centre is about half a block behind its face
            }
            if (distance[0] >= blockDistance) {
                cube = null;
            }
        }
        this.cubeHold.setTargeted(cube);
    }

    /** nothing is aimed at (freecam, no hit): drops the cube target */
    public void clearCubeTarget() {
        this.cubeHold.setTargeted(null);
        this.ctx.level.getCubeManager().setTargetedPiece(null);
    }

    /**
     * the interact key, tried before everything else: lets go of a carried cube, or picks up the one under the crosshair. true if it did something
     */
    public boolean interactWithCube() {
        if (this.cubeHold.isHolding()) {
            this.cubeHold.drop();
            suppressHeldButtonsUntilReleased();
            return true;
        }

        if (this.cubeHold.getTargeted() != null && this.cubeHold.pickUp()) {
            resetHold();
            HandHud.swingCurrent(HandHud.Action.INTERACT);
            return true;
        }
        return false;
    }

    /** the server does not let the player carry this cube, or it is gone: it is let go of */
    public void cubeDenied(int cubeId) {
        this.cubeHold.denied(cubeId);
    }

    /** the level is going away: a carried cube is forgotten */
    public void releaseCube() {
        this.cubeHold.forget();
    }

    // Targeting

    public HitResult getHitResult() {
        return this.hitTracker.current();
    }

    public void setHitResult(HitResult hitResult) {
        this.hitTracker.set(hitResult);
    }

    /** crosshair is on a tile the interact key does something with (drives the "[E] Interact" prompt) */
    public boolean isTargetingInteractable() {
        HitResult hit = this.hitTracker.current();
        if (this.ctx.level.getCubeManager().getTargetedPiece() != null) {
            return true;   // a chess piece: interact picks it up, or takes it when another one is picked
        }
        if (hit == null) {
            return false;
        }

        if (this.chess.isAimingAtMove(hit)) {
            return true;   // a lit square: interact puts the picked chess piece there
        }

        Tile tile = this.ctx.targetTile(hit);
        return tile != null && tile.isInteractable();
    }

    /** origin cell {x, y, z} of the dimensional door under the crosshair, null if not aiming at one (a multi-tile hit position is its origin) */
    public int[] getTargetedDoorOrigin() {
        HitResult hit = this.hitTracker.current();
        if (hit == null) {
            return null;
        }

        Tile tile = this.ctx.targetTile(hit);
        return tile instanceof DimensionalDoorTile ? new int[]{hit.x, hit.y, hit.z} : null;
    }

    /** the interact key: handles tiles that react straight away (wooden doors open/close). true if it did something, so the caller skips its own handling (screens for dimensional doors etc.) */
    public boolean interact() {
        HitResult hit = this.hitTracker.current();

        // chess pieces: pick one up, put the picked one on a lit square (swings the hand itself)
        if (this.chess.interact(this.ctx.level, this.ctx.player, hit)) {
            return true;
        }

        if (hit == null || !(this.ctx.targetTile(hit) instanceof WoodenDoorTile)) {
            return false;
        }

        WoodenDoorTile.toggle(this.ctx.level, hit.x, hit.y, hit.z, this.ctx.player.x, this.ctx.player.z);
        HandHud.swingCurrent(HandHud.Action.INTERACT);
        return true;
    }

    /** raps on the (wooden or dimensional) door under the crosshair if it's closed, true if it did */
    public boolean knock() {
        HitResult hit = this.hitTracker.current();
        if (hit == null) {
            return false;
        }

        Tile tile = this.ctx.targetTile(hit);
        boolean closedDoor = (tile instanceof WoodenDoorTile && !((WoodenDoorTile) tile).isOpen())
                || (tile instanceof DimensionalDoorTile && !((DimensionalDoorTile) tile).isOpen());
        if (!closedDoor) {
            return false;
        }

        SoundEffects.play("door_knock");
        Multiplayer.sound(Protocol.SOUND_KNOCK, 1.0F, hit.x, hit.y, hit.z);
        HandHud.swingCurrent(HandHud.Action.KNOCK);
        return true;
    }

    private static final double PAT_REACH = 3.0D;
    private static final long PAT_COOLDOWN_NANOS = 800_000_000L;
    private long lastPatNanos = 0L;

    /** pats the other player under the crosshair (within arm's reach): plays the pat and the purr for both, true if there was one */
    public boolean pat() {
        if (!Multiplayer.isActive() || this.ctx.player.noClip) {
            return false;
        }
        long now = System.nanoTime();
        if (now - this.lastPatNanos < PAT_COOLDOWN_NANOS) {
            return false;
        }

        com.nikohalfkino.rubydung.net.RemotePlayer target = patTarget();
        if (target == null) {
            return false;
        }
        this.lastPatNanos = now;

        BodySounds.pat(1.0F);
        Multiplayer.sound(Protocol.SOUND_PAT, 1.0F, (int) Math.floor(target.x),
                (int) Math.floor(target.y - Player.EYE_HEIGHT + 0.9D), (int) Math.floor(target.z));
        HandHud.swingCurrent(HandHud.Action.INTERACT);
        return true;
    }

    /** the nearest other player the crosshair is on, within PAT_REACH, with no block in between. null if none */
    private com.nikohalfkino.rubydung.net.RemotePlayer patTarget() {
        Player me = this.ctx.player;
        double yaw = Math.toRadians(me.yRotation);
        double pitch = Math.toRadians(me.xRotation);
        double dx = Math.sin(yaw) * Math.cos(pitch);
        double dy = -Math.sin(pitch);
        double dz = -Math.cos(yaw) * Math.cos(pitch);

        // a block in the way (the same hit the crosshair uses) hides whoever is behind it
        double blocked = Double.MAX_VALUE;
        HitResult hit = this.hitTracker.current();
        if (hit != null) {
            double hx = hit.x + 0.5D - me.x, hy = hit.y + 0.5D - me.y, hz = hit.z + 0.5D - me.z;
            blocked = Math.sqrt(hx * hx + hy * hy + hz * hz) - 0.5D;
        }

        com.nikohalfkino.rubydung.net.RemotePlayer best = null;
        double bestDistance = Math.min(PAT_REACH, blocked);
        double half = PlayerPhysics.HALF_WIDTH + 0.1D;   // a little forgiving
        for (com.nikohalfkino.rubydung.net.RemotePlayer other : Multiplayer.remotes().all()) {
            if (other.isNoClip()) {
                continue;
            }
            double feet = other.y - Player.EYE_HEIGHT;
            double t = rayBox(me.x, me.y, me.z, dx, dy, dz,
                    other.x - half, feet, other.z - half, other.x + half, feet + PlayerPhysics.HEIGHT, other.z + half);
            if (t >= 0.0D && t < bestDistance) {
                best = other;
                bestDistance = t;
            }
        }
        return best;
    }

    /** distance along the ray to the box (slab test), -1 if it misses */
    private static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz,
                                 double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double near = 0.0D, far = Double.MAX_VALUE;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-9D) {
                if (o[i] < lo[i] || o[i] > hi[i]) {
                    return -1.0D;
                }
                continue;
            }
            double t1 = (lo[i] - o[i]) / d[i];
            double t2 = (hi[i] - o[i]) / d[i];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (near > far) {
                return -1.0D;
            }
        }
        return near;
    }

    // Selected tile

    public Tile getSelectedTile() {
        return this.ctx.selectedTile;
    }

    public void setSelectedTile(Tile tile) {
        this.ctx.selectedTile = tile;
    }

    public void cycleSelectedTile(int direction) {
        List<Tile> tiles = Tile.getCycleOrder();
        int size = tiles.size();
        int index = tiles.indexOf(this.ctx.selectedTile);

        index = (index + direction + size) % size;

        this.ctx.selectedTile = tiles.get(index);
    }

    public void pickTargetedTile() {
        // an entity in front of the block behind it is picked as the tile it was placed from
        WorldEntity entity = targetedEntity();
        if (entity != null) {
            Tile tile = tileOf(entity);
            if (tile != null) {
                this.ctx.selectedTile = tile;
            }
            return;
        }

        HitResult hit = this.hitTracker.current();
        if (hit == null) {
            return;
        }

        Tile tile = this.ctx.targetTile(hit);
        if (tile != null && tile.id > 0) {
            // An open door can't be placed, so picking one selects its closed form.
            if (tile instanceof DimensionalDoorTile) {
                this.ctx.selectedTile = ((DimensionalDoorTile) tile).getClosedForm();
            } else if (tile instanceof WoodenDoorTile) {
                this.ctx.selectedTile = ((WoodenDoorTile) tile).getClosedForm();
            } else {
                this.ctx.selectedTile = tile;
            }
        }
    }

    // Clicks

    public void handleClick(int button, boolean pressed) {
        if (!pressed) {
            return;
        }
        if (button == 0) {
            // an entity in front of a block is what the click is on: it is not the block behind it that breaks
            if (!breakTargetedEntity()) {
                this.breaker.handleClick();
            }
            return;
        }

        // and placing does nothing against the block behind an entity either
        if (targetedEntity() != null) {
            return;
        }

        HitResult hit = this.hitTracker.current();
        if (hit == null) {
            if (DEBUG_CLICKS) {
                System.err.println("[click] button " + button + ": no hit result at click time");
            }
            return;
        }

        if (button == 1) {
            this.placer.handleClick(hit);
        }
    }

    // Entities (the weighted cubes and the chess pieces): they are targeted, picked, placed and broken like the tiles they are placed from

    /** the weighted cube or chess piece the crosshair is on (nothing in front of it), null while carrying one or if none */
    private WorldEntity targetedEntity() {
        if (this.cubeHold.isHolding()) {
            return null;
        }
        PhysCube cube = this.cubeHold.getTargeted();
        return cube != null ? cube : this.ctx.level.getCubeManager().getTargetedPiece();
    }

    /** the tile a cube or a piece is placed from (the sound, the pick and the tile selector know it by that) */
    private static Tile tileOf(WorldEntity entity) {
        if (entity instanceof ChessPiece) {
            return ChessPieceTile.of((ChessPiece) entity);
        }
        return entity instanceof Dice ? TileRegistry.DICE : TileRegistry.WEIGHTED_CUBE;
    }

    /**
     * a fresh break click on an entity removes it, with the sound of its tile and the hand swing, and starts the repeat timer like a broken block does, so the same press does not go on to break the block behind it (holding the button still breaks on, like it does from block to block). true if the click was on an entity
     */
    private boolean breakTargetedEntity() {
        WorldEntity target = targetedEntity();
        if (target == null) {
            return false;
        }
        if (!this.breakSuppressedUntilRelease) {
            removeEntity(target);
        }
        return true;
    }

    private void removeEntity(WorldEntity target) {
        Tile tile = tileOf(target);
        if (Multiplayer.isClient()) {
            Multiplayer.cubeRemove(target);   // it goes when the server says so
        } else {
            this.ctx.level.getCubeManager().remove(target);
        }
        if (tile != null) {
            SoundEffects.playTileBreak(tile);
        }
        this.cubeHold.setTargeted(null);
        this.ctx.level.getCubeManager().setTargetedPiece(null);
        HandHud.swingCurrent(HandHud.Action.BREAK);

        this.ctx.acted = true;
        this.ctx.actionCooldown = BREAK_DELAY_TICKS - 1;
        this.cubeBreakWasDown = true;   // this press is spent
    }

    // Held buttons

    public void suppressHeldButtonsUntilReleased() {
        if (KeyBinding.BREAK_BLOCK.isDown()) {
            this.breakSuppressedUntilRelease = true;
        }
        if (KeyBinding.PLACE_BLOCK.isDown()) {
            this.placeSuppressedUntilRelease = true;
        }
    }

    private void resetHold() {
        this.ctx.actionCooldown = 0;
        this.ctx.acted = false;
        this.heldTicks = 0;
        this.tapPending = false;
        this.breaker.reset();
        this.replacer.reset();
        this.placer.reset();
    }

    public void tick() {
        this.chess.tick(this.ctx.level);

        // Always drained, so a stale queue never builds up while no button is held.
        List<HitResult> hits = this.hitTracker.drain();
        double speed = playerSpeed();

        boolean breakHeld = KeyBinding.BREAK_BLOCK.isDown();
        boolean placeHeld = KeyBinding.PLACE_BLOCK.isDown();

        // Runs before the held-button logic, so it works even if the button was already released.
        this.breaker.retryBufferedClick(hits);

        if (!breakHeld) {
            this.breakSuppressedUntilRelease = false;
        }
        if (!placeHeld) {
            this.placeSuppressedUntilRelease = false;
        }

        // After a replace, ignore whichever button is still held, so letting go of one
        // button doesn't turn into a break or a placement.
        if (this.chordActive && !(breakHeld && placeHeld)) {
            this.chordActive = false;

            if (breakHeld) {
                this.breakSuppressedUntilRelease = true;
            }
            if (placeHeld) {
                this.placeSuppressedUntilRelease = true;
            }
        }

        // Carrying a cube takes over the buttons (throw, rotate) and locks all block work: no tile selection, placing or breaking.
        if (this.cubeHold.isHolding()) {
            resetHold();
            this.chordActive = false;
            this.cubeHold.tick();
            if (!this.cubeHold.isHolding()) {
                // thrown or dropped: the button still down must not turn into a break or a placement
                suppressHeldButtonsUntilReleased();
            }
            this.cubeBreakWasDown = breakHeld;
            return;
        }

        // A cube or a chess piece in front of whatever is behind it: the break button removes it (a fresh press only, never by sweeping
        // over it), the place button does nothing, and the hold is reset so what is behind it is not broken by the same press.
        WorldEntity entity = targetedEntity();
        if (entity != null) {
            resetHold();
            this.chordActive = false;
            if (breakHeld && !this.cubeBreakWasDown && !this.breakSuppressedUntilRelease) {
                removeEntity(entity);
            }
            this.cubeBreakWasDown = breakHeld;
            return;
        }
        this.cubeBreakWasDown = breakHeld;

        boolean breaking = breakHeld && !this.breakSuppressedUntilRelease;
        boolean placing = placeHeld && !this.placeSuppressedUntilRelease;

        if (!breaking && !placing) {
            // A tap that ended inside the chord grace period never got to act: do it now, on release.
            boolean tap = this.tapPending && !this.ctx.acted;
            resetHold();
            if (tap) {
                this.breaker.breakOnRelease(hits);
            }
            return;
        }

        boolean replacing = breaking && placing;

        if (replacing) {
            this.chordActive = true;
            this.tapPending = false;
        }

        // Give the second button a moment to arrive before acting on the first.
        if (!this.ctx.acted && !replacing && this.heldTicks < CHORD_GRACE_TICKS) {
            this.tapPending = breaking && !placing;
            this.heldTicks++;
            return;
        }
        this.tapPending = false;

        if (placing && !breaking) {
            this.placer.tick(hits, speed);
        } else if (breaking && !placing) {
            this.breaker.tick(hits, speed);
        } else {
            // Both buttons: replace what the crosshair sweeps over, paced like breaking.
            this.replacer.tick(hits, speed);
        }
    }

    // blocks moved during the last tick
    private double playerSpeed() {
        Player p = this.ctx.player;
        double dx = p.x - p.prevX;
        double dy = p.y - p.prevY;
        double dz = p.z - p.prevZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
