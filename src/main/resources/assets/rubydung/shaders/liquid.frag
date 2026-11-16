#version 120
// MAX_LIQUIDS, MAX_ENTITIES, MAX_WAKES and WAKE_LIFE are #defined by LiquidShader.java (from LiquidTile / LiquidEntities),
// which also writes the liqTexN sampler uniforms
// and sampleLiq() in place of the marker line further down

// ---- tunables -------------------------------------------------------------
// texels per block: matches the 16x16 block textures. every sample, the shore and the foam
// are snapped to this grid, so the liquid has the same pixel size as the terrain.
// (lower = chunkier pixels, 8.0 = each pixel is 2x2 texels of the terrain)
const float PIXELS_PER_BLOCK = 16.0;
// how far the liquid's own colour is pushed onto the texture (0 = texture only, 1 = full tint)
const float TINT = 0.35;
// colour steps per channel for the retro palette (8 -> 512 colours, 32 -> 32768).
// keep it high enough for dark liquids: sludge.png only spans about 0.14 - 0.29 per channel, which 8 steps
// (1/7 = 0.14 apart) squash into 2-3 colours
const float COLOR_LEVELS = 32.0;
// turbulence reach in blocks per unit of the liquid's warp setting
const float WARP_BLOCKS = 0.75;
// the warp fades out with distance from the eye (blocks): full strength up to START, none from END on.
// past END the four sin() calls are skipped altogether
const float WARP_FADE_START = 24.0;
const float WARP_FADE_END   = 56.0;
// opacity of the shore rim (it is shaped by the same foam texture as the foam, just always present)
const float RIM_STRENGTH = 0.7;
// half the thickness, in blocks, of a pane (2 px collision) when the shader draws its footprint for the shore
const float PANE_HALF = 1.0 / 16.0;
// foam: distortion.png repeats this many times per block. 0.25 = one texel of the image is one texel of the
// world (64px image -> repeats every 4 blocks), so the foam pixels match the terrain's pixel size
const float FOAM_SCALE = 0.25;
// how far the foam coordinates are pushed around (in repeats of the image) and how fast that pattern varies
const float FOAM_WOBBLE = 0.04;
const float FOAM_WOBBLE_FREQ = 2.0;
// how much of the liquid's foam colour is laid over the water (1 = solid, lower = see-through foam)
const float FOAM_OPACITY = 0.55;

// lighting: the same steps and curve as TerrainShader (LIGHT_STEPS / LIGHT_CONTRAST / LIGHT_SMOOTH), so the liquid is lit in the same bands as the blocks around it
const float LIGHT_STEPS    = 15.0;
const float LIGHT_CONTRAST = 1.5;
const float LIGHT_SMOOTH   = 0.75;

// things standing in the liquid (LiquidEntities): their footprint is a wall of the shore, so it gets the same rim and foam waves.
// a body counts while the surface is between its feet and its head, give or take this many blocks
const float ENTITY_Y_SLACK = 0.06;

// wake: a trail left behind anything that moves through the liquid (LiquidEntities drops one stretch every ~1/3 block). it is drawn
// with the SAME wave as the shore foam: every stretch sends a foam crest sideways from the flank of the body, the crest has the
// shore's sharp leading texel and soft trailing edge, and the same foam texture breaks it into blobs. the crests drift outwards as
// they age, so a moving body trails a V of foam arms and the strip between the arms (right behind the body) stays clear. a body that
// falls in sends one ring out the same way
const float WAKE_SPREAD       = 0.35;   // blocks per second a crest drifts away from the body, in water (it follows the liquid's flow speed)
const float WAKE_SPLASH_SPEED = 0.8;    // blocks per second the ring of a splash grows
const float WAKE_TRAIL        = 0.45;   // blocks behind its leading edge a crest can still show (the wave profile ends well before this)
const float WAKE_DISSOLVE     = 0.9;    // how much of the foam texture is lost as a stretch ages (higher = breaks into smaller blobs sooner)
const float WAKE_FAST_LIFE    = 0.4;    // fraction of WAKE_LIFE a stretch lasts when the body was at full speed (it was dropped with strength 0.9). a trail is
// speed * lifetime long, so the faster the body, the faster the wake has to go. 1.0 = no shortening
const float WAKE_FPS          = 8.0;    // the wake moves in steps, this many per second (lower = choppier), like the pixel-art foam
const float WAKE_OPACITY      = 0.55;   // how much of the foam colour the wake lays over the liquid. same as FOAM_OPACITY, so wake and shore match

