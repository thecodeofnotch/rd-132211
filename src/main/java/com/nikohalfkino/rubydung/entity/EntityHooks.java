package com.nikohalfkino.rubydung.entity;

import com.nikohalfkino.rubydung.entity.player.Player;

/**
 * what an entity type does when something happens to one of its entities. every method has an empty default, so a type overrides only what it needs
 *
 * this is the seam a script binds to: a JS mod hands the type one of these (built from its callbacks) and never touches CubeManager or CubeHold. the hooks run on the machine that does the thing: tick and spawn on the one that steps the entity (not on a client that only shows it), pickup and drop on the player's own machine
 *
 * (the entity is an Entity: a WorldEntity subclass for everything the CubeManager holds)
 */
public interface EntityHooks {

    EntityHooks NONE = new EntityHooks() {
    };

    default void onSpawn(Entity entity) {
    }

    /** once per game tick, before the entity is stepped */
    default void onTick(Entity entity) {
    }

    /** the player is about to pick it up. false = not now, nothing happens */
    default boolean onPickup(Entity entity, Player player) {
        return true;
    }

    /** the player let go of it. thrown = it was thrown and not just dropped */
    default void onDrop(Entity entity, boolean thrown) {
    }

    default void onRemove(Entity entity) {
    }
}
