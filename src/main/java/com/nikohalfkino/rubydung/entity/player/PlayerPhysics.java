package com.nikohalfkino.rubydung.entity.player;

import com.nikohalfkino.rubydung.level.Level;
import com.nikohalfkino.rubydung.level.tiles.special.LiquidTile;
import com.nikohalfkino.rubydung.entity.phys.AABB;

import java.util.List;

/**
 * Port of Quake's pmove.c (QuakeWorld player movement) onto the block world.
 *
 * Function names follow the C source so the two can be read side by side:
 *   PM_Friction -> friction, PM_Accelerate -> accelerate, PM_AirAccelerate -> airAccelerate,
 *   PM_FlyMove -> flyMove, PM_GroundMove -> groundMove, PM_WaterMove -> waterMove,
 *   PM_AirMove -> airMove, PM_CatagorizePosition -> categorizePosition, JumpButton -> jumpButton,
 *   CheckWaterJump -> checkWaterJump, NudgePosition -> nudgePosition,
 *   PlayerMove -> move.
 *
 * Units: 1 block = 32 Quake units, so every Quake constant is written as "N * U". Velocities are in
 * blocks per second, the frame time is in seconds.
 *
 * Axes: x and z are horizontal, y is up (Quake: x, y horizontal and z up). Quake's index [2] is
 * therefore Y here and its [1] is Z.
 *
 * origin = the middle of the feet (not the hull centre like in Quake).
 *
 * Differences from the C source, all on purpose:
 *   - the world is axis aligned boxes, so trace normals are always axis aligned (no slopes)
 *   - the dropoff test in friction is a point trace (like Quake 1's SV_Friction, which is what the
 *     code means to do)
 *   - in mid air a whole block can be stepped up when running into it (airStep), so bunnyhops don't get stuck
 *   - walking speed is scaled by SPEED_SCALE and holding jump keeps jumping (AUTO_JUMP)
 *   - the water code reads the liquid layer: water level / type come from LiquidTile, and viscosity
 *     scales water friction and the swim-up speed (water = Quake's water, thicker = Quake's lava)
 *   - nudgePosition has a fallback that lifts a player who got walled in (a block placed on top of
 *     them) up out of it, because in a sandbox that happens
 */
public class PlayerPhysics {

    public enum Mode { WALK, FLY, NOCLIP }

    public static final int X = 0, Y = 1, Z = 2;

    /** one Quake unit in blocks */
    private static final double U = 1.0 / 32.0;

    // hull (blocks), relative to origin = middle of the feet. Quake's is 32 x 32 x 56 units
    public static final double HALF_WIDTH = 0.3D;
    public static final double HEIGHT = 1.8D;
    public static final double EYE_HEIGHT = 1.62D;

    // ---- movevars ----
    /** walking is slower than Quake's 320 units/s (10 blocks/s): 0.7 = 7 blocks/s. 1.0 = Quake */
    public static final double SPEED_SCALE = 0.7;
    private static final double MAX_SPEED        = 320 * U * SPEED_SCALE;
    private static final double STOP_SPEED       = 100 * U;
    private static final double GRAVITY          = 800 * U;
    private static final double ENT_GRAVITY      = 1.0;
    private static final double ACCELERATE       = 10.0;
    private static final double WATER_ACCELERATE = 10.0;
    private static final double FRICTION         = 6.0;
    private static final double WATER_FRICTION   = 1.0;

    // ---- usercmd speeds (QuakeWorld default "always run") ----
    private static final double FORWARD_SPEED = 400 * U;
    private static final double SIDE_SPEED    = 350 * U;
    private static final double UP_SPEED      = 200 * U;

    // ---- other constants of pmove.c ----
    private static final double STEPSIZE       = 18 * U;
    private static final double STOP_EPSILON   = 0.1 * U;
    private static final double JUMP_SPEED     = 270 * U;
    private static final double NO_GROUND_UP   = 180 * U;   // moving up faster than this = in the air
    /**
     * the air step only happens above this horizontal speed, so it needs a real bunnyhop and not just walking up
     * to a block and jumping. walking tops out at MAX_SPEED, so this is 10% above it
     */
    private static final double AIR_STEP_MIN_SPEED = MAX_SPEED * 1.1;

    /** highest ledge climbed in mid air when running into it (a whole block, plus a little slack) */
    private static final double AIR_STEP_HEIGHT = 1.0 + 1.0 / 16.0;

    /** holding jump keeps jumping on landing (Quake needs the key released in between) */
    public static final boolean AUTO_JUMP = true;

    /** the old flying / noclip speed, blocks per tick, times Player.flySpeedMultiplier */
    private static final double BASE_FLY_SPEED = 0.2;

    private static final double AIR_WISHSPEED  = 30 * U;
    private static final int    MAX_CLIP_PLANES = 5;
    private static final int    NUM_BUMPS = 4;

    /** liquid with this viscosity behaves exactly like Quake's water */
    private static final double WATER_VISCOSITY = 0.6;

    /** trace stops this far short of what it hits (Quake: DIST_EPSILON = 1/32 unit) */
    private static final double DIST_EPSILON = 1.0 / 1024.0;
    private static final double OVERLAP_EPSILON = 1.0E-7;

