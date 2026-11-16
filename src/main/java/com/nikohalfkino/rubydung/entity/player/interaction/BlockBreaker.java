package com.nikohalfkino.rubydung.entity.player.interaction;

import com.nikohalfkino.rubydung.audio.SoundEffects;
import com.nikohalfkino.rubydung.interfaces.hud.HandHud;
import com.nikohalfkino.rubydung.level.SubTile;
import com.nikohalfkino.rubydung.level.SubTiles;
import com.nikohalfkino.rubydung.level.tiles.Tile;
import com.nikohalfkino.rubydung.entity.phys.AABB;

import java.util.ArrayList;
import java.util.List;

import static com.nikohalfkino.rubydung.entity.player.interaction.InteractionContext.*;

/** breaking blocks: single clicks, buffered clicks, hold-to-break and break-while-moving */
final class BlockBreaker extends BlockAction {

    // ticks a break click that found nothing to break is kept alive and retried
    private static final int BREAK_CLICK_BUFFER_TICKS = 3;

    private final HitTracker hitTracker;

    // ticks left to retry a break click that had no valid target when pressed
    private int bufferedBreakTicks = 0;

    BlockBreaker(InteractionContext ctx, HitTracker hitTracker) {
        super(ctx);
        this.hitTracker = hitTracker;
    }

    // left mouse button pressed
    void handleClick() {
        HandHud.swingCurrent(HandHud.Action.BREAK);

        // The hit result can be null or stale (already-broken cell) at the instant of the press,
        // e.g. on rapid clicks or right after a block was broken. Fall back to the hits seen
        // since the last tick, and if there is still nothing, keep the click alive for a few
        // ticks instead of dropping it.
        if (breakTargetedOrRecent()) {
            startBreakCooldown();
        } else {
            this.bufferedBreakTicks = BREAK_CLICK_BUFFER_TICKS;
            if (DEBUG_CLICKS) {
                System.err.println("[click] break: nothing to break yet, buffering click");
            }
        }
    }

    // retries a break click that had nothing to break when pressed, called before the held-button logic so it works even if the button was already released
    void retryBufferedClick(List<HitResult> hits) {
        if (this.bufferedBreakTicks <= 0) {
            return;
        }
        if (breakTargetedTile() || breakNewest(hits)) {
            startBreakCooldown();
        } else {
            this.bufferedBreakTicks--;
        }
    }

    // a tap that ended inside the chord grace period never got to act, so do it on release
    void breakOnRelease(List<HitResult> hits) {
        if (breakTargetedTile()) {
            return;
        }
        breakNewest(hits);
    }

    @Override
    void tick(List<HitResult> hits, double speed) {
        tickBanked(hits, speed);
    }

    // held button: keep the running swing going instead of restarting it for every block
    @Override
    protected void onHeldDone(int count) {
        HandHud.swingHeld(HandHud.Action.BREAK);
    }

    @Override
    protected boolean apply(HitResult hit) {
        if (!stillThere(hit)) {
            return false; // already gone
        }
        breakAt(hit);
        return true;
    }

    // Internals

    // starts the repeat timer so tick() doesn't break a second block a few ticks later
    private void startBreakCooldown() {
        this.bufferedBreakTicks = 0;
        startCooldown();
    }

    private boolean breakTargetedTile() {
        HitResult hit = this.hitTracker.current();
        if (hit != null && stillThere(hit)) {
            breakAt(hit);
            return true;
        }
        return false;
    }

