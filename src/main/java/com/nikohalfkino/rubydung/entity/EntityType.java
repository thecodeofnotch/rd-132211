package com.nikohalfkino.rubydung.entity;

import java.util.HashMap;
import java.util.Map;

/**
 * what kind of thing an entity is: the entity counterpart of Tile. the systems ask the type ("may this be held?") instead of checking which class or which special value (piece >= 0) the entity has
 *
 * build one with the fluent setters and register it in EntityTypes, like a Tile in TileRegistry. a type is only changed while it is being set up: once EntityTypes is frozen the setters throw
 *
 * a type is data, not behaviour. what an entity does when something happens to it is in its EntityHooks
 */
public final class EntityType {

    /** how it is drawn */
    public enum Shape {
        /** a textured box turned by the entity's orientation (CubeRenderer.render) */
        BOX,
        /** a sprite that faces the camera (CubeRenderer.renderPieces) */
        BILLBOARD
    }

    /** the key for saves, the network and scripts. never changes once worlds exist */
    public final String name;

    int id = -1;

    private boolean holdable;
    private boolean throwable;
    private boolean pushable;
    private boolean solidForPlayer;
    private boolean selectable;
    private boolean physics;
    private boolean networked = true;
    private boolean saved = true;

    private float hullW = 1.0F, hullH = 1.0F;
    private float spriteW = 1.0F, spriteH = 1.0F;
    private Shape shape = Shape.BOX;

    private String capGroup;
    private int capMax = 64;

    private EntityHooks hooks = EntityHooks.NONE;
    private final Map<String, Object> properties = new HashMap<>();

    public EntityType(String name) {
        this.name = name;
    }

    // ---------------------------------------------------------------- what it can do

    /** the player can carry it (CubeHold) */
    public EntityType holdable() {
        checkOpen();
        this.holdable = true;
        return this;
    }

    /** a carried one can be thrown, if not the throw button just lets go of it */
    public EntityType throwable() {
        checkOpen();
        this.throwable = true;
        return this;
    }

    /** a player walking into it sets it moving */
    public EntityType pushable() {
        checkOpen();
        this.pushable = true;
        return this;
    }

    /** the player bumps into it, it is part of the world for walking */
    public EntityType solidForPlayer() {
        checkOpen();
        this.solidForPlayer = true;
        return this;
    }

    /** the crosshair can pick it to interact with it (chess pieces), without carrying it */
    public EntityType selectable() {
        checkOpen();
        this.selectable = true;
        return this;
    }

    /** it is a rigid body: gravity, collisions, bouncing. without it the entity stays where it is put or moves the way its own code says (a piece gliding) */
    public EntityType physics() {
        checkOpen();
        this.physics = true;
        return this;
    }

    /** purely local: not sent to the other players (decoration that every machine makes for itself) */
    public EntityType localOnly() {
        checkOpen();
        this.networked = false;
        return this;
    }

    /** not written to the level's save file */
    public EntityType transientEntity() {
        checkOpen();
        this.saved = false;
        return this;
    }

    // ---------------------------------------------------------------- size and look

    /** the box that is picked, and for a piece the box it fills, in blocks */
    public EntityType hull(float width, float height) {
        checkOpen();
        this.hullW = width;
        this.hullH = height;
        return this;
    }

    /** the size of the sprite drawn, in blocks (a billboard) */
    public EntityType sprite(float width, float height) {
        checkOpen();
        this.spriteW = width;
        this.spriteH = height;
        return this;
    }

    public EntityType shape(Shape shape) {
        checkOpen();
        this.shape = shape;
        return this;
    }

    // ---------------------------------------------------------------- limits

    /**
     * types of the same group share one cap: at most max entities of the group exist in a level. keeps the old rule "64 cubes, 128 pieces in all" while the pieces are six types
     */
    public EntityType capGroup(String group, int max) {
        checkOpen();
        this.capGroup = group;
        this.capMax = max;
        return this;
    }

    // ---------------------------------------------------------------- behaviour and extra data

    public EntityType hooks(EntityHooks hooks) {
        checkOpen();
        this.hooks = hooks == null ? EntityHooks.NONE : hooks;
        return this;
    }

    /** free-form data attached to the type, for scripts (a name for a menu, a colour, a speed...) */
    public EntityType property(String key, Object value) {
        checkOpen();
        this.properties.put(key, value);
        return this;
    }

    // ---------------------------------------------------------------- queries

    /** the number this type has in this run of the game. do not save it or send it, use name */
    public int getId() {
        return this.id;
    }

    public boolean isHoldable() {
        return this.holdable;
    }

    public boolean isThrowable() {
        return this.throwable;
    }

    public boolean isPushable() {
        return this.pushable;
    }

    public boolean isSolidForPlayer() {
        return this.solidForPlayer;
    }

    public boolean isSelectable() {
        return this.selectable;
    }

    public boolean isPhysics() {
        return this.physics;
    }

    public boolean isNetworked() {
        return this.networked;
    }

    public boolean isSaved() {
        return this.saved;
    }

    public float getHullWidth() {
        return this.hullW;
    }

    public float getHullHeight() {
        return this.hullH;
    }

    public float getSpriteWidth() {
        return this.spriteW;
    }

    public float getSpriteHeight() {
        return this.spriteH;
    }

    public Shape getShape() {
        return this.shape;
    }

    public String getCapGroup() {
        return this.capGroup;
    }

    public int getCapMax() {
        return this.capMax;
    }

    public EntityHooks getHooks() {
        return this.hooks;
    }

    public Object getProperty(String key) {
        return this.properties.get(key);
    }

    @Override
    public String toString() {
        return this.name;
    }

    private void checkOpen() {
        if (EntityTypes.isFrozen()) {
            throw new IllegalStateException("entity type \"" + this.name + "\" can not be changed after start-up");
        }
    }
}