    private final Level level;

    // ---- pmove state ----
    public final double[] origin = new double[3];
    public final double[] velocity = new double[3];

    private boolean onGround;
    private int waterLevel;            // 0 dry, 1 feet, 2 waist, 3 head
    private LiquidTile waterType;      // liquid at the feet, null when waterLevel == 0
    private double waterJumpTime;
    private boolean oldJump;           // BUTTON_JUMP in oldbuttons
    private double lastGroundY;        // feet height when last standing on something (limits airStep)
    private double ceilingHitSpeed;    // how fast the head hit something overhead during the last move (blocks per second), 0 = it did not

    /** scales the spectator / flying speed (Player.flySpeedMultiplier) */
    public double spectatorSpeedScale = 1.0D;

    // ---- per move ----
    private double frameTime;
    private double forwardMove, sideMove, upMove;
    private float cmdForward, cmdSide, cmdUp;
    private final double[] forward = new double[3];
    private final double[] right = new double[3];
    private double flatForwardX, flatForwardZ;   // forward without pitch, normalized
    private double flatRightX, flatRightZ;

    // ---- debug: read by the vector overlay (DebugRenderer), nothing in the physics uses them ----
    private final double[] debugWishVelocity = new double[3];   // what the inputs ask for (direction * speed), blocks per second
    private final double[] debugMovedVelocity = new double[3];  // how far the origin really moved in the last command, per second

    public PlayerPhysics(Level level) {
        this.level = level;
    }

    // =====================================================================
    // access

    public boolean isOnGround()      { return this.onGround; }
    /** how fast the head hit something overhead during the last move, going up (blocks per second), 0 if it did not. CubeManager turns it into the bounce of a cube above */
    public double getCeilingHitSpeed() { return this.ceilingHitSpeed; }
    public int getWaterLevel()       { return this.waterLevel; }
    public LiquidTile getWaterType() { return this.waterType; }

    /** what the inputs asked for in the last command (wish direction * wish speed), blocks per second. debug overlay only */
    public double[] getWishVelocity()  { return this.debugWishVelocity; }
    /** how far the origin really moved in the last command, divided by its length (blocks per second). debug overlay only */
    public double[] getMovedVelocity() { return this.debugMovedVelocity; }

    public void teleport(double x, double y, double z) {
        this.origin[X] = x;
        this.origin[Y] = y;
        this.origin[Z] = z;
        this.velocity[X] = this.velocity[Y] = this.velocity[Z] = 0.0;
        this.waterJumpTime = 0.0;
        this.lastGroundY = y;
        this.onGround = false;
        this.waterLevel = 0;
        this.waterType = null;
    }

    public AABB buildBox() {
        return new AABB(this.origin[X] - HALF_WIDTH, this.origin[Y], this.origin[Z] - HALF_WIDTH,
                this.origin[X] + HALF_WIDTH, this.origin[Y] + HEIGHT, this.origin[Z] + HALF_WIDTH);
    }

    /** moves by a fixed amount with collision (no sliding, no velocity change). for scripted moves / pushes */
    public void moveBy(double dx, double dy, double dz) {
        double[] end = new double[3];
        end[X] = this.origin[X];
        end[Y] = this.origin[Y] + dy;
        end[Z] = this.origin[Z];
        set(this.origin, trace(this.origin, end).endPos);

        end[X] = this.origin[X] + dx;
        end[Y] = this.origin[Y];
        set(this.origin, trace(this.origin, end).endPos);

        end[X] = this.origin[X];
        end[Z] = this.origin[Z] + dz;
        set(this.origin, trace(this.origin, end).endPos);
    }

    // =====================================================================
    // PlayerMove

    /** Quake's PlayerMove: one command, frameTime seconds long */
    public void move(MoveCommand cmd, Mode mode, double frameTime) {
        double startX = this.origin[X];
        double startY = this.origin[Y];
        double startZ = this.origin[Z];
        this.ceilingHitSpeed = 0.0D;
        this.debugWishVelocity[X] = this.debugWishVelocity[Y] = this.debugWishVelocity[Z] = 0.0;

        playerMove(cmd, mode, frameTime);

        this.debugMovedVelocity[X] = (this.origin[X] - startX) / frameTime;
        this.debugMovedVelocity[Y] = (this.origin[Y] - startY) / frameTime;
        this.debugMovedVelocity[Z] = (this.origin[Z] - startZ) / frameTime;
    }

    private void playerMove(MoveCommand cmd, Mode mode, double frameTime) {
        this.frameTime = frameTime;

        angleVectors(cmd.yaw, cmd.pitch);
        this.forwardMove = cmd.forward * FORWARD_SPEED;
        this.sideMove = cmd.side * SIDE_SPEED;
        this.upMove = cmd.up * UP_SPEED;
        this.cmdForward = cmd.forward;
        this.cmdSide = cmd.side;
        this.cmdUp = cmd.up;

        if (mode != Mode.WALK) {
            flyOrNoclip(mode == Mode.FLY);
            return;
        }

        nudgePosition();

        // set onGround, waterType and waterLevel
        categorizePosition();

        if (this.waterLevel == 2) {
            checkWaterJump();
        }

        if (this.velocity[Y] < 0) {
            this.waterJumpTime = 0;
        }

        if (cmd.jump) {
            jumpButton();
        } else {
            this.oldJump = false;
        }

        friction();

        if (this.waterLevel >= 2) {
            waterMove();
        } else {
            airMove();
        }

        // set onGround, waterType and waterLevel for the final spot
        categorizePosition();
    }

