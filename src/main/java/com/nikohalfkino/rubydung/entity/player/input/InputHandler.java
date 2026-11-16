package com.nikohalfkino.rubydung.entity.player.input;

import com.nikohalfkino.rubydung.entity.player.Player;
import com.nikohalfkino.rubydung.entity.player.interaction.PlayerInteraction;
import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.net.Multiplayer;
import com.nikohalfkino.rubydung.console.Console;
import com.nikohalfkino.rubydung.interfaces.hud.HandHud;
import com.nikohalfkino.rubydung.interfaces.screen.ChatScreen;
import com.nikohalfkino.rubydung.interfaces.screen.DimensionalDoorScreen;
import com.nikohalfkino.rubydung.interfaces.screen.InventoryScreen;
import com.nikohalfkino.rubydung.interfaces.screen.PauseScreen;
import com.nikohalfkino.rubydung.interfaces.Screen;
import com.nikohalfkino.rubydung.render.PlayerCamera;
import com.nikohalfkino.rubydung.util.KeyBinding;
import com.nikohalfkino.rubydung.util.KeyMap;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import java.util.Locale;

public class InputHandler {

    private final RubyDung game;

    private boolean wasPlaying = false;

    public InputHandler(RubyDung game) {
        this.game = game;
    }

    public void handle(boolean playing, int mouseX, int mouseY) {
        if (playing && !this.wasPlaying) {
            this.game.getPlayerInteraction().suppressHeldButtonsUntilReleased();
        }
        this.wasPlaying = playing;

        handleScrollWheel(playing);
        handleMouse(playing, mouseX, mouseY);
        handleKeyboard();
    }

    private void handleScrollWheel(boolean playing) {
        int wheel = Mouse.getDWheel();
        if (wheel == 0) {
            return;
        }

        if (!playing) {
            Screen currentScreen = this.game.getCurrentScreen();
            if (currentScreen instanceof InventoryScreen) {
                ((InventoryScreen) currentScreen).scroll(wheel > 0 ? -1 : 1);
            } else if (currentScreen instanceof ChatScreen) {
                // Wheel up = back in time.
                ((ChatScreen) currentScreen).scroll(wheel > 0 ? 3 : -3);
            } else if (currentScreen instanceof DimensionalDoorScreen) {
                ((DimensionalDoorScreen) currentScreen).scroll(wheel > 0 ? -1 : 1);
            }
            return;
        }

        // Carrying a weighted cube: the wheel moves it nearer or farther (and does not change the selected tile).
        PlayerInteraction interaction = this.game.getPlayerInteraction();
        if (interaction != null && interaction.isHoldingCube()) {
            interaction.scrollCube(wheel > 0 ? 1 : -1);
            return;
        }

        Player player = this.game.getPlayer();
        boolean ctrlHeld = KeyBinding.FLY_SPEED_MODIFIER.isDown() || KeyMap.isDown(KeyMap.RCONTROL);

        PlayerCamera camera = this.game.hasWorld() ? this.game.getGameRenderer().getPlayerCamera() : null;

        if (ctrlHeld && camera != null && camera.isFreecam()) {
            // the player stands still in the freecam, so the wheel changes the camera's speed instead
            adjustFreecamSpeed(camera, wheel > 0 ? 1 : -1);
        } else if (ctrlHeld && (player.flying || player.noClip)) {
            player.adjustFlySpeed(wheel > 0 ? 1 : -1);
            this.game.getCenterIndicator().showMessage(
                    String.format(Locale.ROOT, "Flight Speed: %.2fx", player.flySpeedMultiplier));
        } else {
            this.game.getPlayerInteraction().cycleSelectedTile(wheel > 0 ? 1 : -1);
        }
    }

    /** one wheel step is the same 15% as for flying; the camera keeps whatever limits it has */
    private void adjustFreecamSpeed(PlayerCamera camera, int direction) {
        float speed = camera.getFreecamSpeed();
        speed = direction > 0 ? speed * 1.15F : speed / 1.15F;
        camera.setFreecamSpeed(Math.max(0.5F, speed));

        // read back, in case the camera clamped it
        this.game.getCenterIndicator().showMessage(
                String.format(Locale.ROOT, "Freecam Speed: %.1f blocks/s", camera.getFreecamSpeed()));
    }

    private void handleMouse(boolean playing, int mouseX, int mouseY) {
        while (Mouse.next()) {
            if (!playing) {
                Screen currentScreen = this.game.getCurrentScreen();
                if (currentScreen != null && Mouse.getEventButtonState()) {
                    currentScreen.mouseClicked(mouseX, mouseY, Mouse.getEventButton());

                    if (this.game.getCurrentScreen() == null) {
                        this.game.getPlayerInteraction().suppressHeldButtonsUntilReleased();
                    }
                }
            }
        }
    }

