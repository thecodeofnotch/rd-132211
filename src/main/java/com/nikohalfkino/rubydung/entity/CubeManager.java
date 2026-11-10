package com.nikohalfkino.rubydung.entity;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.player.Player;
import com.nikohalfkino.rubydung.phys.AABB;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * every weighted cube of one level: spawns them, runs their physics once per tick, answers "what is the crosshair on" and "what does the player bump into", and saves them next to the level
 *
 * the cubes are solid for the player through Level.getCubes (see addCollisionBoxes), and the player is solid for them here
 *
 * the chess pieces live here too (CubeEntity with piece >= 0): they are not bodies, nothing pushes them or collides with them, they only stand on their square, glide to another when told to (movePiece) and are picked with raycastPiece. they travel to the players, are saved and removed like the cubes
 */
public final class CubeManager {

    /** a cap, every cube costs a block search around it every tick */
    public static final int MAX_CUBES = EntityTypes.groupCap(EntityTypes.GROUP_CUBES);

    /** a cap on the chess pieces (two full sets is 32), they cost nothing per tick */
    public static final int MAX_PIECES = EntityTypes.groupCap(EntityTypes.GROUP_PIECES);

    /** everything that can be in the list the server sends */
    public static final int MAX_ENTITIES = MAX_CUBES + MAX_PIECES;

    // how the walk speed of the player turns into the speed of the cube being pushed
    private static final double PUSH_FACTOR = 0.8D;
    private static final double PUSH_MAX_SPEED = 6.0D;
    private static final double PUSH_REACH = 0.08D;     // gap that still counts as touching
    private static final double PUSH_MIN_WISH = 0.5D;   // blocks/s the player has to ask for toward the cube

    private static final double VOID_Y = -32.0D;

    private static final int FILE_MAGIC = 0x43554245;   // "CUBE"
    private static final int FILE_VERSION = 2;   // 2 adds the piece byte to every entry, 1 is read as cubes only

    /**
     * another player's body as the server sees it: where it is and how fast it is walking, so a cube can be pushed by it and is blocked by it. the player of this machine is not one (it is passed to tick)
     */
    public static final class Body {
        public final AABB box;
        public final double wishX, wishZ;   // blocks/s

        public Body(AABB box, double wishX, double wishZ) {
            this.box = box;
            this.wishX = wishX;
            this.wishZ = wishZ;
        }
    }

    /** told (on the machine that runs the physics) when a thrown dice has come to rest. holderId is the id of the player who threw it (see CubeEntity.heldBy), -1 for the player of this machine / the LAN host */
    public interface RollListener {
        void rolled(CubeEntity dice, int value, int holderId);
    }

    // a throw has ended when the dice has lain still this many ticks
    private static final int DICE_REST_TICKS = 15;
    private static final int DICE_GIVE_UP_TICKS = 60 * 30;
    private static final double DICE_THROW_SPEED = 3.0D;   // blocks/s at the moment it is let go, slower is just put down
    private static final double DICE_REST_SPEED = 0.15D;
    private static final double DICE_REST_SPIN = 0.5D;

    private RollListener rollListener;

    private final Level level;
    private final List<CubeEntity> cubes = new ArrayList<>();
    private final List<CubeEntity.Box> obstacles = new ArrayList<>();
    private int nextId = 1;

    // the other players, on a server (null elsewhere)
    private Supplier<List<Body>> bodies;

    // joined a server: the cubes are moved by it, this machine only eases them toward what it is told (see tick)
    private boolean replica = false;

    // the piece the crosshair is on, worked out every frame by the renderer (see GameRenderer.pick), null if none
    private CubeEntity targetedPiece;

    public CubeManager(Level level) {
        this.level = level;
    }

    public List<CubeEntity> all() {
        return this.cubes;
    }

    public int count() {
        return this.cubes.size();
    }

    /** the cube with this id, null if there is none */
    public CubeEntity byId(int id) {
        for (CubeEntity cube : this.cubes) {
            if (cube.id == id) {
                return cube;
            }
        }
        return null;
    }

