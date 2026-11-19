package com.nikohalfkino.rubydung.entity.player.interaction;

import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.interfaces.hud.HandHud;
import com.nikohalfkino.rubydung.level.SubTilePlacement;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.common.RotatableTile;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.level.tiles.special.CubeTile;
import com.nikohalfkino.rubydung.level.tiles.special.ColoredDiceTile;
import com.nikohalfkino.rubydung.level.tiles.special.DiceTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.entity.phys.AABB;
import com.nikohalfkino.rubydung.util.KeyBinding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.nikohalfkino.rubydung.entity.player.interaction.InteractionContext.*;

/**
 * placing blocks: single clicks, hold-to-build with a paced budget, drag-building along a row, and extending a column while flying along it
 *
 * placing and breaking logic from https://mojang.github.io/bedrock-protocol-docs/guides/build-action-simulation-rate/
 */
final class BlockPlacer extends BlockAction {

    private static final double TICKS_PER_SECOND = 20.0;
    private static final double TICK_MS = 1000.0 / TICKS_PER_SECOND;

    private static final double BUILD_DELAY_STILL_MS = 200.0;
    private static final double BUILD_DELAY_SNEAK_MS = 300.0;
    private static final double BUILD_DELAY_MOVING_MAX_MS = 180.0;
    private static final double BUILD_DELAY_MOVING_FACTOR = 900.0;

    // how far (in blocks) from the eyes the NEXT block of a flown-along column may be, kept inside the normal pick reach so a column never races ahead of the player
    private static final double EXTEND_REACH = 4.0;
    private static final double MAX_COLUMN_BANK = 3.0;

    private static final int[][] FACE_NORMALS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    private double buildBudget = 1.0;

    // cell the last block of the current drag-build went into (see extendBuildColumn)
    private int lastPlacedX, lastPlacedY, lastPlacedZ;

    // height flown along the column being built that hasn't been paid out as blocks yet
    private double columnBank = 0.0;

    // true while tick() (place button held) is running, false for a fresh click
    private boolean holding = false;

    // horizontal unit vector the player looks along this tick, from the crosshair hit (it lies on the look ray). only meaningful while hasLook is true
    private double lookX, lookZ;
    private boolean hasLook = false;

    private boolean buildActive = false;
    private int buildFace;
    private int buildPlane;
    private final Set<Long> placedCells = new HashSet<>();

    BlockPlacer(InteractionContext ctx) {
        super(ctx);
    }

    // right mouse button pressed: a fresh placement, not part of any drag-build
    void handleClick(HitResult hit) {
        this.holding = false;
        this.buildBudget = 1.0;
        clearBuildLine();
        if (this.ctx.ragdollSelected) {
            // a ragdoll, not the selected tile. one per press: PlayerInteraction.tick does not place another while the button is held
            if (placeRagdoll(hit)) {
                this.ctx.ragdollPlaced = true;
            }
            return;
        }
        placeAgainst(hit);
    }

    @Override
    void reset() {
        super.reset();
        this.buildBudget = 1.0;
        clearBuildLine();
    }

    // Shared with BlockReplacer

    // direction (0..3) the player faces, used as the rotation of multi-tile objects
    int placementDirection() {
        int rot = (int) Math.floor((this.ctx.player.yRotation * 4.0F / 360.0F) + 0.5D) & 3;
        return (4 - rot) & 3;
    }

    // axis data for rotatable tiles from the face they were placed against (0 = Y, 1 = Z, 2 = X)
    int axisForFace(int face) {
        Tile selected = this.ctx.selectedTile;
        if (!(selected instanceof RotatableTile)) {
            double yaw = Math.toRadians(this.ctx.player.yRotation);
            double lookX = Math.sin(yaw);
            double lookZ = Math.cos(yaw);
            return selected.getPlacementData(face, this.ctx.aim.fx, this.ctx.aim.fy, this.ctx.aim.fz,
                    lookX, lookZ);
        }

        if (face == 2 || face == 3) return 1;
        if (face == 4 || face == 5) return 2;
        return 0;
    }