    // yaw 0 looks along -z, forward = (sin yaw, 0, -cos yaw). positive pitch looks down
    private void angleVectors(float yawDegrees, float pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double sy = Math.sin(yaw), cy = Math.cos(yaw);
        double sp = Math.sin(pitch), cp = Math.cos(pitch);

        this.forward[X] = sy * cp;
        this.forward[Y] = -sp;
        this.forward[Z] = -cy * cp;

        this.right[X] = cy;
        this.right[Y] = 0.0;
        this.right[Z] = sy;

        this.flatForwardX = sy;
        this.flatForwardZ = -cy;
        this.flatRightX = cy;
        this.flatRightZ = sy;
    }

    // =====================================================================
    // PM_ClipVelocity / PM_FlyMove

    private static int clipVelocity(double[] in, double[] normal, double[] out, double overbounce) {
        int blocked = 0;
        if (normal[Y] > 0) {
            blocked |= 1;   // floor
        }
        if (normal[Y] == 0) {
            blocked |= 2;   // step
        }

        double backoff = dot(in, normal) * overbounce;

        for (int i = 0; i < 3; i++) {
            double change = normal[i] * backoff;
            out[i] = in[i] - change;
            if (out[i] > -STOP_EPSILON && out[i] < STOP_EPSILON) {
                out[i] = 0;
            }
        }
        return blocked;
    }

    /** the basic solid body movement clip that slides along multiple planes */
    private int flyMove() {
        double[][] planes = new double[MAX_CLIP_PLANES][];
        double[] originalVelocity = this.velocity.clone();
        double[] primalVelocity = this.velocity.clone();
        double[] end = new double[3];
        double[] dir = new double[3];

        int blocked = 0;
        int numPlanes = 0;
        double timeLeft = this.frameTime;

        for (int bumpCount = 0; bumpCount < NUM_BUMPS; bumpCount++) {
            for (int i = 0; i < 3; i++) {
                end[i] = this.origin[i] + timeLeft * this.velocity[i];
            }

            Trace trace = trace(this.origin, end);

            if (trace.startSolid || trace.allSolid) {
                // entity is trapped in another solid
                this.velocity[X] = this.velocity[Y] = this.velocity[Z] = 0.0;
                return 3;
            }

            if (trace.fraction > 0) {
                // actually covered some distance
                set(this.origin, trace.endPos);
                numPlanes = 0;
            }

            if (trace.fraction == 1) {
                break;   // moved the entire distance
            }

            if (trace.normal[Y] > 0.7) {
                blocked |= 1;   // floor
            }
            if (trace.normal[Y] == 0) {
                blocked |= 2;   // step
            }
            if (trace.normal[Y] < -0.7 && this.velocity[Y] > this.ceilingHitSpeed) {
                this.ceilingHitSpeed = this.velocity[Y];   // head against the ceiling (or the underside of a cube), going up
            }

            timeLeft -= timeLeft * trace.fraction;

            // clipped to another plane
            if (numPlanes >= MAX_CLIP_PLANES) {
                // this shouldn't really happen
                this.velocity[X] = this.velocity[Y] = this.velocity[Z] = 0.0;
                break;
            }

            planes[numPlanes] = trace.normal.clone();
            numPlanes++;

            // modify original velocity so it parallels all of the clip planes
            int i;
            for (i = 0; i < numPlanes; i++) {
                clipVelocity(originalVelocity, planes[i], this.velocity, 1);
                int j;
                for (j = 0; j < numPlanes; j++) {
                    if (j != i && dot(this.velocity, planes[j]) < 0) {
                        break;   // not ok
                    }
                }
                if (j == numPlanes) {
                    break;
                }
            }

            if (i != numPlanes) {
                // go along this plane
            } else {
                // go along the crease
                if (numPlanes != 2) {
                    this.velocity[X] = this.velocity[Y] = this.velocity[Z] = 0.0;
                    break;
                }
                cross(planes[0], planes[1], dir);
                double d = dot(dir, this.velocity);
                for (int k = 0; k < 3; k++) {
                    this.velocity[k] = dir[k] * d;
                }
            }

            // if velocity is against the original velocity, stop dead
            // to avoid tiny oscillations in sloping corners
            if (dot(this.velocity, primalVelocity) <= 0) {
                this.velocity[X] = this.velocity[Y] = this.velocity[Z] = 0.0;
                break;
            }
        }

        if (this.waterJumpTime > 0) {
            set(this.velocity, primalVelocity);
        }
        return blocked;
    }

    // =====================================================================
    // PM_GroundMove

