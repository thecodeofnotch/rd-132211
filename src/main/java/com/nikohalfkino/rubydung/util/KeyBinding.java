package com.nikohalfkino.rubydung.util;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * one rebindable action -> input mapping, shown as a row in ControlsScreen. can be bound to a keyboard key or a mouse button (see Device, needed for break/place which are mouse buttons by default), plus an optional modifier on top (e.g. "Ctrl + E", see hasModifier())
 *
 * grouped below by what they do (movement, flight, interaction, interface). every entry is read somewhere: movement/jump/sneak/sprint by Player.tick, break/place by PlayerInteraction.tick, the rest directly by InputHandler
 *
 * this is just the data side of rebinding, it doesn't know about InputHandler
 */
public class KeyBinding {

    public enum Device {
        KEYBOARD, MOUSE
    }

    private static final Map<String, KeyBinding> REGISTRY = new LinkedHashMap<>();
    private static final String[] MOUSE_BUTTON_NAMES = {"Left Click", "Right Click", "Middle Click"};
    private static final String KEYBINDS_FILE_NAME = "keybinds.txt";

    // movement: read via isDown() in Player.tick, so binding one to a mouse button works
    // (unlike the interface group below)
    public static final KeyBinding FORWARD = new KeyBinding("forward", "Move Forward", Keyboard.KEY_W, true);
    public static final KeyBinding BACK = new KeyBinding("back", "Move Backward", Keyboard.KEY_S, true);
    public static final KeyBinding LEFT = new KeyBinding("left", "Strafe Left", Keyboard.KEY_A, true);
    public static final KeyBinding RIGHT = new KeyBinding("right", "Strafe Right", Keyboard.KEY_D, true);
    public static final KeyBinding JUMP = new KeyBinding("jump", "Jump / Fly Up", Keyboard.KEY_SPACE, true);
    public static final KeyBinding SNEAK = new KeyBinding("sneak", "Fly Down", Keyboard.KEY_LSHIFT, true);

    // flight: FLY_TOGGLE / NOCLIP_TOGGLE are mouseBindable = false because InputHandler only compares them against keyboard events, a mouse button would silently do nothing
    // FLY_SPEED_MODIFIER is read via isDown() (device-aware) but kept keyboard-only, since it's held alongside a scroll gesture
    public static final KeyBinding FLY_TOGGLE = new KeyBinding("fly_toggle", "Toggle Flying", Keyboard.KEY_V, false);
    public static final KeyBinding NOCLIP_TOGGLE = new KeyBinding("noclip_toggle", "Toggle Noclip", Keyboard.KEY_N, false);
    public static final KeyBinding FLY_SPEED_MODIFIER = new KeyBinding("fly_speed_modifier", "Fly Speed Modifier", Keyboard.KEY_LCONTROL, false);

    // interaction: BREAK_BLOCK / PLACE_BLOCK are read via isDown() in PlayerInteraction.tick, so mouse rebinding works
    // PICK_BLOCK / CYCLE_TREE are compared via getKey() (keyboard only) in InputHandler
    public static final KeyBinding BREAK_BLOCK = new KeyBinding("break_block", "Break Block", Device.MOUSE, 0, true);
    public static final KeyBinding PLACE_BLOCK = new KeyBinding("place_block", "Place Block", Device.MOUSE, 1, true);
    public static final KeyBinding PICK_BLOCK = new KeyBinding("pick_block", "Pick Block", Keyboard.KEY_LMENU, false);
    public static final KeyBinding INTERACT = new KeyBinding("interact", "Interact", Keyboard.KEY_E, false);
    public static final KeyBinding KNOCK = new KeyBinding("knock", "Knock", Keyboard.KEY_Q, false);

    // interface: all compared via getKey() (keyboard only) in InputHandler
    // INVENTORY / CHAT are also compared by InventoryScreen / ChatScreen, so the same key closes / is ignored on open
    public static final KeyBinding INVENTORY = new KeyBinding("inventory", "Open Inventory", Keyboard.KEY_TAB, false);
    public static final KeyBinding CHAT = new KeyBinding("chat", "Open Chat", Keyboard.KEY_T, false);
    public static final KeyBinding CONSOLE = new KeyBinding("console", "Open Console", Keyboard.CHAR_NONE, false);
    public static final KeyBinding DEBUG_HUD = new KeyBinding("debug_hud", "Toggle Debug Info", Keyboard.KEY_F3, false);
    public static final KeyBinding SCREENSHOT = new KeyBinding("screenshot", "Screenshot", Keyboard.KEY_F2, false);
    public static final KeyBinding CAMERA_VIEW = new KeyBinding("camera_view", "Change Camera View", Keyboard.KEY_F5, false);