uniform float time;
uniform float ambient;
uniform sampler2D foamTex;   // textures/block/liquid/distortion.png, bound by LiquidShader

// LiquidEntities, one row per entity: box = minX, minZ, maxX, maxZ.  info = minY, maxY, corner radius, unused
uniform vec4  uEntityBox[MAX_ENTITIES];
uniform vec4  uEntityInfo[MAX_ENTITIES];
uniform float uEntityCount;
// one row per stretch of wake: x, z, birth time (same clock as `time`), surface y.
// info = unit direction x, z the body was travelling, strength 0..1, half width of the body. a splash: no direction, w = minus its radius
uniform vec4  uWake[MAX_WAKES];
uniform vec4  uWakeInfo[MAX_WAKES];
//@SAMPLERS

varying vec3  vWorld;
varying vec2  vCell;
varying vec3  vLight;
varying vec4  vBase;
varying vec4  vFoam;
varying vec4  vMotion;
varying vec2  vSheet;
varying vec4  vEdges;
varying vec4  vCorners;
varying vec2  vPane;
varying vec4  vRectA;
varying vec4  vRectB;
varying vec4  vRectC;
varying float vTop;
varying float vFoamWeight;
varying float vLiqIndex;

vec3 posterize(vec3 c) {
    float n = COLOR_LEVELS - 1.0;
    return clamp(floor(c * n + 0.5) / n, 0.0, 1.0);
}

