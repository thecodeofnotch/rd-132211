package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.entity.CubeManager;
import com.nikohalfkino.rubydung.entity.WorldEntity;
import com.nikohalfkino.rubydung.entity.ragdoll.Ragdoll;
import com.nikohalfkino.rubydung.entity.ragdoll.RagdollRig;
import com.nikohalfkino.rubydung.entity.ragdoll.RagdollSkin;
import com.nikohalfkino.rubydung.level.Level;
import org.lwjgl.opengl.GL20;

import static org.lwjgl.opengl.GL11.*;

/**
 * draws the ragdolls: the model of each one (ObjModel, one per RagdollRig), textured, lit like the cubes, with the mesh bent to the particles of the body (RagdollSkin, the particles come from ParticleEntity.renderPositions) and with the snapped vertices every model has (ModelRenderer)
 *
 * a model is read the first time one is drawn. if it can not be read, the ragdolls of that model are not drawn and the reason is printed once
 *
 * the mesh has no skeleton of its own: RagdollSkin gives every vertex the bone it follows (and a share of the next near a joint), worked out once per model
 *
 * the bending is done on the graphics card (RagdollShader): a model is uploaded once, and a ragdoll costs only its bones as uniforms and one draw call. if the shader can not be built the old way is used (RagdollSkin.pose on the CPU, then ModelRenderer.drawSkinned), which is also what the highlight of a targeted ragdoll uses
 *
 * fixed-function drawing with the camera's matrices, so it is called right after the cubes
 */
public final class RagdollRenderer {

    private static final String DIR = "/assets/rubydung/models/ragdolls/";

    /** a model that is loaded: the mesh, its texture, how much each vertex follows each limb, and the vertices as they are bent for the ragdoll being drawn */
    private static final class Mesh {
        ObjModel model;
        int texture;
        RagdollSkin skin;
        // the vertices bent on the CPU: for the highlight, and for everything when the shader is not available
        float[] posed;
        // the model on the graphics card, null when the shader is not available
        RagdollShader.Mesh gpu;
    }

    // static: the world renderer and the 2D icons (drawIcon) share one loaded copy of each model
    private static final Mesh[] meshes = new Mesh[RagdollRig.count()];
    private static final boolean[] failed = new boolean[RagdollRig.count()];

    private final double[] particles = new double[RagdollRig.PARTICLES * 3];

    // the bones of the ragdoll being drawn (RagdollSkin.bones) and the snap grid of the viewport
    private final float[] boneRotation = new float[RagdollSkin.BONES * 9];
    private final float[] boneOrigin = new float[RagdollSkin.BONES * 3];
    private final float[] snap = new float[2];

    // the ragdolls that passed the frustum test this frame (reused, so the test allocates nothing)
    private final java.util.List<Ragdoll> visible = new java.util.ArrayList<>();

    // half the side of the box a ragdoll is culled with, around its position: a person is under 2 blocks tall, a limb flung out stays well inside this
    private static final double CULL_RADIUS = 2.5D;

