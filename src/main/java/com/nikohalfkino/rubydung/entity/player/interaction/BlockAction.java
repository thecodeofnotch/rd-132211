package com.nikohalfkino.rubydung.entity.player.interaction;

import java.util.List;

import static com.nikohalfkino.rubydung.entity.player.interaction.InteractionContext.*;

/**
 * what breaking, placing and replacing have in common: a held button works through the hits the crosshair swept over since the last tick (sweep), as many per tick as the pacing allows
 *
 * the pacing is the part that differs. breaking and replacing share the repeat timer plus the distance bank (tickBanked): the normal repeat rate standing still, while moving the distance travelled pays for extra blocks. placing keeps its own time budget, see BlockPlacer.tick
 *
 * a subclass says what one hit does (apply) and what a held tick is (tick)
 */
abstract class BlockAction {

    protected final InteractionContext ctx;

    // distance travelled that pays for extra actions while moving
    private double bank = 0.0;

    BlockAction(InteractionContext ctx) {
        this.ctx = ctx;
    }

    /** one tick of the button being held */
    abstract void tick(List<HitResult> hits, double speed);

    /** does the action on one hit. true if it did something (a hit that is already gone, or already what it should be, is not an action) */
    protected abstract boolean apply(HitResult hit);

    /** the hold is over (button released, a cube picked up...): forgets the distance that was saved up */
    void reset() {
        this.bank = 0.0;
    }

    /** runs after a held tick that did something, before the timer is restarted. for the hand swing */
    protected void onHeldDone(int count) {
    }

    /**
     * the hits in order, until allowance of them did something. a hit that did nothing does not use up the allowance. returns how many did something
     */
    protected final int sweep(List<HitResult> hits, int allowance) {
        int done = 0;
        for (HitResult hit : hits) {
            if (done >= allowance) {
                break;
            }
            if (hit != null && apply(hit)) {
                done++;
            }
        }
        return done;
    }

    /**
     * the repeat pacing of breaking and replacing. standing still: one action per BREAK_DELAY_TICKS. moving must never be slower than that, so the distance travelled is banked and pays for extra actions, the remainder is kept
     */
    protected final void tickBanked(List<HitResult> hits, double speed) {
        boolean moving = speed > MOVING_EPSILON;

        int allowance = this.ctx.actionCooldown <= 0 ? 1 : 0;
        if (moving) {
            this.bank = Math.min(this.bank + speed, MAX_BREAKS_PER_TICK);
            allowance = Math.max(allowance, (int) this.bank);
        }

        if (this.ctx.actionCooldown > 0) {
            this.ctx.actionCooldown--;
        }
        if (allowance <= 0) {
            return;
        }

        int done = sweep(hits, allowance);
        if (done > 0) {
            onHeldDone(done);
            this.ctx.acted = true;
            this.ctx.actionCooldown = BREAK_DELAY_TICKS - 1;
            if (moving) {
                this.bank = Math.max(0.0, this.bank - done);
            }
        }
    }

    /** starts the repeat timer after a click acted, so the next held tick does not act again at once, and empties the bank */
    protected final void startCooldown() {
        this.ctx.acted = true;
        this.ctx.actionCooldown = BREAK_DELAY_TICKS - 1;
        this.bank = 0.0;
    }
}
