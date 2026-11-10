package com.nikohalfkino.rubydung.net;

/** another player as this machine sees them: the last position the server told us and a smoothed copy of it that is drawn */
public final class RemotePlayer {

    public final int id;
    public final String name;

    // last known state (eye position, like Player.x/y/z)
    public double x, y, z;
    public float yaw, pitch, bodyYaw;
    public int flags;

    // what is drawn: eases toward the last known state
    public double drawX, drawY, drawZ;
    public float drawBodyYaw;

    // what they just said, shown in a bubble over their name until bubbleUntilNanos
    public String bubble;
    public long bubbleUntilNanos;

    // their voice in the world: made the first time they speak, stopped when they leave
    public com.nikohalfkino.rubydung.audio.tts.JTalkSpeaker voice;

    /** walking through blocks */
    public boolean isNoClip() {
        return (this.flags & Protocol.FLAG_NOCLIP) != 0;
    }

    /** the body, from the last position the server told us (not the smoothed one: that is what the server checks too) */
    public com.nikohalfkino.rubydung.phys.AABB boundingBox() {
        double feet = this.y - com.nikohalfkino.rubydung.player.Player.EYE_HEIGHT;
        double half = com.nikohalfkino.rubydung.player.PlayerPhysics.HALF_WIDTH;
        return new com.nikohalfkino.rubydung.phys.AABB(this.x - half, feet, this.z - half,
                this.x + half, feet + com.nikohalfkino.rubydung.player.PlayerPhysics.HEIGHT, this.z + half);
    }

    public RemotePlayer(int id, String name, double x, double y, double z) {
        this.id = id;
        this.name = name;
        setState(x, y, z, 0.0F, 0.0F, 0.0F, 0);
        this.drawX = x;
        this.drawY = y;
        this.drawZ = z;
        this.drawBodyYaw = 0.0F;
    }

    public void setState(double x, double y, double z, float yaw, float pitch, float bodyYaw, int flags) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.bodyYaw = bodyYaw;
        this.flags = flags;
    }

    /** eases the drawn state toward the real one, seconds is the time since the last frame */
    public void step(float seconds) {
        double alpha = 1.0D - Math.exp(-seconds * 18.0D);

        // too far away (teleported): jump
        double dx = this.x - this.drawX;
        double dy = this.y - this.drawY;
        double dz = this.z - this.drawZ;
        if (dx * dx + dy * dy + dz * dz > 32.0D * 32.0D) {
            alpha = 1.0D;
        }

        this.drawX += dx * alpha;
        this.drawY += dy * alpha;
        this.drawZ += dz * alpha;

        float diff = ((this.bodyYaw - this.drawBodyYaw) % 360.0F + 540.0F) % 360.0F - 180.0F;
        this.drawBodyYaw += diff * (float) alpha;
    }
}