    /** draws every ragdoll. partialTicks blends the last two ticks */
    public void render(Level level, CubeManager manager, float partialTicks) {
        // only the ragdolls on screen are posed and drawn: each one costs thousands of vertex calls, and with the
        // camera's matrices still active here (ModelRenderer.begin replaces them below) the frustum is the camera's
        this.visible.clear();
        Frustum frustum = null;
        for (WorldEntity entity : manager.all()) {
            if (!(entity instanceof Ragdoll)) {
                continue;
            }
            if (frustum == null) {
                frustum = Frustum.getFrustum();
            }
            Ragdoll ragdoll = (Ragdoll) entity;
            double x = ragdoll.prevX + (ragdoll.x - ragdoll.prevX) * partialTicks;
            double y = ragdoll.prevY + (ragdoll.y - ragdoll.prevY) * partialTicks;
            double z = ragdoll.prevZ + (ragdoll.z - ragdoll.prevZ) * partialTicks;
            if (Fog.beyond(x, z, CULL_RADIUS)) {
                continue;   // past the fog: the terrain around it is gone too
            }
            if (frustum.cubeInFrustum((float) (x - CULL_RADIUS), (float) (y - CULL_RADIUS), (float) (z - CULL_RADIUS),
                    (float) (x + CULL_RADIUS), (float) (y + CULL_RADIUS), (float) (z + CULL_RADIUS))) {
                this.visible.add(ragdoll);
            }
        }
        if (this.visible.isEmpty()) {
            return;
        }

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT);
        glEnable(GL_TEXTURE_2D);
        glDisable(GL_FOG);
        glDisable(GL_BLEND);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_CULL_FACE);
        glDepthMask(true);

        // on the graphics card when it can: the camera's matrices stay as they are and the program does the snapping.
        // otherwise ModelRenderer takes over the matrices (it sets them back in end())
        boolean gpu = RagdollShader.available();
        if (gpu) {
            ModelRenderer.snapStep(this.snap);
            RagdollShader.bind(this.snap[0], this.snap[1]);
        } else {
            ModelRenderer.begin();
        }
        try {
            for (int i = 0; i < this.visible.size(); i++) {
                draw(level, this.visible.get(i), partialTicks, gpu);
            }
        } finally {
            if (gpu) {
                RagdollShader.unbind();
            } else {
                ModelRenderer.end();
            }
            glPopAttrib();
            GL20.glUseProgram(program);
        }
    }

    private void draw(Level level, Ragdoll ragdoll, float partialTicks, boolean gpu) {
        Mesh mesh = mesh(ragdoll.model());
        if (mesh == null) {
            return;
        }

        double x = ragdoll.prevX + (ragdoll.x - ragdoll.prevX) * partialTicks;
        double y = ragdoll.prevY + (ragdoll.y - ragdoll.prevY) * partialTicks;
        double z = ragdoll.prevZ + (ragdoll.z - ragdoll.prevZ) * partialTicks;

        int cellX = (int) Math.floor(x);
        int cellY = (int) Math.floor(y);
        int cellZ = (int) Math.floor(z);
        float base = level.getBrightness(cellX, cellY, cellZ);
        float block = level.getBlockLight(cellX, cellY, cellZ);
        float brightness = base + (1.0F - base) * block;

        glBindTexture(GL_TEXTURE_2D, mesh.texture);
        if (gpu && mesh.gpu != null) {
            // only the bones go over: the shader bends the mesh
            ragdoll.renderPositions(partialTicks, this.particles);
            mesh.skin.bones(this.particles, x, y, z, this.boneRotation, this.boneOrigin);
            RagdollShader.setPose(this.boneRotation, this.boneOrigin, x, y, z, RagdollShader.scaleOf(mesh.gpu), brightness);
            RagdollShader.draw(mesh.gpu);
            return;
        }

        if (gpu) {
            // this model has no copy on the graphics card (its upload failed), and the program is bound: draw it the CPU way with the fixed pipeline
            RagdollShader.unbind();
            ModelRenderer.begin();
            try {
                pose(ragdoll, mesh, partialTicks, x, y, z);
                ModelRenderer.drawSkinned(mesh.model, mesh.posed, x, y, z, brightness, true, null, -1);
            } finally {
                ModelRenderer.end();
                RagdollShader.bind(this.snap[0], this.snap[1]);
            }
            return;
        }

        pose(ragdoll, mesh, partialTicks, x, y, z);
        ModelRenderer.drawSkinned(mesh.model, mesh.posed, x, y, z, brightness, true, null, -1);
    }

    /**
     * the highlight of the ragdoll the crosshair is on: the same pulsing white glow a targeted cube gets, on the limb that would be picked up (part, see GrabPick). call with the camera's matrices active, like CubeRenderer.renderHighlight
     */
    public void renderHighlight(Ragdoll ragdoll, int part, float partialTicks) {
        Mesh mesh = mesh(ragdoll.model());
        if (mesh == null) {
            return;
        }

        double x = ragdoll.prevX + (ragdoll.x - ragdoll.prevX) * partialTicks;
        double y = ragdoll.prevY + (ragdoll.y - ragdoll.prevY) * partialTicks;
        double z = ragdoll.prevZ + (ragdoll.z - ragdoll.prevZ) * partialTicks;
        float pulse = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2F + 0.4F;

        pose(ragdoll, mesh, partialTicks, x, y, z);

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_POLYGON_BIT | GL_CURRENT_BIT);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_FOG);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        glColor4f(1.0F, 1.0F, 1.0F, pulse);

        // pulled toward the viewer like the block highlight, so it does not fight with the model's own faces
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0F, -4.0F);
        glDepthMask(false);

        ModelRenderer.begin();
        try {
            ModelRenderer.drawSkinned(mesh.model, mesh.posed, x, y, z, 1.0F, false,
                    mesh.skin.primaryBones(), RagdollSkin.boneForPart(part));
        } finally {
            ModelRenderer.end();
            glPopAttrib();
            GL20.glUseProgram(program);
        }
    }

    // ---------------------------------------------------------------- the icon

    /**
     * draws a model as a 2d icon (inventory slot, selected-tile HUD, hand): the real mesh in its rest pose, turned spinDegrees about its vertical axis, standing with its middle at (centerX, centerY) and height pixels tall. centerX/Y are in window pixels (top-left origin), screenWidth/Height is the area the 2D UI is laid out in, like TileIconRenderer.draw. brightness 1 = as the texture is
     *
     * it sets up its own orthographic projection and hands the model to ModelRenderer through it, so it can be called wherever TileIconRenderer.draw can (not inside a Gui2D.begin/end pass) and leaves the GL state as it found it. render thread only
     */
    public static void drawIcon(int model, float centerX, float centerY, float height,
                                int screenWidth, int screenHeight, float spinDegrees, float brightness) {
        Mesh mesh = mesh(model);
        if (mesh == null || height < 1.0F) {
            return;
        }
        RagdollRig rig = RagdollRig.get(model);

        int program = glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT);
        glEnable(GL_TEXTURE_2D);
        glDisable(GL_FOG);
        glDisable(GL_BLEND);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glClear(GL_DEPTH_BUFFER_BIT);   // like TileIconRenderer: the icon sorts against itself only

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, screenWidth, screenHeight, 0.0, 100.0, 300.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glTranslatef(0.0F, 0.0F, -200.0F);
        glTranslatef(centerX, centerY, 0.0F);
        glScalef(1.0F, -1.0F, 1.0F);   // window y points down, the model's y up (culling is off, so the flipped winding does not matter)

        // yaw about +y, column-major like WorldEntity.writeMatrix
        double yaw = Math.toRadians(spinDegrees);
        float c = (float) Math.cos(yaw);
        float s = (float) Math.sin(yaw);
        float[] rotation = {
                c, 0.0F, -s, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F,
                s, 0.0F, c, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F};

        glBindTexture(GL_TEXTURE_2D, mesh.texture);
        ModelRenderer.begin();   // reads the matrices above, so the model lands in window pixels
        try {
            // the unbent mesh is the rest pose. its middle (half the model's height) goes to the origin
            ModelRenderer.draw(mesh.model, mesh.model.positions, rotation, (float) (height / rig.modelHeight),
                    0.0F, (float) (rig.modelHeight * 0.5D), 0.0F,
                    0.0D, 0.0D, 0.0D, brightness, true);
        } finally {
            ModelRenderer.end();
            glMatrixMode(GL_MODELVIEW);
            glPopMatrix();
            glMatrixMode(GL_PROJECTION);
            glPopMatrix();
            glMatrixMode(GL_MODELVIEW);
            glPopAttrib();
            GL20.glUseProgram(program);
        }
    }

    // ---------------------------------------------------------------- the pose

    // the mesh bent to the particles as they are between the last tick and this one, in blocks relative to (ox, oy, oz), in mesh.posed
    private void pose(Ragdoll ragdoll, Mesh mesh, float partialTicks, double ox, double oy, double oz) {
        ragdoll.renderPositions(partialTicks, this.particles);
        mesh.skin.pose(mesh.model.positions, this.particles, ox, oy, oz, mesh.posed);
    }

    // ---------------------------------------------------------------- loading

    private static Mesh mesh(int index) {
        if (meshes[index] != null) {
            return meshes[index];
        }
        if (failed[index]) {
            return null;
        }

        RagdollRig rig = RagdollRig.get(index);
        String folder = DIR + rig.name + "/";
        try {
            Mesh mesh = new Mesh();
            mesh.model = ObjModel.load(folder + rig.objFile);
            mesh.texture = Textures.loadTexture(folder + rig.textureFile, GL_NEAREST);

            mesh.skin = new RagdollSkin(rig, mesh.model.positions);
            mesh.posed = new float[mesh.model.vertexCount() * 3];
            if (RagdollShader.available()) {
                try {
                    mesh.gpu = RagdollShader.upload(mesh.model, mesh.skin);
                } catch (RuntimeException e) {
                    System.err.println("[RagdollRenderer] model " + rig.name + " stays on the CPU: " + e.getMessage());
                }
            }
            meshes[index] = mesh;
            return mesh;
        } catch (RuntimeException e) {
            System.err.println("[RagdollRenderer] model " + rig.name + " failed: " + e.getMessage());
            failed[index] = true;
            return null;
        }
    }
}