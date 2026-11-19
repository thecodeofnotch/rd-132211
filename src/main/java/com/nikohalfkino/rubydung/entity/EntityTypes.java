package com.nikohalfkino.rubydung.entity;

import com.nikohalfkino.rubydung.entity.cube.Dice;
import com.nikohalfkino.rubydung.entity.phys.ParticleEntity;
import com.nikohalfkino.rubydung.entity.ragdoll.Ragdoll;
import com.nikohalfkino.rubydung.entity.ragdoll.RagdollRig;
import com.nikohalfkino.rubydung.entity.player.PlayerPhysics;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.util.Registry;

/**
 * every kind of entity there is, registered here like the tiles are in TileRegistry. a mod adds its own with register() before freeze()
 *
 * the chess pieces are six types (one per ChessPieceTile.Type), white / black and "has moved" are not types but data, they stay in the bits of ChessPiece's piece code, so the save file and the network do not change
 */
public final class EntityTypes {

    private EntityTypes() {
    }

    private static final Registry<EntityType> REGISTRY = new Registry<>("entity_type");

    public static final String GROUP_CUBES = "cube";
    public static final String GROUP_PIECES = "chess";

    public static final EntityType WEIGHTED_CUBE = register(
            new EntityType("weighted_cube")
                    .holdable().throwable().pushable().solidForPlayer().physics()
                    .shape(EntityType.Shape.BOX)
                    .capGroup(GROUP_CUBES, 64));

    /** acts like the weighted cube, but shows a number after a throw (see Dice.trackRoll). shares the cube cap */
    public static final EntityType DICE = register(
            new EntityType("dice")
                    .holdable().throwable().pushable().solidForPlayer().physics()
                    .shape(EntityType.Shape.BOX)
                    .capGroup(GROUP_CUBES, 64));

    /**
     * a ragdoll: a person made of points, drawn as a 3D model bent to them (see Ragdoll). picked up by any limb, thrown and pushed, and it shares the cube cap. the player is not stopped by it (no solidForPlayer): the limbs are pushed out of the player's box instead, so walking into one shoves it aside and never traps anybody. its hull is the box around the limbs, it changes with the pose, so it is a dynamic one
     */
    public static final EntityType RAGDOLL = register(
            new EntityType("ragdoll")
                    .holdable().throwable().pushable().physics().dynamicHull()
                    .shape(EntityType.Shape.MODEL)
                    .capGroup(GROUP_CUBES, 64));

    // index = ChessPieceTile.Type ordinal
    private static final EntityType[] CHESS = new EntityType[ChessPieceTile.Type.values().length];

    static {
        for (ChessPieceTile.Type type : ChessPieceTile.Type.values()) {
            CHESS[type.ordinal()] = register(
                    new EntityType("chess_" + type.id)
                            .selectable()
                            .shape(EntityType.Shape.BILLBOARD)
                            .hull(type.hitWidth, type.hitHeight)
                            .sprite(type.spriteWidth, type.spriteHeight)
                            .capGroup(GROUP_PIECES, 128));
        }
    }

    /**
     * the player of this machine (Player). it has no capabilities: it is not held, pushed or picked, and the CubeManager does not hold it. the other players are not entities (see RemotePlayer). saved and sent by its own means, not as an entity
     */
    public static final EntityType PLAYER = register(
            new EntityType("player")
                    .hull((float) (PlayerPhysics.HALF_WIDTH * 2.0D), (float) PlayerPhysics.HEIGHT)
                    .localOnly().transientEntity());

    /** adds a type. only at start-up: the built-ins are here, mods come after them and before freeze() */
    public static <T extends EntityType> T register(T type) {
        REGISTRY.register(type.name, type);
        type.id = REGISTRY.idOf(type);
        return type;
    }

    /** null if there is none with that name */
    public static EntityType byName(String name) {
        return REGISTRY.get(name);
    }

    public static EntityType byId(int id) {
        return REGISTRY.get(id);
    }

    public static Iterable<EntityType> all() {
        return REGISTRY.entries();
    }

    /** the type of a piece code (see WorldEntity.pieceCode): a plain cube, a dice or a ragdoll for a negative one */
    public static EntityType forPieceCode(int code) {
        if (code < 0) {
            if (Ragdoll.isRagdollCode(code)) {
                return RAGDOLL;
            }
            return Dice.isDiceCode(code) ? DICE : WEIGHTED_CUBE;
        }
        return CHESS[Math.min(code & 7, CHESS.length - 1)];
    }

    /** the cap of a group (the largest of its types'), 0 if no type is in it */
    public static int groupCap(String group) {
        int cap = 0;
        for (EntityType type : REGISTRY.entries()) {
            if (group.equals(type.getCapGroup())) {
                cap = Math.max(cap, type.getCapMax());
            }
        }
        return cap;
    }

    /**
     * how many bytes of extra state (see WorldEntity.writeExtra) follow the entry of an entity with this piece code in the save file and on the network. 0 for everything that has none. the size is a fact of the piece code alone, so a reader knows how much to take without asking the entity
     */
    public static int extraSizeOf(int pieceCode) {
        if (Ragdoll.isRagdollCode(pieceCode)) {
            return ParticleEntity.extraBytes(RagdollRig.PARTICLES);
        }
        return 0;
    }

    /** call once the game and its mods have registered everything */
    public static void freeze() {
        REGISTRY.freeze();
    }

    public static boolean isFrozen() {
        return REGISTRY.isFrozen();
    }
}