    private final String id;
    private final String displayName;
    private final Device defaultDevice;
    private final int defaultCode;

    // whether it can be rebound to a mouse button, not just a key (see the group comments above)
    private final boolean mouseBindable;

    private Device device;
    private int code;

    // optional modifier held alongside the primary binding (e.g. Ctrl in "Ctrl + E"), null = none. set via the combo overload of setBinding, see ControlsScreen's chord capture
    private Device modifierDevice = null;
    private int modifierCode;

    private KeyBinding(String id, String displayName, int defaultKey, boolean mouseBindable) {
        this(id, displayName, Device.KEYBOARD, defaultKey, mouseBindable);
    }

    private KeyBinding(String id, String displayName, Device defaultDevice, int defaultCode, boolean mouseBindable) {
        this.id = id;
        this.displayName = displayName;
        this.defaultDevice = defaultDevice;
        this.defaultCode = defaultCode;
        this.mouseBindable = mouseBindable;
        this.device = defaultDevice;
        this.code = defaultCode;
        REGISTRY.put(id, this);
    }

    public static Collection<KeyBinding> all() {
        return REGISTRY.values();
    }

    public static KeyBinding byId(String id) {
        return REGISTRY.get(id);
    }

    /**
     * reads every binding's device/code (and modifier) from keybinds.txt if it exists. called once from RubyDung.init, before any screen (ControlsScreen reads getKeyName() when constructed) can be open
     *
     * only entries in the file are touched: a binding with no line (new since the file was written) keeps its hardcoded default, and so does one whose line fails to parse, which is caught per binding so one bad line doesn't take the rest down
     */
    public static void load() {
        File file = new File(KEYBINDS_FILE_NAME);
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

        for (KeyBinding binding : REGISTRY.values()) {
            String raw = props.getProperty(binding.id);
            if (raw == null) {
                continue;
            }
            try {
                binding.applySerialized(raw);
            } catch (RuntimeException e) {
                // malformed line (hand-edited file, or from a build with different Device values), keep this binding's default
            }
        }
    }