    /** server: where the other players are, asked every tick */
    public void setBodyProvider(Supplier<List<Body>> provider) {
        this.bodies = provider;
    }

    /** client: the cubes belong to a server from now on */
    public void setReplica(boolean replica) {
        this.replica = replica;
    }

    public boolean isReplica() {
        return this.replica;
    }

    /** client: a cube the server told about, at its place at once. null if the cap is reached */
    public CubeEntity addReplica(int id, double x, double y, double z) {
        if (this.cubes.size() >= MAX_ENTITIES) {
            return null;
        }
        CubeEntity cube = new CubeEntity(x, y, z);
        cube.id = id;
        this.cubes.add(cube);
        return cube;
    }

    /** client: drops every cube whose id is not in the set (the server sent the whole list) */
    public void retainOnly(Set<Integer> ids) {
        for (int i = this.cubes.size() - 1; i >= 0; i--) {
            CubeEntity cube = this.cubes.get(i);
            if (!ids.contains(cube.id) && !cube.held) {
                this.cubes.remove(i);
            }
        }
    }

    /** client: what the server says a piece is (see CubeEntity.pieceCode). a plain cube is -1 */
    public void applyPieceCode(CubeEntity cube, int code) {
        if (code < 0) {
            cube.piece = code <= CubeEntity.DICE_IDLE ? Math.max(code, CubeEntity.diceCode(6)) : -1;
            cube.type = EntityTypes.forPieceCode(cube.piece);
            return;
        }
        ChessPieceTile.Type[] types = ChessPieceTile.Type.values();
        ChessPieceTile.Type type = types[Math.min(code & 7, types.length - 1)];
        cube.piece = code;
        cube.type = EntityTypes.forPieceCode(code);
        cube.spriteW = type.spriteWidth;
        cube.spriteH = type.spriteHeight;
        cube.hullW = type.hitWidth;
        cube.hullH = type.hitHeight;
    }

    public void setRollListener(RollListener listener) {
        this.rollListener = listener;
    }

    private List<Body> currentBodies() {
        if (this.bodies == null) {
            return Collections.emptyList();
        }
        List<Body> list = this.bodies.get();
        return list == null ? Collections.<Body>emptyList() : list;
    }

    // ---------------------------------------------------------------- spawning

