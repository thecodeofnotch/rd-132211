package com.nikohalfkino.rubydung.entity;

import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.util.Registry;

/**
 * every kind of entity there is, registered here like the tiles are in TileRegistry. a mod adds its own with register() before freeze()
 *
 * the chess pieces are six types (one per ChessPieceTile.Type), white / black and "has moved" are not types but data, they stay in the bits of CubeEntity.piece, so the save file and the network do not change
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

    /** acts like the weighted cube, but shows a number after a throw (see CubeManager.trackDice). shares the cube cap */
    public static final EntityType DICE = register(
            new EntityType("dice")
                    .holdable().throwable().pushable().solidForPlayer().physics()
                    .shape(EntityType.Shape.BOX)
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

    /** the type of a CubeEntity.piece code: a plain cube for a negative one */
    public static EntityType forPieceCode(int code) {
        if (code < 0) {
            return code <= CubeEntity.DICE_IDLE ? DICE : WEIGHTED_CUBE;
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

    /** call once the game and its mods have registered everything */
    public static void freeze() {
        REGISTRY.freeze();
    }

    public static boolean isFrozen() {
        return REGISTRY.isFrozen();
    }
}
