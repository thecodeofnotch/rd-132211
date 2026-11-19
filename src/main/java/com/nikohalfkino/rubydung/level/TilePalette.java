package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * a world's tile palette: which tile each saved block value stands for, by name ("in this save, 0 is air, 1 is grass...")
 *
 * worlds store palette indices instead of live tile ids, so the registration order in Tile can change freely, on load the indices are matched back to the current ids
 *
 * append-only: a name keeps its index for the life of the save, new tiles go at the end, and the palette file is written before the level file so a crash between the two can't leave a level that disagrees with its palette
 */
public final class TilePalette {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 256; // block values are stored in one byte

    // tile order of saves from before palettes existed (ids were registration order)
    // frozen snapshot, NOT the live registry: never change it, old worlds were written with exactly this order
    private static final String[] LEGACY_ORDER = {
            "air", "grass", "cobblestone", "slate", "bricks", "metal", "metal_girder", "metal_grate",
            "gold", "glass", "tree_sapling", "tree_medium", "tree_large", "lamppost", "stone", "gravel",
            "sand", "planks", "log", "lamp", "path", "bricks_large", "stone_bricks", "stone_bricks_large",
            "pillar", "pillar_top", "pillar_bottom", "column", "marble", "wood"
    };

    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> indexByName = new HashMap<>();

    private TilePalette() {
    }

    /** palette for saves that have none stored */
    public static TilePalette legacy() {
        TilePalette palette = new TilePalette();
        for (String name : LEGACY_ORDER) {
            palette.add(name);
        }
        return palette;
    }

    /** palette for a brand-new world */
    public static TilePalette fromCurrentRegistry() {
        TilePalette palette = new TilePalette();
        for (Tile tile : Tile.getTiles()) {
            palette.add(tile.name);
        }
        return palette;
    }

    private int add(String name) {
        if (this.names.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("Tile palette is full (" + MAX_ENTRIES + " entries)");
        }
        int index = this.names.size();
        this.names.add(name);
        this.indexByName.put(name, index);
        return index;
    }

    public int size() {
        return this.names.size();
    }

    /** saved block values (palette indices) -> current tile ids, in place. tiles that no longer exist become air, a warning lists them */
    public void decode(byte[] blocks) {
        int[] toCurrent = new int[256];
        Arrays.fill(toCurrent, Tile.AIR.id);
        List<String> missing = new ArrayList<>();

        for (int index = 0; index < this.names.size(); index++) {
            String name = this.names.get(index);
            Tile tile = Tile.byName(name);
            if (tile != null) {
                toCurrent[index] = tile.id;
            } else {
                missing.add(name);
            }
        }

        int replaced = 0;
        for (int i = 0; i < blocks.length; i++) {
            int index = blocks[i] & 0xFF;
            int id = toCurrent[index];
            if (id == Tile.AIR.id && index != 0 && index < this.names.size() && missing.contains(this.names.get(index))) {
                replaced++;
            }
            blocks[i] = (byte) id;
        }

        if (!missing.isEmpty()) {
            System.out.println("Warning: world uses tiles that no longer exist " + missing
                    + " - " + replaced + " blocks were replaced with air.");
        }
    }

    /** current tile ids -> palette indices, returns a new array (live blocks untouched), tiles not in the palette yet are appended to it */
    public byte[] encode(byte[] blocks) {
        int[] fromCurrent = new int[256];
        Arrays.fill(fromCurrent, -1);

        byte[] encoded = new byte[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            int id = blocks[i] & 0xFF;
            int index = fromCurrent[id];
            if (index < 0) {
                index = indexFor(id);
                fromCurrent[id] = index;
            }
            encoded[i] = (byte) index;
        }
        return encoded;
    }

    /** same as encode() but rewrites the given array instead of allocating another one, only for a copy nobody else reads */
    public byte[] encodeInPlace(byte[] blocks) {
        int[] fromCurrent = new int[256];
        Arrays.fill(fromCurrent, -1);

        for (int i = 0; i < blocks.length; i++) {
            int id = blocks[i] & 0xFF;
            int index = fromCurrent[id];
            if (index < 0) {
                index = indexFor(id);
                fromCurrent[id] = index;
            }
            blocks[i] = (byte) index;
        }
        return blocks;
    }

    private int indexFor(int tileId) {
        Tile tile = Tile.byId(tileId);
        String name = tile != null ? tile.name : Tile.AIR.name;

        Integer index = this.indexByName.get(name);
        return index != null ? index : add(name);
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(FORMAT_VERSION);
        out.writeInt(this.names.size());
        for (String name : this.names) {
            out.writeUTF(name);
        }
    }

    /** reads a palette written by write(), IOException if the data isn't valid */
    public static TilePalette read(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported palette version " + version);
        }

        int count = in.readInt();
        if (count < 1 || count > MAX_ENTRIES) {
            throw new IOException("Invalid palette size " + count);
        }

        TilePalette palette = new TilePalette();
        for (int i = 0; i < count; i++) {
            palette.add(in.readUTF());
        }
        return palette;
    }
}