    boolean intersectsPlayer(int x, int y, int z, int data) {
        Tile selected = this.ctx.selectedTile;
        List<AABB> boxes = selected.getCollisionBoxes(x, y, z, data);
        if (boxes.isEmpty() && selected.getAABB(x, y, z) == null) {
            // Keep the old behaviour: a tile without a box counts as filling the cell.
            boxes = java.util.Collections.singletonList(new AABB(x, y, z, x + 1, y + 1, z + 1));
        }

        for (AABB box : boxes) {
            if (box.intersects(this.ctx.player.boundingBox)) {
                return true;
            }
            // other players of a multiplayer game are solid for building too (unless they walk through blocks)
            for (com.nikohalfkino.rubydung.net.RemotePlayer other : Multiplayer.remotes().all()) {
                if (!other.isNoClip() && box.intersects(other.boundingBox())) {
                    return true;
                }
            }
        }
        return false;
    }

    // whether any cell the selected tile would occupy overlaps the player (rotation matters for non-square footprints)
    boolean footprintIntersectsPlayer(int x, int y, int z, int rotation) {
        Tile selected = this.ctx.selectedTile;
        int sizeX = selected.getSizeX(rotation);
        int sizeY = selected.getSizeY();
        int sizeZ = selected.getSizeZ(rotation);

        for (int cx = x; cx < x + sizeX; cx++) {
            for (int cy = y; cy < y + sizeY; cy++) {
                for (int cz = z; cz < z + sizeZ; cz++) {
                    if (intersectsPlayer(cx, cy, cz, rotation)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Placing

    private boolean placeTile(int x, int y, int z, int face) {
        Tile selected = this.ctx.selectedTile;
        if (selected.isMultiTile()) {
            int rotation = selected.getMultiTileRotation(this.ctx.level, x, y, z, placementDirection());
            if (!this.ctx.level.canPlaceTile(selected, x, y, z, rotation)
                    || footprintIntersectsPlayer(x, y, z, rotation)) {
                return false;
            }

            this.ctx.level.setTile(x, y, z, selected.id, rotation);
            return true;
        }

        int data = axisForFace(face);
        if (intersectsPlayer(x, y, z, data) || !this.ctx.level.canSetHost(x, y, z, selected, data)) {
            return false;
        }

        this.ctx.level.setTile(x, y, z, selected.id, data);
        return true;
    }

    // ms before the next block may be placed
    private double placeDelayMs(double speed) {
        boolean sneaking = KeyBinding.SNEAK.isDown() && !this.ctx.player.flying && !this.ctx.player.noClip;
        if (sneaking) {
            return BUILD_DELAY_SNEAK_MS; // while flying, sneak is the descend key, not a slow-down
        }
        if (speed > MOVING_EPSILON) {
            double blocksPerSecond = speed * TICKS_PER_SECOND;
            return Math.min(BUILD_DELAY_MOVING_MAX_MS, BUILD_DELAY_MOVING_FACTOR / blocksPerSecond);
        }
        return BUILD_DELAY_STILL_MS;
    }

    // places a block against the hit face, costs one block of the budget. in order:
    // 1. complete a half slab of the same kind (the clicked cell first, then the cell next to it)
    // 2. if the tile doesn't fill its cell, put it INTO the free space of an occupied cell (the clicked cell when the face is inside it, like the top of a bottom slab, or the occupied cell next to the face), pushed flush against the clicked surface, see SubTilePlacement
    // 3. otherwise ordinary placement into the empty cell next to the face
    private boolean placeAgainst(HitResult hit) {
        Tile selected = this.ctx.selectedTile;
        PlacementAim aim = this.ctx.aim;

        // Relative to the cell that was actually hit, not the origin of a multi-tile object.
        int[] normal = FACE_NORMALS[hit.face];
        int x = hit.cellX + normal[0];
        int y = hit.cellY + normal[1];
        int z = hit.cellZ + normal[2];

        if (!this.ctx.inWorld(x, y, z)) {
            return false;
        }

        // A weighted cube is a body, not a block: it is dropped into the cell, aligned to the grid.
        if (selected instanceof CubeTile) {
            return placeCube(hit, x, y, z, CubeManager.KIND_CUBE);
        }

        // A dice is the same, a cube that shows a number after it has been thrown. the red and blue ones are see-through.
        if (selected instanceof DiceTile) {
            return placeCube(hit, x, y, z, CubeManager.KIND_DICE);
        }
        if (selected instanceof ColoredDiceTile) {
            return placeCube(hit, x, y, z, CubeManager.kindOfDice(((ColoredDiceTile) selected).getColor()));
        }

        // A chess piece is an entity too: it stands on the square, aligned to the grid.
        if (selected instanceof ChessPieceTile) {
            return placeChessPiece(hit, x, y, z);
        }

        // An entity standing in the cell fills it like a sprite tile does.
        if (this.ctx.level.getCubeManager().pieceAt(x, y, z) != null) {
            return false;
        }

        // 0. A liquid goes in the liquid layer of the cell next to the face, next to whatever block is there
        // (a slab, a stair...). a full block in the cell refuses it. the player never blocks it.
        if (selected instanceof LiquidTile) {
            boolean placed = this.ctx.level.getLiquids().set(x, y, z, (LiquidTile) selected);
            if (placed) {
                notePlaced(hit, x, y, z);
            }
            return placed;
        }

        // 1. Complete a half slab.
        aim.aim(hit, x, y, z);
        boolean merged;
        try {
            merged = selected.tryMergePlacement(this.ctx.level, hit, aim.fx, aim.fy, aim.fz);
        } finally {
            aim.clear();
        }
        if (merged) {
            notePlaced(hit, x, y, z);
            return true;
        }

        // 2. Share a cell that already has something in it.
        if ((selected.canShareCell() || selected.canClipToHalf()) && hit.hasPoint) {
            int[] cell = SubTilePlacement.targetCell(hit);
            boolean insideClickedCell = cell[0] == hit.cellX && cell[1] == hit.cellY && cell[2] == hit.cellZ;
            boolean nextCellTaken = !insideClickedCell && this.ctx.inWorld(cell[0], cell[1], cell[2])
                    && (this.ctx.level.getTile(cell[0], cell[1], cell[2]) != 0
                    || this.ctx.level.getSubTiles().has(cell[0], cell[1], cell[2]));

            if ((insideClickedCell || nextCellTaken) && this.ctx.inWorld(cell[0], cell[1], cell[2])) {
                SubTilePlacement.Plan plan;
                aim.aim(hit, cell[0], cell[1], cell[2]);
                try {
                    plan = SubTilePlacement.plan(this.ctx.level, hit, selected,
                            axisForFace(hit.face), cell, this.ctx.player.boundingBox);
                } finally {
                    aim.clear();
                }
                if (plan != null) {
                    plan.commit(this.ctx.level, selected);
                    notePlaced(hit, x, y, z);
                    return true;
                }
            }
        }

        // 3. Ordinary placement into the empty cell next to the face.
        if (this.ctx.level.getTile(x, y, z) != 0) {
            return false; // occupied, and nothing could be merged or tucked in
        }

        boolean placed;
        aim.aim(hit, x, y, z);
        try {
            placed = placeTile(x, y, z, hit.face);
        } finally {
            aim.clear();
        }
        if (!placed) {
            return false;
        }

        notePlaced(hit, x, y, z);
        return true;
    }

    // puts a weighted cube (or a dice) into the free cell (x, y, z). joined to a server it is asked of the server, which puts it there for everybody (it shows up when the server says so)
    private boolean placeCube(HitResult hit, int x, int y, int z, int kind) {
        if (!spawnBody(x, y, z, kind)) {
            return false;
        }
        notePlaced(hit, x, y, z);
        return true;
    }

    // the place-ragdoll key: a ragdoll into the free cell next to the face under the crosshair, turned to face the player. it is not a tile, so it takes nothing from the build budget and starts no drag-build
    boolean placeRagdoll(HitResult hit) {
        int[] normal = FACE_NORMALS[hit.face];
        int x = hit.cellX + normal[0];
        int y = hit.cellY + normal[1];
        int z = hit.cellZ + normal[2];
        if (!this.ctx.inWorld(x, y, z) || !spawnBody(x, y, z, CubeManager.KIND_RAGDOLL)) {
            return false;
        }
        HandHud.swingCurrent(HandHud.Action.PLACE);
        SoundEffects.play("phys_put_down");
        return true;
    }

    // asks for the body (CubeManager.KIND_*) to be put into the cell: alone it is done here, joined to a server the server is asked
    private boolean spawnBody(int x, int y, int z, int kind) {
        CubeManager cubes = this.ctx.level.getCubeManager();
        if (Multiplayer.isClient()) {
            if (!cubes.canSpawnIn(x, y, z, this.ctx.player, kind)) {
                return false;
            }
            Multiplayer.cubeSpawn(x, y, z, kind);
            return true;
        }
        return cubes.spawnIn(x, y, z, this.ctx.player, kind, this.ctx.player.x, this.ctx.player.z) != null;
    }

    // stands the selected chess piece on the free cell (x, y, z). setting the tile is what puts it there (Level.setTile), joined to a server it asks the server, which stands it there for everybody
    private boolean placeChessPiece(HitResult hit, int x, int y, int z) {
        if (!this.ctx.level.getCubeManager().canSpawnPieceIn(x, y, z, this.ctx.player)) {
            return false;
        }
        this.ctx.level.setTile(x, y, z, this.ctx.selectedTile.id, 0);
        notePlaced(hit, x, y, z);
        return true;
    }

    // bookkeeping after any successful placement: spends the budget and extends the drag-build line
    private void notePlaced(HitResult hit, int x, int y, int z) {
        if (this.holding) {
            HandHud.swingHeld(HandHud.Action.PLACE);       // held: let the swing play out
        } else {
            HandHud.swingCurrent(HandHud.Action.PLACE);    // click: cancels the running swing
        }
        SoundEffects.playTilePlace(this.ctx.selectedTile);
        this.buildBudget = Math.max(0.0, this.buildBudget - 1.0);
        this.ctx.acted = true;

        this.buildActive = true;
        this.buildFace = hit.face;
        this.buildPlane = axisValue(x, y, z, hit.face);
        this.placedCells.add(cellKey(x, y, z));
        this.lastPlacedX = x;
        this.lastPlacedY = y;
        this.lastPlacedZ = z;
    }

    private void clearBuildLine() {
        this.buildActive = false;
        this.columnBank = 0.0;
        this.placedCells.clear();
    }

    private static long cellKey(int x, int y, int z) {
        return (x & 0xFFFFFL) | ((z & 0xFFFFFL) << 20) | ((y & 0xFFFFFL) << 40);
    }

    private static int axisValue(int x, int y, int z, int face) {
        int[] normal = FACE_NORMALS[face];
        return normal[0] != 0 ? x : normal[1] != 0 ? y : z;
    }

    private boolean isStrayHitOnOwnRow(HitResult hit) {
        if (!this.buildActive || !this.placedCells.contains(cellKey(hit.cellX, hit.cellY, hit.cellZ))) {
            return false;
        }

        int[] normal = FACE_NORMALS[hit.face];
        int[] target = {hit.cellX + normal[0], hit.cellY + normal[1], hit.cellZ + normal[2]};
        int plane = axisValue(target[0], target[1], target[2], hit.face);
        if (hit.face == this.buildFace && plane == this.buildPlane) {
            return false;
        }

        // Growing the row toward the player is only wanted while they back away from it,
        // not while they strafe along it (which used to place extra blocks in front of the row).
        return !(hit.face == this.buildFace
                && inPlayerColumn(target, hit.face)
                && movingAlongFace(hit.face));
    }

    // whether the player moves this tick in the direction the face points (away from the block)
    // horizontal faces go by the keys held, not the raw position change: only forward/back count so strafing along a row never reads as backing away, however far off-square the player stands. the actual movement must still agree, so a back key held against an obstacle doesn't count
    private boolean movingAlongFace(int face) {
        int[] n = FACE_NORMALS[face];
        Player p = this.ctx.player;
        double along = (p.x - p.prevX) * n[0] + (p.y - p.prevY) * n[1] + (p.z - p.prevZ) * n[2];
        if (along <= MOVING_EPSILON) {
            return false;
        }
        if (n[1] != 0) {
            return true; // vertical faces: the fly up/down movement itself is what counts
        }
        return keyAlongFace(n) > 0.0;
    }

    // how much the held forward/back keys push the player along the face normal: positive = away from the block, negative = toward it, 0 = none (or both). strafe keys contribute nothing
    private double keyAlongFace(int[] normal) {
        if (!this.hasLook) {
            return 0.0;
        }
        double forward = (KeyBinding.FORWARD.isDown() ? 1.0 : 0.0) - (KeyBinding.BACK.isDown() ? 1.0 : 0.0);
        return forward * (this.lookX * normal[0] + this.lookZ * normal[2]);
    }

    // horizontal look direction from the newest hit (the one under the crosshair now)
    private void updateLook(List<HitResult> hits) {
        this.hasLook = false;
        if (hits.isEmpty()) {
            return;
        }

        HitResult hit = hits.get(hits.size() - 1);
        double hx = hit.hasPoint ? hit.hitX : hit.cellX + 0.5;
        double hz = hit.hasPoint ? hit.hitZ : hit.cellZ + 0.5;
        double dx = hx - this.ctx.player.x;
        double dz = hz - this.ctx.player.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3) {
            return; // looking (almost) straight up or down: no horizontal direction
        }

        this.lookX = dx / len;
        this.lookZ = dz / len;
        this.hasLook = true;
    }

    private boolean inPlayerColumn(int[] cell, int face) {
        AABB box = this.ctx.player.boundingBox;
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};
        int skip = axisValue(0, 1, 2, face);

        for (int axis = 0; axis < 3; axis++) {
            if (axis == skip) {
                continue;
            }
            if (cell[axis] < (int) Math.floor(min[axis]) || cell[axis] > (int) Math.floor(max[axis] - 1.0E-6)) {
                return false;
            }
        }
        return true;
    }

    // one tick of holding the place button
    @Override
    void tick(List<HitResult> hits, double speed) {
        this.holding = true;
        try {
            tickHeld(hits, speed);
        } finally {
            this.holding = false;
        }
    }

    private void tickHeld(List<HitResult> hits, double speed) {
        double perTick = TICK_MS / placeDelayMs(speed);
        this.buildBudget = Math.min(this.buildBudget + perTick, 1.0 + perTick);

        // the budget pays for whole blocks: each placement spends one (see notePlaced)
        updateLook(hits);
        sweep(hits, (int) Math.floor(this.buildBudget));

        // Flying up (or down) along a column that is being built extends it by distance, not by the
        // placing delay: one block for every block of height the player has climbed, so the column
        // keeps pace with the player however fast they fly. Works whatever the crosshair is on.
        if (this.buildActive && (this.buildFace == Tile.FACE_TOP || this.buildFace == Tile.FACE_BOTTOM)) {
            double dy = this.ctx.player.y - this.ctx.player.prevY;
            boolean along = this.buildFace == Tile.FACE_TOP ? dy > 0.0 : dy < 0.0;
            if (along) {
                this.columnBank = Math.min(this.columnBank + Math.abs(dy), MAX_COLUMN_BANK);
            }
            while (this.columnBank >= 1.0 && extendBuildColumn()) {
                this.columnBank -= 1.0;
            }
        } else {
            this.columnBank = 0.0;
        }
    }

    // a hit on the row being built is not placed against again, see isStrayHitOnOwnRow
    @Override
    protected boolean apply(HitResult hit) {
        return !isStrayHitOnOwnRow(hit) && placeAgainst(hit);
    }

    // while a vertical drag-build is active (placed against a top or bottom face) and the player flies along it (up for a top face, down for a bottom face), places the next block of the column whatever the crosshair is on
    // the movement asks for it, so standing next to a column never grows it
    private boolean extendBuildColumn() {
        if (!this.buildActive || (this.buildFace != Tile.FACE_TOP && this.buildFace != Tile.FACE_BOTTOM)) {
            return false;
        }

        double dy = this.ctx.player.y - this.ctx.player.prevY;
        boolean growsUp = this.buildFace == Tile.FACE_TOP;
        if (growsUp ? dy <= MOVING_EPSILON : dy >= -MOVING_EPSILON) {
            return false; // not moving along the column
        }

        int[] n = FACE_NORMALS[this.buildFace];
        int cx = this.lastPlacedX, cy = this.lastPlacedY, cz = this.lastPlacedZ;
        if (this.ctx.level.getTile(cx, cy, cz) == 0 && !this.ctx.level.getSubTiles().has(cx, cy, cz)) {
            // The last action merged into the cell behind it (completing a slab): continue from there.
            cx -= n[0];
            cy -= n[1];
            cz -= n[2];
        }
        if (!this.ctx.inWorld(cx, cy, cz)) {
            return false;
        }

        // Measure to the cell that would be filled next, not to the last block placed.
        double rx = cx + n[0] + 0.5 - this.ctx.player.x;
        double ry = cy + n[1] + 0.5 - this.ctx.player.y;
        double rz = cz + n[2] + 0.5 - this.ctx.player.z;
        if (rx * rx + ry * ry + rz * rz > EXTEND_REACH * EXTEND_REACH) {
            return false;
        }

        // Act as if the end face of the column's last block had been clicked dead centre.
        HitResult along = new HitResult(cx, cy, cz, 0, this.buildFace, cx, cy, cz);
        along.withPoint(cx + 0.5 + n[0] * 0.5, cy + 0.5 + n[1] * 0.5, cz + 0.5 + n[2] * 0.5, null);
        return placeAgainst(along);
    }
}