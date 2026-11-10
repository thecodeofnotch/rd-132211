package com.nikohalfkino.rubydung.render;

/**
 * where the 3D world is seen from. GameRenderer.renderWorld draws the picture for any WorldView, so the screen and a screenshot go through the same code and can not drift apart
 *
 * CameraSnapshot is the player's camera (first person, third person, freecam), FixedViews has the panorama faces and the isometric view. a script can supply its own (a security camera, a minimap)
 */
public interface WorldView {

    /** must reach past the skybox (radius 2000) and the sun / moon */
    float FAR_PLANE = 4096.0F;

    /** loads the projection matrix, the matrix mode is already GL_PROJECTION and the matrix is the identity */
    void applyProjection(int viewportWidth, int viewportHeight);

    /** loads the view matrix, the matrix mode is already GL_MODELVIEW and the matrix is the identity */
    void applyView();

    float yaw();

    float pitch();

    /** where the eye is: the billboards grow with the distance from it, the terrain is drawn around it */
    double eyeX();

    double eyeY();

    double eyeZ();

    /** where the sky is centred, the eye unless the view is detached from the player (freecam), then it stays on the player */
    default double skyX() {
        return eyeX();
    }

    default double skyY() {
        return eyeY();
    }

    default double skyZ() {
        return eyeZ();
    }

    /** the sky can be seen from outside, so it has to write and test depth */
    default boolean solidSky() {
        return false;
    }

    /** whether the local player's own sprite is in the picture (third person, a view from outside) */
    default boolean showsPlayer() {
        return false;
    }

    /** where the viewer is for choosing the sprite frame of a player (a parallel view looks from far away) */
    default double viewX() {
        return eyeX();
    }

    default double viewZ() {
        return eyeZ();
    }
}
