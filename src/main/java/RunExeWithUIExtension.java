package com.example.burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RunExeWithUIExtension implements BurpExtension {

    private MontoyaApi montoya;
    private Logging logging;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final JTextArea outputArea = new JTextArea(15, 80);
    private final JFrame outputFrame = new JFrame("Process Output");

    public RunExeWithUIExtension() {
        outputArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(outputArea);
        outputFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        outputFrame.getContentPane().add(scrollPane, BorderLayout.CENTER);
        outputFrame.pack();
    }

    @Override
    public void initialize(MontoyaApi montoyaApi) {
        this.montoya = montoyaApi;
        this.logging = montoya.logging();

        montoya.extension().setName("Run EXE with UI");

        montoya.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                JMenuItem runExeItem = new JMenuItem("Run external EXE (UI)");
                runExeItem.addActionListener((ActionEvent e) -> showExeDialog(event));
                return Collections.singletonList((Component) runExeItem);
            }
        });

        logging.logToOutput("Run EXE with UI extension loaded.");
    }

    private void showExeDialog(ContextMenuEvent event) {
        JTextField exePathField = new JTextField(40);
        JTextField argsField = new JTextField(40);

        JPanel panel = new JPanel(new GridLayout(2, 2, 6, 6));
        panel.add(new JLabel("EXE Path:"));
        panel.add(exePathField);
        panel.add(new JLabel("Arguments (space-separated):"));
        panel.add(argsField);

        int result = JOptionPane.showConfirmDialog(null, panel,
                "Run External EXE", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
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

            executor.submit(() -> runProcess(cmd));
            SwingUtilities.invokeLater(() -> {
                if (!outputFrame.isVisible()) outputFrame.setVisible(true);
                outputFrame.toFront();
            });
        }
    }

    private void runProcess(String[] cmd) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder outputBuffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                outputBuffer.append(line).append("\n");
                final String snapshot = outputBuffer.toString();
                SwingUtilities.invokeLater(() -> outputArea.setText(snapshot));
            }

            int rc = proc.waitFor();
            SwingUtilities.invokeLater(() -> outputArea.append("\n--- Process exited with code: " + rc + " ---\n"));
            logging.logToOutput("Process finished. Exit code: " + rc);

        } catch (IOException | InterruptedException e) {
            logging.logToOutput("Error running process: " + e.getMessage());
            SwingUtilities.invokeLater(() -> outputArea.setText("Error: " + e.getMessage()));
            Thread.currentThread().interrupt();
        } finally {
            if (proc != null) proc.destroy();
        }
    }
}