    // breaks the newest hit in the list that still has a block in it
    private boolean breakNewest(List<HitResult> hits) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (sweep(hits.subList(i, i + 1), 1) == 1) {
                return true;
            }
        }
        return false;
    }

    private boolean breakTargetedOrRecent() {
        return breakTargetedTile() || breakNewest(this.hitTracker.pending());
    }

    // a block (or sub-tile) is still in the targeted cell, or for a liquid hit the liquid is
    private boolean stillThere(HitResult hit) {
        if (hit.liquid) {
            return this.ctx.level.getLiquids().has(hit.x, hit.y, hit.z);
        }
        return this.ctx.hasTarget(hit);
    }

    private void breakAt(HitResult hit) {
        if (hit.liquid) {
            // The player holds this liquid's tile: breaking takes the liquid out of the cell.
            SoundEffects.playTileBreak(this.ctx.selectedTile);
            this.ctx.level.getLiquids().clear(hit.x, hit.y, hit.z);
            return;
        }
        breakPart(hit);
        fillFreedHalf(hit.x, hit.y, hit.z);
    }

    private void breakPart(HitResult hit) {
        // the sound belongs to the tile being broken, looked up before it is removed
        SoundEffects.playTileBreak(brokenTile(hit));
        if (hit.overlayId >= 0) {
            // A tile sharing the cell: remove just that one; the cell's normal tile stays.
            this.ctx.level.getSubTiles().remove(hit.x, hit.y, hit.z, hit.overlayId);
            return;
        }
        Tile tile = Tile.byId(this.ctx.level.getTile(hit.x, hit.y, hit.z));
        if (tile != null) {
            int result = tile.getBreakResult(this.ctx.level, hit.x, hit.y, hit.z, hit.hitBox);
            if (result >= 0) {
                // Partial break: downgrade to surviving state (e.g. double slab -> single half).
                this.ctx.level.setTile(hit.x, hit.y, hit.z, tile.id, result);
                return;
            }
        }
        this.ctx.level.setTile(hit.x, hit.y, hit.z, 0);
    }

    // the tile a break hit is about: the sub-tile sharing the cell, or the cell's own tile
    private Tile brokenTile(HitResult hit) {
        if (hit.overlayId >= 0) {
            for (SubTile sub : this.ctx.level.getSubTiles().at(hit.x, hit.y, hit.z)) {
                if (sub.id == hit.overlayId) {
                    return Tile.byId(sub.tileId);
                }
            }
            return null;
        }
        return Tile.byId(this.ctx.level.getTile(hit.x, hit.y, hit.z));
    }

    // a block placed into the free half of a slab's cell (a clipped sub-tile) is only a half block because the slab was in the way
    // once the slab is gone it grows to fill the cell: back to a normal tile if nothing else is in there, otherwise a full-height sub-tile
    // left alone if the player is standing in the freed half
    private void fillFreedHalf(int x, int y, int z) {
        SubTiles store = this.ctx.level.getSubTiles();
        for (SubTile sub : new ArrayList<>(store.at(x, y, z))) {
            if (SubTile.clipOf(sub.data) == 0) {
                continue;
            }
            Tile tile = Tile.byId(sub.tileId);
            if (tile == null) {
                continue;
            }
            int base = SubTile.baseData(sub.data);
            List<AABB> full = tile.getOccupancyBoxes(x, y, z, base);

            List<AABB> others = new ArrayList<>(store.hostOccupancy(x, y, z));
            for (SubTile other : store.at(x, y, z)) {
                if (other.id != sub.id) {
                    others.addAll(store.occupancy(other, x, y, z));
                }
            }
            if (SubTiles.overlapsAny(full, others)) {
                continue;                       // something still sits in the other half
            }
            boolean hitsPlayer = false;
            for (AABB box : full) {
                hitsPlayer |= box.intersects(this.ctx.player.boundingBox);
            }
            if (hitsPlayer) {
                continue;
            }

            store.remove(x, y, z, sub.id);
            if (this.ctx.level.getTile(x, y, z) == 0 && !store.has(x, y, z) && sub.ox == 0.0F && sub.oy == 0.0F && sub.oz == 0.0F) {
                this.ctx.level.setTile(x, y, z, sub.tileId, base);
            } else {
                store.add(x, y, z, sub.tileId, base, sub.ox, sub.oy, sub.oz);
            }
        }
    }
}