package com.nikohalfkino.rubydung.entity;

import com.nikohalfkino.rubydung.entity.cube.CollisionBox;
import com.nikohalfkino.rubydung.entity.cube.Dice;
import com.nikohalfkino.rubydung.entity.cube.PhysCube;
import com.nikohalfkino.rubydung.entity.cube.WeightedCube;
import com.nikohalfkino.rubydung.entity.piece.ChessPiece;
import com.nikohalfkino.rubydung.entity.piece.MovablePiece;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.entity.phys.AABB;

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
 * every world entity of one level: the weighted cubes, the dice and the chess pieces. it spawns them, runs the physics of the cubes once per tick, answers "what is the crosshair on" and "what does the player bump into", and saves them next to the level
 *
 * the cubes (PhysCube) are solid for the player through Level.getCubes (see addCollisionBoxes), and the player is solid for them here
 *
 * the chess pieces (ChessPiece) are not bodies, nothing pushes them or collides with them, they only stand on their square, glide to another when told to (movePiece) and are picked with raycastPiece. they travel to the players, are saved and removed like the cubes
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

    // jumping under a cube: the head hit it going up at least this fast (blocks/s, a jump leaves the ground at about 8.4), and the cube leaves at this share of that speed, at most BOUNCE_MAX_SPEED
    private static final double BOUNCE_MIN_HIT = 2.0D;
    private static final double BOUNCE_FACTOR = 0.9D;
    private static final double BOUNCE_MAX_SPEED = 12.0D;
    private static final double BOUNCE_REACH = 0.06D;   // gap between head and cube that still counts as touching

    private static final double VOID_Y = -32.0D;

    private static final int FILE_MAGIC = 0x43554245;   // "CUBE"
    private static final int FILE_VERSION = 2;   // 2 adds the piece byte to every entry, 1 is read as cubes only

    /**
     * another player's body as the server sees it: where it is and how fast it is walking, so a cube can be pushed by it and is blocked by it. the player of this machine is not one (it is passed to tick)
     */
    public static final class Body {
        public final AABB box;
        public final double wishX, wishZ;   // blocks/s
        /** the player's id on the server (what the impact listener gets back) */
        public int id = -1;

        public Body(AABB box, double wishX, double wishZ) {
            this.box = box;
            this.wishX = wishX;
            this.wishZ = wishZ;
        }

        public Body withId(int id) {
            this.id = id;
            return this;
        }
    }

    /** a cube went into water: where, and how hard (0..1). set by a server so everybody in the world hears it, else the player here does */
    public interface SplashListener {
        void splashed(double x, double y, double z, float strength);
    }

    private SplashListener splashListener;

    /** a cube hit a player's body: where, which player (the id from Body, -1 = the player of this machine) and how hard (0..1). set by a server, else the sound is played here */
    public interface ImpactListener {
        void hit(double x, double y, double z, int bodyId, float strength);
    }

    private ImpactListener impactListener;

    public void setImpactListener(ImpactListener listener) {
        this.impactListener = listener;
    }

    // a cube hitting a body slower than this (blocks/s) makes no sound, this fast it is full volume
    private static final double HIT_MIN_SPEED = 3.0D;
    private static final double HIT_FULL_SPEED = 14.0D;
    private static final int HIT_COOLDOWN_TICKS = 12;

    // a cube hitting the world (wall, floor) or another cube: same idea, a little higher bar. bodyId BUMP = not a player
    public static final int BUMP = -2;
    private static final double BUMP_MIN_SPEED = 2.5D;
    private static final double BUMP_FULL_SPEED = 14.0D;
    private static final int BUMP_COOLDOWN_TICKS = 8;

    // a cube sliding on the floor faster than this (blocks/s) is dragging, and the sound of it
    private static final double DRAG_MIN_SPEED = 0.5D;
    private static final double DRAG_FULL_SPEED = 5.0D;
    private static final double DRAG_RANGE = 24.0D;
    private final java.util.Map<PhysCube, com.nikohalfkino.rubydung.audio.SoundEffects.Loop> dragLoops = new java.util.HashMap<>();

    public void setSplashListener(SplashListener listener) {
        this.splashListener = listener;
    }

    private Dice.RollListener rollListener;

    private final Level level;
    private final List<WorldEntity> cubes = new ArrayList<>();
    private final List<CollisionBox> obstacles = new ArrayList<>();
    private int nextId = 1;

    // the other players, on a server (null elsewhere)
    private Supplier<List<Body>> bodies;

    // joined a server: the cubes are moved by it, this machine only eases them toward what it is told (see tick)
    private boolean replica = false;

    // the piece the crosshair is on, worked out every frame by the renderer (see GameRenderer.pick), null if none
    private ChessPiece targetedPiece;

    public CubeManager(Level level) {
        this.level = level;
    }

    public List<WorldEntity> all() {
        return this.cubes;
    }

    public int count() {
        return this.cubes.size();
    }

    /** the entity (cube, dice or piece) with this id, null if there is none */
    public WorldEntity byId(int id) {
        for (WorldEntity entity : this.cubes) {
            if (entity.id == id) {
                return entity;
            }
        }
        return null;
    }

    /** the cube or dice with this id, null if there is none or it is a piece */
    public PhysCube cubeById(int id) {
        WorldEntity entity = byId(id);
        return entity instanceof PhysCube ? (PhysCube) entity : null;
    }

    /** the chess piece with this id, null if there is none or it is a cube */
    public ChessPiece pieceById(int id) {
        WorldEntity entity = byId(id);
        return entity instanceof ChessPiece ? (ChessPiece) entity : null;
    }

    /** a new entity for a piece code (see WorldEntity.pieceCode): a chess piece for 0 and up, a dice for -2 and below, else a weighted cube */
    public static WorldEntity create(int code, double x, double y, double z) {
        if (code >= 0) {
            return new ChessPiece(x, y, z, code);
        }
        if (code <= Dice.CODE_IDLE) {
            Dice dice = new Dice(x, y, z);
            dice.applyPieceCode(code);
            return dice;
        }
        return new WeightedCube(x, y, z);
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

    /** client: an entity the server told about (pieceCode says what it is), at its place at once. null if the cap is reached */
    public WorldEntity addReplica(int id, int pieceCode, double x, double y, double z) {
        if (this.cubes.size() >= MAX_ENTITIES) {
            return null;
        }
        WorldEntity entity = create(pieceCode, x, y, z);
        entity.id = id;
        this.cubes.add(entity);
        return entity;
    }

    /** client: drops every cube whose id is not in the set (the server sent the whole list) */
    public void retainOnly(Set<Integer> ids) {
        for (int i = this.cubes.size() - 1; i >= 0; i--) {
            WorldEntity entity = this.cubes.get(i);
            boolean carriedHere = entity instanceof PhysCube && ((PhysCube) entity).held;
            if (!ids.contains(entity.id) && !carriedHere) {
                this.cubes.remove(i);
            }
        }
    }

    public void setRollListener(Dice.RollListener listener) {
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

        PhysCube probe = spawnPoint(cellX, cellY, cellZ, false);
        for (WorldEntity other : this.cubes) {
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
    public PhysCube spawnIn(int cellX, int cellY, int cellZ, Player player) {
        return spawnIn(cellX, cellY, cellZ, player, false);
    }

    /** dice = a dice instead of a plain cube */
    public PhysCube spawnIn(int cellX, int cellY, int cellZ, Player player, boolean dice) {
        if (!canSpawnIn(cellX, cellY, cellZ, player)) {
            return null;
        }
        PhysCube cube = spawnPoint(cellX, cellY, cellZ, dice);
        cube.id = this.nextId++;
        this.cubes.add(cube);
        cube.type.getHooks().onSpawn(cube);
        return cube;
    }

    private static PhysCube spawnPoint(int cellX, int cellY, int cellZ, boolean dice) {
        double x = cellX + 0.5D;
        double y = cellY + PhysCube.HALF + 0.001D;
        double z = cellZ + 0.5D;
        return dice ? new Dice(x, y, z) : new WeightedCube(x, y, z);
    }

    public void remove(WorldEntity entity) {
        if (this.cubes.remove(entity)) {
            entity.type.getHooks().onRemove(entity);
        }
    }

    public void clear() {
        this.cubes.clear();
        stopDragSounds();
    }

    // ---------------------------------------------------------------- picking

    /** the nearest loose cube the ray (unit direction) hits within maxDistance, null if none. distanceOut[0] gets the distance */
    public PhysCube raycast(double ox, double oy, double oz, double dx, double dy, double dz,
                            double maxDistance, double[] distanceOut) {
        PhysCube best = null;
        double bestDistance = maxDistance;
        for (WorldEntity entity : this.cubes) {
            if (!(entity instanceof PhysCube) || !entity.type.isHoldable()) {
                continue;
            }
            PhysCube cube = (PhysCube) entity;
            if (cube.held || cube.remoteHeld) {
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
        for (WorldEntity entity : this.cubes) {
            if (!(entity instanceof PhysCube) || !entity.type.isSolidForPlayer()) {
                continue;
            }
            PhysCube cube = (PhysCube) entity;
            if (cube.held || cube.remoteHeld || cube.ignorePlayer) {
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
            stopDragSounds();
            return;
        }

        AABB body = player == null ? null : player.boundingBox;
        List<Body> others = this.replica ? Collections.<Body>emptyList() : currentBodies();

        if (!this.replica) {
            for (WorldEntity entity : this.cubes) {
                if (!(entity instanceof PhysCube) || !entity.type.isPushable()) {
                    continue;
                }
                PhysCube cube = (PhysCube) entity;
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
                        bounceUnder(cube, body, player.getPhysics().getCeilingHitSpeed());
                    }
                    if (!cube.ignoreBodies) {
                        for (Body other : others) {
                            pushByBox(cube, other.box, new double[]{other.wishX, 0.0D, other.wishZ});
                        }
                    }
                }
            }
        }

        for (WorldEntity entity : this.cubes) {
            if (!this.replica) {
                entity.type.getHooks().onTick(entity);
            }
            if (!(entity instanceof PhysCube)) {
                // a piece is no body: the server glides it, a replica eases toward what the server says
                if (this.replica) {
                    entity.stepReplica();
                } else if (entity instanceof MovablePiece) {
                    ((MovablePiece) entity).tickPiece();
                }
                continue;
            }
            PhysCube cube = (PhysCube) entity;
            if (this.replica && !cube.held) {
                cube.stepReplica();
                continue;
            }

            double carryFromX = cube.x;
            double carryFromZ = cube.z;
            boolean carriesPlayer = !this.replica && !cube.held && player != null && body != null && standsOn(body, cube);

            // a carried cube takes the cubes lying on it along (see carryRiders): they are not in its way
            List<PhysCube> riders = cube.held && !this.replica ? ridersOf(cube) : Collections.<PhysCube>emptyList();
            double carryFromY = cube.y;

            collectObstacles(cube, body, others, riders);
            cube.inLiquid = this.level.getLiquids().has(
                    (int) Math.floor(cube.x), (int) Math.floor(cube.y), (int) Math.floor(cube.z));
            cube.tick(this.obstacles);
            keepInsideLevel(cube);
            if (cube.hitBody != null && cube.hitSpeed >= HIT_MIN_SPEED && cube.hitCooldown == 0) {
                cube.hitCooldown = HIT_COOLDOWN_TICKS;
                bodyHit(cube, player);
            }
            if (cube.bumpSpeed >= BUMP_MIN_SPEED && cube.bumpCooldown == 0) {
                cube.bumpCooldown = BUMP_COOLDOWN_TICKS;
                bump(cube, player);
            }
            float splash = cube.sounds.tickWater(cube.vy, Math.hypot(cube.vx, cube.vz), cube.inLiquid);
            if (splash > 0.0F) {
                if (this.splashListener != null) {
                    // a server: everybody in the world within hearing distance gets it
                    this.splashListener.splashed(cube.x, cube.y, cube.z, splash);
                } else if (player != null) {
                    // alone: the player hears it, the volume falls off with distance
                    float volume = com.nikohalfkino.rubydung.audio.BodySounds.volumeAt(
                            cube.x - player.x, cube.y - player.y, cube.z - player.z, 32.0D);
                    com.nikohalfkino.rubydung.audio.BodySounds.play(
                            com.nikohalfkino.rubydung.audio.BodySounds.WATER_ENTER, splash, volume);
                }
            }
            if (!this.replica && cube instanceof Dice) {
                ((Dice) cube).trackRoll(this.rollListener);
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

            // the same for the cubes lying on a carried one
            if (cube.held && !this.replica && !riders.isEmpty()) {
                double dy = cube.y - carryFromY;
                double allowed = carryRiders(cube, riders, cube.x - carryFromX, dy, cube.z - carryFromZ, body, others);
                if (dy > allowed) {
                    cube.lowerBy(dy - allowed);   // what is on it could not go up any further (a ceiling): the stack stays together
                }
            }
        }

        for (int i = this.cubes.size() - 1; i >= 0; i--) {
            if (this.cubes.get(i).dead) {
                this.cubes.remove(i);
            }
        }

        updateDragSounds(player);
    }

    // the cubes lying on top of a carried one move along with it, up and down as well as sideways (and the ones on top of those, and so on): the stack is carried as one body, it does not slide off or bounce on the cube below. returns how far up the whole stack could really go, at most dy, so the carrier can stay under it when a ceiling is in the way
    private double carryRiders(PhysCube carrier, List<PhysCube> riders, double dx, double dy, double dz, AABB body, List<Body> others) {
        double allowedUp = dy;
        for (PhysCube rider : riders) {
            List<PhysCube> above = ridersOf(rider);   // taken before it moves
            double fromX = rider.x;
            double fromZ = rider.z;
            collectObstacles(rider, body, others, Collections.<PhysCube>emptyList());
            double up = rider.carryBy(this.obstacles, dx, dy, dz);
            double top = up;
            if (!above.isEmpty()) {
                top = Math.min(up, carryRiders(rider, above, rider.x - fromX, up, rider.z - fromZ, body, others));
                if (top < up) {
                    rider.lowerBy(up - top);
                }
            }
            // it rides: it is not falling, and it does not keep a speed it had before
            rider.vy = 0.0D;
            rider.onGround = true;
            rider.bumpCooldown = Math.max(rider.bumpCooldown, 2);
            if (dy > 0.0D) {
                allowedUp = Math.min(allowedUp, top);
            }
        }
        return allowedUp;
    }

    // the cubes lying directly on this one
    private List<PhysCube> ridersOf(PhysCube under) {
        List<PhysCube> result = null;
        for (WorldEntity entity : this.cubes) {
            if (entity == under || !(entity instanceof PhysCube)) {
                continue;
            }
            PhysCube top = (PhysCube) entity;
            if (top.held || top.remoteHeld || !top.type.isPhysics() || !liesOn(top, under)) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(2);
            }
            result.add(top);
        }
        return result == null ? Collections.<PhysCube>emptyList() : result;
    }

    // a cube was pushed along axis: everything that lies on it is pushed along, so a stack moves as one (the top of a tall stack is out of the player's reach)
    private void pushRiders(PhysCube under, int axis, double speed) {
        for (PhysCube rider : ridersOf(under)) {
            rider.pushHorizontal(axis, speed);
            pushRiders(rider, axis, speed);
        }
    }

    // the cube stands on top of the other one: over it, and its underside is on the other's top
    private static boolean liesOn(PhysCube top, PhysCube under) {
        boolean over = top.minX() < under.maxX() && top.maxX() > under.minX()
                && top.minZ() < under.maxZ() && top.maxZ() > under.minZ();
        return over && Math.abs(top.minY() - under.maxY()) < 0.06D;
    }

    // a cube hit a player: a server tells the listener, alone the player here hears it (and feels it if it was them)
    private void bodyHit(PhysCube cube, Player player) {
        float strength = (float) Math.max(0.3D, Math.min(1.0D, cube.hitSpeed / HIT_FULL_SPEED));
        int bodyId = cube.hitBody.bodyId;
        if (this.impactListener != null) {
            this.impactListener.hit(cube.x, cube.y, cube.z, bodyId, strength);
        } else if (player != null) {
            float volume = com.nikohalfkino.rubydung.audio.BodySounds.volumeAt(
                    cube.x - player.x, cube.y - player.y, cube.z - player.z, 32.0D);
            com.nikohalfkino.rubydung.audio.SoundEffects.play("cube_impact_player", strength * volume);
            if (bodyId == -1) {
                com.nikohalfkino.rubydung.audio.SoundEffects.play("phys_impact_player", strength);
            }
        }
    }

    // a cube hit a wall, the floor or another cube
    private void bump(PhysCube cube, Player player) {
        float strength = (float) Math.max(0.25D, Math.min(1.0D, cube.bumpSpeed / BUMP_FULL_SPEED));
        if (this.impactListener != null) {
            this.impactListener.hit(cube.x, cube.y, cube.z, BUMP, strength);
        } else if (player != null) {
            float volume = com.nikohalfkino.rubydung.audio.BodySounds.volumeAt(
                    cube.x - player.x, cube.y - player.y, cube.z - player.z, 32.0D);
            com.nikohalfkino.rubydung.audio.SoundEffects.play("cube_bump", strength * volume);
        }
    }

    // every machine plays the drag sound of the cubes it can see sliding: a replica knows where a cube is and how fast it moves, so nothing has to be sent
    private void updateDragSounds(Player player) {
        if (player == null) {
            stopDragSounds();   // a dedicated server has nobody to hear it
            return;
        }

        for (WorldEntity entity : this.cubes) {
            if (!(entity instanceof PhysCube)) {
                continue;
            }
            PhysCube cube = (PhysCube) entity;
            float volume = 0.0F;
            if (!cube.held && !cube.remoteHeld && !cube.dead) {
                double speed = Math.hypot(cube.vx, cube.vz);
                if (speed >= DRAG_MIN_SPEED && Math.abs(cube.vy) < 0.3D && (cube.onGround || onFloor(cube))) {
                    float near = com.nikohalfkino.rubydung.audio.BodySounds.volumeAt(
                            cube.x - player.x, cube.y - player.y, cube.z - player.z, DRAG_RANGE);
                    volume = near * (float) Math.max(0.25D, Math.min(1.0D, speed / DRAG_FULL_SPEED));
                }
            }

            com.nikohalfkino.rubydung.audio.SoundEffects.Loop loop = this.dragLoops.get(cube);
            if (volume > 0.02F) {
                if (loop == null) {
                    loop = com.nikohalfkino.rubydung.audio.SoundEffects.startLoop("cube_drag_floor_loop", volume);
                    this.dragLoops.put(cube, loop);
                }
                loop.setVolume(volume);
            } else if (loop != null) {
                loop.stop();
                this.dragLoops.remove(cube);
            }
        }

        // cubes that are gone (removed, or the level changed)
        java.util.Iterator<java.util.Map.Entry<PhysCube, com.nikohalfkino.rubydung.audio.SoundEffects.Loop>> it =
                this.dragLoops.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<PhysCube, com.nikohalfkino.rubydung.audio.SoundEffects.Loop> entry = it.next();
            if (entry.getKey().dead || !this.cubes.contains(entry.getKey())) {
                entry.getValue().stop();
                it.remove();
            }
        }
    }

    private void stopDragSounds() {
        for (com.nikohalfkino.rubydung.audio.SoundEffects.Loop loop : this.dragLoops.values()) {
            loop.stop();
        }
        this.dragLoops.clear();
    }

    // a solid block just under the cube (a replica does not know onGround)
    private boolean onFloor(PhysCube cube) {
        int y = (int) Math.floor(cube.minY() - 0.05D);
        double e = 0.02D;
        return this.level.isSolidTile((int) Math.floor(cube.minX() + e), y, (int) Math.floor(cube.minZ() + e))
                || this.level.isSolidTile((int) Math.floor(cube.maxX() - e), y, (int) Math.floor(cube.minZ() + e))
                || this.level.isSolidTile((int) Math.floor(cube.minX() + e), y, (int) Math.floor(cube.maxZ() - e))
                || this.level.isSolidTile((int) Math.floor(cube.maxX() - e), y, (int) Math.floor(cube.maxZ() - e));
    }

    private static boolean overlapsAny(PhysCube cube, List<Body> others, double grow) {
        for (Body other : others) {
            AABB b = other.box;
            if (cube.overlaps(b.minX - grow, b.minY - grow, b.minZ - grow, b.maxX + grow, b.maxY + grow, b.maxZ + grow)) {
                return true;
            }
        }
        return false;
    }

    private static boolean standsOn(AABB body, PhysCube cube) {
        boolean overTop = body.minX < cube.maxX() && body.maxX > cube.minX()
                && body.minZ < cube.maxZ() && body.maxZ > cube.minZ();
        return overTop && Math.abs(body.minY - cube.maxY()) < 0.06D;
    }

    // the player jumps under the cube and hits it with their head: it is knocked up, the harder the jump the higher it goes
    // only the cube right above the head counts (touching, overlapping in x and z), and only a cube free to move, not a carried one
    private void bounceUnder(PhysCube cube, AABB body, double hitSpeed) {
        if (hitSpeed < BOUNCE_MIN_HIT || cube.held || cube.remoteHeld) {
            return;
        }
        boolean over = body.minX < cube.maxX() && body.maxX > cube.minX()
                && body.minZ < cube.maxZ() && body.maxZ > cube.minZ();
        if (!over || Math.abs(cube.minY() - body.maxY) > BOUNCE_REACH) {
            return;
        }
        double speed = Math.min(hitSpeed * BOUNCE_FACTOR, BOUNCE_MAX_SPEED);
        liftStack(cube, speed);
    }

    // knocked up with everything that lies on it, at the same speed
    private void liftStack(PhysCube cube, double speed) {
        if (speed > cube.vy) {
            cube.vy = speed;
            cube.onGround = false;
        }
        for (PhysCube rider : ridersOf(cube)) {
            liftStack(rider, speed);
        }
    }

    // the player walks into the cube: it gets going in the direction they are walking
    private void pushByBox(PhysCube cube, AABB body, double[] wish) {
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
            double pushSpeed = bestSign * Math.min(bestAlong * PUSH_FACTOR, PUSH_MAX_SPEED);
            cube.pushHorizontal(bestAxis, pushSpeed);
            pushRiders(cube, bestAxis, pushSpeed);
        }
    }

    // everything this cube could hit this tick
    private void collectObstacles(PhysCube cube, AABB body, List<Body> others, List<PhysCube> skip) {
        this.obstacles.clear();

        double r = cube.reach();
        AABB region = new AABB(cube.minX() - r, cube.minY() - r, cube.minZ() - r,
                cube.maxX() + r, cube.maxY() + r, cube.maxZ() + r);
        for (AABB box : this.level.getWorldCubes(region)) {
            this.obstacles.add(new CollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, null));
        }

        for (WorldEntity entity : this.cubes) {
            if (entity != cube && entity instanceof PhysCube && entity.type.isPhysics() && !skip.contains(entity)) {
                PhysCube other = (PhysCube) entity;
                this.obstacles.add(new CollisionBox(other.minX(), other.minY(), other.minZ(),
                        other.maxX(), other.maxY(), other.maxZ(), other));
            }
        }

        // a carried cube goes through the player, so it never shoves the one carrying it
        if (!cube.held && !cube.ignorePlayer && body != null) {
            CollisionBox box = new CollisionBox(body.minX, body.minY, body.minZ,
                    body.maxX, body.maxY, body.maxZ, null);
            box.body = true;   // bodyId stays -1: the player of this machine
            this.obstacles.add(box);
        }
        if (!cube.held && !cube.ignoreBodies) {
            for (Body other : others) {
                CollisionBox box = new CollisionBox(other.box.minX, other.box.minY, other.box.minZ,
                        other.box.maxX, other.box.maxY, other.box.maxZ, null);
                box.body = true;
                box.bodyId = other.id;
                this.obstacles.add(box);
            }
        }
    }

    private void keepInsideLevel(PhysCube cube) {
        double h = PhysCube.HALF;
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
        for (WorldEntity cube : this.cubes) {
            if (cube.isPiece() == pieces) {
                n++;
            }
        }
        return n;
    }

    public boolean hasPieces() {
        for (WorldEntity cube : this.cubes) {
            if (cube.isPiece()) {
                return true;
            }
        }
        return false;
    }

    /** the piece standing on the square (or gliding to it), null if none */
    public ChessPiece pieceAt(int cellX, int cellY, int cellZ) {
        for (WorldEntity entity : this.cubes) {
            if (!(entity instanceof ChessPiece)) {
                continue;
            }
            ChessPiece piece = (ChessPiece) entity;
            if (piece.pieceCellX() == cellX && piece.pieceCellZ() == cellZ && piece.pieceCellY() == cellY) {
                return piece;
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
        for (WorldEntity cube : this.cubes) {
            if (!cube.isPiece() && cube.overlaps(x, y, z, x + 1, y + 1, z + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * stands a new piece on the square (the middle of the cell, on its floor). null if there is a piece on it already, the cap is reached, or this machine only shows what a server says (the server is asked by sending the block, see Level.setTile)
     */
    public ChessPiece spawnPiece(int cellX, int cellY, int cellZ, ChessPieceTile.Type type, boolean white, boolean moved) {
        if (this.replica || cellX < 0 || cellY < 0 || cellZ < 0
                || cellX >= this.level.width || cellY >= this.level.depth || cellZ >= this.level.height
                || countPieces(true) >= MAX_PIECES || pieceAt(cellX, cellY, cellZ) != null
                || cubeInCell(cellX, cellY, cellZ)) {
            return null;
        }
        double feet = cellY - this.level.getBillboardDrop(cellX, cellY, cellZ);
        ChessPiece piece = new ChessPiece(cellX + 0.5D, feet, cellZ + 0.5D,
                ChessPiece.pieceCode(type.ordinal(), white, moved));
        piece.id = this.nextId++;
        this.cubes.add(piece);
        piece.type.getHooks().onSpawn(piece);
        return piece;
    }

    /**
     * the piece goes to the square and takes the piece there, if any. it only starts to glide, pieceAt answers for the new square at once. nothing is checked, the caller asked ChessPieceTile.getMoves
     */
    public void movePiece(ChessPiece piece, int toX, int toZ) {
        int cellY = piece.pieceCellY();
        ChessPiece taken = pieceAt(toX, cellY, toZ);
        if (taken != null && taken != piece) {
            this.cubes.remove(taken);
        }
        piece.glideTo(toX + 0.5D, toZ + 0.5D);
        piece.y = cellY - this.level.getBillboardDrop(toX, cellY, toZ);
    }

    /** the nearest piece the ray (unit direction) hits within maxDistance, null if none. distanceOut[0] gets the distance */
    public ChessPiece raycastPiece(double ox, double oy, double oz, double dx, double dy, double dz,
                                   double maxDistance, double[] distanceOut) {
        ChessPiece best = null;
        double bestDistance = maxDistance;
        for (WorldEntity entity : this.cubes) {
            if (!(entity instanceof ChessPiece) || !entity.type.isSelectable()) {
                continue;
            }
            ChessPiece cube = (ChessPiece) entity;
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

    public ChessPiece getTargetedPiece() {
        return this.targetedPiece != null && this.cubes.contains(this.targetedPiece) ? this.targetedPiece : null;
    }

    public void setTargetedPiece(ChessPiece piece) {
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
            for (WorldEntity cube : this.cubes) {
                out.writeDouble(cube.x);
                out.writeDouble(cube.y);
                out.writeDouble(cube.z);
                out.writeFloat((float) cube.qw);
                out.writeFloat((float) cube.qx);
                out.writeFloat((float) cube.qy);
                out.writeFloat((float) cube.qz);
                out.writeByte(cube.pieceCode());
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
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                double qw = in.readFloat();
                double qx = in.readFloat();
                double qy = in.readFloat();
                double qz = in.readFloat();
                WorldEntity cube = create(version >= 2 ? in.readByte() : WeightedCube.CODE, x, y, z);
                cube.setOrientation(qw, qx, qy, qz);
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
