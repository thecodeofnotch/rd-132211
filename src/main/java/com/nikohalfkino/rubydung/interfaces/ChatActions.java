package com.nikohalfkino.rubydung.interfaces;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.Locale;

/** what clicking a link or a copyable part of the chat does */
public final class ChatActions {

    private ChatActions() {
    }

    /** puts text on the clipboard, false if there is none to use */
    public static boolean copy(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** opens an http / https address in the browser, on its own thread so the game doesn't stall */
    public static void openLink(String url) {
        // a server that does not allow links does not get them opened, whichever screen asked
        if (!com.nikohalfkino.rubydung.net.Multiplayer.linksAllowed()) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                    return;
                }
            } catch (Throwable ignored) {
                // try the system's own opener below
            }

            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("win")) {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }
            } catch (Throwable ignored) {
                // nothing to open it with
            }
        }, "open-link");

        thread.setDaemon(true);
        thread.start();
    }
}
