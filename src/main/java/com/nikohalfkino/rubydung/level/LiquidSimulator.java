package com.nikohalfkino.rubydung.level;

import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;

import java.util.BitSet;

/**
 * moves the liquid of a LiquidLayer around. a cell holds a liquid and a fill (1..MAX_FILL):
 *
 *   fill MAX_FILL (8)  a SOURCE. permanent: it never changes by itself, only a player (or a block) removes it
 *   fill 1..7          FLOWING liquid. it only exists because something feeds it, and it goes away when that stops
 *
 * nothing is conserved, so there is no volume to pile up: liquid that runs into an ocean just meets sources and
 * stops, it can never raise the ocean. that is the whole point of this model (it works like minecraft's)
 *
 * every cell works out for itself what it should hold, from its neighbours only ("pull", no pushing):
 *   - a liquid cell above feeds this cell: it holds FALL_LEVEL (7), a falling stream
 *   - a side neighbour feeds this cell one level less than it holds (a source offers 7, a level 5 offers 4...), so a
 *     source reaches 7 cells over flat ground. a flowing cell only spreads sideways when it rests on something
 *     solid: over a hole (air, or more of its own liquid) it is a falling stream and just falls
 *   - nothing feeds it: it is empty
 *   - liquids never mix: a cell keeps the liquid it has, and only an empty cell can be taken by another one
 *   - an INFINITE liquid (LiquidTile.isInfinite, water) with 2 or more source neighbours on its sides, resting on a
 *     solid block or on a source, becomes a source itself. so a hole dug into an ocean fills up again, and a
 *     pond made of 2 sources never runs dry
 *   - flowing liquid is never put on top of a source of the same liquid (no skin of water over the ocean). a falling
 *     stream may still end on one, it is just one cell that goes away with the stream
 *   every feed goes down in height or down in level, never in a circle, so when a source is taken away the liquid
 *   it fed always drains completely (it can't keep itself alive)
 *
 * EVENT DRIVEN: nothing is ever scanned. a cell is looked at only when it is woken: when its own liquid changed,
 * when a neighbour's did, or when LiquidLayer.wake is called (Level calls it for every block placed or removed). a cell
 * that changes wakes its neighbours, so a change spreads outwards as a wave and then dies out. a world at rest
 * costs nothing: no active cells, update() returns at once
 *
 * COST IS BOUNDED: per tick at most getMaxStepsPerTick() cells are looked at, and one update() (one frame) never
 * spends more than FRAME_BUDGET_NANOS in total. what doesn't fit simply stays queued for the next frame
 *
 * thick liquids (LiquidTile.getViscosity) just act less often: every round(viscosity) ticks
 *
 * THE OCEAN is not stored per cell, so the raw cells / fills arrays can't see it. every read here goes through
 * LiquidLayer.typeAt / fillAt, which answer for stored liquid and ocean alike. plain ocean cells are inexhaustible
 * sources: they never lose anything, and they only feed the cells of their own region (holes and partial cells), so a hole
 * dug in the sea fills up again and the sea never floods the land beside it. liquid poured onto the sea joins it
 *
 * FINITE MODE (isFinite, on by default, switch with `liquid finite`): everything above describes the infinite model.
 * in finite mode liquid is conserved and fill 8 is just a full cell, not a source. a liquid cell pushes its units
 * to the cell below while that has room, then evens itself out with lower side neighbours (a move only happens when
 * the difference is 2 or more, so a surface rests with steps of at most 1 and never oscillates). liquids still never mix.
 * liquid in a cell that gets a full block is moved to a free neighbour (above first), only what doesn't fit is lost
 */
public final class LiquidSimulator {