    /** player is on the ground, with no upwards velocity */
    private void groundMove() {
        this.velocity[Y] = 0;
        if (this.velocity[X] == 0 && this.velocity[Y] == 0 && this.velocity[Z] == 0) {
            return;
        }

        // first try just moving to the destination
        double[] dest = {
                this.origin[X] + this.velocity[X] * this.frameTime,
                this.origin[Y],
                this.origin[Z] + this.velocity[Z] * this.frameTime
        };

        Trace trace = trace(this.origin, dest);
        if (trace.fraction == 1) {
            set(this.origin, trace.endPos);
            return;
        }

        // try sliding forward both on ground and up one step. take the move that goes farthest
        double[] original = this.origin.clone();
        double[] originalVel = this.velocity.clone();

        // slide move
        flyMove();

        double[] down = this.origin.clone();
        double[] downVel = this.velocity.clone();

        set(this.origin, original);
        set(this.velocity, originalVel);

        // move up a stair height
        dest = this.origin.clone();
        dest[Y] += STEPSIZE;
        trace = trace(this.origin, dest);
        if (!trace.startSolid && !trace.allSolid) {
            set(this.origin, trace.endPos);
        }

        // slide move
        flyMove();

        // press down the stepheight
        dest = this.origin.clone();
        dest[Y] -= STEPSIZE;
        trace = trace(this.origin, dest);

        boolean useDown;
        if (trace.normal[Y] < 0.7) {
            useDown = true;
        } else {
            if (!trace.startSolid && !trace.allSolid) {
                set(this.origin, trace.endPos);
            }
            double[] up = this.origin.clone();

            // decide which one went farther
            double downDist = (down[X] - original[X]) * (down[X] - original[X])
                    + (down[Z] - original[Z]) * (down[Z] - original[Z]);
            double upDist = (up[X] - original[X]) * (up[X] - original[X])
                    + (up[Z] - original[Z]) * (up[Z] - original[Z]);
            useDown = downDist > upDist;
        }

        if (useDown) {
            set(this.origin, down);
            set(this.velocity, downVel);
        } else {
            // copy y value from slide move
            this.velocity[Y] = downVel[Y];
        }
    }

    // =====================================================================
    // PM_Friction / PM_Accelerate / PM_AirAccelerate

    /** handles both ground friction and water friction */
    private void friction() {
        if (this.waterJumpTime > 0) {
            return;
        }

        double[] vel = this.velocity;

        double speed = Math.sqrt(vel[X] * vel[X] + vel[Y] * vel[Y] + vel[Z] * vel[Z]);
        if (speed < 1 * U) {
            vel[X] = 0;
            vel[Z] = 0;
            return;
        }

        double friction = FRICTION;

        // if the leading edge is over a dropoff, increase friction
        if (this.onGround) {
            double[] start = {
                    this.origin[X] + vel[X] / speed * HALF_WIDTH,
                    this.origin[Y],
                    this.origin[Z] + vel[Z] / speed * HALF_WIDTH
            };
            double[] stop = start.clone();
            stop[Y] = start[Y] - 34 * U;

            Trace trace = pointTrace(start, stop);

            if (trace.fraction == 1) {
                friction *= 2;
            }
        }

        double drop = 0;

        if (this.waterLevel >= 2) {
            // apply water friction, thicker liquids drag more
            double thickness = this.waterType.getViscosity() / WATER_VISCOSITY;
            drop += speed * WATER_FRICTION * thickness * this.waterLevel * this.frameTime;
        } else if (this.onGround) {
            // apply ground friction
            double control = speed < STOP_SPEED ? STOP_SPEED : speed;
            drop += control * friction * this.frameTime;
        }

        // scale the velocity
        double newSpeed = speed - drop;
        if (newSpeed < 0) {
            newSpeed = 0;
        }
        newSpeed /= speed;

        vel[X] = vel[X] * newSpeed;
        vel[Y] = vel[Y] * newSpeed;
        vel[Z] = vel[Z] * newSpeed;
    }

    private void accelerate(double[] wishDir, double wishSpeed, double accel) {
        if (this.waterJumpTime > 0) {
            return;
        }

        double currentSpeed = dot(this.velocity, wishDir);
        double addSpeed = wishSpeed - currentSpeed;
        if (addSpeed <= 0) {
            return;
        }
        double accelSpeed = accel * this.frameTime * wishSpeed;
        if (accelSpeed > addSpeed) {
            accelSpeed = addSpeed;
        }

        for (int i = 0; i < 3; i++) {
            this.velocity[i] += accelSpeed * wishDir[i];
        }
    }

    private void airAccelerate(double[] wishDir, double wishSpeed, double accel) {
        if (this.waterJumpTime > 0) {
            return;
        }

        double wishSpd = wishSpeed;
        if (wishSpd > AIR_WISHSPEED) {
            wishSpd = AIR_WISHSPEED;
        }
        double currentSpeed = dot(this.velocity, wishDir);
        double addSpeed = wishSpd - currentSpeed;
        if (addSpeed <= 0) {
            return;
        }
        double accelSpeed = accel * wishSpeed * this.frameTime;
        if (accelSpeed > addSpeed) {
            accelSpeed = addSpeed;
        }

        for (int i = 0; i < 3; i++) {
            this.velocity[i] += accelSpeed * wishDir[i];
        }
    }

