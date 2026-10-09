package org.caravel.android;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Agent-first native CARAVEL shell. Runtime ownership remains in RuntimeService. */
public final class MainActivity extends android.app.Activity {
    private static final int BG = Color.rgb(6, 15, 24);
    private static final int PANEL = Color.rgb(11, 27, 40);
    private static final int PANEL_2 = Color.rgb(14, 35, 50);
    private static final int BORDER = Color.rgb(28, 60, 78);
    private static final int TEXT = Color.rgb(240, 246, 252);
    private static final int MUTED = Color.rgb(151, 170, 192);
    private static final int TEAL = Color.rgb(23, 218, 202);
    private static final int BLUE = Color.rgb(36, 132, 255);
    private static final int GREEN = Color.rgb(36, 218, 126);
    private static final int RED = Color.rgb(255, 104, 112);

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final List<GatewayClient.Message> chatHistory = new ArrayList<>();
    private final List<ActivityItem> activity = new ArrayList<>();
    private final List<TextView> navViews = new ArrayList<>();

    private LinearLayout root;
    private FrameLayout content;
    private TextView headerState;
    private String destination = "Agent";
    private boolean gatewayReady;
    private boolean receiverRegistered;
    private EditText composer;
    private LinearLayout conversation;
    private LinearLayout timeline;
    private ScrollView agentScroll;
    private Process terminalProcess;
    private PrintWriter terminalInput;
    private TextView terminalOutput;
    private File workspaceDirectory;

    private UbuntuManager ubuntu;
    private HermesManager hermes;
    private ProviderStore provider;
    private GatewayClient gateway;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(RuntimeService.EXTRA_MESSAGE);
            int percent = intent.getIntExtra(RuntimeService.EXTRA_PERCENT, 0);
            if (message == null || message.trim().isEmpty()) return;
            String detail = percent > 0 && percent < 100 ? message + " · " + percent + "%" : message;
            addActivity("Runtime", detail, percent < 0 ? "failed" : percent == 100 ? "completed" : "running");
            if (message.startsWith("Hermes Gateway ready")) probeGateway();
            refreshHeader();
            if ("Agent".equals(destination)) showAgent();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ubuntu = new UbuntuManager(this);
        hermes = new HermesManager(this);
        provider = ProviderStore.getInstance(this);
        gateway = new GatewayClient(this);
        workspaceDirectory = new File(getFilesDir(), "caravel/workspace");
        workspaceDirectory.mkdirs();

