package org.caravel.android;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final int bg = Color.rgb(4, 28, 28);
    private final int panel = Color.rgb(10, 41, 40);
    private final int panelAlt = Color.rgb(16, 51, 50);
    private final int text = Color.rgb(255, 230, 203);
    private final int muted = Color.rgb(183, 169, 151);
    private final int accent = Color.rgb(255, 189, 56);
    private final int ok = Color.rgb(105, 209, 139);

    private TextView ubuntuState;
    private TextView hermesState;
    private TextView runtimeState;
    private Button ubuntuButton;
    private Button hermesButton;
    private Button startGatewayButton;
    private Button openDashboardButton;


    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!RuntimeService.ACTION_STATUS.equals(intent.getAction())) return;

            String message = intent.getStringExtra(RuntimeService.EXTRA_MESSAGE);
            int percent = intent.getIntExtra(RuntimeService.EXTRA_PERCENT, 0);

            runtimeState.setText(
                message + (percent >= 0 ? "\n" + percent + "%" : "")
            );

            if (message != null && message.startsWith("Ubuntu")) {
                ubuntuState.setText(message);
            }
            if (message != null && message.startsWith("Hermes")) {
                hermesState.setText(message);
            }

            refreshEnvironment();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10
            );
        }

        startService(new Intent(this, RuntimeService.class));
        setContentView(buildDashboard());
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter(RuntimeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(statusReceiver, filter);
        }

        refreshEnvironment();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(statusReceiver);
        super.onStop();
    }

    private void refreshEnvironment() {
        UbuntuManager ubuntu = new UbuntuManager(this);
        HermesManager hermes = new HermesManager(this);

        boolean ubuntuInstalled = ubuntu.isInstalled();
        boolean hermesInstalled = hermes.isInstalled();

        if (ubuntuInstalled) {
            ubuntuState.setText("Ubuntu Base 24.04.5 installed");
            ubuntuButton.setEnabled(false);
            hermesButton.setEnabled(true);
        } else {
            ubuntuState.setText("Ubuntu Base 24.04.5 not installed");
            ubuntuButton.setEnabled(true);
            hermesState.setText("Hermes waits for Ubuntu");
            hermesButton.setEnabled(false);
        }

        if (hermesInstalled) {
            hermesState.setText(
                "Hermes Agent " + HermesManager.VERSION + " installed"
            );
            hermesButton.setEnabled(false);
        }

        if (startGatewayButton != null) {
            startGatewayButton.setEnabled(
                hermesInstalled
            );
        }

        if (openDashboardButton != null) {
            openDashboardButton.setEnabled(
                hermesInstalled
            );
        }
    }

    private View buildDashboard() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        root.addView(header(), new LinearLayout.LayoutParams(-1, dp(64)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(10));

        TextView kicker = label("CARAVEL • HERMES HOST", 12, muted);
        kicker.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(kicker);
        body.addView(title("Standalone Agent Host", 28, text));

        TextView subtitle = label("Hermes runtime • Gateway • Dashboard • Android host", 13, muted);
        subtitle.setPadding(0, dp(4), 0, 0);
        body.addView(subtitle);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setPadding(0, dp(14), 0, dp(8));
        cards.addView(statusCard("AGENT", "Hermes", ok), weight(1));
        cards.addView(space(), new LinearLayout.LayoutParams(dp(10), 1));
        cards.addView(statusCard("BACKEND", "Local", ok), weight(1));
        body.addView(cards);

        body.addView(section("STEP 1 • LINUX ENVIRONMENT"));

        ubuntuState = textView("", 15, text);
        ubuntuState.setBackgroundColor(panel);
        ubuntuState.setPadding(dp(14), dp(14), dp(14), dp(14));
        body.addView(ubuntuState);

        ubuntuButton = new Button(this);
        ubuntuButton.setText("INSTALL UBUNTU");
        ubuntuButton.setTextColor(bg);
        ubuntuButton.setBackgroundColor(accent);
        ubuntuButton.setOnClickListener(v -> {
            ubuntuButton.setEnabled(false);

            Intent i = new Intent(this, RuntimeService.class)
                .setAction(RuntimeService.ACTION_INSTALL_UBUNTU);
            startService(i);
        });
        body.addView(ubuntuButton, margins(0, dp(10), 0, 0));

        body.addView(section("STEP 2 • HERMES AGENT"));

        hermesState = textView("", 15, text);
        hermesState.setBackgroundColor(panel);
        hermesState.setPadding(dp(14), dp(14), dp(14), dp(14));
        body.addView(hermesState);

        hermesButton = new Button(this);
        hermesButton.setText("INSTALL HERMES AGENT");
        hermesButton.setTextColor(bg);
        hermesButton.setBackgroundColor(accent);
        hermesButton.setOnClickListener(v -> {
            hermesButton.setEnabled(false);

            Intent i = new Intent(this, RuntimeService.class)
                .setAction(RuntimeService.ACTION_INSTALL_HERMES);
            startService(i);
        });
        body.addView(hermesButton, margins(0, dp(10), 0, 0));

        body.addView(section("STEP 3 • DASHBOARD"));

        openDashboardButton = new Button(this);
        openDashboardButton.setText("OPEN HERMES DASHBOARD");
        openDashboardButton.setTextColor(text);
        openDashboardButton.setBackgroundColor(panelAlt);
        openDashboardButton.setEnabled(false);
        openDashboardButton.setOnClickListener(v ->
            startActivity(new Intent(this, DashboardActivity.class))
        );
        body.addView(openDashboardButton);

        startGatewayButton = new Button(this);
        startGatewayButton.setText("START GATEWAY");
        startGatewayButton.setTextColor(text);
        startGatewayButton.setBackgroundColor(panelAlt);
        startGatewayButton.setOnClickListener(v -> {
            startGatewayButton.setEnabled(false);

            Intent i = new Intent(this, RuntimeService.class)
                .setAction(RuntimeService.ACTION_START_BACKEND);
            startService(i);
        });
        body.addView(
            startGatewayButton,
            margins(0, dp(8), 0, 0)
        );

        runtimeState = textView(
            "Runtime status will appear here.",
            14, muted
        );
        runtimeState.setPadding(dp(4), dp(14), dp(4), dp(14));
        body.addView(runtimeState);

        body.addView(section("RUNTIME ARCHITECTURE"));
        body.addView(runtimeLine("Android host", "CARAVEL"));
        body.addView(runtimeLine("Linux layer", "Ubuntu ARM64 + Android PRoot engine"));
        body.addView(runtimeLine("Agent", "Hermes Agent " + HermesManager.VERSION));
        body.addView(runtimeLine("Dashboard", "Official Hermes web UI in WebView"));
        body.addView(runtimeLine("Gateway", "Hermes API • 127.0.0.1:8642"));
        body.addView(runtimeLine("Dashboard", "Hermes UI • 127.0.0.1:9119"));

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView footer = label("CARAVEL 0.4.1  •  STANDALONE ANDROID HOST", 11, muted);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(34)));

        return root;
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(16), 0, dp(16), 0);
        h.setBackgroundColor(panel);

        TextView logo = title("✦  CARAVEL", 20, text);
        h.addView(logo, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView live = label("●  CARAVEL", 12, ok);
        live.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        h.addView(live);

        return h;
    }

    private TextView statusCard(String name, String value, int valueColor) {
        TextView v = textView(name + "\n" + value, 14, text);
        v.setBackgroundColor(panel);
        v.setPadding(dp(14), dp(12), dp(14), dp(12));
        return v;
    }

    private TextView runtimeLine(String left, String right) {
        TextView v = textView(left + "    " + right, 14, text);
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        v.setBackgroundColor(panel);
        return v;
    }

    private TextView section(String name) {
        TextView v = label(name, 12, muted);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setPadding(0, dp(16), 0, dp(8));
        return v;
    }

    private TextView title(String s, int size, int c) {
        return textView(s, size, c);
    }

    private TextView label(String s, int size, int c) {
        return textView(s, size, c);
    }

    private TextView textView(String s, int size, int c) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(c);
        return t;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, dp(76), w);
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private View space() {
        return new View(this);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