    /** whether a cube may be placed into the cell: nothing in it, no other cube, not on top of the player */
    public boolean canSpawnIn(int cellX, int cellY, int cellZ, Player player) {
        if (countPieces(false) >= MAX_CUBES) {
            return false;
        }
        if (cellX < 0 || cellY < 0 || cellZ < 0
                || cellX >= this.level.width || cellY >= this.level.depth || cellZ >= this.level.height) {
            return false;
        }
        if (this.level.getTile(cellX, cellY, cellZ) != 0 || this.level.getSubTiles().has(cellX, cellY, cellZ)
                || pieceAt(cellX, cellY, cellZ) != null) {
            return false;
        }

        CubeEntity probe = spawnPoint(cellX, cellY, cellZ);
        for (CubeEntity other : this.cubes) {
            if (!other.isPiece()
                    && probe.overlaps(other.minX(), other.minY(), other.minZ(), other.maxX(), other.maxY(), other.maxZ())) {
                return false;
            }
        }

        for (Body other : currentBodies()) {
            AABB b = other.box;
            if (probe.overlaps(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
                return false;
            }
        }

        AABB body = player == null ? null : player.boundingBox;
        return body == null
                || !probe.overlaps(body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ);
    }

    /** puts a cube in the middle of the cell, on its floor: aligned to the grid like a tile. null if canSpawnIn says no */
    public CubeEntity spawnIn(int cellX, int cellY, int cellZ, Player player) {
        return spawnIn(cellX, cellY, cellZ, player, false);
    }

    /** dice = a dice instead of a plain cube */
    public CubeEntity spawnIn(int cellX, int cellY, int cellZ, Player player, boolean dice) {
        if (!canSpawnIn(cellX, cellY, cellZ, player)) {
            return null;
        }
        CubeEntity cube = spawnPoint(cellX, cellY, cellZ);
        if (dice) {
            applyPieceCode(cube, CubeEntity.DICE_IDLE);
        }
        cube.id = this.nextId++;
        this.cubes.add(cube);
        cube.type.getHooks().onSpawn(cube);
        return cube;
    }

    private static CubeEntity spawnPoint(int cellX, int cellY, int cellZ) {
        return new CubeEntity(cellX + 0.5D, cellY + CubeEntity.HALF + 0.001D, cellZ + 0.5D);
    }

    public void remove(CubeEntity cube) {
        if (this.cubes.remove(cube)) {
            cube.type.getHooks().onRemove(cube);
        }
    }

    public void clear() {
        this.cubes.clear();
    }

    // ---------------------------------------------------------------- picking

    /** the nearest loose cube the ray (unit direction) hits within maxDistance, null if none. distanceOut[0] gets the distance */
    public CubeEntity raycast(double ox, double oy, double oz, double dx, double dy, double dz,
                              double maxDistance, double[] distanceOut) {
        CubeEntity best = null;
        double bestDistance = maxDistance;
        for (CubeEntity cube : this.cubes) {
            if (cube.held || cube.remoteHeld || !cube.type.isHoldable()) {
                continue;
            }
            double d = cube.raycast(ox, oy, oz, dx, dy, dz, bestDistance);
            if (d >= 0.0D && d <= bestDistance) {
                best = cube;
                bestDistance = d;
            }
        }
        if (best != null && distanceOut != null) {
            distanceOut[0] = bestDistance;
        }
        return best;
    }

    // ---------------------------------------------------------------- collision for the player

    /** adds the boxes of the cubes the player can bump into near region: not the one in the hands, not one just let go of while it overlaps the player */
    public void addCollisionBoxes(AABB region, List<AABB> out) {
        for (CubeEntity cube : this.cubes) {
            if (cube.held || cube.remoteHeld || cube.ignorePlayer || !cube.type.isSolidForPlayer()) {
                continue;
            }
            if (cube.overlaps(region.minX - 1.0D, region.minY - 1.0D, region.minZ - 1.0D,
                    region.maxX + 1.0D, region.maxY + 1.0D, region.maxZ + 1.0D)) {
                out.add(new AABB(cube.minX(), cube.minY(), cube.minZ(), cube.maxX(), cube.maxY(), cube.maxZ()));
            }
        }
    }

    // ---------------------------------------------------------------- tick

    /**
     * once per game tick, after the player has moved and the hold has set where a carried cube should go. player is the one on this machine (null on a dedicated server), the others come from the body provider
     *
     * a replica (joined a server) does no physics: the server moves the cubes, only the one carried here is stepped
     */
    public void tick(Player player) {
        if (this.cubes.isEmpty()) {
            return;
        }

        AABB body = player == null ? null : player.boundingBox;
        List<Body> others = this.replica ? Collections.<Body>emptyList() : currentBodies();

        if (!this.replica) {
            for (CubeEntity cube : this.cubes) {
                if (!cube.type.isPushable()) {
                    continue;
                }
                if (cube.ignorePlayer && (body == null || !cube.overlaps(body.minX - 0.02D, body.minY - 0.02D,
                        body.minZ - 0.02D, body.maxX + 0.02D, body.maxY + 0.02D, body.maxZ + 0.02D))) {
                    cube.ignorePlayer = false;
                }
                if (cube.ignoreBodies && !overlapsAny(cube, others, 0.02D)) {
                    cube.ignoreBodies = false;
                }

                if (!cube.held) {
                    if (!cube.ignorePlayer && player != null && body != null) {
                        pushByBox(cube, body, player.getPhysics().getWishVelocity());
                    }
                    if (!cube.ignoreBodies) {
                        for (Body other : others) {
                            pushByBox(cube, other.box, new double[]{other.wishX, 0.0D, other.wishZ});
                        }
                    }
                }
            }
        }

        for (CubeEntity cube : this.cubes) {
            if (!this.replica) {
                cube.type.getHooks().onTick(cube);
            }
            if (!cube.type.isPhysics()) {
                // a piece is no body: the server glides it, a replica eases toward what the server says
                if (this.replica) {
                    cube.stepReplica();
                } else {
                    cube.tickPiece();
                }
                continue;
            }
            if (this.replica && !cube.held) {
                cube.stepReplica();
                continue;
            }

            double carryFromX = cube.x;
            double carryFromZ = cube.z;
            boolean carriesPlayer = !this.replica && !cube.held && player != null && body != null && standsOn(body, cube);

            collectObstacles(cube, body, others);
            cube.inLiquid = this.level.getLiquids().has(
                    (int) Math.floor(cube.x), (int) Math.floor(cube.y), (int) Math.floor(cube.z));
            cube.tick(this.obstacles);
            keepInsideLevel(cube);
            if (!this.replica && cube.isDice()) {
                trackDice(cube);
            }

            if (this.replica) {
                // carried here: the server is told where it is, what comes back for it is ignored, see GameClient
                cube.syncNetToSelf();
            }

            // a cube that slides out from under the player takes them along instead of leaving them behind
            if (carriesPlayer) {
                double mx = cube.x - carryFromX;
                double mz = cube.z - carryFromZ;
                if (mx != 0.0D || mz != 0.0D) {
                    player.move(mx, 0.0D, mz);
                    body = player.boundingBox;
                }
            }
        }

        for (int i = this.cubes.size() - 1; i >= 0; i--) {
            if (this.cubes.get(i).dead) {
                this.cubes.remove(i);
            }
        }
    }

    /**
     * the dice rolls when it is thrown (let go of with some speed) and counts when it has come to rest: then the face that points up is its number, shown over it (it is in the piece code, so the players get it with the rest of the state) and told to the listener. picking it up again clears the number
     */
    private void trackDice(CubeEntity dice) {
        if (dice.held) {
            dice.wasHeld = true;
            dice.lastHolder = dice.heldBy;
            dice.rollArmed = false;
            dice.restTicks = 0;
            dice.piece = CubeEntity.DICE_IDLE;
            return;
        }

        if (dice.wasHeld) {
            // let go this tick: thrown if it has speed or spin
            dice.wasHeld = false;
            double spin = Math.sqrt(dice.avx * dice.avx + dice.avy * dice.avy + dice.avz * dice.avz);
            if (dice.speed() >= DICE_THROW_SPEED || spin >= 2.0D) {
                dice.rollArmed = true;
                dice.restTicks = 0;
                dice.rollTicks = 0;
            }
            return;
        }

        if (!dice.rollArmed) {
            return;
        }

        double spin = Math.sqrt(dice.avx * dice.avx + dice.avy * dice.avy + dice.avz * dice.avz);
        boolean still = dice.onGround && dice.speed() < DICE_REST_SPEED && spin < DICE_REST_SPIN;
        dice.restTicks = still ? dice.restTicks + 1 : 0;
        dice.rollTicks++;

        if (dice.restTicks >= DICE_REST_TICKS) {
            dice.rollArmed = false;
            int value = dice.upFaceValue();
            dice.piece = CubeEntity.diceCode(value);
            if (this.rollListener != null) {
                this.rollListener.rolled(dice, value, dice.lastHolder);
            }
        } else if (dice.rollTicks > DICE_GIVE_UP_TICKS) {
            dice.rollArmed = false;   // stuck in a liquid or on a moving thing: no roll
        }
    }

    private static boolean overlapsAny(CubeEntity cube, List<Body> others, double grow) {
        for (Body other : others) {
            AABB b = other.box;
            if (cube.overlaps(b.minX - grow, b.minY - grow, b.minZ - grow, b.maxX + grow, b.maxY + grow, b.maxZ + grow)) {
                return true;
            }
        }
        return false;
    }

    private static boolean standsOn(AABB body, CubeEntity cube) {
        boolean overTop = body.minX < cube.maxX() && body.maxX > cube.minX()
                && body.minZ < cube.maxZ() && body.maxZ > cube.minZ();
        return overTop && Math.abs(body.minY - cube.maxY()) < 0.06D;
    }

    // the player walks into the cube: it gets going in the direction they are walking
    private void pushByBox(CubeEntity cube, AABB body, double[] wish) {
        boolean beside = body.minY < cube.maxY() - 0.1D && body.maxY > cube.minY() + 0.1D;
        if (!beside) {
            return;   // standing on it, or it is over their head
        }

        double gapX = Math.max(body.minX - cube.maxX(), cube.minX() - body.maxX);
        double gapZ = Math.max(body.minZ - cube.maxZ(), cube.minZ() - body.maxZ);
        double overlapX = Math.min(body.maxX, cube.maxX()) - Math.max(body.minX, cube.minX());
        double overlapZ = Math.min(body.maxZ, cube.maxZ()) - Math.max(body.minZ, cube.minZ());

        double bestAlong = PUSH_MIN_WISH;
        int bestAxis = -1;
        double bestSign = 0.0D;

        // touching on x means the faces are in contact along x and the two overlap along z (and the other way round)
        if (gapX <= PUSH_REACH && overlapZ > 0.0D) {
            double sign = cube.x >= (body.minX + body.maxX) * 0.5D ? 1.0D : -1.0D;
            double along = wish[0] * sign;
            if (along > bestAlong) {
                bestAlong = along;
                bestAxis = 0;
                bestSign = sign;
            }
        }
        if (gapZ <= PUSH_REACH && overlapX > 0.0D) {
            double sign = cube.z >= (body.minZ + body.maxZ) * 0.5D ? 1.0D : -1.0D;
            double along = wish[2] * sign;
            if (along > bestAlong) {
                bestAlong = along;
                bestAxis = 2;
                bestSign = sign;
            }
        }

        if (bestAxis >= 0) {
            cube.pushHorizontal(bestAxis, bestSign * Math.min(bestAlong * PUSH_FACTOR, PUSH_MAX_SPEED));
        }
    }

    // everything this cube could hit this tick
    private void collectObstacles(CubeEntity cube, AABB body, List<Body> others) {
        this.obstacles.clear();

        double r = cube.reach();
        AABB region = new AABB(cube.minX() - r, cube.minY() - r, cube.minZ() - r,
                cube.maxX() + r, cube.maxY() + r, cube.maxZ() + r);
        for (AABB box : this.level.getWorldCubes(region)) {
            this.obstacles.add(new CubeEntity.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, null));
        }

        for (CubeEntity other : this.cubes) {
            if (other != cube && other.type.isPhysics()) {
                this.obstacles.add(new CubeEntity.Box(other.minX(), other.minY(), other.minZ(),
                        other.maxX(), other.maxY(), other.maxZ(), other));
            }
        }

        // a carried cube goes through the player, so it never shoves the one carrying it
        if (!cube.held && !cube.ignorePlayer && body != null) {
            this.obstacles.add(new CubeEntity.Box(body.minX, body.minY, body.minZ,
                    body.maxX, body.maxY, body.maxZ, null));
        }
        if (!cube.held && !cube.ignoreBodies) {
            for (Body other : others) {
                this.obstacles.add(new CubeEntity.Box(other.box.minX, other.box.minY, other.box.minZ,
                        other.box.maxX, other.box.maxY, other.box.maxZ, null));
            }
        }
    }

