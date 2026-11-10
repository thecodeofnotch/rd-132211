package com.nikohalfkino.rubydung.render;

import com.nikohalfkino.rubydung.player.Player;

import static org.lwjgl.opengl.GL11.glOrtho;
import static org.lwjgl.opengl.GL11.glRotatef;
import static org.lwjgl.opengl.GL11.glTranslated;
import static org.lwjgl.util.glu.GLU.gluPerspective;

/** views that are not the player's camera, but are placed on the player: the faces of a panorama and the isometric view */
public final class FixedViews {

    private static final float ISOMETRIC_VIEW_SIZE = 40.0F;
    private static final float ISOMETRIC_PITCH = 35.264F;

    private FixedViews() {
    }

    /** one face of a cube map around the player's eyes: 90 degrees wide, square */
    public static WorldView panoramaFace(Player player, float partialTicks, float yaw, float pitch) {
        return new PlayerAnchored(player, partialTicks, yaw, pitch) {
            @Override
            public void applyProjection(int viewportWidth, int viewportHeight) {
                gluPerspective(90.0F, 1.0F, 0.05F, FAR_PLANE);
            }
        };
    }

    /** a parallel view from above at an angle, looking at the player. yaw: see isometricYaw */
    public static WorldView isometric(Player player, float partialTicks, float yaw) {
        return new PlayerAnchored(player, partialTicks, yaw, ISOMETRIC_PITCH) {
            @Override
            public void applyProjection(int viewportWidth, int viewportHeight) {
                glOrtho(-ISOMETRIC_VIEW_SIZE, ISOMETRIC_VIEW_SIZE, -ISOMETRIC_VIEW_SIZE, ISOMETRIC_VIEW_SIZE,
                        -FAR_PLANE, FAR_PLANE);
            }

            @Override
            public boolean showsPlayer() {
                return true;
            }

            // the view is parallel: the sprite is seen from the side the view looks from, not from a point
            @Override
            public double viewX() {
                return eyeX() - Math.sin(Math.toRadians(yaw())) * 1000.0D;
            }

            @Override
            public double viewZ() {
                return eyeZ() + Math.cos(Math.toRadians(yaw())) * 1000.0D;
            }
        };
    }

    /** NE, SE, SW or NW (anything else is NW) */
    public static float isometricYaw(String direction) {
        switch (direction) {
            case "NE":
                return 45.0F;
            case "SE":
                return 135.0F;
            case "SW":
                return 225.0F;
            case "NW":
            default:
                return 315.0F;
        }
    }

    // a view whose eye is the player's, turned any way
    private abstract static class PlayerAnchored implements WorldView {

        private final double x, y, z;
        private final float yaw, pitch;

        PlayerAnchored(Player player, float partialTicks, float yaw, float pitch) {
            this.x = player.prevX + (player.x - player.prevX) * partialTicks;
            this.y = player.prevY + (player.y - player.prevY) * partialTicks;
            this.z = player.prevZ + (player.z - player.prevZ) * partialTicks;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public void applyView() {
            glRotatef(this.pitch, 1.0F, 0.0F, 0.0F);
            glRotatef(this.yaw, 0.0F, 1.0F, 0.0F);
            glTranslated(-this.x, -this.y, -this.z);
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
            return this.x;
        }

        @Override
        public double eyeY() {
            return this.y;
        }

        @Override
        public double eyeZ() {
            return this.z;
        }
    }
}
