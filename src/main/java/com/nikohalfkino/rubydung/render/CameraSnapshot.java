package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.util.GameSettings;

import static org.lwjgl.opengl.GL11.glRotatef;
import static org.lwjgl.opengl.GL11.glTranslated;
import static org.lwjgl.opengl.GL11.glTranslatef;
import static org.lwjgl.util.glu.GLU.gluPerspective;

/**
 * a copy of the player's camera as it was in one frame (PlayerCamera.snapshot). it does not change afterwards, so a screenshot taken later in the same frame sees exactly what the screen showed, and the same copy can be used by anything else that needs "the player's view" (a minimap, a replay)
 *
 * it does what PlayerCamera.apply does, and nothing is drawn with it except the world: the HUD is not part of a view
 */
public final class CameraSnapshot implements WorldView {

    private final double pivotX, pivotY, pivotZ;
    private final float backOffset;
    private final float distance;
    private final float yaw, pitch;
    private final double eyeX, eyeY, eyeZ;
    private final double skyX, skyY, skyZ;
    private final boolean detached;
    private final boolean playerVisible;

    CameraSnapshot(double pivotX, double pivotY, double pivotZ, float backOffset, float distance,
                   float yaw, float pitch, double eyeX, double eyeY, double eyeZ,
                   double skyX, double skyY, double skyZ, boolean detached, boolean playerVisible) {
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.pivotZ = pivotZ;
        this.backOffset = backOffset;
        this.distance = distance;
        this.yaw = yaw;
        this.pitch = pitch;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.skyX = skyX;
        this.skyY = skyY;
        this.skyZ = skyZ;
        this.detached = detached;
        this.playerVisible = playerVisible;
    }

    @Override
    public void applyProjection(int viewportWidth, int viewportHeight) {
        gluPerspective(GameSettings.getFov(), viewportWidth / (float) viewportHeight, 0.05F, FAR_PLANE);
    }

    @Override
    public void applyView() {
        glTranslatef(0.0F, 0.0F, -(this.backOffset + this.distance));
        glRotatef(this.pitch, 1.0F, 0.0F, 0.0F);
        glRotatef(this.yaw, 0.0F, 1.0F, 0.0F);
        glTranslated(-this.pivotX, -this.pivotY, -this.pivotZ);
    }

    @Override
    public float yaw() {
        return this.yaw;
    }

    @Override
    public float pitch() {
        return this.pitch;
    }

    @Override
    public double eyeX() {
        return this.eyeX;
    }

    @Override
    public double eyeY() {
        return this.eyeY;
    }

    @Override
    public double eyeZ() {
        return this.eyeZ;
    }

    @Override
    public double skyX() {
        return this.skyX;
    }

    @Override
    public double skyY() {
        return this.skyY;
    }

    @Override
    public double skyZ() {
        return this.skyZ;
    }

    @Override
    public boolean solidSky() {
        return this.detached;
    }

    @Override
    public boolean showsPlayer() {
        return this.playerVisible;
    }
}