    private void keepInsideLevel(CubeEntity cube) {
        double h = CubeEntity.HALF;
        if (cube.x < h) { cube.x = h; cube.vx = Math.max(cube.vx, 0.0D); }
        if (cube.x > this.level.width - h) { cube.x = this.level.width - h; cube.vx = Math.min(cube.vx, 0.0D); }
        if (cube.z < h) { cube.z = h; cube.vz = Math.max(cube.vz, 0.0D); }
        if (cube.z > this.level.height - h) { cube.z = this.level.height - h; cube.vz = Math.min(cube.vz, 0.0D); }
        if (cube.y < VOID_Y) {
            cube.dead = true;
        }
    }

    // ---------------------------------------------------------------- chess pieces

    private int countPieces(boolean pieces) {
        int n = 0;
        for (CubeEntity cube : this.cubes) {
            if (cube.isPiece() == pieces) {
                n++;
            }
        }
        return n;
    }

    public boolean hasPieces() {
        for (CubeEntity cube : this.cubes) {
            if (cube.isPiece()) {
                return true;
            }
        }
        return false;
    }

    /** the piece standing on the square (or gliding to it), null if none */
    public CubeEntity pieceAt(int cellX, int cellY, int cellZ) {
        for (CubeEntity cube : this.cubes) {
            if (cube.isPiece() && cube.pieceCellX() == cellX && cube.pieceCellZ() == cellZ && cube.pieceCellY() == cellY) {
                return cube;
            }
        }
        return null;
    }