// distance from p to the rectangle lo..hi (negative inside)
float boxDistance(vec2 p, vec2 lo, vec2 hi) {
    vec2 c = (lo + hi) * 0.5;
    vec2 d = abs(p - c) - (hi - lo) * 0.5;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

// distance from u (position inside the cell, 0..1) to the nearest solid wall
float shoreDistance(vec2 u) {
    float sdf = 9.0;
    if (vEdges.x > 0.5) sdf = min(sdf, u.x);
    if (vEdges.y > 0.5) sdf = min(sdf, 1.0 - u.x);
    if (vEdges.z > 0.5) sdf = min(sdf, u.y);
    if (vEdges.w > 0.5) sdf = min(sdf, 1.0 - u.y);
    if (vCorners.x > 0.5) sdf = min(sdf, length(u));
    if (vCorners.y > 0.5) sdf = min(sdf, length(vec2(1.0 - u.x, u.y)));
    if (vCorners.z > 0.5) sdf = min(sdf, length(vec2(u.x, 1.0 - u.y)));
    if (vCorners.w > 0.5) sdf = min(sdf, length(vec2(1.0) - u));
    // panes (metal bars, framed glass): a thin plane through the middle of the cell, running along x and/or z
    if (vPane.x > 0.5) sdf = min(sdf, boxDistance(u, vec2(0.0, 0.5 - PANE_HALF), vec2(1.0, 0.5 + PANE_HALF)));
    if (vPane.y > 0.5) sdf = min(sdf, boxDistance(u, vec2(0.5 - PANE_HALF, 0.0), vec2(0.5 + PANE_HALF, 1.0)));
    // the real footprints of partial shapes (doors, panes, stairs, slabs, sub tiles): the shore follows their box, not the cell
    if (vRectA.z > vRectA.x) sdf = min(sdf, boxDistance(u, vRectA.xy, vRectA.zw));
    if (vRectB.z > vRectB.x) sdf = min(sdf, boxDistance(u, vRectB.xy, vRectB.zw));
    if (vRectC.z > vRectC.x) sdf = min(sdf, boxDistance(u, vRectC.xy, vRectC.zw));
    return sdf;
}

// distance from p (blocks, xz) to the nearest entity body that the liquid surface at height y runs through (negative inside, 9 = none)
float entityDistance(vec2 p, float y) {
    float d = 9.0;
    for (int i = 0; i < MAX_ENTITIES; i++) {
        if (float(i) >= uEntityCount) break;
        vec4 info = uEntityInfo[i];
        if (y < info.x - ENTITY_Y_SLACK || y > info.y + ENTITY_Y_SLACK) continue;
        vec4 b = uEntityBox[i];
        float r = info.z;   // rounded corners: a player or a piece is round, a cube nearly square
        d = min(d, boxDistance(p, b.xy + r, b.zw - r) - r);
    }
    return d;
}

// foam pattern, from the distortion image (the red channel is the blob texture)
float foamAt(vec2 pos, float t) {
    // foam perturbed coordinates
    vec2 uv = pos * FOAM_SCALE;
    uv.x += cos(pos.y * FOAM_WOBBLE_FREQ + t) * FOAM_WOBBLE;
    uv.y += sin(pos.x * FOAM_WOBBLE_FREQ + t) * FOAM_WOBBLE;

    // process the blob texture into a foam-like pattern
    float foam = texture2D(foamTex, uv).x;
    foam = 1.0 - foam;
    foam = min(foam * 1.2, 1.0);
    foam = pow(foam, 4.0);
    return min(foam * 1.5, 1.0);
}

// the sawtooth of the coastal foam wave, 0..1: a one texel ramp up, the crest, then a soft fall-off over the next few texels.
// texel is a whole number of texels along the direction the wave travels (the crest sits where texel wraps round), the wave repeats every block
float wavePulse(float texel) {
    float pulse = (mod(texel, PIXELS_PER_BLOCK) + 0.5) / PIXELS_PER_BLOCK;
    float front = min((pulse - 0.95) * 20.0, 1.0);
    float back  = pow(1.0 - pulse, 8.0);
    return max(front, back);
}

// the wake: 0..1 of foam at p, from every stretch of wake that is still alive on this liquid surface (height y). foam is foamAt(p).
// a stretch is a crest of the shore wave that starts at the flank of the body (the "wall") and travels outwards. `lead` is how far
// behind the front of that crest p lies, in blocks, and it goes through the same texel snapping, wavePulse() and foam breakup as the
// foam waves of the shore. flow is the liquid's flow speed: the arms of lava creep apart
float wakeAt(vec2 p, float y, float foam, float flow) {
    float spread = WAKE_SPREAD * clamp(flow, 0.35, 1.5);
    float pixel  = 1.0 / PIXELS_PER_BLOCK;
    float k = 0.0;
    // the clock the wake runs on is rounded down to WAKE_FPS steps, and so is the birth of every stretch: all ages are whole steps,
    // so the crests move in jumps instead of smoothly
    float stepped = floor(time * WAKE_FPS) / WAKE_FPS;
    for (int i = 0; i < MAX_WAKES; i++) {
        vec4 w = uWake[i];
        float age = stepped - floor(w.z * WAKE_FPS) / WAKE_FPS;
        if (age < 0.0 || age > WAKE_LIFE) continue;
        if (abs(y - w.w) > 0.25) continue;               // it only lies on the surface it was dropped on
        vec4  info = uWakeInfo[i];
        // a fast body leaves stretches that die sooner, so a trail dragged through the liquid stays a few blocks long, not 20
        float lifeLen = info.w < 0.0 ? WAKE_LIFE : WAKE_LIFE * mix(1.0, WAKE_FAST_LIFE, smoothstep(0.4, 0.9, info.z));
        if (age > lifeLen) continue;
        float life = age / lifeLen;
        vec2  rel  = p - w.xy;
        float env  = 1.0 - life * life;                  // how much of it is left: nearly all for a while, then it goes, smoothly
        float power = 0.6 + 0.4 * info.z;                // 0.6..1, harder hits and faster bodies leave a stronger wake
        float sd;                                        // distance from the body's edge (the "wall"), blocks. negative = inside the body's path
        float front;                                     // where the crest is right now, as a distance from that edge
        float cover = 1.0;                               // 0..1, how much of the length of the trail this stretch owns

        if (info.w < 0.0) {
            // splash: a ring that leaves the body's rim and grows, gone in half the time
            float splashLife = min(age / (WAKE_LIFE * 0.5), 1.0);
            env   = 1.0 - splashLife * splashLife;
            sd    = length(rel) + info.w;                // info.w = minus the radius
            front = age * WAKE_SPLASH_SPEED * (0.6 + 0.4 * info.z);
        } else {
            vec2  dir   = info.xy;
            float along = dot(rel, dir);
            // this stretch owns the way back to the one dropped before it, so the trail has no gaps. the back end fades out (the older
            // stretch overlaps it there), and it ends where the body was when it was dropped
            if (along > 0.06 || along < -0.6) continue;
            cover = (1.0 - smoothstep(0.0, 0.06, along)) * smoothstep(-0.6, -0.45, along);
            float side = abs(dot(rel, vec2(-dir.y, dir.x)));
            sd    = side - info.w;
            front = 0.03 + age * spread * (0.5 + info.z);
            if (sd < 0.0) continue;                      // between the arms, right behind the body: clear water, no line down the middle
        }

        float a = 0.0;
        float lead = front - sd;                         // 0 = on the crest, positive = behind it, towards the body
        if (sd < 0.0) {
            // inside a splash: loose patches of the foam texture that go as the splash does
            a = max(0.0, foam - 0.2) * env;
        } else if (lead > -pixel && lead < WAKE_TRAIL) {
            // the same wave as the shore. texel counted outwards-to-inwards from the front, so the crest has its sharp texel on the outer side
            float height = wavePulse(floor(lead * PIXELS_PER_BLOCK));
            float tail   = 1.0 - smoothstep(0.5 * WAKE_TRAIL, WAKE_TRAIL, lead);
            // same breakup formula as the shore foam. an older stretch loses part of the texture, so only the strongest blobs are left
            float dissolve = (1.0 - env) * WAKE_DISSOLVE + (1.0 - power) * 0.5;
            a = max(0.0, height * 1.5 - 1.0 + foam - dissolve) * tail * clamp(env * 1.5, 0.0, 1.0);
        }
        k = max(k, a * cover);
    }
    return clamp(k, 0.0, 1.0);
}

void main() {
    float t    = time * vMotion.y;
    bool  top  = vTop > 0.5;
    int   idx  = int(vLiqIndex + 0.5);
    float eyeDist = length(vFogWorldXZ - uFogEye);   // both declared by LiquidShader's fog injection
    float warp = vMotion.x * WARP_BLOCKS * (1.0 - smoothstep(WARP_FADE_START, WARP_FADE_END, eyeDist));
    float P    = PIXELS_PER_BLOCK;

    // top face uses XZ, sides unroll along the wall
    vec2 pos = top ? vWorld.xz : vec2(vWorld.x + vWorld.z, vWorld.y);

    // snap to the texel grid first, so the whole texel moves together when warped
    vec2 pp = (floor(pos * P) + 0.5) / P;

    // Quake-style turbulence, in blocks; then snap again so we always read whole texels
    vec2 w = pp;
    if (warp > 0.0) {
        w.x += warp * (sin(pp.y * 4.0 + t)        + 0.4 * sin(pp.y * 7.3 - t * 0.6));
        w.y += warp * (sin(pp.x * 4.0 + t * 1.07) + 0.4 * sin(pp.x * 8.1 + t * 0.8));
    }
    vec2 uv = (floor(w * P) + 0.5) / P;      // position in blocks, on the texel grid

    // the texture is a sheet of vSheet.x * vSheet.y cells laid over the world, like WrappedTile:
    // one cell per block, repeating every vSheet blocks. top faces: columns follow X, rows follow Z.
    // side faces: along the wall, and up is up the sheet (row 0 = top)
    uv = vec2(uv.x, top ? uv.y : -uv.y) / vSheet;

    vec4 tex   = sampleLiq(idx, uv);
    vec3 color = mix(tex.rgb, clamp(tex.rgb * vBase.rgb * 2.0, 0.0, 1.0), TINT);
    // a fully opaque liquid (lava, sludge) ignores the texture's own alpha
    float alpha = vBase.a > 0.999 ? 1.0 : vBase.a * tex.a;

    if (top) {
        vec2 u = (min(floor(vCell * P), P - 1.0) + 0.5) / P;   // pixel centre inside the cell
        float sdf = max(shoreDistance(u), 0.0);
        // entities are walls too: the distance to the nearest of them is measured in the world, on the texel grid
        if (uEntityCount > 0.5) {
            sdf = min(sdf, max(entityDistance(pp, vWorld.y), 0.0));
        }

        bool  nearWall = sdf < 8.0;
        bool  wakeOn   = eyeDist < WARP_FADE_END;
        float foam     = (nearWall || wakeOn) ? foamAt(pp, t) : 0.0;
        float wake     = wakeOn ? wakeAt(pp, vWorld.y, foam, vMotion.y) : 0.0;

        if (nearWall || wake > 0.0) {
            // rim: a lip hugging every wall (and every body in the liquid), broken up by the same foam texture as the foam itself
            float lip = nearWall ? (1.0 - smoothstep(0.0, 0.12, sdf)) * (0.4 + 0.6 * foam) : 0.0;
            float k = lip * RIM_STRENGTH;

            // foam waves: only where LiquidTile marked the body as big (foamWeight > 0)
            if (nearWall && vFoam.a > 0.0 && vFoamWeight > 0.0) {
                // coastal pulse wave. the wave position is counted in whole texels (read at the texel centre),
                // so the crest is never thinner than one pixel
                float height = wavePulse(floor((sdf + t * 0.2) * P));
                float fade   = 1.0 - clamp((sdf - 0.6) * 5.0, 0.0, 1.0);
                float wave   = max(0.0, height * 1.5 - 1.0 + foam) * fade * vFoam.a * vFoamWeight;
                k = max(k, wave * FOAM_OPACITY);
            }

            // wake: the foam colour of the liquid, so lava's is orange
            k = max(k, wake * WAKE_OPACITY);

            color = mix(color, vFoam.rgb, k);
            alpha = mix(alpha, 1.0, max(lip * RIM_STRENGTH * 0.5, wake * 0.5));
        }
    }

    color = posterize(color);

    // lit like the terrain: sky and block light curved, then rounded to LIGHT_STEPS levels with a soft edge between two of them
    float s  = pow(clamp(vLight.x, 0.0, 1.0), LIGHT_CONTRAST) * ambient;
    float bl = pow(clamp(vLight.y, 0.0, 1.0), LIGHT_CONTRAST);
    float l0 = clamp(s + (1.0 - s) * bl, 0.0, 1.0);
    float sw = max(LIGHT_SMOOTH, 1.0e-4);
    float q  = l0 * LIGHT_STEPS + 0.5 - 0.5 * sw;
    float l  = (floor(q) + smoothstep(1.0 - sw, 1.0, fract(q))) / LIGHT_STEPS * vLight.z;
    gl_FragColor = vec4(color * l, alpha);
}