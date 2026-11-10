package com.nikohalfkino.rubydung.interfaces.hud;

import com.nikohalfkino.rubydung.RubyDung;
import com.nikohalfkino.rubydung.interfaces.widget.Label;
import com.nikohalfkino.rubydung.util.KeyBinding;

public class InteractPromptHud extends Label {

    public InteractPromptHud(RubyDung game) {
        super(() -> "[" + KeyBinding.INTERACT.getBindingName() + "] " + word(game));

        setColor(0xFFFFFF);
        setScale(2.0F);

        setVisibleWhen(() -> game.hasWorld()
                && game.getCurrentScreen() == null
                && !game.getConsole().isOpen()
                && (game.getPlayerInteraction().isTargetingInteractable()
                || game.getPlayerInteraction().getCubePrompt() != null));
    }

    // what the key does: "Pick up" / "Drop" for a weighted cube, "Interact" for everything else
    private static String word(RubyDung game) {
        String cube = game.hasWorld() ? game.getPlayerInteraction().getCubePrompt() : null;
        return cube != null ? cube : "Interact";
    }
}