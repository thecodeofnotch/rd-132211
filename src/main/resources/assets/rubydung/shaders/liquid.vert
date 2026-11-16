#version 120
invariant gl_Position;   // the depth-only program (liquid_depth.vert) computes the same position, so both passes agree exactly
// MAX_LIQUIDS is #defined by LiquidShader.java (keep in sync with LiquidTile.MAX_LIQUIDS)

uniform vec4 liqColor[MAX_LIQUIDS];
uniform vec4 liqFoam[MAX_LIQUIDS];
uniform vec4 liqMotion[MAX_LIQUIDS];
uniform vec4 liqSheet[MAX_LIQUIDS];   // x = columns, y = rows of the texture sheet

varying vec3  vWorld;
varying vec2  vCell;
varying vec3  vLight;
varying vec4  vBase;
varying vec4  vFoam;
varying vec4  vMotion;
varying vec2  vSheet;
varying vec4  vEdges;
varying vec4  vCorners;
varying vec2  vPane;         // a pane runs along x / along z through this cell
varying vec4  vRectA;       // partial footprints (door, pane, stair, slab...) in cell coordinates: x0, z0, x1, z1. empty = all zero
varying vec4  vRectB;
varying vec4  vRectC;
varying float vTop;
varying float vFoamWeight;   // 0..1 (from 0..3)
varying float vLiqIndex;

float bit(float v, float n) {
    return mod(floor(v / pow(2.0, n)), 2.0);
}

// packed alpha layout, written by LiquidTile.pack():
//   bits 0-3 edge mask, 4-7 corner mask, 8 top face, 9 surface vertex (unused now that the waves are gone),
//   10-11 foam weight, 12-14 liquid index, 15 pane along x, 16 pane along z
void main() {
    float allBits     = floor(gl_Color.a * 131072.0 + 0.5);
    float paneF       = floor(allBits / 32768.0);
    float packedBits  = allBits - paneF * 32768.0;
    float indexF      = floor(packedBits / 4096.0);
    float remaining   = packedBits - indexF * 4096.0;
    float foamWeightF = floor(remaining / 1024.0);
    float flags       = remaining - foamWeightF * 1024.0;
    int   index       = int(indexF + 0.5);

    vBase       = liqColor[index];
    vFoam       = liqFoam[index];
    vMotion     = liqMotion[index];
    vSheet      = liqSheet[index].xy;
    vEdges      = vec4(bit(flags, 0.0), bit(flags, 1.0), bit(flags, 2.0), bit(flags, 3.0));
    vCorners    = vec4(bit(flags, 4.0), bit(flags, 5.0), bit(flags, 6.0), bit(flags, 7.0));
    vTop        = bit(flags, 8.0);
    vPane       = vec2(bit(paneF, 0.0), bit(paneF, 1.0));
    vFoamWeight = foamWeightF / 3.0;
    vLiqIndex   = indexF;

    vec4 p = gl_Vertex;

    vWorld      = p.xyz;
    // the ocean's quads are built at y = 0 and lifted to the sea with a glTranslatef (OceanSurface), so gl_Vertex.y is not the world height
    // there. only the ocean sets texcoord 1 to a real sea height, everything else has OceanSurface.NO_FADE (-1e4). uSeaSurface is declared by
    // LiquidShader.addFog(). entity foam and wake rings compare against vWorld.y
    if (gl_MultiTexCoord1.s > -9000.0) {
        vWorld.y += uSeaSurface;
    }
    vCell       = gl_MultiTexCoord0.st;
    vLight      = gl_Color.rgb;
    vRectA      = gl_MultiTexCoord2;   // only the ocean surface sets these, everything else leaves them 0
    vRectB      = gl_MultiTexCoord3;
    vRectC      = gl_MultiTexCoord4;
    gl_Position = gl_ModelViewProjectionMatrix * p;
}
