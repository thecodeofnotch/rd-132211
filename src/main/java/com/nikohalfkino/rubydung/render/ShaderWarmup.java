package com.nikohalfkino.rubydung.render;

/**
 * builds the game's shader programs while the world loads, one per call, so none of them is compiled in the middle of the first world frame (each one is 5 to 30 ms, and the first frame used to pay for all of them at once)
 *
 * call step() once per frame from the loading screen: it compiles the next program and returns true while there are more. render thread only (it needs the GL context). a program that fails to build prints its error here, the same one the first real use would have thrown, and is skipped so the others still get built
 */
public final class ShaderWarmup {

    private static final Runnable[] PROGRAMS = {
            TerrainShader::warmUp,
            LiquidShader::warmUp,
            LiquidShader::warmUpDepth,
            StaticBillboardShader::warmUp,
            BillboardShader::warmUp,
            RagdollShader::warmUp,
            SkyDomeShader::warmUp,
            CloudShader::warmUp,
            WorldBorderRenderer::warmUp,
    };

    private static int next = 0;

    private ShaderWarmup() {
    }

    /** builds the next program. true if there are more to build on the next call */
    public static boolean step() {
        if (next >= PROGRAMS.length) {
            return false;
        }
        Runnable program = PROGRAMS[next++];
        try {
            program.run();
        } catch (RuntimeException e) {
            System.err.println("[ShaderWarmup] " + e.getMessage());
        }
        return next < PROGRAMS.length;
    }

    /** builds everything that is left at once (for a place that can not spread it over frames) */
    public static void finish() {
        while (step()) {
            // each call builds one
        }
    }
}