    /**
     * whether a piece may be put on the square: the cell is empty (no block, no sub-tile), no piece or cube is in it, the player is not standing in it (a sprite tile counts as filling its cell, and a piece does the same), and the cap is not reached. like canSpawnIn for a cube
     */
    public boolean canSpawnPieceIn(int cellX, int cellY, int cellZ, Player player) {
        if (cellX < 0 || cellY < 0 || cellZ < 0
                || cellX >= this.level.width || cellY >= this.level.depth || cellZ >= this.level.height
                || countPieces(true) >= MAX_PIECES) {
            return false;
        }
        if (this.level.getTile(cellX, cellY, cellZ) != 0 || this.level.getSubTiles().has(cellX, cellY, cellZ)
                || pieceAt(cellX, cellY, cellZ) != null || cubeInCell(cellX, cellY, cellZ)) {
            return false;
        }
        for (Body other : currentBodies()) {
            if (overlapsCell(other.box, cellX, cellY, cellZ)) {
                return false;
            }
        }
        return player == null || player.boundingBox == null || !overlapsCell(player.boundingBox, cellX, cellY, cellZ);
    }

    private static boolean overlapsCell(AABB b, int x, int y, int z) {
        return b.minX < x + 1 && b.maxX > x && b.minY < y + 1 && b.maxY > y && b.minZ < z + 1 && b.maxZ > z;
    }

