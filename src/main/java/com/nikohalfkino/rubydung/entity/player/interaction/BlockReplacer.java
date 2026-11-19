package com.nikohalfkino.rubydung.entity.player.interaction;

import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.interfaces.hud.HandHud;
import com.nikohalfkino.rubydung.level.tiles.special.ChessPieceTile;
import com.nikohalfkino.rubydung.level.tiles.special.CubeTile;
import com.nikohalfkino.rubydung.level.tiles.special.ColoredDiceTile;
import com.nikohalfkino.rubydung.level.tiles.special.DiceTile;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.level.tiles.Tile;

import java.util.List;

import static com.nikohalfkino.rubydung.entity.player.interaction.InteractionContext.*;

/** replacing blocks (both mouse buttons held): swaps what the crosshair sweeps over for the selected tile */
final class BlockReplacer extends BlockAction {

    private final BlockPlacer placer;

    BlockReplacer(InteractionContext ctx, BlockPlacer placer) {
        super(ctx);
        this.placer = placer;
    }

    // one tick of holding both buttons, works like breaking: normal repeat rate standing still, while moving the distance travelled pays for extra replacements
    // so sweeping the crosshair over (or flying along) blocks replaces every one it passed over, not just the one it ends on
    @Override
    void tick(List<HitResult> hits, double speed) {
        tickBanked(hits, speed);
    }

    // skips gone hits and ones already the selected tile
    @Override
    protected boolean apply(HitResult hit) {
        return replaceAt(hit);
    }

    private boolean replaceAt(HitResult hit) {
        if (hit == null) {
            return false;
        }

        if (hit.overlayId >= 0) {
            return false; // replacing a tile that shares a cell: not supported (break it, then place)
        }

        if (!this.ctx.hasTarget(hit)) {
            return false; // already gone: there is nothing to replace
        }

        Tile selected = this.ctx.selectedTile;

        if (selected instanceof CubeTile || selected instanceof DiceTile || selected instanceof ColoredDiceTile || selected instanceof ChessPieceTile) {
            return false; // a weighted cube, a dice and a chess piece are bodies, never a block to swap in
        }

        if (selected instanceof LiquidTile) {
            // A liquid hit is already that liquid, nothing to swap. A block is replaced by the liquid.
            return !hit.liquid && replaceWithLiquid(hit, (LiquidTile) selected);
        }

        this.ctx.aim.aim(hit, hit.cellX, hit.cellY, hit.cellZ);
        int newData = this.placer.axisForFace(hit.face);
        this.ctx.aim.clear();

        // Already the same block: nothing to do.
        if (this.ctx.level.getTile(hit.x, hit.y, hit.z) == selected.id
                && this.ctx.level.getData(hit.x, hit.y, hit.z) == newData) {
            return false;
        }

        if (selected.isMultiTile()) {
            return replaceWithMultiTile(hit);
        }

        if (this.placer.intersectsPlayer(hit.x, hit.y, hit.z, newData)
                || !this.ctx.level.canSetHost(hit.x, hit.y, hit.z, selected, newData)) {
            return false;
        }

        this.ctx.level.setTile(hit.x, hit.y, hit.z, selected.id, newData);
        HandHud.swingHeld(HandHud.Action.PLACE);
        SoundEffects.playTilePlace(selected);
        return true;
    }

    // the targeted block (the whole object for a multi-tile one) is removed and its cell gets the liquid
    private boolean replaceWithLiquid(HitResult hit, LiquidTile liquid) {
        this.ctx.level.setTile(hit.x, hit.y, hit.z, 0);
        this.ctx.level.getLiquids().set(hit.x, hit.y, hit.z, liquid);
        HandHud.swingHeld(HandHud.Action.PLACE);
        SoundEffects.playTilePlace(liquid);
        return true;
    }

    // replaces the targeted block (or whole multi-tile object) with a multi-tile object anchored at the target's origin
    // the target is cleared first so its own cells count as free, if the new object still doesn't fit the original is put back
    private boolean replaceWithMultiTile(HitResult hit) {
        Tile selected = this.ctx.selectedTile;
        int oldId = this.ctx.level.getTile(hit.x, hit.y, hit.z);
        int oldData = this.ctx.level.getData(hit.x, hit.y, hit.z);
        int rotation = this.placer.placementDirection();

        this.ctx.level.setTile(hit.x, hit.y, hit.z, 0);
        rotation = selected.getMultiTileRotation(this.ctx.level, hit.x, hit.y, hit.z, rotation);

        if (!this.ctx.level.canPlaceTile(selected, hit.x, hit.y, hit.z, rotation)
                || this.placer.footprintIntersectsPlayer(hit.x, hit.y, hit.z, rotation)) {
            this.ctx.level.setTile(hit.x, hit.y, hit.z, oldId, oldData);
            return false;
        }

        this.ctx.level.setTile(hit.x, hit.y, hit.z, selected.id, rotation);
        HandHud.swingHeld(HandHud.Action.PLACE);
        SoundEffects.playTilePlace(selected);
        return true;
    }
}