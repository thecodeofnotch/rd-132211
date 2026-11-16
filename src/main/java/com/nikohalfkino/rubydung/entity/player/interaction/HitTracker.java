package com.nikohalfkino.rubydung.entity.player.interaction;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.TilePicker;

import java.util.ArrayList;
import java.util.List;

/** the current crosshair hit plus the queue of distinct hits since the last tick, so fast sweeps over blocks don't lose cells the crosshair passed over */
final class HitTracker {

    private static final int MAX_PENDING_HITS = 64;

    private final Level level;
    private final List<HitResult> pendingHits = new ArrayList<>();
    private HitResult hitResult;

    HitTracker(Level level) {
        this.level = level;
    }

    HitResult current() {
        return this.hitResult;
    }

    /** live view of the hits queued since the last drain (not a copy) */
    List<HitResult> pending() {
        return this.pendingHits;
    }

    void set(HitResult hitResult) {
        // Exact hit point, box and face on partial blocks (slabs, stairs), before anything uses the hit.
        if (hitResult != null && !hitResult.liquid) {
            TilePicker.refine(this.level, hitResult);
        }
        this.hitResult = hitResult;

        if (hitResult == null) {
            return;
        }
        if (!this.pendingHits.isEmpty() && sameHit(this.pendingHits.get(this.pendingHits.size() - 1), hitResult)) {
            return;
        }

        this.pendingHits.add(hitResult);
        if (this.pendingHits.size() > MAX_PENDING_HITS) {
            this.pendingHits.remove(0);
        }
    }

    List<HitResult> drain() {
        List<HitResult> hits = new ArrayList<>(this.pendingHits);
        this.pendingHits.clear();

        if (this.hitResult != null && (hits.isEmpty() || !sameHit(hits.get(hits.size() - 1), this.hitResult))) {
            hits.add(this.hitResult);
        }
        return hits;
    }

    private static boolean sameHit(HitResult a, HitResult b) {
        return a.x == b.x && a.y == b.y && a.z == b.z
                && a.cellX == b.cellX && a.cellY == b.cellY && a.cellZ == b.cellZ
                && a.face == b.face && a.overlayId == b.overlayId && a.liquid == b.liquid;
    }
}
