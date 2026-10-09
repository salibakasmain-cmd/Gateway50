package org.caravel.bubblie;

import android.content.Context;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One process-backed Ubuntu shell retained while users switch destinations. */
public final class TerminalSession {
    public interface Listener { void onOutput(String text); void onState(String state); }
    private static TerminalSession instance;
    public static synchronized TerminalSession get(Context context) {
        if (instance == null) instance = new TerminalSession(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final StringBuilder scrollback = new StringBuilder();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile Process process;
    private volatile PrintWriter input;
    private volatile String state = "Stopped";

    private TerminalSession(Context context) { this.context = context; }
    public synchronized void addListener(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    public synchronized void removeListener(Listener listener) { listeners.remove(listener); }
    public synchronized String scrollback() { return scrollback.toString(); }
    public String state() { return state; }
    public boolean isRunning() { Process p = process; return p != null && p.isAlive(); }

    public synchronized void start() {
        if (isRunning() || "Starting".equals(state)) return;
        state("Starting");
        executor.execute(() -> {
            try {
                List<String> binds = new ArrayList<>();
                binds.add(new java.io.File(context.getFilesDir(), "bubblie/workspace").getAbsolutePath() + ":/root/workspace");
                String command = "cd /root/workspace && " +
                    "if [ -x /usr/bin/script ]; then exec /usr/bin/script -qefc '/bin/bash -l' /dev/null; " +
                    "else exec /bin/bash -l; fi";
                Process running = new PrCliRuntime(context).runInDistro("ubuntu", command, binds);
                process = running;
                input = new PrintWriter(new OutputStreamWriter(running.getOutputStream(), StandardCharsets.UTF_8), true);
                state("Running");
                try (InputStreamReader reader = new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] chars = new char[2048]; int count;
                    while ((count = reader.read(chars)) >= 0) publish(clean(new String(chars, 0, count)));
                }
                int exit = running.waitFor();
                publish("\n[Shell exited with status " + exit + "]\n");
                state("Stopped");
            } catch (Exception error) {
                publish("\n[Terminal failed: " + safe(error) + "]\n");
                state("Failed");
            } finally { process = null; input = null; }
        });
    }

    public void writeLine(String command) {
        PrintWriter writer = input;
        if (writer == null || !isRunning()) throw new IllegalStateException("Shell is not running");
        writer.print(command); writer.print('\n'); writer.flush();
    }
    public void interrupt() {
        PrintWriter writer = input;
        if (writer == null || !isRunning()) throw new IllegalStateException("Shell is not running");
        writer.write(3); writer.flush();
    }
    public synchronized void stop() { Process p = process; if (p != null) p.destroy(); }
    public synchronized void restart() { stop(); executor.execute(() -> { try { Thread.sleep(350); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } start(); }); }

    private void publish(String text) {
        if (text.isEmpty()) return;
        List<Listener> copy;
        synchronized (this) {
            scrollback.append(text);
            if (scrollback.length() > 200000) scrollback.delete(0, scrollback.length() - 180000);
            copy = new ArrayList<>(listeners);
        }
        for (Listener listener : copy) listener.onOutput(text);
    }
    private void state(String value) {
        state = value;
        List<Listener> copy;
        synchronized (this) { copy = new ArrayList<>(listeners); }
        for (Listener listener : copy) listener.onState(value);
    }
    private static String clean(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
            .replaceAll("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)", "")
            .replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
    }
    private static String safe(Exception error) { String m = error.getMessage(); return m == null ? error.getClass().getSimpleName() : m; }
}