    /** writes every binding's device/code (and modifier) to keybinds.txt. called from RubyDung.destroy and after each rebind in ControlsScreen */
    public static void save() {
        Properties props = new Properties();
        for (KeyBinding binding : REGISTRY.values()) {
            props.setProperty(binding.id, binding.serialize());
        }

        try (FileOutputStream out = new FileOutputStream(KEYBINDS_FILE_NAME)) {
            props.store(out, "Strata key bindings - see KeyBinding");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getId() {
        return this.id;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public boolean isMouseBindable() {
        return this.mouseBindable;
    }

    public Device getDevice() {
        return this.device;
    }

    public int getCode() {
        return this.code;
    }

    public boolean hasModifier() {
        return this.modifierDevice != null;
    }

    public Device getModifierDevice() {
        return this.modifierDevice;
    }

    public int getModifierCode() {
        return this.modifierCode;
    }

    /** keyboard-code accessor from before mouse bindings existed, for call sites (like InputHandler) that only compare against keyboard events. returns whatever's bound even if it's a mouse button, but those call sites only exist on actions where isMouseBindable() is false */
    public int getKey() {
        return this.code;
    }

    /** plain primary binding, clears any modifier */
    public void setBinding(Device device, int code) {
        this.device = device;
        this.code = code;
        this.modifierDevice = null;
    }

    /** combo: primary binding plus a modifier that must also be held */
    public void setBinding(Device modifierDevice, int modifierCode, Device device, int code) {
        this.modifierDevice = modifierDevice;
        this.modifierCode = modifierCode;
        this.device = device;
        this.code = code;
    }

    /** keyboard-only convenience for callers that never bind a mouse button */
    public void setKey(int key) {
        setBinding(Device.KEYBOARD, key);
    }

    public void resetToDefault() {
        this.device = this.defaultDevice;
        this.code = this.defaultCode;
        this.modifierDevice = null;
    }

    // one line for save(): "DEVICE:code", or "modDEVICE:modCode>DEVICE:code" with a modifier. doesn't reuse getBindingName(), that's a display string ("Ctrl + E") not meant to round-trip through applySerialized
    private String serialize() {
        String primary = this.device + ":" + this.code;
        if (this.modifierDevice == null) {
            return primary;
        }
        return this.modifierDevice + ":" + this.modifierCode + ">" + primary;
    }

    // reverse of serialize(), throws on anything that doesn't parse cleanly (including a mouse primary on a binding that isn't mouse-bindable) so load() can skip just that line. only mutates the binding once the whole line is valid, so a bad line never leaves it half-updated
    private void applySerialized(String raw) {
        String[] halves = raw.split(">", 2);
        String primaryToken = halves[halves.length - 1];
        String[] primaryParts = primaryToken.split(":", 2);
        Device device = Device.valueOf(primaryParts[0]);
        int code = Integer.parseInt(primaryParts[1]);

        if (device == Device.MOUSE && !this.mouseBindable) {
            throw new IllegalArgumentException("Not mouse-bindable: " + this.id);
        }

        if (halves.length == 1) {
            setBinding(device, code);
            return;
        }

        String[] modifierParts = halves[0].split(":", 2);
        Device modifierDevice = Device.valueOf(modifierParts[0]);
        int modifierCode = Integer.parseInt(modifierParts[1]);
        setBinding(modifierDevice, modifierCode, device, code);
    }

    public boolean isDown() {
        boolean primaryDown = this.device == Device.KEYBOARD ? Keyboard.isKeyDown(this.code) : Mouse.isButtonDown(this.code);
        if (!primaryDown) {
            return false;
        }
        if (this.modifierDevice == null) {
            return true;
        }
        return this.modifierDevice == Device.KEYBOARD ? Keyboard.isKeyDown(this.modifierCode) : Mouse.isButtonDown(this.modifierCode);
    }

    /** human-readable name of whatever's bound, e.g. "W", "Right Click", "Ctrl + E" */
    public String getBindingName() {
        String primary = describeBinding(this.device, this.code);
        if (this.modifierDevice == null) {
            return primary;
        }
        return describeBinding(this.modifierDevice, this.modifierCode) + " + " + primary;
    }

    /** @deprecated use getBindingName() */
    @Deprecated
    public String getKeyName() {
        return getBindingName();
    }

    /** human-readable name for one device+code pair, with friendly names for common modifier keys (raw Keyboard.getKeyName gives "LCONTROL"). shared by getBindingName() and ControlsScreen's "listening for the second half of a combo" prompt */
    public static String describeBinding(Device device, int code) {
        if (device == Device.MOUSE) {
            return code < MOUSE_BUTTON_NAMES.length ? MOUSE_BUTTON_NAMES[code] : "Mouse " + (code + 1);
        }
        switch (code) {
            case Keyboard.KEY_LCONTROL:
            case Keyboard.KEY_RCONTROL:
                return "Ctrl";
            case Keyboard.KEY_LSHIFT:
            case Keyboard.KEY_RSHIFT:
                return "Shift";
            case Keyboard.KEY_LMENU:
            case Keyboard.KEY_RMENU:
                return "Alt";
            case Keyboard.KEY_LMETA:
            case Keyboard.KEY_RMETA:
                return "Win";
            default:
                return Keyboard.getKeyName(code);
        }
    }

    /** true if another binding's primary is already this device+code, regardless of modifier. ControlsScreen can warn but still allow it, some players want deliberate overlaps */
    public boolean conflictsWith(Device device, int code) {
        for (KeyBinding binding : REGISTRY.values()) {
            if (binding != this && binding.device == device && binding.code == code) {
                return true;
            }
        }
        return false;
    }

    /** true if another binding has this exact combo (same modifier or lack of one, same primary). stricter than the other overload: plain "E" and "Ctrl + E" don't conflict */
    public boolean conflictsWith(Device modifierDevice, int modifierCode, Device device, int code) {
        for (KeyBinding binding : REGISTRY.values()) {
            if (binding == this) {
                continue;
            }
            boolean sameModifier = binding.modifierDevice == modifierDevice
                    && (modifierDevice == null || binding.modifierCode == modifierCode);
            if (sameModifier && binding.device == device && binding.code == code) {
                return true;
            }
        }
        return false;
    }
}