        Window window = getWindow();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 35) window.setNavigationBarContrastEnforced(false);
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        buildShell();
        applyInsets();
        requestNotifications();
        registerStatusReceiver();
        startForegroundService(new Intent(this, RuntimeService.class));
        addActivity("App", "CARAVEL opened", "completed");
        showAgent();
        probeGateway();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = horizontal(16, 10, 16, 10);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("◢", 34, TEAL, true);
        header.addView(mark, lp(48, 52));
        TextView title = text("CARAVEL", 24, TEXT, true);
        title.setLetterSpacing(.12f);
        header.addView(title, weight(1));
        headerState = text("●  Checking", 14, MUTED, false);
        header.addView(headerState);
        TextView menu = text("⋮", 28, MUTED, false);
        menu.setGravity(Gravity.CENTER);
        menu.setOnClickListener(v -> showSettings());
        header.addView(menu, lp(44, 48));
        root.addView(header, matchWrap());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildNavigation(), new LinearLayout.LayoutParams(-1, dp(72)));
        setContentView(root);
    }

    private View buildNavigation() {
        LinearLayout bar = horizontal(4, 5, 4, 3);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(Color.rgb(5, 13, 21));
        addNav(bar, "◢", "Agent");
        addNav(bar, "□", "Workspace");
        addNav(bar, ">_", "Terminal");
        addNav(bar, "◇", "Tools");
        addNav(bar, "⚙", "Settings");
        return bar;
    }

    private void addNav(LinearLayout parent, String icon, String label) {
        TextView item = text(icon + "\n" + label, 11, MUTED, false);
        item.setGravity(Gravity.CENTER);
        item.setTag(label);
        item.setOnClickListener(v -> navigate(label));
        navViews.add(item);
        parent.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void navigate(String name) {
        destination = name;
        for (TextView nav : navViews) {
            boolean active = name.equals(nav.getTag());
            nav.setTextColor(active ? ("Agent".equals(name) ? TEAL : BLUE) : MUTED);
            nav.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        }
        if ("Agent".equals(name)) showAgent();
        else if ("Workspace".equals(name)) showWorkspace(workspaceDirectory);
        else if ("Terminal".equals(name)) showTerminal();
        else if ("Tools".equals(name)) showTools();
        else showSettings();
    }

    private void showAgent() {
        destination = "Agent";
        selectNav("Agent");
        content.removeAllViews();
        FrameLayout page = new FrameLayout(this);
        agentScroll = new ScrollView(this);
        agentScroll.setFillViewport(true);
        LinearLayout body = vertical(16, 10, 16, 104);
        body.addView(buildHero());
        body.addView(sectionTitle("Suggested Tasks", null));
        body.addView(buildSuggestions());
        body.addView(sectionTitle("Recent Activity", null));
        timeline = vertical(0, 0, 0, 0);
        rebuildTimeline();
        body.addView(timeline);
        agentScroll.addView(body);
        page.addView(agentScroll, matchMatch());
        page.addView(buildComposer(), frameBottom(86, 12, 10));
        content.addView(page, matchMatch());
    }

    private View buildHero() {
        LinearLayout card = card(14);
        LinearLayout top = horizontal(12, 14, 12, 14);
        TextView avatar = text("◢", 38, TEAL, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(round(Color.rgb(5, 65, 66), 48, 0));
        top.addView(avatar, lp(76, 76));
        LinearLayout copy = vertical(12, 5, 4, 4);
        boolean ready = ubuntu.isInstalled() && hermes.isInstalled() && provider.isConfigured() && gatewayReady;
        copy.addView(text(ready ? "Agent ready" : setupTitle(), 22, TEXT, true));
        copy.addView(text(ready ? "Your AI agent is ready to help. Ask, build, explore, or run a task."
            : setupDescription(), 14, MUTED, false));
        top.addView(copy, weight(1));
        card.addView(top);

        LinearLayout states = horizontal(5, 12, 5, 12);
        states.addView(statusCell("Model", provider.isConfigured() ? provider.model() : "Not configured", provider.isConfigured()), weight(1));
        states.addView(statusCell("Linux", ubuntu.isInstalled() ? "Ready" : "Installing", ubuntu.isInstalled()), weight(1));
        states.addView(statusCell("Agent", gatewayReady ? "Ready" : hermes.isInstalled() ? "Offline" : "Not installed", gatewayReady), weight(1));
        card.addView(states);
        if (!hermes.isInstalled() && ubuntu.isInstalled()) {
            Button install = actionButton("Choose Hermes edition", TEAL);
            install.setOnClickListener(v -> chooseEdition());
            card.addView(install, margins(-1, 48, 12, 5, 12, 14));
        } else if (!provider.isConfigured() && hermes.isInstalled()) {
            Button configure = actionButton("Configure provider", BLUE);
            configure.setOnClickListener(v -> navigate("Settings"));
            card.addView(configure, margins(-1, 48, 12, 5, 12, 14));
        } else if (hermes.isInstalled() && provider.isConfigured() && !gatewayReady) {
            Button start = actionButton("Start agent runtime", TEAL);
            start.setOnClickListener(v -> serviceAction(RuntimeService.ACTION_START_BACKEND));
            card.addView(start, margins(-1, 48, 12, 5, 12, 14));
        }
        return card;
    }

    private String setupTitle() {
        if (!ubuntu.isInstalled()) return "Preparing Linux environment";
        if (!hermes.isInstalled()) return "Choose an agent edition";
        if (!provider.isConfigured()) return "Connect a model provider";
        return "Agent runtime offline";
    }

    private String setupDescription() {
        if (!ubuntu.isInstalled()) return "Ubuntu ARM64 is being safely installed. Progress appears below.";
        if (!hermes.isInstalled()) return "Install Lite, Standard, or Full to continue.";
        if (!provider.isConfigured()) return "Your API key is encrypted with Android Keystore.";
        return "Start Hermes Gateway, then send your first task.";
    }

    private View statusCell(String label, String value, boolean ok) {
        LinearLayout cell = vertical(9, 4, 8, 5);
        cell.addView(text(label, 11, MUTED, false));
        cell.addView(text(value, 13, ok ? GREEN : MUTED, true));
        return cell;
    }

    private View buildSuggestions() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = horizontal(0, 0, 0, 8);
        row.addView(suggestion("</>", "Build something", "Create apps, websites, or scripts.", v -> setPrompt("Build a new project in my workspace")));
        row.addView(suggestion("□", "Explore workspace", "Browse real project files and agent data.", v -> navigate("Workspace")));
        row.addView(suggestion("ϟ", "Run a task", "Execute commands, tools, or automation.", v -> setPrompt("Help me automate a task")));
        scroll.addView(row);
        return scroll;
    }

    private View suggestion(String icon, String title, String subtitle, View.OnClickListener click) {
        LinearLayout box = card(12);
        box.setPadding(dp(14), dp(14), dp(14), dp(12));
        box.addView(text(icon, 22, BLUE, true));
        box.addView(text(title, 15, TEXT, true), margins(-1, -2, 0, 8, 0, 0));
        box.addView(text(subtitle + "  ›", 12, MUTED, false));
        box.setOnClickListener(click);
        LinearLayout.LayoutParams params = margins(dp(196), dp(132), 0, 0, 10, 0);
        box.setLayoutParams(params);
        return box;
    }

    private View buildComposer() {
        LinearLayout row = horizontal(12, 8, 12, 8);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(BG);
        Button plus = circleButton("+", PANEL_2);
        plus.setOnClickListener(v -> navigate("Workspace"));
        row.addView(plus, lp(48, 48));
        composer = new EditText(this);
        composer.setHint("Ask CARAVEL anything…");
        composer.setHintTextColor(MUTED);
        composer.setTextColor(TEXT);
        composer.setTextSize(14);
        composer.setSingleLine(false);
        composer.setMaxLines(4);
        composer.setPadding(dp(16), dp(7), dp(12), dp(7));
        composer.setBackground(round(PANEL_2, 28, BORDER));
        row.addView(composer, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button send = circleButton("➤", BLUE);
        send.setTextColor(Color.WHITE);
        send.setOnClickListener(v -> sendMessage());
        row.addView(send, margins(dp(50), dp(50), 10, 0, 0, 0));
        return row;
    }

    private void sendMessage() {
        if (composer == null) return;
        String prompt = composer.getText().toString().trim();
        if (prompt.isEmpty()) return;
        if (!gatewayReady) {
            toast(provider.isConfigured() ? "Agent runtime is not ready yet" : "Configure a provider in Settings");
            return;
        }
        composer.setText("");
        chatHistory.add(new GatewayClient.Message("user", prompt));
        addActivity("You", prompt, "completed");
        addActivity("Agent", "Request submitted to Hermes Gateway", "running");
        showAgent();
        io.execute(() -> {
            final StringBuilder streaming = new StringBuilder();
            try {
                String answer = gateway.chat(new ArrayList<>(chatHistory), new GatewayClient.StreamListener() {
                    @Override public void onText(String delta) { streaming.append(delta); }
                    @Override public void onTool(String name, String state) {
                        runOnUiThread(() -> addActivity("Tool · " + name, "Hermes invoked this tool", state));
                    }
                });
                chatHistory.add(new GatewayClient.Message("assistant", answer));
                runOnUiThread(() -> {
                    completeLastRunning("Agent", answer, "completed");
                    completeToolActivities("completed");
                    showAgent();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    completeLastRunning("Agent", safeMessage(error), "failed");
                    completeToolActivities("failed");
                    showAgent();
                });
            }
        });
    }

    private void setPrompt(String value) {
        if (!"Agent".equals(destination)) navigate("Agent");
        if (composer != null) { composer.setText(value); composer.setSelection(value.length()); composer.requestFocus(); }
    }

    private void rebuildTimeline() {
        if (timeline == null) return;
        timeline.removeAllViews();
        if (activity.isEmpty()) {
            timeline.addView(text("Real runtime and agent events will appear here.", 13, MUTED, false));
            return;
        }
        int start = Math.max(0, activity.size() - 12);
        for (int i = activity.size() - 1; i >= start; i--) {
            ActivityItem item = activity.get(i);
            LinearLayout event = card(10);
            LinearLayout heading = horizontal(12, 10, 12, 2);
            int color = "failed".equals(item.state) ? RED : "completed".equals(item.state) ? GREEN : TEAL;
            heading.addView(text("●  " + item.title, 13, color, true), weight(1));
            heading.addView(text(item.time, 11, MUTED, false));
            event.addView(heading);
            event.addView(text(item.detail, 13, TEXT, false), margins(-1, -2, 28, 3, 12, 10));
            timeline.addView(event, margins(-1, -2, 0, 0, 0, 8));
        }
    }

    private void addActivity(String title, String detail, String state) {
        activity.add(new ActivityItem(title, detail, state));
        if (activity.size() > 60) activity.remove(0);
        rebuildTimeline();
    }

    private void completeLastRunning(String title, String detail, String state) {
        for (int i = activity.size() - 1; i >= 0; i--) {
            ActivityItem item = activity.get(i);
            if (title.equals(item.title) && "running".equals(item.state)) {
                item.detail = detail; item.state = state; return;
            }
        }
        addActivity(title, detail, state);
    }

    private void completeToolActivities(String state) {
        for (ActivityItem item : activity) {
            if (item.title.startsWith("Tool · ") && "running".equals(item.state)) {
                item.state = state;
                item.detail = "completed".equals(state) ? "Hermes tool call completed" : "Hermes request failed during this tool call";
            }
        }
    }

    private void showWorkspace(File directory) {
        destination = "Workspace";
        selectNav("Workspace");
        File rootDir = new File(getFilesDir(), "caravel/workspace");
        try {
            if (!directory.getCanonicalPath().startsWith(rootDir.getCanonicalPath())) directory = rootDir;
        } catch (Exception ignored) { directory = rootDir; }
        workspaceDirectory = directory;
        content.removeAllViews();
        LinearLayout page = vertical(16, 10, 16, 10);
        page.addView(pageTitle("Workspace", "Real files shared with /root/workspace"));
        LinearLayout actions = horizontal(0, 4, 0, 8);
        if (!directory.equals(rootDir)) {
            Button up = smallButton("← Up");
            File parent = directory.getParentFile();
            up.setOnClickListener(v -> showWorkspace(parent));
            actions.addView(up);
        }
        Button create = smallButton("+ New file");
        File finalDirectory = directory;
        create.setOnClickListener(v -> createWorkspaceFile(finalDirectory));
        actions.addView(create, margins(-2, 42, 8, 0, 0, 0));
        Button refresh = smallButton("Refresh");
        refresh.setOnClickListener(v -> showWorkspace(finalDirectory));
        actions.addView(refresh, margins(-2, 42, 8, 0, 0, 0));
        page.addView(actions);
        ScrollView scroll = new ScrollView(this);
        LinearLayout files = vertical(0, 0, 0, 0);
        File[] list = directory.listFiles();
        if (list == null || list.length == 0) {
            files.addView(emptyState("□", "Workspace is empty", "Files created by the agent in /root/workspace appear here."));
        } else {
            java.util.Arrays.sort(list, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File file : list) files.addView(fileRow(file));
        }
        scroll.addView(files);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(page, matchMatch());
    }

    private View fileRow(File file) {
        LinearLayout row = card(8);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        TextView icon = text(file.isDirectory() ? "□" : "≡", 22, file.isDirectory() ? TEAL : BLUE, true);
        row.addView(icon, lp(40, 44));
        LinearLayout names = vertical(5, 0, 0, 0);
        names.addView(text(file.getName(), 14, TEXT, true));
        names.addView(text(file.isDirectory() ? "Folder" : formatBytes(file.length()), 11, MUTED, false));
        row.addView(names, weight(1));
        row.addView(text("›", 24, MUTED, false));
        row.setOnClickListener(v -> { if (file.isDirectory()) showWorkspace(file); else editWorkspaceFile(file); });
        return withMargins(row, 0, 0, 0, 8);
    }

    private void createWorkspaceFile(File directory) {
        EditText name = dialogInput("notes.md", false);
        new AlertDialog.Builder(this).setTitle("New workspace file").setView(name)
            .setNegativeButton("Cancel", null).setPositiveButton("Create", (d, w) -> {
                try {
                    String value = name.getText().toString().trim();
                    if (value.isEmpty() || value.contains("/") || value.contains("\\")) throw new Exception("Invalid file name");
                    File file = new File(directory, value);
                    if (!file.createNewFile()) throw new Exception("File already exists");
                    editWorkspaceFile(file);
                } catch (Exception e) { toast(safeMessage(e)); }
            }).show();
    }

    private void editWorkspaceFile(File file) {
        if (file.length() > 1024 * 1024) { toast("Files larger than 1 MB are read-only here"); return; }
        EditText editor = dialogInput("", false);
        editor.setMinLines(10); editor.setGravity(Gravity.TOP); editor.setHorizontallyScrolling(false);
        try { editor.setText(readFile(file)); } catch (Exception e) { toast(safeMessage(e)); return; }
        new AlertDialog.Builder(this).setTitle(file.getName()).setView(editor)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                try {
                    writeFile(file, editor.getText().toString());
                    addActivity("Files", "Saved " + file.getName(), "completed");
                    showWorkspace(workspaceDirectory);
                } catch (Exception e) { toast(safeMessage(e)); }
            }).show();
    }

    private void showTerminal() {
        destination = "Terminal";
        selectNav("Terminal");
        content.removeAllViews();
        LinearLayout page = vertical(12, 10, 12, 10);
        page.addView(pageTitle("Terminal", "Ubuntu ARM64 · /root/workspace"));
        ScrollView outputScroll = new ScrollView(this);
        terminalOutput = text("", 12, Color.rgb(194, 237, 221), false);
        terminalOutput.setTypeface(Typeface.MONOSPACE);
        terminalOutput.setTextIsSelectable(true);
        terminalOutput.setPadding(dp(12), dp(12), dp(12), dp(12));
        terminalOutput.setBackground(round(Color.rgb(3, 13, 18), 12, BORDER));
        outputScroll.addView(terminalOutput);
        page.addView(outputScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout command = horizontal(0, 8, 0, 0);
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setTextColor(TEXT); input.setHintTextColor(MUTED);
        input.setHint(ubuntu.isInstalled() ? "Enter a Linux command" : "Ubuntu is not ready");
        input.setTypeface(Typeface.MONOSPACE); input.setBackground(round(PANEL_2, 10, BORDER));
        input.setPadding(dp(12), 0, dp(12), 0); input.setEnabled(ubuntu.isInstalled());
        command.addView(input, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button run = actionButton("Run", BLUE); run.setEnabled(ubuntu.isInstalled());
        command.addView(run, margins(dp(72), dp(50), 8, 0, 0, 0));
        run.setOnClickListener(v -> submitTerminal(input));
        input.setOnEditorActionListener((v, action, event) -> { submitTerminal(input); return true; });
        page.addView(command);
        content.addView(page, matchMatch());
        if (terminalProcess == null || !terminalProcess.isAlive()) startTerminal();
        else terminalOutput.setText("Session is active.\n");
    }

    private synchronized void startTerminal() {
        if (!ubuntu.isInstalled()) { if (terminalOutput != null) terminalOutput.setText("Ubuntu installation is not complete.\n"); return; }
        if (terminalProcess != null && terminalProcess.isAlive()) return;
        appendTerminal("Starting real PRoot shell…\n");
        io.execute(() -> {
            try {
                List<String> binds = new ArrayList<>();
                binds.add(new File(getFilesDir(), "caravel/workspace").getAbsolutePath() + ":/root/workspace");
                terminalProcess = new PrCliRuntime(this).runInDistro("ubuntu", "cd /root/workspace && exec /bin/bash -l", binds);
                terminalInput = new PrintWriter(new OutputStreamWriter(terminalProcess.getOutputStream(), StandardCharsets.UTF_8), true);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(terminalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) appendTerminal(line + "\n");
                }
                int exit = terminalProcess.waitFor(); appendTerminal("\n[Shell exited " + exit + "]\n");
            } catch (Exception e) { appendTerminal("Terminal failed: " + safeMessage(e) + "\n"); }
        });
    }

    private void submitTerminal(EditText input) {
        String command = input.getText().toString().trim();
        if (command.isEmpty()) return;
        input.setText(""); appendTerminal("root@caravel:~/workspace# " + command + "\n");
        PrintWriter writer = terminalInput;
        if (writer == null || terminalProcess == null || !terminalProcess.isAlive()) {
            appendTerminal("Shell is starting; try again.\n"); startTerminal(); return;
        }
        writer.println(command); writer.flush();
        addActivity("Terminal", command, "running");
    }

    private void appendTerminal(String value) {
        runOnUiThread(() -> { if (terminalOutput != null) terminalOutput.append(value); });
    }

    private void showTools() {
        destination = "Tools"; selectNav("Tools"); content.removeAllViews();
        LinearLayout page = vertical(16, 10, 16, 10);
        page.addView(pageTitle("Tools", "Truthful runtime capabilities and services"));
        page.addView(toolRow("Hermes Gateway", gatewayReady ? "Connected · localhost:8642" : "Offline", gatewayReady));
        page.addView(toolRow("Ubuntu / PRoot", ubuntu.isInstalled() ? "Ready · ARM64 userspace" : "Not installed", ubuntu.isInstalled()));
        HermesManager.Layer edition = hermes.installedLayer();
        page.addView(toolRow("Agent runtime", edition == null ? "Not installed" : edition.name() + " edition", edition != null));
        page.addView(toolRow("Provider", provider.isConfigured() ? provider.model() : "Not configured", provider.isConfigured()));
        page.addView(toolRow("Workspace bridge", workspaceDirectory.getAbsolutePath() + " ↔ /root/workspace", true));
        Button dashboard = actionButton("Open Hermes Dashboard", BLUE);
        dashboard.setEnabled(hermes.isInstalled());
        dashboard.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class)));
        page.addView(dashboard, margins(-1, 50, 0, 12, 0, 0));
        Button retry = actionButton("Refresh runtime status", TEAL);
        retry.setOnClickListener(v -> probeGateway());
        page.addView(retry, margins(-1, 50, 0, 10, 0, 0));
        content.addView(page, matchMatch());
    }

    private View toolRow(String name, String detail, boolean ready) {
        LinearLayout row = card(10); row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout copy = vertical(0, 0, 0, 0);
        copy.addView(text(name, 14, TEXT, true)); copy.addView(text(detail, 11, MUTED, false));
        row.addView(copy, weight(1)); row.addView(text(ready ? "● Ready" : "○ Unavailable", 12, ready ? GREEN : MUTED, true));
        return withMargins(row, 0, 0, 0, 8);
    }

    private void showSettings() {
        destination = "Settings"; selectNav("Settings"); content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = vertical(16, 10, 16, 20);
        page.addView(pageTitle("Settings", "Provider secrets are encrypted by Android Keystore"));
        TextView modelLabel = fieldLabel("Model"); page.addView(modelLabel);
        EditText model = settingInput(provider.model(), "e.g. gpt-4.1"); page.addView(model);
        page.addView(fieldLabel("Provider base URL"));
        EditText base = settingInput(provider.baseUrl(), "https://api.provider.com/v1"); page.addView(base);
        page.addView(fieldLabel("API key"));
        EditText key = settingInput("", provider.isConfigured() ? "Saved securely · enter only to replace" : "Enter API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); page.addView(key);
        page.addView(fieldLabel("API mode"));
        Button mode = smallButton(provider.apiMode());
        mode.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("API mode")
            .setSingleChoiceItems(new String[]{"chat_completions", "codex_responses"}, "codex_responses".equals(mode.getText()) ? 1 : 0,
                (dialog, which) -> { mode.setText(which == 0 ? "chat_completions" : "codex_responses"); dialog.dismiss(); }).show());
        page.addView(mode, margins(-1, 48, 0, 3, 0, 8));
        Button save = actionButton("Save and restart agent", BLUE);
        save.setOnClickListener(v -> saveProvider(model, base, key, mode));
        page.addView(save, margins(-1, 52, 0, 14, 0, 0));
        Button test = actionButton("Test provider connection", TEAL);
        test.setEnabled(provider.isConfigured()); test.setOnClickListener(v -> testProvider(test));
        page.addView(test, margins(-1, 52, 0, 10, 0, 0));
        Button edition = actionButton("Change Hermes edition", PANEL_2);
        edition.setOnClickListener(v -> chooseEdition()); page.addView(edition, margins(-1, 52, 0, 10, 0, 0));
        TextView security = text("Security boundary\nThe agent runs inside the Ubuntu PRoot userspace. Only the CARAVEL workspace is explicitly bound into Linux; Android permissions and private storage remain enforced.", 12, MUTED, false);
        security.setPadding(dp(14), dp(14), dp(14), dp(14)); security.setBackground(round(PANEL, 12, BORDER));
        page.addView(security, margins(-1, -2, 0, 18, 0, 0));
        scroll.addView(page); content.addView(scroll, matchMatch());
    }

    private void saveProvider(EditText model, EditText base, EditText key, Button mode) {
        String secret = key.getText().toString();
        try {
            provider.saveCustomProvider(model.getText().toString(), base.getText().toString(), secret, mode.getText().toString());
            key.setText(""); addActivity("Settings", "Provider saved securely", "completed");
            gatewayReady = false;
            serviceAction(RuntimeService.ACTION_RESTART_GATEWAY); toast("Saved. Agent runtime is restarting.");
            probeGatewayDelayed();
        } catch (Exception e) { toast(safeMessage(e)); }
    }

    private void testProvider(Button button) {
        button.setEnabled(false); button.setText("Testing authenticated endpoint…");
        io.execute(() -> {
            try {
                String result = provider.testConnection();
                runOnUiThread(() -> { button.setEnabled(true); button.setText("Test provider connection"); toast(result); addActivity("Provider", result, "completed"); });
            } catch (Exception e) {
                runOnUiThread(() -> { button.setEnabled(true); button.setText("Test provider connection"); toast(safeMessage(e)); addActivity("Provider", safeMessage(e), "failed"); });
            }
        });
    }

    private void chooseEdition() {
        String[] names = {"Lite · core agent", "Standard · broader tools", "Full · complete bundle"};
        HermesManager.Layer current = hermes.getSelectedLayer(); int selected = current == null ? 0 : current.ordinal();
        final int[] choice = {selected};
        new AlertDialog.Builder(this).setTitle("Choose Hermes edition")
            .setSingleChoiceItems(names, selected, (d, which) -> choice[0] = which)
            .setNegativeButton("Cancel", null).setPositiveButton("Install selected", (d, w) -> {
                HermesManager.Layer layer = HermesManager.Layer.values()[choice[0]];
                if (hermes.installedLayer() == layer) { toast(layer.name() + " is already installed"); return; }
                hermes.setSelectedLayer(layer);
                addActivity("Installer", "Selected " + layer.name() + " edition", "running");
                serviceAction(RuntimeService.ACTION_INSTALL_HERMES);
            }).show();
    }

    private void probeGateway() {
        headerState.setText("●  Checking"); headerState.setTextColor(MUTED);
        io.execute(() -> {
            boolean ready = gateway.health();
            runOnUiThread(() -> {
                boolean changed = gatewayReady != ready; gatewayReady = ready; refreshHeader();
                if (changed && ready) addActivity("Runtime", "Hermes Gateway health check passed", "completed");
                if ("Agent".equals(destination)) showAgent(); else if ("Tools".equals(destination)) showTools();
            });
        });
    }

    private void probeGatewayDelayed() {
        io.execute(() -> { try { Thread.sleep(3500); } catch (InterruptedException ignored) {} probeGateway(); });
    }

    private void refreshHeader() {
        boolean ready = ubuntu.isInstalled() && hermes.isInstalled() && provider.isConfigured() && gatewayReady;
        headerState.setText(ready ? "●  Ready" : "●  Setup"); headerState.setTextColor(ready ? GREEN : MUTED);
    }

    private void serviceAction(String action) {
        Intent intent = new Intent(this, RuntimeService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter(RuntimeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        receiverRegistered = true;
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 50);
    }

    private void applyInsets() {
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(statusReceiver);
        // Keep the foreground runtime alive. The interactive shell belongs to this activity only.
        if (terminalProcess != null) terminalProcess.destroy();
        io.shutdownNow();
        super.onDestroy();
    }

    private View pageTitle(String title, String subtitle) {
        LinearLayout block = vertical(0, 0, 0, 10);
        block.addView(text(title, 24, TEXT, true)); block.addView(text(subtitle, 12, MUTED, false)); return block;
    }
    private View sectionTitle(String title, String action) { TextView view = text(title, 19, TEXT, true); view.setPadding(0, dp(22), 0, dp(10)); return view; }
    private View emptyState(String icon, String title, String subtitle) { LinearLayout box = card(10); box.setGravity(Gravity.CENTER); box.setPadding(dp(20), dp(40), dp(20), dp(40)); box.addView(text(icon, 36, TEAL, true)); box.addView(text(title, 17, TEXT, true)); TextView sub = text(subtitle, 12, MUTED, false); sub.setGravity(Gravity.CENTER); box.addView(sub); return box; }
    private TextView fieldLabel(String value) { TextView label = text(value, 12, MUTED, true); label.setPadding(0, dp(10), 0, dp(5)); return label; }
    private EditText settingInput(String value, String hint) { EditText input = new EditText(this); input.setText(value); input.setHint(hint); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(14); input.setSingleLine(true); input.setPadding(dp(14), 0, dp(14), 0); input.setBackground(round(PANEL_2, 10, BORDER)); input.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(50))); return input; }
    private EditText dialogInput(String hint, boolean password) { EditText input = new EditText(this); input.setHint(hint); input.setPadding(dp(16), dp(10), dp(16), dp(10)); if (password) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return input; }
    private TextView text(String value, float size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(0, 1.18f); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private LinearLayout vertical(int l, int t, int r, int b) { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); view.setPadding(dp(l), dp(t), dp(r), dp(b)); return view; }
    private LinearLayout horizontal(int l, int t, int r, int b) { LinearLayout view = vertical(l, t, r, b); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private LinearLayout card(int radius) { LinearLayout view = vertical(0, 0, 0, 0); view.setBackground(round(PANEL, radius, BORDER)); return view; }
    private Button actionButton(String value, int color) { Button button = new Button(this); button.setText(value); button.setTextColor(TEXT); button.setTextSize(13); button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setBackground(round(color, 12, color == PANEL_2 ? BORDER : color)); return button; }
    private Button smallButton(String value) { Button button = actionButton(value, PANEL_2); button.setPadding(dp(12), 0, dp(12), 0); return button; }
    private Button circleButton(String value, int color) { Button button = actionButton(value, color); button.setTextSize(20); button.setPadding(0, 0, 0, 0); return button; }
    private GradientDrawable round(int color, int radius, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d; }
    private LinearLayout.LayoutParams weight(float value) { return new LinearLayout.LayoutParams(0, -2, value); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(dp(w), dp(h)); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private FrameLayout.LayoutParams matchMatch() { return new FrameLayout.LayoutParams(-1, -1); }
    private FrameLayout.LayoutParams frameBottom(int height, int horizontal, int bottom) { FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, dp(height), Gravity.BOTTOM); p.leftMargin = dp(horizontal); p.rightMargin = dp(horizontal); p.bottomMargin = dp(bottom); return p; }
    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private <T extends View> T withMargins(T view, int l, int t, int r, int b) { view.setLayoutParams(margins(-1, -2, l, t, r, b)); return view; }
    private void selectNav(String name) { for (TextView nav : navViews) { boolean active = name.equals(nav.getTag()); nav.setTextColor(active ? ("Agent".equals(name) ? TEAL : BLUE) : MUTED); nav.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL); } }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private static String safeMessage(Throwable error) { String value = error.getMessage(); return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value; }
    private static String formatBytes(long bytes) { if (bytes < 1024) return bytes + " B"; if (bytes < 1024 * 1024) return (bytes / 1024) + " KB"; return (bytes / (1024 * 1024)) + " MB"; }
    private static String readFile(File file) throws Exception { byte[] data = java.nio.file.Files.readAllBytes(file.toPath()); return new String(data, StandardCharsets.UTF_8); }
    private static void writeFile(File file, String value) throws Exception { try (FileOutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(StandardCharsets.UTF_8)); } }

    private static final class ActivityItem {
        final String title; String detail; String state; final String time;
        ActivityItem(String title, String detail, String state) { this.title = title; this.detail = detail; this.state = state; this.time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()); }
    }
}