    private boolean cubeInCell(int x, int y, int z) {
        for (CubeEntity cube : this.cubes) {
            if (!cube.isPiece() && cube.overlaps(x, y, z, x + 1, y + 1, z + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * stands a new piece on the square (the middle of the cell, on its floor). null if there is a piece on it already, the cap is reached, or this machine only shows what a server says (the server is asked by sending the block, see Level.setTile)
     */
    public CubeEntity spawnPiece(int cellX, int cellY, int cellZ, ChessPieceTile.Type type, boolean white, boolean moved) {
        if (this.replica || cellX < 0 || cellY < 0 || cellZ < 0
                || cellX >= this.level.width || cellY >= this.level.depth || cellZ >= this.level.height
                || countPieces(true) >= MAX_PIECES || pieceAt(cellX, cellY, cellZ) != null
                || cubeInCell(cellX, cellY, cellZ)) {
            return null;
        }
        double feet = cellY - this.level.getBillboardDrop(cellX, cellY, cellZ);
        CubeEntity piece = new CubeEntity(cellX + 0.5D, feet, cellZ + 0.5D);
        applyPieceCode(piece, CubeEntity.pieceCode(type.ordinal(), white, moved));
        piece.id = this.nextId++;
        this.cubes.add(piece);
        piece.type.getHooks().onSpawn(piece);
        return piece;
    }

    /**
     * the piece goes to the square and takes the piece there, if any. it only starts to glide, pieceAt answers for the new square at once. nothing is checked, the caller asked ChessPieceTile.getMoves
     */
    public void movePiece(CubeEntity piece, int toX, int toZ) {
        int cellY = piece.pieceCellY();
        CubeEntity taken = pieceAt(toX, cellY, toZ);
        if (taken != null && taken != piece) {
            this.cubes.remove(taken);
        }
        piece.glideTo(toX + 0.5D, toZ + 0.5D);
        piece.y = cellY - this.level.getBillboardDrop(toX, cellY, toZ);
    }

    /** the nearest piece the ray (unit direction) hits within maxDistance, null if none. distanceOut[0] gets the distance */
    public CubeEntity raycastPiece(double ox, double oy, double oz, double dx, double dy, double dz,
                                   double maxDistance, double[] distanceOut) {
        CubeEntity best = null;
        double bestDistance = maxDistance;
        for (CubeEntity cube : this.cubes) {
            if (!cube.type.isSelectable()) {
                continue;
            }
            double d = cube.raycast(ox, oy, oz, dx, dy, dz, bestDistance);
            if (d >= 0.0D && d <= bestDistance) {
                best = cube;
                bestDistance = d;
            }
        }
        if (best != null && distanceOut != null) {
            distanceOut[0] = bestDistance;
        }
        return best;
    }

    public CubeEntity getTargetedPiece() {
        return this.targetedPiece != null && this.cubes.contains(this.targetedPiece) ? this.targetedPiece : null;
    }

    public void setTargetedPiece(CubeEntity piece) {
        this.targetedPiece = piece;
    }

    /**
     * a level saved before the pieces were entities has them as tiles: each one becomes an entity on its square and the tile goes. called once after a level is loaded
     */
    public void convertChessTiles() {
        boolean[] chess = new boolean[256];
        boolean any = false;
        for (int id = 1; id < 256; id++) {
            if (Tile.byId(id) instanceof ChessPieceTile) {
                chess[id] = true;
                any = true;
            }
        }
        if (!any) {
            return;
        }

        for (int x = 0; x < this.level.width; x++) {
            for (int z = 0; z < this.level.height; z++) {
                for (int y = 0; y < this.level.depth; y++) {
                    int id = this.level.getTile(x, y, z) & 0xFF;
                    if (!chess[id]) {
                        continue;
                    }
                    ChessPieceTile tile = (ChessPieceTile) Tile.byId(id);
                    boolean moved = (this.level.getData(x, y, z) & ChessPieceTile.DATA_MOVED) != 0;
                    this.level.setTile(x, y, z, Tile.AIR.id, 0);
                    spawnPiece(x, y, z, tile.getType(), tile.isWhite(), moved);
                }
            }
        }
    }

    // ---------------------------------------------------------------- saving

    /** the cubes as bytes, taken on the game thread so the write can run on another one. a carried cube is saved where it is */
    public byte[] snapshot() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FILE_MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeInt(this.cubes.size());
            for (CubeEntity cube : this.cubes) {
                out.writeDouble(cube.x);
                out.writeDouble(cube.y);
                out.writeDouble(cube.z);
                out.writeFloat((float) cube.qw);
                out.writeFloat((float) cube.qx);
                out.writeFloat((float) cube.qy);
                out.writeFloat((float) cube.qz);
                out.writeByte(cube.piece);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);   // a byte array does not fail
        }
        return bytes.toByteArray();
    }

    public static void writeFile(File file, byte[] snapshot) {
        try {
            Files.write(file.toPath(), snapshot);
        } catch (IOException e) {
            System.err.println("[CubeManager] could not save cubes: " + e.getMessage());
        }
    }

    public void save(File file) {
        writeFile(file, snapshot());
    }

    /** replaces the cubes with the ones in the file, no file = none */
    public void load(File file) {
        this.cubes.clear();
        if (!file.isFile()) {
            return;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file.toPath())))) {
            if (in.readInt() != FILE_MAGIC) {
                return;
            }
            int version = in.readInt();
            if (version < 1 || version > FILE_VERSION) {
                return;
            }
            int count = Math.min(in.readInt(), MAX_ENTITIES);
            for (int i = 0; i < count; i++) {
                CubeEntity cube = new CubeEntity(in.readDouble(), in.readDouble(), in.readDouble());
                cube.setOrientation(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
                if (version >= 2) {
                    applyPieceCode(cube, in.readByte());
                }
                cube.prevQw = cube.qw;
                cube.prevQx = cube.qx;
                cube.prevQy = cube.qy;
                cube.prevQz = cube.qz;
                cube.id = this.nextId++;
                this.cubes.add(cube);
            }
        } catch (IOException e) {
            System.err.println("[CubeManager] could not read cubes: " + e.getMessage());
        }
    }
}
