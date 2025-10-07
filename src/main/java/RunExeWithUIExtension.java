package com.example.burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Montoya extension: runs an external EXE from a context menu,
 * shows a centered, visible JFrame on Linux (with WM quirks handled),
 * and streams stdout/stderr into a scrollable JTextArea.
 *
 * Save as: src/main/java/RunExeWithUIExtension.java
 */
public class RunExeWithUIExtension implements BurpExtension {

    private MontoyaApi montoya;
    private Logging logging;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // UI components (created once, but shown on demand)
    private final JTextArea outputArea = new JTextArea();
    private final JFrame outputFrame = new JFrame("Run EXE - Output");

    public RunExeWithUIExtension() {
        // Configure outputArea
        outputArea.setEditable(false);
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);

        // Configure frame contents but DON'T show it here.
        JScrollPane scrollPane = new JScrollPane(outputArea);
        outputFrame.getContentPane().setLayout(new BorderLayout(6, 6));
        outputFrame.getContentPane().add(scrollPane, BorderLayout.CENTER);

        // Use a safe default size; pack() may result in tiny window if text area is empty.
        outputFrame.setSize(900, 420);
        outputFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        // Do NOT setAlwaysOnTop here; only toggle it briefly when showing.
        outputFrame.setAlwaysOnTop(false);
    }

    @Override
    public void initialize(MontoyaApi montoyaApi) {
        this.montoya = montoyaApi;
        this.logging = montoya.logging();

        montoya.extension().setName("Run EXE (UI)");

        // Register a context menu item that opens a dialog for exe path + args
        montoya.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                JMenuItem runExeItem = new JMenuItem("Run external EXE (UI)");
                runExeItem.addActionListener(e -> showExeDialog(event));
                return Collections.singletonList((Component) runExeItem);
            }
        });

        logging.logToOutput("Run EXE (UI) extension loaded.");
    }

    private void showExeDialog(ContextMenuEvent event) {
        // Simple dialog to accept EXE path and args
        JTextField exePathField = new JTextField(45);
        JTextField argsField = new JTextField(45);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel("EXE Path (absolute):"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(exePathField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Arguments (space-separated):"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(argsField, gbc);

        int result = JOptionPane.showConfirmDialog(null, panel,
                "Run External EXE", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        final String exePath = exePathField.getText().trim();
        final String argsText = argsField.getText().trim();
        final String[] argsArray = argsText.isEmpty() ? new String[0] : argsText.split("\\s+");

        if (exePath.isEmpty()) {
            JOptionPane.showMessageDialog(null, "EXE path is required.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String[] cmd = new String[argsArray.length + 1];
        cmd[0] = exePath;
        System.arraycopy(argsArray, 0, cmd, 1, argsArray.length);

        // Clear previous output and show frame reliably (on EDT)
        SwingUtilities.invokeLater(() -> {
            outputArea.setText("Running: " + String.join(" ", cmd) + "\n\n");
            showOutputFrameReliable();
        });

        // Run process off the EDT
        executor.submit(() -> runProcess(cmd));
    }

    /**
     * Shows the outputFrame reliably across Linux window managers:
     * - Ensures it's created/shown on EDT
     * - Temporarily sets always-on-top and uses a small Timer to call toFront/requestFocus
     * - Then releases always-on-top to avoid annoying the user
     */
    private void showOutputFrameReliable() {
        // If headless (rare in Burp), don't attempt to show frame
        if (GraphicsEnvironment.isHeadless()) {
            logging.logToOutput("Headless environment: cannot show output window.");
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                // center and show
                outputFrame.setLocationRelativeTo(null);
                // briefly force on-top so WMs (especially tiling) will bring it forward
                outputFrame.setAlwaysOnTop(true);
                outputFrame.setVisible(true);

                // Give WM a moment, then try to bring to front & release always-on-top
                Timer t = new Timer(80, ae -> {
                    try {
                        outputFrame.toFront();
                        outputFrame.requestFocus();
                    } catch (Throwable ignored) {}
                    // turn off always-on-top shortly afterwards
                    outputFrame.setAlwaysOnTop(false);
                });
                t.setRepeats(false);
                t.start();
            } catch (Throwable t) {
                logging.logToOutput("Failed to show output window: " + t.getMessage());
            }
        });
    }

    private void runProcess(String[] cmd) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();

            // Stream process output incrementally to the JTextArea
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                StringBuilder buffer = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append(System.lineSeparator());
                    final String snapshot = buffer.toString();
                    SwingUtilities.invokeLater(() -> outputArea.setText(snapshot));
                }
            }

            int rc = proc.waitFor();
            SwingUtilities.invokeLater(() -> outputArea.append(System.lineSeparator() + "--- Process exited with code: " + rc + " ---" + System.lineSeparator()));
            logging.logToOutput("Process finished. Exit code: " + rc);
        } catch (IOException ioe) {
            logging.logToOutput("IO error while running process: " + ioe.getMessage());
            SwingUtilities.invokeLater(() -> outputArea.setText("IO error: " + ioe.getMessage()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logging.logToOutput("Process thread interrupted: " + ie.getMessage());
            SwingUtilities.invokeLater(() -> outputArea.setText("Interrupted: " + ie.getMessage()));
        } finally {
            if (proc != null) proc.destroy();
        }
    }
}