    private static volatile boolean enabled = true;
    private static volatile float ticksPerSecond = 10.0F;
    private static volatile int maxStepsPerTick = 20000;
    private static volatile boolean finite = true;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean on) { enabled = on; }
    public static float getTicksPerSecond() { return ticksPerSecond; }
    public static void setTicksPerSecond(float tps) { ticksPerSecond = tps; }

    /**
     * FINITE mode: liquid is conserved. nothing is a permanent source, a cell just holds 0..MAX_FILL units and units
     * move between cells (down first, then sideways towards lower neighbours). digging a hole in an ocean takes the
     * water from the ocean, and a bucket of water poured out stays a bucket of water.
     * off = the old minecraft-like model (permanent sources, flowing liquid that drains)
     */
    public static boolean isFinite() { return finite; }
    public static void setFinite(boolean on) { finite = on; }

    /** most cells looked at in one tick (the rest wait in the queue) */
    public static int getMaxStepsPerTick() { return maxStepsPerTick; }
    public static void setMaxStepsPerTick(int cells) { maxStepsPerTick = Math.max(1, cells); }

    /** @deprecated bodies aren't analysed any more (sources decide what is infinite), kept so callers still compile */
    @Deprecated
    public static int getInfiniteBodySize() { return 0; }

    /** @deprecated see getInfiniteBodySize, does nothing */
    @Deprecated
    public static void setInfiniteBodySize(int cells) { }

    /** @deprecated nothing is swept near the player any more, does nothing */
    @Deprecated
    public void setFocus(int x, int y, int z) { }

    private static final int MAX_TICKS_PER_UPDATE = 3;
    private static final long FRAME_BUDGET_NANOS = 3_000_000L;     // 3 ms of simulation per frame at most

    private static final int SOURCE = LiquidLayer.MAX_FILL;
    private static final int FALL_LEVEL = SOURCE - 1;

    // side neighbours, in the order of `sides`: -x, +x, -z, +z
    private static final int[] SIDE_DX = {-1, 1, 0, 0};
    private static final int[] SIDE_DZ = {0, 0, -1, 1};

    private final LiquidLayer layer;
    private final byte[] cells;
    private final byte[] fills;
    private final int width, depth, height;
    private final int layerSize;       // cells in one horizontal layer (the index step for one block up)

    // the cells waiting to be looked at: a FIFO ring (power of two size) and a bit per cell so none is queued twice
    private final BitSet queued = new BitSet();
    private int[] queue = new int[1 << 12];
    private int head, size;

    private float accumulator;
    private long tickCount;
    private boolean lastFinite = finite;        // to notice a mode switch

    private final int[] sides = new int[4];     // scratch: indices of the 4 side neighbours, -1 outside the level

    LiquidSimulator(LiquidLayer layer, byte[] cells, byte[] fills, int width, int depth, int height) {
        this.layer = layer;
        this.cells = cells;
        this.fills = fills;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.layerSize = width * height;
    }

    // what a cell really holds, ocean included (the raw arrays only hold what differs from plain ocean)
    private byte type(int i) {
        return this.layer.typeAt(i);
    }

    private int fill(int i) {
        return this.layer.fillAt(i);
    }

    // ------------------------------------------------------------------------------------------------
    // waking

    int activeCount() {
        return this.size;
    }

    /** looks at the cell and its 6 neighbours on the next tick, if there is any liquid around to react to */
    void wake(int x, int y, int z) {
        wakeIndex((y * this.height + z) * this.width + x);
    }

    private void wakeIndex(int i) {
        int x = i % this.width;
        int rest = i / this.width;
        int z = rest % this.height;
        int y = rest / this.height;

        if (!liquidNear(i, x, y, z)) {
            return;                       // dry surroundings: a block placed in a dry place costs nothing
        }
        push(i);
        pushNeighbours(i, x, y, z);
    }

    private boolean liquidNear(int i, int x, int y, int z) {
        return type(i) != 0
                || (x > 0 && type(i - 1) != 0)
                || (x < this.width - 1 && type(i + 1) != 0)
                || (z > 0 && type(i - this.width) != 0)
                || (z < this.height - 1 && type(i + this.width) != 0)
                || (y > 0 && type(i - this.layerSize) != 0)
                || (y < this.depth - 1 && type(i + this.layerSize) != 0);
    }

    private void pushNeighbours(int i, int x, int y, int z) {
        if (x > 0) push(i - 1);
        if (x < this.width - 1) push(i + 1);
        if (z > 0) push(i - this.width);
        if (z < this.height - 1) push(i + this.width);
        if (y > 0) push(i - this.layerSize);
        if (y < this.depth - 1) push(i + this.layerSize);
    }

    /** after a load: flowing liquid with nothing feeding it (saved by the older simulation) gets one look and drains */
    void wakeUnsettled() {
        for (int i = 0; i < this.cells.length; i++) {
            if (this.cells[i] != 0 && (this.fills[i] & 0xFF) < SOURCE) {
                push(i);
            }
        }
    }

    private void push(int i) {
        if (this.queued.get(i)) {
            return;
        }
        this.queued.set(i);
        if (this.size == this.queue.length) {
            grow();
        }
        this.queue[(this.head + this.size) & (this.queue.length - 1)] = i;
        this.size++;
    }

    private int pop() {
        int i = this.queue[this.head];
        this.head = (this.head + 1) & (this.queue.length - 1);
        this.size--;
        this.queued.clear(i);
        return i;
    }

    private void grow() {
        int[] bigger = new int[this.queue.length << 1];
        for (int k = 0; k < this.size; k++) {
            bigger[k] = this.queue[(this.head + k) & (this.queue.length - 1)];
        }
        this.queue = bigger;
        this.head = 0;
    }

    // ------------------------------------------------------------------------------------------------
    // time

    // switching to infinite: partial cells have no source behind them any more, so give each one a look and let it drain.
    // switching to finite needs nothing, a world at rest is already at rest
    private void syncMode() {
        boolean now = finite;
        if (now != this.lastFinite) {
            this.lastFinite = now;
            if (!now) {
                wakeUnsettled();
            }
        }
    }

    void update(float seconds) {
        syncMode();
        if (!enabled || ticksPerSecond <= 0.0F) {
            this.accumulator = 0.0F;
            return;
        }
        float interval = 1.0F / ticksPerSecond;
        if (this.size == 0) {
            this.accumulator = interval;      // at rest: the next wake gets its first tick on the very next frame
            return;
        }

        this.accumulator += seconds;
        long deadline = System.nanoTime() + FRAME_BUDGET_NANOS;
        int ran = 0;
        while (this.accumulator >= interval && ran < MAX_TICKS_PER_UPDATE && this.size > 0) {
            tick(deadline, true);
            this.accumulator -= interval;
            ran++;
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        if (this.accumulator > interval) {
            this.accumulator = interval;      // behind: slow down, never build up a backlog
        }
    }

    void stepNow(int ticks) {
        syncMode();
        for (int k = 0; k < ticks && this.size > 0; k++) {
            tick(0L, false);
        }
    }

    // looks at the cells that were queued when the tick began. cells woken meanwhile go to the back and wait for
    // the next tick, so a change moves one cell per tick, at a steady speed
    private void tick(long deadline, boolean timed) {
        this.tickCount++;
        int n = Math.min(this.size, maxStepsPerTick);
        for (int k = 0; k < n; k++) {
            if (timed && (k & 63) == 63 && System.nanoTime() >= deadline) {
                break;                        // out of time: the rest stays at the front of the queue
            }
            process(pop());
        }
    }

    // ------------------------------------------------------------------------------------------------
    // one cell

    private void process(int i) {
        int x = i % this.width;
        int rest = i / this.width;
        int z = rest % this.height;
        int y = rest / this.height;

        byte cur = type(i);
        int curFill = cur == 0 ? 0 : fill(i);

        if (!this.layer.passable(x, y, z)) {
            if (cur != 0) {                   // a block that fills the cell was put where this liquid is
                set(i, x, y, z, (byte) 0, 0);
                if (finite) {
                    relocate(x, y, z, cur, curFill);
                }
            }
            return;
        }
        if (finite) {
            processFinite(i, x, y, z);
            return;
        }
        if (curFill >= SOURCE) {
            return;                           // sources are permanent
        }

        int[] nb = this.sides;
        nb[0] = x > 0 ? i - 1 : -1;
        nb[1] = x < this.width - 1 ? i + 1 : -1;
        nb[2] = z > 0 ? i - this.width : -1;
        nb[3] = z < this.height - 1 ? i + this.width : -1;

        byte wantType = 0;
        int wantFill = 0;

        // the sea only feeds its own region, never the land beside it
        boolean region = this.layer.isRegionCell(i);

        // 0. a hole (or partial cell) of the ocean next to plain ocean is simply refilled
        if (region) {
            byte ocean = this.layer.oceanValue();
            if (cur == 0 || cur == ocean) {
                boolean fed = y < this.depth - 1 && this.layer.isOceanCell(i + this.layerSize);
                for (int s = 0; s < 4 && !fed; s++) {
                    fed = nb[s] >= 0 && this.layer.isOceanCell(nb[s]);
                }
                if (fed) {
                    wantType = ocean;
                    wantFill = SOURCE;
                }
            }
        }

        // 1. an infinite liquid with two source neighbours becomes a source
        for (int s = 0; s < 4 && wantFill < SOURCE; s++) {
            int j = nb[s];
            if (j < 0) continue;
            byte t = type(j);
            if (t == 0 || (cur != 0 && t != cur) || fill(j) < SOURCE) continue;
            if (!region && this.layer.isOceanCell(j)) continue;
            if (!LiquidTile.byIndex(t - 1).isInfinite()) continue;
            int sources = 1;
            for (int r = s + 1; r < 4; r++) {
                int k = nb[r];
                if (k >= 0 && type(k) == t && fill(k) >= SOURCE && (region || !this.layer.isOceanCell(k))) sources++;
            }
            if (sources >= 2 && supported(i, x, y, z, t)) {
                wantType = t;
                wantFill = SOURCE;
            }
        }

        // 2. otherwise the best feed: from above, or from the sides
        if (wantFill == 0) {
            if (y < this.depth - 1) {
                byte t = type(i + this.layerSize);
                if (t != 0 && (cur == 0 || t == cur) && (region || !this.layer.isOceanCell(i + this.layerSize))) {
                    wantType = t;
                    wantFill = FALL_LEVEL;
                }
            }

            byte below = y > 0 ? type(i - this.layerSize) : 0;
            boolean onSource = below != 0 && fill(i - this.layerSize) >= SOURCE;

            for (int s = 0; s < 4; s++) {
                int j = nb[s];
                if (j < 0) continue;
                byte t = type(j);
                if (t == 0 || (cur != 0 && t != cur)) continue;
                if (!region && this.layer.isOceanCell(j)) continue;
                if (onSource && below == t) continue;     // no skin of liquid over a body of the same liquid
                int level = offered(j, x + SIDE_DX[s], y, z + SIDE_DZ[s], t);
                if (level > wantFill) {
                    wantType = t;
                    wantFill = level;
                }
            }
        }

        if (wantType == cur && wantFill == curFill) {
            return;                           // already as it should be: the wave stops here
        }

        // thick liquids act every few ticks
        byte gate = wantType != 0 ? wantType : cur;
        int interval = Math.max(1, Math.round(LiquidTile.byIndex(gate - 1).getViscosity()));
        if (interval > 1 && this.tickCount % interval != 0) {
            push(i);
            return;
        }

        set(i, x, y, z, wantType, wantFill);
    }

    // ------------------------------------------------------------------------------------------------
    // finite mode

    // a liquid cell gives units away: down while the cell below has room, then sideways to neighbours that hold
    // at least 2 units less (half the difference). empty cells do nothing: the liquid next to them acts for them.
    // plain ocean cells are the exception to "gives away": they hold 8 forever and top up the cells of their own region
    private void processFinite(int i, int x, int y, int z) {
        byte type = type(i);
        int fill = type == 0 ? 0 : fill(i);
        if (fill == 0) {
            return;
        }
        boolean sea = this.layer.isOceanCell(i);

        // thick liquids only move on every few ticks; a settled cell is not requeued, only one that wants to move
        int interval = Math.max(1, Math.round(LiquidTile.byIndex(type - 1).getViscosity()));
        boolean acts = interval == 1 || this.tickCount % interval == 0;

        // 1. down
        if (y > 0) {
            int b = i - this.layerSize;
            byte bt = type(b);
            int bf = bt == 0 ? 0 : fill(b);

            // liquid poured on top of the sea joins it, so there is no skin of water lying on the surface
            if (!sea && bt == type && this.layer.isOceanCell(b) && !this.layer.isRegionCell(i)) {
                if (!acts) {
                    push(i);
                    return;
                }
                set(i, x, y, z, (byte) 0, 0);
                return;
            }

            if ((bt == 0 || bt == type) && bf < SOURCE && this.layer.passable(x, y - 1, z)
                    && (!sea || this.layer.isRegionCell(b))) {
                if (!acts) {
                    push(i);
                    return;
                }
                int amount = Math.min(fill, SOURCE - bf);
                set(b, x, y - 1, z, type, bf + amount);
                if (!sea) {
                    fill -= amount;
                    set(i, x, y, z, fill == 0 ? (byte) 0 : type, fill);
                    if (fill == 0) {
                        return;
                    }
                }
            }
        }

        // 2. sideways, starting from a different side each time so the liquid doesn't lean in one direction
        int start = (int) ((this.tickCount + i) & 3);
        for (int k = 0; k < 4; k++) {
            int s = (k + start) & 3;
            int nx = x + SIDE_DX[s];
            int nz = z + SIDE_DZ[s];
            if (nx < 0 || nz < 0 || nx >= this.width || nz >= this.height) {
                continue;
            }
            int j = i + SIDE_DX[s] + SIDE_DZ[s] * this.width;
            byte jt = type(j);
            if (jt != 0 && jt != type) {
                continue;                     // liquids never mix
            }
            int jf = jt == 0 ? 0 : fill(j);
            int diff = fill - jf;
            if (diff < (sea ? 1 : 2)) {
                continue;
            }
            if (sea && !this.layer.isRegionCell(j)) {
                continue;                     // the sea never floods the land
            }
            if (jt == 0 && !this.layer.passable(nx, y, nz)) {
                continue;
            }
            if (!acts) {
                push(i);
                return;
            }
            if (sea) {
                set(j, nx, y, nz, type, SOURCE);     // tops the cell up completely, the sea itself loses nothing
                continue;
            }
            int amount = diff / 2;
            set(j, nx, y, nz, type, jf + amount);
            fill -= amount;
            set(i, x, y, z, fill == 0 ? (byte) 0 : type, fill);
            if (fill == 0) {
                return;
            }
        }
    }

    // liquid that a block pushed out of its cell: goes up, then to the sides, into whatever has room. the rest is lost
    private void relocate(int x, int y, int z, byte type, int amount) {
        for (int d = 0; d < 5 && amount > 0; d++) {
            int nx = x, ny = y, nz = z;
            if (d == 0) {
                ny++;
            } else {
                nx += SIDE_DX[d - 1];
                nz += SIDE_DZ[d - 1];
            }
            if (nx < 0 || ny < 0 || nz < 0 || nx >= this.width || ny >= this.depth || nz >= this.height) {
                continue;
            }
            int j = (ny * this.height + nz) * this.width + nx;
            byte jt = type(j);
            if ((jt != 0 && jt != type) || !this.layer.passable(nx, ny, nz)) {
                continue;
            }
            int jf = jt == 0 ? 0 : fill(j);
            int room = SOURCE - jf;
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, amount);
            set(j, nx, ny, nz, type, jf + moved);
            amount -= moved;
        }
    }

    // ------------------------------------------------------------------------------------------------

    // what the side cell j (at nx, y, nz) feeds into its neighbour: sources always spread, flowing liquid only
    // while it rests on something solid, and every step costs one level
    private int offered(int j, int nx, int y, int nz, byte type) {
        int f = fill(j);
        if (f >= SOURCE) {
            return FALL_LEVEL;
        }
        if (f < 2 || holeBelow(j, nx, y, nz, type)) {
            return 0;
        }
        return f - 1;
    }

    // nothing solid under the cell: air, or more of the same liquid. (another liquid counts as a floor)
    private boolean holeBelow(int j, int x, int y, int z, byte type) {
        if (y == 0) {
            return false;
        }
        byte b = type(j - this.layerSize);
        if (b != 0) {
            return b == type;
        }
        return this.layer.passable(x, y - 1, z);
    }

    // a block or a source of the same liquid under the cell
    private boolean supported(int i, int x, int y, int z, byte type) {
        if (y == 0) {
            return true;
        }
        byte b = type(i - this.layerSize);
        if (b != 0) {
            return b == type && fill(i - this.layerSize) >= SOURCE;
        }
        return !this.layer.passable(x, y - 1, z);
    }

    // writes the cell and wakes what depends on it
    private void set(int i, int x, int y, int z, byte type, int fill) {
        this.layer.applyRaw(i, type, (byte) (type == 0 ? 0 : fill));
        push(i);                              // it may now be taken by another liquid
        pushNeighbours(i, x, y, z);
    }
}