    // =====================================================================
    // PM_WaterMove / PM_AirMove

    private void waterMove() {
        // user intentions
        double[] wishVel = new double[3];
        for (int i = 0; i < 3; i++) {
            wishVel[i] = this.forward[i] * this.forwardMove + this.right[i] * this.sideMove;
        }

        if (this.forwardMove == 0 && this.sideMove == 0 && this.upMove == 0) {
            wishVel[Y] -= 60 * U;   // drift towards bottom
        } else {
            wishVel[Y] += this.upMove;
        }

        double[] wishDir = wishVel.clone();
        double wishSpeed = normalize(wishDir);

        if (wishSpeed > MAX_SPEED) {
            wishSpeed = MAX_SPEED;
        }
        wishSpeed *= 0.7;

        for (int i = 0; i < 3; i++) {
            this.debugWishVelocity[i] = wishDir[i] * wishSpeed;
        }

        // water acceleration
        accelerate(wishDir, wishSpeed, WATER_ACCELERATE);

        // assume it is a stair or a slope, so press down from stepheight above
        double[] dest = new double[3];
        for (int i = 0; i < 3; i++) {
            dest[i] = this.origin[i] + this.frameTime * this.velocity[i];
        }
        double[] start = dest.clone();
        start[Y] += STEPSIZE + 1 * U;
        Trace trace = trace(start, dest);
        if (!trace.startSolid && !trace.allSolid) {
            // walked up the step
            set(this.origin, trace.endPos);
            return;
        }

        flyMove();
    }

    private void airMove() {
        double fmove = this.forwardMove;
        double smove = this.sideMove;

        // forward / right with the pitch removed and renormalized
        double wishX = this.flatForwardX * fmove + this.flatRightX * smove;
        double wishZ = this.flatForwardZ * fmove + this.flatRightZ * smove;

        double[] wishDir = {wishX, 0.0, wishZ};
        double wishSpeed = normalize(wishDir);

        // clamp to server defined max speed
        if (wishSpeed > MAX_SPEED) {
            wishSpeed = MAX_SPEED;
        }

        this.debugWishVelocity[X] = wishDir[X] * wishSpeed;
        this.debugWishVelocity[Y] = 0.0;
        this.debugWishVelocity[Z] = wishDir[Z] * wishSpeed;

        if (this.onGround) {
            this.velocity[Y] = 0;
            accelerate(wishDir, wishSpeed, ACCELERATE);
            this.velocity[Y] -= ENT_GRAVITY * GRAVITY * this.frameTime;
            groundMove();
        } else {
            // not on ground, so little effect on velocity
            airAccelerate(wishDir, wishSpeed, ACCELERATE);

            // add gravity
            this.velocity[Y] -= ENT_GRAVITY * GRAVITY * this.frameTime;

            double[] originalOrigin = this.origin.clone();
            double[] originalVelocity = this.velocity.clone();

            int blocked = flyMove();
            double speedBefore = Math.hypot(originalVelocity[X], originalVelocity[Z]);
            if ((blocked & 2) != 0 && speedBefore >= AIR_STEP_MIN_SPEED) {
                airStep(originalOrigin, originalVelocity);
            }
        }
    }