    private void handleKeyboard() {
        Console console = this.game.getConsole();

        while (Keyboard.next()) {
            int key = Keyboard.getEventKey();
            char character = Keyboard.getEventCharacter();
            boolean pressed = Keyboard.getEventKeyState();

            // F11 works everywhere: in menus, in the console, with a text box focused.
            if (key == Keyboard.KEY_F11 && pressed) {
                this.game.toggleFullscreen();
                continue;
            }

            if (key == KeyBinding.CONSOLE.getKey() && pressed) {
                console.toggle();
                continue;
            }

            if (console.isOpen()) {
                if (pressed) {
                    console.keyTyped(key, character);
                }
                continue;
            }

            Screen currentScreen = this.game.getCurrentScreen();
            if (currentScreen != null) {
                if (pressed) {
                    currentScreen.keyPressed(key, character);
                }
                continue;
            }

            handleGameplayKey(key, pressed);
        }
    }

    private void handleGameplayKey(int key, boolean pressed) {
        if (!pressed) {
            return;
        }

        if (key == KeyMap.ENTER) {
            this.game.saveLevel();
        } else if (key == KeyMap.ESC) {
            this.game.openScreen(new PauseScreen(this.game, this.game.getFont()));
        } else if (key == KeyBinding.INVENTORY.getKey()) {
            // the tile selection is locked while a cube is carried
            if (!this.game.getPlayerInteraction().isHoldingCube()) {
                this.game.openScreen(new InventoryScreen(this.game));
            }
        } else if (key == KeyBinding.CHAT.getKey() && this.game.hasWorld()) {
            this.game.openScreen(new ChatScreen(this.game));
        } else if (key == KeyBinding.INTERACT.getKey()) {
            // A weighted cube: drops the one in the hands, or picks up the one under the crosshair.
            if (this.game.getPlayerInteraction().interactWithCube()) {
                return;
            }
            // Wooden doors react straight away (and swing the hand themselves).
            if (this.game.getPlayerInteraction().interact()) {
                return;
            }
            if (this.game.getPlayerInteraction().isTargetingInteractable()) {
                HandHud.swingCurrent(HandHud.Action.INTERACT);
            }
            int[] door = this.game.getPlayerInteraction().getTargetedDoorOrigin();
            if (door != null && Multiplayer.doorsAllowed()) {
                this.game.openScreen(new DimensionalDoorScreen(this.game, door[0], door[1], door[2]));
            } else if (door != null) {
                this.game.getChatLog().notice(Multiplayer.isHosting()
                        ? "Dimensional doors do not work in a LAN game"
                        : "Dimensional doors are off on this server (it needs more than one world)");
            }
        } else if (key == KeyBinding.KNOCK.getKey()) {
            // knocks if a closed door is under the crosshair, otherwise (a secret) pats the player there
            if (!this.game.getPlayerInteraction().knock() && this.game.hasWorld()) {
                this.game.getPlayerInteraction().pat();
            }
        } else if (key == KeyBinding.DEBUG_HUD.getKey()) {
            this.game.getDebugHud().toggle();
        } else if (key == KeyBinding.PICK_BLOCK.getKey() || key == Keyboard.KEY_RMENU) {
            if (!this.game.getPlayerInteraction().isHoldingCube()) {
                this.game.getPlayerInteraction().pickTargetedTile();
            }
        } else if (key == KeyBinding.FLY_TOGGLE.getKey()) {
            Player player = this.game.getPlayer();
            player.flying = !player.flying;
            if (!player.flying) {
                player.noClip = false;
            }
            this.game.getCenterIndicator().showMessage(player.flying ? "Flying Enabled" : "Flying Disabled");
        } else if (key == KeyBinding.NOCLIP_TOGGLE.getKey()) {
            Player player = this.game.getPlayer();
            player.noClip = !player.noClip;
            if (player.noClip) {
                player.flying = true;
            } else {
                player.flying = false;
            }
            this.game.getCenterIndicator().showMessage(player.noClip ? "Noclip Enabled" : "Noclip Disabled");
        } else if (key == KeyBinding.CAMERA_VIEW.getKey() && this.game.hasWorld()) {
            PlayerCamera camera = this.game.getGameRenderer().getPlayerCamera();
            if (camera.cycleView()) {
                this.game.getCenterIndicator().showMessage(camera.getView().displayName());
            } else {
                this.game.getCenterIndicator().showMessage("Turn off the freecam first");
            }
        } else if (key == KeyBinding.SCREENSHOT.getKey()) {
            this.game.takeScreenshot(2);
            this.game.getCenterIndicator().showMessage("Screenshot Saved");
        }
    }
}