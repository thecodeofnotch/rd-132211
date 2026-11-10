package com.nikohalfkino.rubydung.level.tiles.special;

import com.nikohalfkino.rubydung.level.tiles.common.BillboardTile;

public class TreeTile extends BillboardTile {

    public enum TreeType {
        SAPLING("Sapling", 4.0F, "tree_sapling"),
        MEDIUM("Medium", 8.0F, "tree_medium"),
        LARGE("Large", 16.0F, "tree_large");

        public final String displayName;
        public final float height;
        public final String textureName;

        TreeType(String displayName, float height, String textureName) {
            this.displayName = displayName;
            this.height = height;
            this.textureName = textureName;
        }
    }

    private final TreeType treeType;

    public TreeTile(String name, TreeType treeType) {
        super(name, treeType.textureName, treeType.height);
        this.treeType = treeType;

        // Trees occupy their full height (1 x height x 1) instead of one cell drawing a tall sprite.
        occupyHeight();
    }

    public TreeType getType() {
        return this.treeType;
    }
}