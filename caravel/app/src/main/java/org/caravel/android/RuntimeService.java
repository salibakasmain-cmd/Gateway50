package org.caravel.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Build;
import android.content.pm.ServiceInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeService extends Service {
    public static final String ACTION_INSTALL_UBUNTU =
        "org.caravel.android.action.INSTALL_UBUNTU";
    public static final String ACTION_INSTALL_HERMES =
        "org.caravel.android.action.INSTALL_HERMES";
    public static final String ACTION_START_BACKEND =
        "org.caravel.android.action.START_BACKEND";
    public static final String ACTION_STATUS =
        "org.caravel.android.action.STATUS";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PERCENT = "percent";

    private static final String CHANNEL = "caravel_runtime";

    private ExecutorService executor;
    private UbuntuManager ubuntuManager;
    private HermesManager hermesManager;

    private volatile Process gatewayProcess;
    private volatile Process dashboardProcess;

    private Thread gatewayLogThread;
    private Thread dashboardLogThread;

    private final AtomicBoolean ubuntuInstalling = new AtomicBoolean(false);
    private final AtomicBoolean hermesInstalling = new AtomicBoolean(false);
    private final AtomicBoolean gatewayStarting = new AtomicBoolean(false);
    private final AtomicBoolean dashboardStarting = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        ubuntuManager = new UbuntuManager(this);
        hermesManager = new HermesManager(this);
        executor = Executors.newFixedThreadPool(3);

        Notification ready = notification("CARAVEL runtime ready");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1001,
                ready,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(1001, ready);
        }

        /*
         * CARAVEL is self-bootstrapping: on first launch it installs Ubuntu,
         * then waits for the user to choose exactly one Hermes edition.
         * Existing selected installations simply start their processes.
         */
        if (!ubuntuManager.isInstalled()) {
            installUbuntu();
        } else if (!hermesManager.isInstalled()) {
            sendStatus(
                "Ubuntu ready — choose a Hermes edition to install",
                0
            );
        } else {
            ensureRuntimeProcesses();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (ACTION_INSTALL_UBUNTU.equals(action)) {
                installUbuntu();
            } else if (ACTION_INSTALL_HERMES.equals(action)) {
                installHermes();
            } else if (ACTION_START_BACKEND.equals(action)) {
                startBackend();
            } else {
                ensureRuntimeProcesses();
            }
        } else {
            ensureRuntimeProcesses();
        }

        return START_STICKY;
    }

    private void ensureRuntimeProcesses() {
        if (hermesManager == null ||
            !hermesManager.isInstalled()) {
            return;
        }

        startDashboard();

        if (providerConfigured()) {
            startGateway();
        }
    }

    private void installUbuntu() {
        if (!ubuntuInstalling.compareAndSet(false, true)) {
            sendStatus("Ubuntu installation is already running", 0);
            return;
        }

        executor.submit(() -> {
            try {
                sendStatus("Starting Ubuntu Base installation", 0);
                ubuntuManager.install(this::sendStatus);
                sendStatus(
                    "Ubuntu ready — choose a Hermes edition to install",
                    100
                );
            } catch (Exception e) {
                sendStatus("Ubuntu installation failed: " + safe(e), -1);
            } finally {
                ubuntuInstalling.set(false);
            }
        });
    }

    private void installHermes() {
        if (!hermesInstalling.compareAndSet(false, true)) {
            sendStatus("Hermes installation is already running", 0);
            return;
        }

        executor.submit(() -> {
            try {
                if (!ubuntuManager.isInstalled()) {
                    throw new IllegalStateException("Ubuntu must be installed first");
                }

                HermesManager.Layer selected =
                    hermesManager.getSelectedLayer();

                if (selected == null) {
                    throw new IllegalStateException(
                        "Choose a Hermes edition before installing"
                    );
                }

                sendStatus(
                    "Installing Hermes " + selectedName(selected) + " edition",
                    0
                );
                hermesManager.install(this::sendStatus);

                if (providerConfigured()) {
                    sendStatus(
                        "Hermes installed — starting backend",
                        100
                    );
                    startGateway();
                } else {
                    sendStatus(
                        "Hermes installed — dashboard ready; configure a model/provider",
                        100
                    );
                    startDashboard();
                }
            } catch (Exception e) {
                sendStatus("Hermes installation failed: " + safe(e), -1);
            } finally {
                hermesInstalling.set(false);
            }
        });
    }

    private synchronized void startBackend() {
        if (hermesManager == null || !hermesManager.isInstalled()) {
            sendStatus("Hermes is not installed", -1);
            return;
        }

        if (!providerConfigured()) {
            sendStatus(
                "Configure a model/provider before starting Hermes",
                -1
            );
            startDashboard();
            return;
        }

        try {
            ProviderStore.getInstance(this)
                .persistHermesFiles();
        } catch (Exception e) {
            sendStatus(
                "Provider configuration could not be synchronized: " +
                safe(e),
                -1
            );
            return;
        }

        startGateway();
    }

    private boolean providerConfigured() {
        if (ProviderStore.getInstance(this).isConfigured()) {
            return true;
        }

        java.io.File home = new java.io.File(
            getFilesDir(),
            "caravel/hermes/home"
        );

        java.io.File config =
            new java.io.File(home, "config.yaml");

        return config.isFile() &&
            hasModelConfig(config);
    }

    private boolean hasModelConfig(java.io.File config) {
        try {
            String text =
                new String(
                    java.nio.file.Files.readAllBytes(
                        config.toPath()
                    ),
                    java.nio.charset.StandardCharsets.UTF_8
                );

            boolean provider =
                text.matches(
                    "(?s).*\\bprovider\\s*:\\s*[^#\\n]+.*"
                );

            boolean model =
                text.matches(
                    "(?s).*\\bdefault\\s*:\\s*[^#\\n]+.*"
                );

            boolean base =
                text.matches(
                    "(?s).*\\bbase_url\\s*:\\s*[^#\\n]+.*"
                );

            boolean key =
                text.matches(
                    "(?s).*\\bapi_key\\s*:\\s*[^#\\n]+.*"
                );

            return provider && model && base && key;
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void startGateway() {
        if (gatewayProcess != null && gatewayProcess.isAlive()) {
            waitForGatewayThenDashboard();
            return;
        }

        if (!gatewayStarting.compareAndSet(false, true)) {
            return;
        }

        executor.submit(() -> {
            try {
                sendStatus(
                    "Starting Hermes Gateway on localhost:" +
                    HermesManager.GATEWAY_PORT,
                    0
                );

                Process process = hermesManager.startGateway();
                gatewayProcess = process;

                gatewayLogThread = new Thread(
                    () -> drainProcess("Gateway", process),
                    "caravel-hermes-gateway-log"
                );
                gatewayLogThread.start();

                waitForGatewayThenDashboard();

                int exit = process.waitFor();
                gatewayProcess = null;

                if (exit != 0) {
                    sendStatus(
                        "Hermes Gateway exited with code " + exit,
                        -1
                    );

                    // Do not recursively restart a failing gateway.
                    // The user can retry explicitly after correcting the cause.
                } else {
                    sendStatus("Hermes Gateway stopped", 0);
                }
            } catch (Exception e) {
                gatewayProcess = null;
                sendStatus(
                    "Hermes Gateway failed: " + safe(e),
                    -1
                );
            } finally {
                gatewayStarting.set(false);
            }
        });
    }

    private void waitForGatewayThenDashboard() {
        executor.submit(() -> {
            try {
                if (!waitForGatewayHealth(30000)) {
                    sendStatus(
                        "Gateway started but health endpoint is not ready",
                        -1
                    );
                    return;
                }

                sendStatus(
                    "Hermes Gateway ready on localhost:" +
                    HermesManager.GATEWAY_PORT,
                    100
                );

                startDashboard();
            } catch (Exception e) {
                sendStatus(
                    "Gateway readiness check failed: " + safe(e),
                    -1
                );
            }
        });
    }

    private boolean waitForGatewayHealth(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String key = hermesManager.gatewayKey();

        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)new URL(
                    hermesManager.gatewayHealthUrl()
                ).openConnection();

                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestProperty(
                    "Authorization", "Bearer " + key
                );

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    return true;
                }
            } catch (Exception ignored) {
                // Gateway is still starting.
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

            Thread.sleep(500);
        }

        return false;
    }

    private synchronized void startDashboard() {
        if (dashboardProcess != null && dashboardProcess.isAlive()) {
            return;
        }

        if (!dashboardStarting.compareAndSet(false, true)) {
            return;
        }

        executor.submit(() -> {
            try {
                sendStatus(
                    "Starting Hermes Dashboard on localhost:" +
                    HermesManager.DASHBOARD_PORT,
                    0
                );

                Process process = hermesManager.startDashboard();
                dashboardProcess = process;

                dashboardLogThread = new Thread(
                    () -> drainDashboard(process),
                    "caravel-hermes-dashboard-log"
                );
                dashboardLogThread.start();

                int exit = process.waitFor();
                dashboardProcess = null;

                if (exit == 0) {
                    sendStatus("Hermes Dashboard stopped", 0);
                } else {
                    sendStatus(
                        "Hermes Dashboard exited with code " + exit,
                        -1
                    );
                }
            } catch (Exception e) {
                dashboardProcess = null;
                sendStatus(
                    "Hermes Dashboard failed: " + safe(e),
                    -1
                );
            } finally {
                dashboardStarting.set(false);
            }
        });
    }

    private void drainDashboard(Process process) {
        drainProcess("Dashboard", process);
    }

    private void drainProcess(String name, Process process) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()))) {

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.contains("ready") ||
                    line.contains("READY") ||
                    line.contains("started") ||
                    line.contains("Started")) {

                    sendStatus(
                        "Hermes " + name + " reports ready",
                        100
                    );
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String selectedName(HermesManager.Layer layer) {
        String name = layer == null ? "Standard" : layer.name();
        return name.substring(0, 1) +
            name.substring(1).toLowerCase(java.util.Locale.US);
    }

    private void sendStatus(String message, int percent) {
        Intent out = new Intent(ACTION_STATUS)
            .setPackage(getPackageName())
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_PERCENT, percent);

        sendBroadcast(out);

        NotificationManager manager =
            getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.notify(1001, notification(message));
        }
    }

    private Notification notification(String message) {
        Intent launch = new Intent(this, MainActivity.class);

        PendingIntent pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(
                android.R.drawable.stat_sys_download_done
            )
            .setContentTitle("CARAVEL runtime")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }

    private static String safe(Exception e) {
        String s = e.getMessage();
        return s == null
            ? e.getClass().getSimpleName()
            : s;
    }

    private void createChannel() {
        NotificationManager manager =
            getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.createNotificationChannel(
                new NotificationChannel(
                    CHANNEL,
                    "CARAVEL runtime",
                    NotificationManager.IMPORTANCE_LOW
                )
            );
        }
    }

    @Override
    public void onDestroy() {
        Process dashboard = dashboardProcess;
        dashboardProcess = null;

        if (dashboard != null) {
            dashboard.destroy();
        }

        Process gateway = gatewayProcess;
        gatewayProcess = null;

        if (gateway != null) {
            gateway.destroy();
        }

        gatewayStarting.set(false);
        dashboardStarting.set(false);

        if (executor != null) {
            executor.shutdownNow();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
