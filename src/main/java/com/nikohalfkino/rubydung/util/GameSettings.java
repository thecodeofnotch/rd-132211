package com.nikohalfkino.rubydung.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public final class GameSettings {

    private static final String OPTIONS_FILE_NAME = "options.txt";

    public static final float MIN_FOV = 30.0f;
    public static final float MAX_FOV = 110.0f;
    public static final float DEFAULT_FOV = 70.0f;

    public static final String DEFAULT_USERNAME = "Hakumu";
    public static final int MAX_USERNAME_LENGTH = 16;

    /** longest server address (host or host:port) that is remembered */
    public static final int MAX_SERVER_ADDRESS_LENGTH = 64;

    public static final int MIN_PIXEL_SIZE = 2;
    public static final int MAX_PIXEL_SIZE = 4;
    public static final int DEFAULT_PIXEL_SIZE = 2;

    /** Fit = scaled to fit with black bars, Expand = UI fills the window, Bordered = 800x600 centred on black. maximized and fullscreen each have their own */
    public enum LayoutMode { FIT, EXPAND, BORDERED }

    /** plain resizable window, maximized to the desktop work area, or exclusive fullscreen */
    public enum DisplayType { WINDOWED, MAXIMIZED, FULLSCREEN }

    /** chunks (16 blocks) drawn around the camera, 0 = no limit */
    public static final int UNLIMITED_RENDER_DISTANCE = 0;
    public static final int MIN_RENDER_DISTANCE = 4;
    public static final int MAX_RENDER_DISTANCE = 32;

    public static final int UNCAPPED_FPS = 0;
    private static final int[] FPS_CAP_STEPS = {30, 60, 120, 144, 240, UNCAPPED_FPS};

    private static float fov = DEFAULT_FOV;
    private static boolean vsyncEnabled = true;
    private static int fpsCap = 60;
    private static boolean showFpsInHud = true;
    private static boolean pixelationEnabled = true;
    private static int pixelSize = DEFAULT_PIXEL_SIZE;
    private static int renderDistance = UNLIMITED_RENDER_DISTANCE;
    private static float musicVolume = 0.15f;
    private static float sfxVolume = 0.8f;
    private static String username = DEFAULT_USERNAME;
    // the multiplayer address typed last, filled into the join screen so it survives closing the game
    private static String lastServer = "";
    private static DisplayType displayType = DisplayType.WINDOWED;
    // last non-fullscreen state so F11 can go back to it, not saved
    private static DisplayType lastWindowType = DisplayType.WINDOWED;
    private static LayoutMode fullscreenMode = LayoutMode.EXPAND;
    private static LayoutMode maximizedMode = LayoutMode.EXPAND;

    private GameSettings() {
    }

    public static float getFov() {
        return fov;
    }

    public static void setFov(float value) {
        fov = Math.max(MIN_FOV, Math.min(MAX_FOV, value));
    }

    public static boolean isVsyncEnabled() {
        return vsyncEnabled;
    }

    public static void setVsyncEnabled(boolean enabled) {
        vsyncEnabled = enabled;
    }

    public static int getFpsCap() {
        return fpsCap;
    }

    public static void setFpsCap(int cap) {
        fpsCap = cap;
    }

    public static void cycleFpsCap() {
        int currentIndex = 0;
        for (int i = 0; i < FPS_CAP_STEPS.length; i++) {
            if (FPS_CAP_STEPS[i] == fpsCap) {
                currentIndex = i;
                break;
            }
        }
        fpsCap = FPS_CAP_STEPS[(currentIndex + 1) % FPS_CAP_STEPS.length];
    }

    public static float getMusicVolume() {
        return musicVolume;
    }

    public static void setMusicVolume(float volume) {
        musicVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public static float getSfxVolume() {
        return sfxVolume;
    }

    public static void setSfxVolume(float volume) {
        sfxVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public static String getUsername() {
        return username;
    }

    /** drops control characters, trims, cuts to MAX_USERNAME_LENGTH, an empty name falls back to DEFAULT_USERNAME */
    public static void setUsername(String name) {
        String cleaned = "";
        if (name != null) {
            StringBuilder builder = new StringBuilder();
            for (char c : name.toCharArray()) {
                if (c >= 32 && c != 127) {
                    builder.append(c);
                }
            }
            cleaned = builder.toString().trim();
            if (cleaned.length() > MAX_USERNAME_LENGTH) {
                cleaned = cleaned.substring(0, MAX_USERNAME_LENGTH).trim();
            }
        }
        username = cleaned.isEmpty() ? DEFAULT_USERNAME : cleaned;
    }

    /** the server address last joined (or tried), "" if there is none yet */
    public static String getLastServer() {
        return lastServer;
    }

    /** drops control characters, trims and cuts to MAX_SERVER_ADDRESS_LENGTH. does not save, call save() */
    public static void setLastServer(String address) {
        StringBuilder builder = new StringBuilder();
        if (address != null) {
            for (char c : address.toCharArray()) {
                if (c >= 32 && c != 127) {
                    builder.append(c);
                }
            }
        }
        String cleaned = builder.toString().trim();
        if (cleaned.length() > MAX_SERVER_ADDRESS_LENGTH) {
            cleaned = cleaned.substring(0, MAX_SERVER_ADDRESS_LENGTH).trim();
        }
        lastServer = cleaned;
    }

    public static DisplayType getDisplayType() {
        return displayType;
    }

    public static void setDisplayType(DisplayType type) {
        displayType = type == null ? DisplayType.WINDOWED : type;
        if (displayType != DisplayType.FULLSCREEN) {
            lastWindowType = displayType;
        }
    }

    public static DisplayType getLastWindowType() {
        return lastWindowType;
    }

    public static boolean isFullscreen() {
        return displayType == DisplayType.FULLSCREEN;
    }

    public static LayoutMode getFullscreenMode() {
        return fullscreenMode;
    }

    public static void setFullscreenMode(LayoutMode mode) {
        fullscreenMode = mode == null ? LayoutMode.EXPAND : mode;
    }

    public static LayoutMode getMaximizedMode() {
        return maximizedMode;
    }

    public static void setMaximizedMode(LayoutMode mode) {
        maximizedMode = mode == null ? LayoutMode.EXPAND : mode;
    }

    public static boolean isShowFpsInHud() {
        return showFpsInHud;
    }

    public static void setShowFpsInHud(boolean show) {
        showFpsInHud = show;
    }

    public static boolean isPixelationEnabled() {
        return pixelationEnabled;
    }

    public static void setPixelationEnabled(boolean enabled) {
        pixelationEnabled = enabled;
    }

    public static int getPixelSize() {
        return pixelSize;
    }

    public static void setPixelSize(int size) {
        pixelSize = Math.max(MIN_PIXEL_SIZE, Math.min(MAX_PIXEL_SIZE, size));
    }

    public static int getRenderDistance() {
        return renderDistance;
    }

    /** anything below 1 means unlimited, otherwise clamped to MIN_RENDER_DISTANCE..MAX_RENDER_DISTANCE */
    public static void setRenderDistance(int chunks) {
        renderDistance = chunks <= 0
                ? UNLIMITED_RENDER_DISTANCE
                : Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, chunks));
    }

    public static void load() {
        File file = new File(OPTIONS_FILE_NAME);
        if (!file.isFile()) {
            return;
        }

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        setFov(parseFloat(props.getProperty("fov"), fov));
        vsyncEnabled = parseBoolean(props.getProperty("vsyncEnabled"), vsyncEnabled);
        fpsCap = parseInt(props.getProperty("fpsCap"), fpsCap);
        showFpsInHud = parseBoolean(props.getProperty("showFpsInHud"), showFpsInHud);
        pixelationEnabled = parseBoolean(props.getProperty("pixelationEnabled"), pixelationEnabled);
        setPixelSize(parseInt(props.getProperty("pixelSize"), pixelSize));
        setRenderDistance(parseInt(props.getProperty("renderDistance"), renderDistance));
        setMusicVolume(parseFloat(props.getProperty("musicVolume"), musicVolume));
        setSfxVolume(parseFloat(props.getProperty("sfxVolume"), sfxVolume));

        String savedType = props.getProperty("displayType");
        if (savedType != null) {
            try {
                setDisplayType(DisplayType.valueOf(savedType.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // keep the default
            }
        } else if (parseBoolean(props.getProperty("fullscreen"), false)) {
            setDisplayType(DisplayType.FULLSCREEN); // options.txt from before display types existed
        }
        fullscreenMode = parseLayoutMode(props.getProperty("fullscreenMode"), fullscreenMode);
        maximizedMode = parseLayoutMode(props.getProperty("maximizedMode"), maximizedMode);

        String savedName = props.getProperty("username");
        if (savedName != null) {
            setUsername(savedName);
        }

        String savedServer = props.getProperty("lastServer");
        if (savedServer != null) {
            setLastServer(savedServer);
        }
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("fov", Float.toString(fov));
        props.setProperty("vsyncEnabled", Boolean.toString(vsyncEnabled));
        props.setProperty("fpsCap", Integer.toString(fpsCap));
        props.setProperty("showFpsInHud", Boolean.toString(showFpsInHud));
        props.setProperty("pixelationEnabled", Boolean.toString(pixelationEnabled));
        props.setProperty("pixelSize", Integer.toString(pixelSize));
        props.setProperty("renderDistance", Integer.toString(renderDistance));
        props.setProperty("musicVolume", Float.toString(musicVolume));
        props.setProperty("sfxVolume", Float.toString(sfxVolume));
        props.setProperty("username", username);
        props.setProperty("lastServer", lastServer);
        props.setProperty("displayType", displayType.name());
        props.setProperty("fullscreenMode", fullscreenMode.name());
        props.setProperty("maximizedMode", maximizedMode.name());

        try (FileOutputStream out = new FileOutputStream(OPTIONS_FILE_NAME)) {
            props.store(out, "Strata video/general options - see GameSettings");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static LayoutMode parseLayoutMode(String value, LayoutMode fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return LayoutMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || !(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }
}