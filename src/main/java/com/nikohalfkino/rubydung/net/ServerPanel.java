package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.console.ChatText;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * the window of a dedicated server started with --gui: the server's log and a line to type commands into.
 * everything printed through System.out / System.err ends up here (and still in the real console)
 */
final class ServerPanel {

    private static final int MAX_CHARS = 200_000;
    private static final int HISTORY = 100;

    private static JFrame window;

    private final JTextArea log = new JTextArea();
    private final JTextField input = new JTextField();
    private final List<String> history = new ArrayList<>();
    private int historyIndex = 0;

    private ServerPanel(String title, Consumer<String> onCommand, Runnable onClose) {
        JFrame frame = new JFrame(title);
        window = frame;
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                frame.setTitle(title + " - stopping...");
                input.setEnabled(false);
                onClose.run();
            }
        });

        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        Color background = new Color(0x14, 0x14, 0x1C);
        Color text = new Color(0xDD, 0xDD, 0xDD);

        this.log.setEditable(false);
        this.log.setLineWrap(true);
        this.log.setWrapStyleWord(true);
        this.log.setFont(mono);
        this.log.setBackground(background);
        this.log.setForeground(text);
        ((DefaultCaret) this.log.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        this.input.setFont(mono);
        this.input.setBackground(new Color(0x08, 0x08, 0x0C));
        this.input.setForeground(text);
        this.input.setCaretColor(text);
        this.input.addActionListener(e -> {
            String line = this.input.getText().trim();
            this.input.setText("");
            if (line.isEmpty()) {
                return;
            }
            this.history.add(line);
            if (this.history.size() > HISTORY) {
                this.history.remove(0);
            }
            this.historyIndex = this.history.size();
            append("> " + line);
            onCommand.accept(line);
        });
        this.input.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_UP && historyIndex > 0) {
                    historyIndex--;
                    input.setText(history.get(historyIndex));
                } else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_DOWN) {
                    historyIndex = Math.min(history.size(), historyIndex + 1);
                    input.setText(historyIndex < history.size() ? history.get(historyIndex) : "");
                }
            }
        });

        frame.add(new JScrollPane(this.log), BorderLayout.CENTER);
        frame.add(this.input, BorderLayout.SOUTH);
        frame.setSize(900, 560);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        this.input.requestFocusInWindow();
    }

    /** opens the window and sends System.out / System.err to it. onCommand gets each typed line, onClose runs when the window is closed */
    static void open(String title, Consumer<String> onCommand, Runnable onClose) {
        ServerPanel[] holder = new ServerPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> holder[0] = new ServerPanel(title, onCommand, onClose));
        } catch (Exception e) {
            throw new IllegalStateException("could not open the server window: " + e, e);
        }
        ServerPanel panel = holder[0];

        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        System.setOut(new PrintStream(new Tee(realOut, panel), true));
        System.setErr(new PrintStream(new Tee(realErr, panel), true));
    }

    /** closes the window once the server is done, so the program can end */
    static void close() {
        SwingUtilities.invokeLater(() -> {
            if (window != null) {
                window.dispose();
            }
        });
    }

    /** thread safe: lines may come from the network threads */
    private void append(String line) {
        String clean = ChatText.strip(line);
        SwingUtilities.invokeLater(() -> {
            this.log.append(clean + "\n");
            int excess = this.log.getDocument().getLength() - MAX_CHARS;
            if (excess > 0) {
                try {
                    this.log.getDocument().remove(0, excess);
                } catch (javax.swing.text.BadLocationException ignored) {
                }
            }
            this.log.setCaretPosition(this.log.getDocument().getLength());
        });
    }

    // splits what is printed into lines for the panel and passes every byte on to the real stream
    private static final class Tee extends OutputStream {
        private final PrintStream real;
        private final ServerPanel panel;
        private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();

        Tee(PrintStream real, ServerPanel panel) {
            this.real = real;
            this.panel = panel;
        }

        @Override
        public synchronized void write(int b) {
            this.real.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                this.pending.write(b);
            }
        }

        @Override
        public void flush() throws IOException {
            this.real.flush();
        }

        private void flushLine() {
            String line = new String(this.pending.toByteArray(), StandardCharsets.UTF_8);
            this.pending.reset();
            this.panel.append(line);
        }
    }
}
