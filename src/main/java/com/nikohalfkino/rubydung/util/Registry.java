package com.nikohalfkino.rubydung.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ordered registry: entries get sequential ids from registration order, and can also be given a name to look them up by
 *
 * TileRegistry and EntityTypes use it. the names are what saves, the network and the scripting API should use: the ids only hold for one run of the game, because adding an entry in the middle shifts the ones after it
 *
 * a registry can be frozen once the game has started, after that nothing can be added. registering is for start-up (built-ins first, then mods)
 */
public class Registry<T> {

    private final String registryName;
    private final List<T> entries = new ArrayList<>();
    private final Map<String, T> byName = new LinkedHashMap<>();
    private final Map<T, Integer> ids = new IdentityHashMap<>();
    private boolean frozen = false;

    public Registry() {
        this("registry");
    }

    /** registryName only shows up in error messages ("entity_type", "tile") */
    public Registry(String registryName) {
        this.registryName = registryName;
    }

    /** the id the next registered entry gets */
    public int nextId() {
        return this.entries.size();
    }

    /** returns the same entry so one expression can register it and assign it to a field. no name, it can only be found by id */
    public <E extends T> E register(E entry) {
        ensureOpen();
        this.ids.put(entry, this.entries.size());
        this.entries.add(entry);
        return entry;
    }

    /** same, and the entry can be found by name too. a name is registered once */
    public <E extends T> E register(String name, E entry) {
        ensureOpen();
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(this.registryName + ": an entry needs a name");
        }
        if (this.byName.containsKey(name)) {
            throw new IllegalStateException(this.registryName + ": \"" + name + "\" is already registered");
        }
        this.byName.put(name, entry);
        return register(entry);
    }

    /** null if no entry has that id */
    public T get(int id) {
        if (id < 0 || id >= this.entries.size()) {
            return null;
        }
        return this.entries.get(id);
    }

    /** null if no entry has that name */
    public T get(String name) {
        return this.byName.get(name);
    }

    public boolean contains(String name) {
        return this.byName.containsKey(name);
    }

    /** the id of the entry, -1 if it is not in this registry */
    public int idOf(T entry) {
        Integer id = this.ids.get(entry);
        return id == null ? -1 : id;
    }

    public List<T> entries() {
        return Collections.unmodifiableList(this.entries);
    }

    /** the names of the entries that have one, in registration order */
    public Set<String> names() {
        return Collections.unmodifiableSet(this.byName.keySet());
    }

    public int size() {
        return this.entries.size();
    }

    // ---------------------------------------------------------------- freezing

    /** no more entries after this */
    public void freeze() {
        this.frozen = true;
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    private void ensureOpen() {
        if (this.frozen) {
            throw new IllegalStateException(this.registryName + " is frozen, entries can only be registered at start-up");
        }
    }
}
