package com.nikohalfkino.rubydung.level.tiles;

import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.special.DoorColor;
import com.nikohalfkino.rubydung.level.tiles.common.SlabTile;
import com.nikohalfkino.rubydung.level.tiles.common.BillboardAura;
import com.nikohalfkino.rubydung.level.tiles.common.BillboardTile;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.level.tiles.special.CubeTile;
import com.nikohalfkino.rubydung.level.tiles.special.DiceTile;
import com.nikohalfkino.rubydung.level.tiles.special.TreeTile;
import com.nikohalfkino.rubydung.level.tiles.special.DimensionalDoorTile;
import com.nikohalfkino.rubydung.level.tiles.common.WrappedTile;
import com.nikohalfkino.rubydung.level.tiles.common.RotatableTile;
import com.nikohalfkino.rubydung.level.tiles.special.WoodenDoorTile;
import com.nikohalfkino.rubydung.level.tiles.common.PaneTile;
import com.nikohalfkino.rubydung.util.Registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TileRegistry {

    private TileRegistry() {}

    private static final Registry<Tile>      REGISTRY      = new Registry<>();
    private static final Map<String, Tile>   BY_NAME       = new HashMap<>();
    private static       List<Tile>          placeableTiles;

    public static final Tile AIR = register(new Tile("air").setSolid(false).setPlaceable(false));

    public static final Tile GRASS       = register(new Tile("grass"));
    public static final Tile SAND        = register(new Tile("sand"));
    public static final Tile GRAVEL      = register(new Tile("gravel"));
    public static final Tile STONE       = register(new Tile("stone"));
    public static final Tile COBBLESTONE = register(new Tile("cobblestone"));
    public static final Tile SLATE       = register(new Tile("slate"));

    public static final Tile BRICKS             = register(new Tile("bricks"));
    public static final Tile BRICKS_SLAB        = register(new SlabTile("bricks_slab").setIconHalf("bottom"));
    public static final Tile BRICKS_LARGE       = register(new Tile("bricks_large"));
    public static final Tile BRICKS_LARGE_SLAB  = register(new SlabTile("bricks_large_slab").setIconHalf("bottom"));
    public static final Tile STONE_BRICKS       = register(new Tile("stone_bricks"));
    public static final Tile STONE_BRICKS_SLAB       = register(new SlabTile("stone_bricks_slab").setIconHalf("bottom"));
    public static final Tile STONE_BRICKS_LARGE = register(new Tile("stone_bricks_large"));
    public static final Tile STONE_BRICKS_LARGE_SLAB = register(new SlabTile("stone_bricks_large_slab").setIconHalf("bottom"));

    public static final Tile STONE_TILES = register(new WrappedTile("stone_tiles"));

    public static final Tile MARBLE = register(new Tile("marble"));

    public static final Tile PILLAR_TOP    = register(new RotatableTile("pillar_top"));
    public static final Tile PILLAR        = register(new RotatableTile("pillar"));
    public static final Tile PILLAR_BOTTOM = register(new RotatableTile("pillar_bottom"));

    public static final Tile COLUMN = register(new BillboardTile("column", 8.0F).occupyHeight());

    public static final Tile STATUE = register(new BillboardTile("statue", 6.0F).setFootprint(2, 6, 2));
    public static final Tile BOULDER = register(new BillboardTile("boulder", 2.0F).setFootprint(2, 2, 2));
    public static final Tile ROCK = register(new BillboardTile("rock", 1.0F).setFootprint(1, 1, 1));
    public static final Tile BARREL  = register(new BillboardTile("barrel", 2.0F).occupyHeight());

    public static final Tile METAL = register(new Tile("metal"));

    public static final Tile METAL_GIRDER = register(
            new RotatableTile("metal_girder")
                    .setTransparent(true)
                    .setSelfCull(Tile.FACE_TOP, Tile.FACE_BOTTOM)
    );

    public static final Tile METAL_GRATE = register(
            new SlabTile("metal_grate")
                    .setIconHalf("top")
                    .setTransparent(true)
                    .setSelfCull(Tile.FACE_NORTH, Tile.FACE_SOUTH, Tile.FACE_EAST, Tile.FACE_WEST)
    );

    public static final Tile METAL_BARS = register(new PaneTile("metal_bars"));

    public static final Tile GOLD = register(new Tile("gold"));

    public static final Tile LOG = register(
            new RotatableTile("log").setTransparent(true)
    );
    public static final Tile PLANKS = register(new Tile("planks"));

    public static final Tile WOOD_SLAB = register(new SlabTile("wood_slab"));
    public static final Tile WOOD   = register(new Tile("wood"));

    public static final Tile WOODEN_DOOR          = register(new WoodenDoorTile(WoodenDoorTile.Swing.CLOSED));
    public static final Tile WOODEN_DOOR_OPEN_IN  = register(new WoodenDoorTile(WoodenDoorTile.Swing.IN).setPlaceable(false));
    public static final Tile WOODEN_DOOR_OPEN_OUT = register(new WoodenDoorTile(WoodenDoorTile.Swing.OUT).setPlaceable(false));


    public static final Tile GLASS = register(
            new Tile("glass").setTransparent(true).setSelfCullAll()
    );
    public static final Tile FRAMED_GLASS = register(new PaneTile("framed_glass"));
    public static final Tile LAMP = register(new Tile("lamp").setLightRadius(12.0F)).setTransparent(true);
    // 8 cells tall, and one light on the topmost of them: a cone pointing straight down, wider the further it gets.
    public static final Tile LAMPPOST = register(
            new BillboardTile("lamppost", 8.0F).occupyHeight()
                    // measured from the old texture: colour of its glow, centred on the lamp 6.2 blocks up, about 2 blocks wide
                    .setAura(BillboardAura.of(0.99F, 0.85F, 0.30F)   // the base colour, half way from the rim to the core
                            .rimColor(0.70F, 0.28F, 0.05F)          // outermost band: dark orange
                            .coreColor(1.00F, 1.00F, 0.85F)         // innermost band: nearly white
                            .at(0.0F, 6.2F)
                            .radius(2.2F)
                            .strength(1F)
                            .falloff(1.2F)
                            .opacity(0.6F)
                            .bands(7)
                            .antialias(5.0F)
                            .grain(0.25F)       // per-pixel wobble of the borders
                            .blend(BillboardAura.Blend.SCREEN))   // NORMAL, ADD or SCREEN

                    .addLight(LightEmitter.top()
                            .radius(20.0F)
                            .cone(0.0F, -1.0F, 0.0F, 40.0F)
                            .falloff(1.0F)
                            .softness(0.35F))   // returns Tile, so the lights stay last in the chain
    );

    public static final Tile WATER = register(
            new LiquidTile("water")
                    .setColor(0.10F, 0.30F, 0.80F, 0.68F)
                    .setFoam(1.0F, 1.0F, 1.0F, 1.0F)
                    .setWarp(0.125F)
                    .setFlowSpeed(1.0F)
                    .setWaveHeight(0.03F)
                    .setViscosity(0.6F)
                    .setInfinite(true)
                    // what blocks look like under water, shallow to deep (the old hard coded palette of TerrainShader)
                    .setDepthPalette(
                            new float[]{0.38F, 0.91F, 1.00F},
                            new float[]{0.21F, 0.72F, 0.94F},
                            new float[]{0.10F, 0.53F, 0.82F},
                            new float[]{0.06F, 0.36F, 0.65F},
                            new float[]{0.04F, 0.24F, 0.47F},
                            new float[]{0.025F, 0.13F, 0.29F})
    );

    public static final Tile LAVA = register(
            new LiquidTile("lava")
                    .setColor(1.00F, 0.38F, 0.05F, 1.0F)
                    .setFoam(0.12F, 0.03F, 0.01F, 1.0F)
                    .setWarp(0.20F)
                    .setFlowSpeed(0.35F)
                    .setWaveHeight(0.012F)
                    .setViscosity(4.0F)
                    // 5 bands: glowing orange just under the surface, dark red deep down
                    .setDepthPalette(
                            new float[]{1.00F, 0.75F, 0.35F},
                            new float[]{1.00F, 0.52F, 0.14F},
                            new float[]{0.92F, 0.34F, 0.07F},
                            new float[]{0.70F, 0.20F, 0.04F},
                            new float[]{0.40F, 0.10F, 0.02F})
                    .setLightRadius(12.0F)   // returns Tile, so it has to stay last in the chain
    );

    public static final Tile ACID = register(
            new LiquidTile("acid")
                    .setColor(0.35F, 0.85F, 0.12F, 0.78F)
                    .setFoam(0.85F, 1.0F, 0.45F, 1.0F)
                    .setNoFoam()
                    .setWarp(0.16F)
                    .setFlowSpeed(0.8F)
                    .setWaveHeight(0.02F)
                    .setViscosity(0.9F)
                    // 6 bands: bright toxic green fading to dark
                    .setDepthPalette(
                            new float[]{0.75F, 1.00F, 0.45F},
                            new float[]{0.55F, 0.95F, 0.30F},
                            new float[]{0.40F, 0.85F, 0.20F},
                            new float[]{0.28F, 0.68F, 0.14F},
                            new float[]{0.18F, 0.50F, 0.10F},
                            new float[]{0.10F, 0.32F, 0.06F})
    );

    public static final Tile SLUDGE = register(
            new LiquidTile("sludge")
                    .setColor(0.30F, 0.26F, 0.12F, 1.0F)
                    .setFoam(0.45F, 0.40F, 0.20F, 1.0F)
                    .setNoFoam()
                    .setWarp(0.10F)
                    .setFlowSpeed(0.25F)
                    .setWaveHeight(0.01F)
                    .setViscosity(5.0F)
                    // 4 bands: murky brown, gets dark quickly
                    .setDepthPalette(
                            new float[]{0.65F, 0.58F, 0.30F},
                            new float[]{0.50F, 0.42F, 0.20F},
                            new float[]{0.35F, 0.28F, 0.12F},
                            new float[]{0.20F, 0.15F, 0.06F})
    );

    public static final Tile TREE_SAPLING = register(
            new TreeTile("tree_sapling", TreeTile.TreeType.SAPLING)
    );
    public static final Tile TREE_MEDIUM = register(
            new TreeTile("tree_medium", TreeTile.TreeType.MEDIUM)
    );
    public static final Tile TREE_LARGE = register(
            new TreeTile("tree_large", TreeTile.TreeType.LARGE)
    );

    public static final Tile TREE = TREE_MEDIUM;

    static {
        for (DoorColor color : DoorColor.values()) {
            register(new DimensionalDoorTile(color, false));
            register(new DimensionalDoorTile(color, true).setPlaceable(false));
        }
    }

    // A 1x2x1 door standing in the middle of its cell. Closed, open-in and open-out are three tiles
    // (like the dimensional doors); only the closed one can be placed.

    public static final Tile PATH = register(new Tile("path").setPlaceable(false));

    // an entity, not a block: placing it spawns a rigid cube, see CubeTile. last, so no saved id changes
    public static final Tile WEIGHTED_CUBE = register(new CubeTile());

    // chess pieces, white then black, textures in textures/block/chess. after everything else, the palette is append-only
    static {
        for (boolean white : new boolean[]{true, false}) {
            for (ChessPieceTile.Type type : ChessPieceTile.Type.values()) {
                register(new ChessPieceTile(type, white));
            }
        }
    }

    // like the weighted cube an entity, not a block: placing it spawns a dice, see DiceTile. last, so no saved id changes
    public static final Tile DICE = register(new DiceTile());

    public static Tile byName(String name) {
        return BY_NAME.get(name);
    }

    public static List<Tile> getTiles() {
        return REGISTRY.entries();
    }

    public static List<Tile> getCycleOrder() {
        if (placeableTiles == null) {
            List<Tile> placeable = new ArrayList<>();
            for (Tile tile : REGISTRY.entries()) {
                if (tile.isPlaceable()) {
                    placeable.add(tile);
                }
            }
            placeableTiles = Collections.unmodifiableList(placeable);
        }
        return placeableTiles;
    }

    private static <T extends Tile> T register(T tile) {
        REGISTRY.register(tile);
        BY_NAME.put(tile.name, tile);
        return tile;
    }
}