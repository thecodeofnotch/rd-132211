package com.nikohalfkino.rubydung.level.tiles.special;

/**
 * colour of a dimensional door: a coloured door only leads to a world with a door of the same colour
 *
 * REGULAR is universal and matches every colour (see matches)
 */
public enum DoorColor {
    REGULAR("", "Regular", 0xE0A868),
    BLUE("blue", "Blue", 0x88AAFF),
    GREEN("green", "Green", 0x90E890),
    PURPLE("purple", "Purple", 0xC8A0FF),
    RED("red", "Red", 0xFF8C8C),
    YELLOW("yellow", "Yellow", 0xFFE680);

    private final String suffix;
    private final String displayName;
    private final int tint;

    DoorColor(String suffix, String displayName, int tint) {
        this.suffix = suffix;
        this.displayName = displayName;
        this.tint = tint;
    }

    /** RGB tint for UI frames that reflect this colour */
    public int getTint() {
        return this.tint;
    }

    /** part of the texture / tile name after "dimensional_door_" ("" for regular doors) */
    public String getSuffix() {
        return this.suffix;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    /** whether a door of this colour and one of the other colour can reach each other */
    public boolean matches(DoorColor other) {
        return this == REGULAR || other == REGULAR || this == other;
    }
}