package com.nikohalfkino.rubydung.net;

import com.nikohalfkino.rubydung.level.WorldManager;
import com.nikohalfkino.rubydung.level.generation.LevelGenerator;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * entry point of the dedicated server package: a small window for the world's settings, then the server opens
 * (ServerMain with its log window). started with arguments it skips the window and behaves exactly like ServerMain,
 * so scripts and hosting panels can keep using the command line options
 */
public final class ServerLauncher {

    private ServerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            ServerMain.main(args);
            return;
        }

        CompletableFuture<String[]> result = new CompletableFuture<>();
        SwingUtilities.invokeAndWait(() -> new Setup(result));
        String[] chosen = result.get();   // blocks until the button is pressed, or the window is closed (null)
        if (chosen == null) {
            System.exit(0);
            return;
        }
        ServerMain.main(chosen);
    }

    private static final class Setup {
        private final JFrame frame = new JFrame("Strata server - world settings");
        private final JComboBox<String> world = new JComboBox<>();
        private final JComboBox<LevelGenerator.WorldType> type = new JComboBox<>(LevelGenerator.WorldType.values());
        private final JComboBox<LevelGenerator.WorldSize> size = new JComboBox<>(LevelGenerator.WorldSize.values());
        private final JTextField seed = new JTextField();
        private final JSpinner port = new JSpinner(new SpinnerNumberModel(Protocol.DEFAULT_PORT, 1024, 65535, 1));
        private final JSpinner maxPlayers = new JSpinner(new SpinnerNumberModel(16, 1, 256, 1));
        private final JSpinner autosave = new JSpinner(new SpinnerNumberModel(5, 0, 120, 1));
        private final JPasswordField password = new JPasswordField();
        private final JLabel note = new JLabel(" ");
        private final JButton start = new JButton();

        Setup(CompletableFuture<String[]> result) {
            List<String> existing = new ArrayList<>();
            for (WorldManager.WorldInfo info : WorldManager.listWorlds()) {
                existing.add(info.name);
            }
            this.world.setEditable(true);
            for (String name : existing) {
                this.world.addItem(name);
            }
            if (existing.isEmpty()) {
                this.world.addItem("server");
            }
            this.type.setSelectedItem(LevelGenerator.WorldType.ISLAND);
            this.world.addActionListener(e -> refresh());

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            row(form, 0, "World (type a new name to create one)", this.world);
            row(form, 1, "World type", this.type);
            row(form, 2, "World size", this.size);
            row(form, 3, "Seed (empty = random)", this.seed);
            row(form, 4, "Port", this.port);
            row(form, 5, "Max players", this.maxPlayers);
            row(form, 6, "Password (empty = none)", this.password);
            row(form, 7, "Autosave minutes (0 = off)", this.autosave);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 8;
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(8, 0, 4, 0);
            form.add(this.note, c);
            c.gridy = 9;
            form.add(this.start, c);

            this.start.addActionListener(e -> {
                String[] args = buildArgs();
                if (args != null) {
                    this.frame.dispose();
                    result.complete(args);
                }
            });
            this.frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            this.frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    frame.dispose();
                    result.complete(null);
                }
            });
            this.frame.add(form);
            refresh();
            this.frame.pack();
            this.frame.setLocationRelativeTo(null);
            this.frame.setVisible(true);
        }

        private static void row(JPanel form, int y, String label, java.awt.Component field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = y;
            c.insets = new Insets(3, 0, 3, 8);
            c.anchor = GridBagConstraints.WEST;
            form.add(new JLabel(label), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(3, 0, 3, 0);
            form.add(field, c);
        }

        private String worldName() {
            Object selected = this.world.getEditor().getItem();
            return WorldManager.sanitize(selected == null ? "" : selected.toString().trim());
        }

        // a world that exists keeps its saved type, size and seed (ServerMain loads it), so those are locked
        private void refresh() {
            boolean exists = WorldManager.exists(worldName());
            this.type.setEnabled(!exists);
            this.size.setEnabled(!exists);
            this.seed.setEnabled(!exists);
            this.start.setText(exists ? "Open server" : "Create world and open server");
            this.note.setText(exists ? "This world exists: its saved settings are used." : " ");
        }

        private String[] buildArgs() {
            String name = worldName();
            if (name.isEmpty()) {
                this.note.setText("Give the world a name.");
                return null;
            }
            List<String> args = new ArrayList<>();
            args.add("--gui");
            args.add("--world");
            args.add(name);
            args.add("--port");
            args.add(String.valueOf(this.port.getValue()));
            args.add("--max-players");
            args.add(String.valueOf(this.maxPlayers.getValue()));
            args.add("--autosave");
            args.add(String.valueOf(this.autosave.getValue()));
            if (this.password.getPassword().length > 0) {
                args.add("--password");
                args.add(new String(this.password.getPassword()));
            }
            if (!WorldManager.exists(name)) {
                LevelGenerator.WorldSize chosenSize = (LevelGenerator.WorldSize) this.size.getSelectedItem();
                args.add("--size");
                args.add(chosenSize.name().toLowerCase(Locale.ROOT));
                args.add("--type");
                args.add(((LevelGenerator.WorldType) this.type.getSelectedItem()).name());
                String text = this.seed.getText().trim();
                if (!text.isEmpty()) {
                    try {
                        args.add("--seed");
                        args.add(String.valueOf(Long.parseLong(text)));
                    } catch (NumberFormatException e) {
                        this.note.setText("The seed must be a whole number.");
                        return null;
                    }
                }
            }
            return args.toArray(new String[0]);
        }
    }
}