    /**
     * Not in Quake: the same up / forward / down trick as groundMove, but with a whole block of height,
     * used in mid air, and only when moving faster than walking (AIR_STEP_MIN_SPEED). A bunnyhop that runs into a wall of one block would otherwise stop dead or slide
     * along it when the jump is a little too low. Only taken when it ends standing on something and gets
     * further than the plain slide, and never lands more than a block above where the player last stood
     * (so it can't be used to climb a tall wall by hopping). The plain slide (already in origin / velocity) is kept otherwise.
     */
    private void airStep(double[] originalOrigin, double[] originalVelocity) {
        double[] slideOrigin = this.origin.clone();
        double[] slideVelocity = this.velocity.clone();

        set(this.origin, originalOrigin);
        set(this.velocity, originalVelocity);
        this.velocity[Y] = 0;

        // up
        double[] dest = this.origin.clone();
        dest[Y] += AIR_STEP_HEIGHT;
        Trace trace = trace(this.origin, dest);
        if (trace.startSolid || trace.allSolid) {
            set(this.origin, slideOrigin);
            set(this.velocity, slideVelocity);
            return;
        }
        set(this.origin, trace.endPos);

        // forward
        flyMove();

        // down onto the ledge
        dest = this.origin.clone();
        dest[Y] -= AIR_STEP_HEIGHT;
        trace = trace(this.origin, dest);

        boolean ok = !trace.startSolid && !trace.allSolid
                && trace.normal[Y] >= 0.7
                && trace.endPos[Y] >= originalOrigin[Y] - 1.0E-6
                && trace.endPos[Y] <= this.lastGroundY + AIR_STEP_HEIGHT;   // never more than a block above where we last stood
        if (ok) {
            double stepDist = sq(trace.endPos[X] - originalOrigin[X]) + sq(trace.endPos[Z] - originalOrigin[Z]);
            double slideDist = sq(slideOrigin[X] - originalOrigin[X]) + sq(slideOrigin[Z] - originalOrigin[Z]);
            ok = stepDist > slideDist + 1.0E-9;
        }

        if (ok) {
            set(this.origin, trace.endPos);
            this.velocity[Y] = 0;   // standing on the ledge now
        } else {
            set(this.origin, slideOrigin);
            set(this.velocity, slideVelocity);
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    // =====================================================================
    // PM_CatagorizePosition / JumpButton / CheckWaterJump

    private void categorizePosition() {
        // if the player hull point one unit down is solid, the player is on ground
        double[] point = {this.origin[X], this.origin[Y] - 1 * U, this.origin[Z]};

        if (this.velocity[Y] > NO_GROUND_UP) {
            this.onGround = false;
        } else {
            Trace trace = trace(this.origin, point);
            this.onGround = trace.normal[Y] >= 0.7;   // otherwise too steep / nothing there
            if (this.onGround) {
                this.waterJumpTime = 0;
                if (!trace.startSolid && !trace.allSolid) {
                    set(this.origin, trace.endPos);
                }
                this.lastGroundY = this.origin[Y];
            }
        }

        // get waterlevel
        this.waterLevel = 0;
        this.waterType = null;

        LiquidTile liquid = liquidAt(this.origin[X], this.origin[Y] + 1 * U, this.origin[Z]);
        if (liquid != null) {
            this.waterType = liquid;
            this.waterLevel = 1;
            if (liquidAt(this.origin[X], this.origin[Y] + HEIGHT * 0.5, this.origin[Z]) != null) {
                this.waterLevel = 2;
                if (liquidAt(this.origin[X], this.origin[Y] + EYE_HEIGHT, this.origin[Z]) != null) {
                    this.waterLevel = 3;
                }
            }
        }
    }

    private void jumpButton() {
        if (this.waterJumpTime > 0) {
            this.waterJumpTime -= this.frameTime;
            if (this.waterJumpTime < 0) {
                this.waterJumpTime = 0;
            }
            return;
        }

        if (this.waterLevel >= 2) {
            // swimming, not jumping
            this.onGround = false;
            this.velocity[Y] = swimUpSpeed(this.waterType);
            return;
        }

        if (!this.onGround) {
            return;   // in air, so no effect
        }

        if (this.oldJump && !AUTO_JUMP) {
            return;   // don't pogo stick
        }

        this.onGround = false;
        this.velocity[Y] += JUMP_SPEED;

        this.oldJump = true;   // don't jump again until released
    }

    // Quake: water 100, slime 80, lava 50. thicker liquids swim up slower
    private static double swimUpSpeed(LiquidTile liquid) {
        double thickness = Math.max(liquid.getViscosity(), WATER_VISCOSITY);
        return 100 * U * Math.sqrt(WATER_VISCOSITY / thickness);
    }

    private void checkWaterJump() {
        if (this.waterJumpTime > 0) {
            return;
        }

        // don't hop out if we just jumped in
        if (this.velocity[Y] < -180 * U) {
            return;   // only hop out if we are moving up
        }

        // see if near an edge
        double spotX = this.origin[X] + 24 * U * this.flatForwardX;
        double spotZ = this.origin[Z] + 24 * U * this.flatForwardZ;
        double spotY = this.origin[Y] + 32 * U;   // Quake: origin (24 above the feet) + 8

        if (!pointSolid(spotX, spotY, spotZ)) {
            return;
        }
        spotY += 24 * U;
        if (pointSolid(spotX, spotY, spotZ) || liquidAt(spotX, spotY, spotZ) != null) {
            return;
        }

        // jump out of water
        this.velocity[X] = this.flatForwardX * 50 * U;
        this.velocity[Y] = 310 * U;
        this.velocity[Z] = this.flatForwardZ * 50 * U;
        this.waterJumpTime = 2;   // safety net
        this.oldJump = true;      // don't jump again until released
    }

    // =====================================================================
    // NudgePosition / SpectatorMove

    private static final int[] SIGN = {0, -1, 1};

    /** if origin is in a solid position, try nudging slightly on all axes */
    private void nudgePosition() {
        double[] base = this.origin.clone();

        for (int i = 0; i < 3; i++) {
            this.origin[i] = ((long) (this.origin[i] * 8 / U)) * 0.125 * U;
        }

        // same order as the C source: vertical outermost
        for (int v = 0; v <= 2; v++) {
            for (int x = 0; x <= 2; x++) {
                for (int z = 0; z <= 2; z++) {
                    this.origin[X] = base[X] + (SIGN[x] * 1.0 / 8) * U;
                    this.origin[Y] = base[Y] + (SIGN[v] * 1.0 / 8) * U;
                    this.origin[Z] = base[Z] + (SIGN[z] * 1.0 / 8) * U;
                    if (isPositionValid(this.origin)) {
                        return;
                    }
                }
            }
        }
        set(this.origin, base);

        // still stuck (not in the C source): lift out of whatever got built around us
        double[] lifted = base.clone();
        for (int i = 1; i <= 32; i++) {
            lifted[Y] = base[Y] + i * 0.0625;
            if (isPositionValid(lifted)) {
                set(this.origin, lifted);
                return;
            }
        }
    }

    /**
     * The old creative flying, not Quake's: noclip glides straight through everything at a fixed speed,
     * flying (collide = true) accelerates and drags (x0.8 per tick) and stops against blocks.
     * Speed is BASE_FLY_SPEED * spectatorSpeedScale (ctrl + scroll). The old code worked in blocks per
     * tick, so the velocity is converted from and to blocks per second around it.
     */
    private void flyOrNoclip(boolean collide) {
        this.onGround = false;
        this.waterLevel = 0;
        this.waterType = null;
        this.waterJumpTime = 0;

        double flySpeed = BASE_FLY_SPEED * this.spectatorSpeedScale;
        double[] v = this.velocity;
        for (int i = 0; i < 3; i++) {
            v[i] *= this.frameTime;   // blocks per tick
        }

        double wishX = 0, wishZ = 0;
        double lengthSq = this.cmdForward * this.cmdForward + this.cmdSide * this.cmdSide;
        boolean wishing = lengthSq >= 0.01;
        if (wishing) {
            double scale = 1.0 / Math.sqrt(lengthSq);
            wishX = (this.flatForwardX * this.cmdForward + this.flatRightX * this.cmdSide) * scale;
            wishZ = (this.flatForwardZ * this.cmdForward + this.flatRightZ * this.cmdSide) * scale;
        }

        // flySpeed is in blocks per tick, the overlay wants blocks per second
        this.debugWishVelocity[X] = wishX * flySpeed / this.frameTime;
        this.debugWishVelocity[Y] = this.cmdUp * flySpeed / this.frameTime;
        this.debugWishVelocity[Z] = wishZ * flySpeed / this.frameTime;

        if (!collide) {
            // noclip
            v[X] = wishX * flySpeed;
            v[Z] = wishZ * flySpeed;
            v[Y] = this.cmdUp * flySpeed;

            for (int i = 0; i < 3; i++) {
                this.origin[i] += v[i];
            }
        } else {
            double accel = flySpeed * 0.2;
            v[X] += wishX * accel;
            v[Z] += wishZ * accel;
            v[Y] += this.cmdUp * accel;

            // move with collision, one axis at a time (y, x, z), a blocked axis loses its velocity
            double[] end = this.origin.clone();
            for (int axis : new int[]{Y, X, Z}) {
                end[axis] = this.origin[axis] + v[axis];
                double[] clipped = trace(this.origin, end).endPos;
                if (clipped[axis] != end[axis]) {
                    v[axis] = 0.0;
                }
                this.origin[axis] = clipped[axis];
                end[axis] = this.origin[axis];
            }

            v[X] *= 0.8;
            v[Y] *= 0.8;
            v[Z] *= 0.8;
        }

        for (int i = 0; i < 3; i++) {
            v[i] /= this.frameTime;   // back to blocks per second
        }
    }

    // =====================================================================
    // world queries (PM_PlayerMove, PM_TestPlayerPosition, PM_PointContents)

    /** result of a swept box, like pmtrace_t */
    public static final class Trace {
        public boolean allSolid;
        public boolean startSolid;
        /** 0..1, how much of the path was free */
        public double fraction = 1.0;
        public final double[] endPos = new double[3];
        /** surface that was hit, (0,0,0) if nothing was */
        public final double[] normal = new double[3];
    }

    /** sweeps the player hull from start to end (start / end are origins) */
    private Trace trace(double[] start, double[] end) {
        return traceBox(start, end, HALF_WIDTH, HEIGHT);
    }

    /** sweeps a point */
    private Trace pointTrace(double[] start, double[] end) {
        return traceBox(start, end, 0.0, 0.0);
    }

    /**
     * Sweeps a box (halfWidth wide each way from the origin, height tall, standing on the origin) through
     * the collision boxes of the level. Stops DIST_EPSILON short of what it hits so the hull never
     * ends up touching or inside a face.
     */
    private Trace traceBox(double[] start, double[] end, double halfWidth, double height) {
        Trace trace = new Trace();

        double[] delta = {end[X] - start[X], end[Y] - start[Y], end[Z] - start[Z]};
        double[] boxMin = {start[X] - halfWidth, start[Y], start[Z] - halfWidth};
        double[] boxMax = {start[X] + halfWidth, start[Y] + height, start[Z] + halfWidth};

        set(trace.endPos, end);

        AABB region = new AABB(boxMin[X], boxMin[Y], boxMin[Z], boxMax[X], boxMax[Y], boxMax[Z])
                .expand(delta[X], delta[Y], delta[Z]);
        List<AABB> cubes = this.level.getCubes(region);

        double best = 1.0;
        int bestAxis = -1;
        boolean startSolid = false;

        double[] obMin = new double[3];
        double[] obMax = new double[3];

        for (AABB cube : cubes) {
            obMin[X] = cube.minX; obMin[Y] = cube.minY; obMin[Z] = cube.minZ;
            obMax[X] = cube.maxX; obMax[Y] = cube.maxY; obMax[Z] = cube.maxZ;

            double entry = Double.NEGATIVE_INFINITY;
            double exit = Double.POSITIVE_INFINITY;
            int entryAxis = -1;
            boolean miss = false;

            for (int a = 0; a < 3; a++) {
                double enter, leave;
                if (delta[a] == 0) {
                    if (boxMax[a] <= obMin[a] || boxMin[a] >= obMax[a]) {
                        miss = true;
                        break;
                    }
                    enter = Double.NEGATIVE_INFINITY;
                    leave = Double.POSITIVE_INFINITY;
                } else if (delta[a] > 0) {
                    enter = (obMin[a] - boxMax[a]) / delta[a];
                    leave = (obMax[a] - boxMin[a]) / delta[a];
                } else {
                    enter = (obMax[a] - boxMin[a]) / delta[a];
                    leave = (obMin[a] - boxMax[a]) / delta[a];
                }
                if (enter > entry) {
                    entry = enter;
                    entryAxis = a;
                }
                if (leave < exit) {
                    exit = leave;
                }
            }

            if (miss || entry >= exit || exit <= 0) {
                continue;   // never overlaps, or only moving away
            }

            if (entry < 0) {
                // already overlapping on every axis? then we start inside it
                boolean inside = true;
                for (int a = 0; a < 3; a++) {
                    double overlap = Math.min(boxMax[a], obMax[a]) - Math.max(boxMin[a], obMin[a]);
                    if (overlap <= OVERLAP_EPSILON) {
                        inside = false;
                        break;
                    }
                }
                if (inside) {
                    startSolid = true;
                    break;
                }
                if (entryAxis < 0) {
                    continue;
                }
                entry = 0;   // just touching, and moving into it
            }

            if (entry < best) {
                best = entry;
                bestAxis = entryAxis;
            }
        }

        if (startSolid) {
            trace.startSolid = true;
            trace.allSolid = true;
            trace.fraction = 0.0;
            set(trace.endPos, start);
            return trace;
        }

        if (bestAxis >= 0) {
            double travel = Math.abs(delta[bestAxis]);
            double fraction = best - DIST_EPSILON / travel;
            if (fraction < 0) {
                fraction = 0;
            }
            trace.fraction = fraction;
            for (int i = 0; i < 3; i++) {
                trace.endPos[i] = start[i] + delta[i] * fraction;
            }
            trace.normal[bestAxis] = delta[bestAxis] > 0 ? -1.0 : 1.0;
        }

        return trace;
    }

    /** PM_TestPlayerPosition: true when the hull at pos is not inside anything */
    private boolean isPositionValid(double[] pos) {
        AABB box = new AABB(pos[X] - HALF_WIDTH, pos[Y], pos[Z] - HALF_WIDTH,
                pos[X] + HALF_WIDTH, pos[Y] + HEIGHT, pos[Z] + HALF_WIDTH);
        for (AABB cube : this.level.getCubes(box)) {
            double ox = Math.min(box.maxX, cube.maxX) - Math.max(box.minX, cube.minX);
            double oy = Math.min(box.maxY, cube.maxY) - Math.max(box.minY, cube.minY);
            double oz = Math.min(box.maxZ, cube.maxZ) - Math.max(box.minZ, cube.minZ);
            if (ox > OVERLAP_EPSILON && oy > OVERLAP_EPSILON && oz > OVERLAP_EPSILON) {
                return false;
            }
        }
        return true;
    }

    /** PM_PointContents == CONTENTS_SOLID */
    private boolean pointSolid(double x, double y, double z) {
        double e = 0.001;
        for (AABB cube : this.level.getCubes(new AABB(x - e, y - e, z - e, x + e, y + e, z + e))) {
            if (x > cube.minX && x < cube.maxX
                    && y > cube.minY && y < cube.maxY
                    && z > cube.minZ && z < cube.maxZ) {
                return true;
            }
        }
        return false;
    }

    /** the liquid the point is under the surface of, null if none */
    private LiquidTile liquidAt(double x, double y, double z) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);

        LiquidTile liquid = this.level.getLiquids().get(ix, iy, iz);
        if (liquid == null) {
            return null;
        }
        return y < liquid.getSurfaceY(this.level, ix, iy, iz) ? liquid : null;
    }

    // =====================================================================
    // vector helpers

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void cross(double[] a, double[] b, double[] out) {
        out[0] = a[1] * b[2] - a[2] * b[1];
        out[1] = a[2] * b[0] - a[0] * b[2];
        out[2] = a[0] * b[1] - a[1] * b[0];
    }

    private static double normalize(double[] v) {
        double length = Math.sqrt(dot(v, v));
        if (length != 0) {
            double inverse = 1.0 / length;
            v[0] *= inverse;
            v[1] *= inverse;
            v[2] *= inverse;
        }
        return length;
    }

    private static void set(double[] to, double[] from) {
        to[0] = from[0];
        to[1] = from[1];
        to[2] = from[2];
    }
}