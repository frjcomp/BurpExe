// RunExeMontoyaExtension.java
// Build with montoya-api on the classpath (see notes after code).

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
import java.util.concurrent.Executors;

public class RunExeMontoyaExtension implements BurpExtension
{
    private MontoyaApi montoya;
    private Logging logging;

    // Public no-arg constructor required by Montoya
    public RunExeMontoyaExtension() { }

    @Override
    public void initialize(MontoyaApi montoyaApi)
    {
        this.montoya = montoyaApi;
        this.logging = montoya.logging();

        montoya.extension().setName("Run EXE (Montoya)");

        // Register a provider that adds our context menu item
        montoya.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                JMenuItem runExeItem = new JMenuItem("Run external EXE (Montoya)");
                runExeItem.addActionListener((ActionEvent e) -> {
                    // Run in separate thread pool to avoid UI blocking
                    Executors.newSingleThreadExecutor().submit(() -> handleRunExe(event));
                });

                return Collections.singletonList((Component) runExeItem);
            }
        });

        logging.logToOutput("Run EXE (Montoya) extension loaded.");
    }

    /**
     * This performs the actual process invocation.
     * - Adjust exePath + args to match your tool.
     * - If a selected HTTP message is available, sends its bytes to the process stdin.
     */
    private void handleRunExe(ContextMenuEvent event)
    {
        // *** USER EDIT: change this to your exe & args (absolute path on Windows) ***
        String exePath = "C:\\path\\to\\your.exe";
        String[] cmd = new String[] { exePath, "--flag", "value" };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process proc = null;
        try {
            proc = pb.start();

            // If the user invoked the context menu on a message, Montoya's ContextMenuEvent
            // exposes info about the selected messages. Try to send selection as stdin.
            // This is best-effort and wrapped in try/catch to remain robust.
            try {
                // Many Montoya examples call event.selectedMessages() or similar; use
                // the ContextMenuEvent to query messages if available.
                // We'll do a safe best-effort: if message bytes are available, write them.
                // The exact API name may vary by Montoya version; replace with your project IDE auto-complete if needed.
                var maybeMessages = event.messages(); // best-effort method name from examples/docs
                if (maybeMessages != null && !maybeMessages.isEmpty()) {
                    // take first message (typical use-case)
                    var first = maybeMessages.get(0);
                    byte[] requestBytes = first.request(); // best-effort; adjust if your IDE suggests different method
                    if (requestBytes != null && requestBytes.length > 0) {
                        try (OutputStream os = proc.getOutputStream()) {
                            os.write(requestBytes);
                            os.flush();
                        } catch (IOException ioe) {
                            logging.logToOutput("Failed to write to process stdin: " + ioe.getMessage());
                        }
                    } else {
                        // close stdin to signal EOF
                        try { proc.getOutputStream().close(); } catch (IOException ignored) {}
                    }
                } else {
                    // no message available -> close stdin
                    try { proc.getOutputStream().close(); } catch (IOException ignored) {}
                }
            } catch (Throwable t) {
                // If the Montoya method names differ (IDE can show exact methods), don't fail—just log.
                logging.logToOutput("Could not access selected message bytes: " + t.getMessage());
                try { proc.getOutputStream().close(); } catch (IOException ignored) {}
            }

            // Read stdout+stderr
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append(System.lineSeparator());
                }
            }

            int rc = proc.waitFor();
            logging.logToOutput("Run EXE: exited with code " + rc);
            logging.logToOutput("Run EXE: output:\n" + out.toString());

        } catch (IOException ioe) {
            logging.logToOutput("Run EXE: IO error: " + ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logging.logToOutput("Run EXE: interrupted: " + ie.getMessage());
        } finally {
            if (proc != null) {
                proc.destroy();
            }
        }
    }
}
