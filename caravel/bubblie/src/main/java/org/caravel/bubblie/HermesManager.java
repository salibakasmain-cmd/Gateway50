package org.caravel.bubblie;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HermesManager {
    /*
     * Bubblie follows the prebuilt layered runtime used by Hermes 3.8:
     * Ubuntu is the base rootfs, then Lite -> Standard -> Full overlays.
     *
     * This is intentionally a release identifier, not a claimed semantic
     * Hermes Agent source version. The release assets are the runtime.
     */
    public static final String VERSION = "2026.08.14-1714";
    public static final String RELEASE_TAG =
        "v" + VERSION;

    private static final String ARM64_PREFIX =
        "arm64-v8a-";

    private static final String PREFS_NAME = "bubblie_hermes";
    private static final String PREF_SELECTED_LAYER = "selected_layer_v2";

    private static final String LITE_MD5 =
        "ebe6f17c7c6ab81631070479f3e5a393";
    private static final String STANDARD_MD5 =
        "50a3f0b972b92eb18a818fd770209170";
    private static final String FULL_MD5 =
        "537c669aa54de416102fc0fb9b6e1d13";

    private static final String LITE_URL =
        "https://github.com/goldenduo/XermesRelease/releases/download/" +
        RELEASE_TAG + "/" + ARM64_PREFIX + "lite.7z";
    private static final String STANDARD_URL =
        "https://github.com/goldenduo/XermesRelease/releases/download/" +
        RELEASE_TAG + "/" + ARM64_PREFIX + "standard.7z";
    private static final String FULL_URL =
        "https://github.com/goldenduo/XermesRelease/releases/download/" +
        RELEASE_TAG + "/" + ARM64_PREFIX + "full.7z";

    public static final int GATEWAY_PORT = 8642;
    public static final int DASHBOARD_PORT = 9119;

    private final Context context;
    private final UbuntuManager ubuntu;
    private final PrCliRuntime runtime;

    private final File root;
    private final File legacyHome;
    private final File workspace;
    private final File guestHome;
    private final File guestAgent;
    private final File guestWeb;

    public HermesManager(Context context) {
        this.context = context.getApplicationContext();
        this.ubuntu = new UbuntuManager(this.context);
        this.runtime = new PrCliRuntime(this.context);

        this.root = new File(
            this.context.getFilesDir(),
            "bubblie/hermes"
        );

        /*
         * ProviderStore keeps the encrypted API key in Android Keystore-backed
         * preferences and persists its Hermes config under this host path.
         * We synchronize these files into the real guest ~/.hermes before
         * launching Hermes, rather than bind-mounting over the whole guest
         * ~/.hermes directory.
         */
        this.legacyHome = new File(root, "home");

        this.workspace = new File(
            this.context.getFilesDir(),
            "bubblie/workspace"
        );

        this.guestHome = new File(
            this.ubuntu.rootfs(),
            "root/.hermes"
        );
        this.guestAgent = new File(
            this.ubuntu.rootfs(),
            "usr/local/lib/hermes-agent"
        );
        this.guestWeb = new File(
            guestHome,
            "web_dist"
        );
    }

    public enum Layer {
        LITE(
            0,
            "lite.7z",
            705463722L,
            LITE_URL,
            LITE_MD5,
            ".hermes_lite_installed"
        ),
        STANDARD(
            1,
            "standard.7z",
            1032222633L,
            STANDARD_URL,
            STANDARD_MD5,
            ".hermes_standard_installed"
        ),
        FULL(
            2,
            "full.7z",
            1053371990L,
            FULL_URL,
            FULL_MD5,
            ".hermes_full_installed"
        );

        final int rank;
        final String artifact;
        public final long downloadBytes;
        final String url;
        final String md5;
        final String marker;

        Layer(
            int rank,
            String artifact,
            long downloadBytes,
            String url,
            String md5,
            String marker
        ) {
            this.rank = rank;
            this.artifact = artifact;
            this.downloadBytes = downloadBytes;
            this.url = url;
            this.md5 = md5;
            this.marker = marker;
        }
    }

    public boolean isInstalled() {
        Layer selected = getSelectedLayer();
        return selected != null
            && layerInstalled(selected)
            && new File(
                ubuntu.rootfs(),
                "usr/local/bin/hermes"
            ).isFile();
    }

    public File sourceDir() {
        return guestAgent;
    }

    public File webDir() {
        return guestWeb;
    }

    public File homeDir() {
        return legacyHome;
    }

    public Layer getSelectedLayer() {
        String name = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).getString(PREF_SELECTED_LAYER, null);

        if (name == null) {
            return null;
        }

        try {
            return Layer.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setSelectedLayer(Layer layer) {
        if (layer == null) {
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            ).edit().remove(PREF_SELECTED_LAYER).apply();
            return;
        }

        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().putString(PREF_SELECTED_LAYER, layer.name()).apply();
    }

    public Layer installedLayer() {
        for (int i = Layer.values().length - 1; i >= 0; i--) {
            Layer layer = Layer.values()[i];
            if (layerInstalled(layer)) return layer;
        }
        return null;
    }

    public void install(Progress progress) throws IOException {
        install(getSelectedLayer(), progress);
    }

    public void installFull(Progress progress) throws IOException {
        setSelectedLayer(Layer.FULL);
        install(Layer.FULL, progress);
    }

    public void install(
        Layer target,
        Progress progress
    ) throws IOException {
        if (!ubuntu.isInstalled()) {
            throw new IOException(
                "Ubuntu must be installed before Hermes"
            );
        }

        runtime.ensureLayout();
        root.mkdirs();
        if (!legacyHome.exists() &&
            !legacyHome.mkdirs() &&
            !legacyHome.isDirectory()) {
            throw new IOException(
                "Unable to create Bubblie Hermes host state"
            );
        }

        if (target == null) {
            target = getSelectedLayer();
        }

        if (target == null) {
            throw new IOException(
                "Select a Hermes edition before installation"
            );
        }

        setSelectedLayer(target);
        if (progress != null) {
            progress.onProgress(
                "Installing Hermes " + targetName(target) + " edition",
                0
            );
        }

        /*
         * The selected edition is the user-visible installation choice and
         * the only Hermes archive Bubblie downloads for that installation.
         * Ubuntu is the already-installed base environment; Bubblie does not
         * silently walk Lite -> Standard -> Full.
         */
        installLayer(target, progress);

        prepareWebAssets(progress);
        ensureRuntimeFiles();

        try {
            validateGuestRuntime(progress);
        } catch (IOException e) {
            /*
             * Do not leave a successful-looking layer marker behind when the
             * guest runtime itself fails validation.
             */
            File marker = new File(
                ubuntu.rootfs(),
                target.marker
            );
            if (marker.exists()) {
                marker.delete();
            }
            throw e;
        }

        /*
         * Remove the old source-install leftovers from earlier Bubblie
         * revisions. The actual runtime now lives inside the Ubuntu rootfs.
         */
        deleteRecursive(
            new File(root, "source")
        );
        deleteRecursive(
            new File(root, "source.new")
        );
        deleteRecursive(
            new File(root, "source.old")
        );

        if (progress != null) {
            progress.onProgress(
                "Hermes prebuilt " + targetName(target) + " ready",
                100
            );
        }
    }

    private void installLayer(
        Layer layer,
        Progress progress
    ) throws IOException {
        long required = layer.downloadBytes * 2L;
        long available = context.getFilesDir().getUsableSpace();
        if (available < required) {
            throw new IOException("Not enough free storage for Hermes " +
                targetName(layer) + ". Need approximately " +
                (required / (1024L * 1024L)) + " MB free; available " +
                (available / (1024L * 1024L)) + " MB");
        }
        File archives = new File(root, "layers");
        if (!archives.isDirectory() &&
            !archives.mkdirs() &&
            !archives.isDirectory()) {
            throw new IOException(
                "Unable to create Hermes download directory"
            );
        }

        File archive = new File(
            archives,
            layer.artifact
        );

        int basePercent = 5;

        if (progress != null) {
            progress.onProgress(
                "Hermes " + targetName(layer) +
                " layer: checking archive",
                basePercent
            );
        }

        if (!hasValidDigest(archive, layer.md5)) {
            if (archive.isFile() && !archive.delete()) {
                throw new IOException(
                    "Unable to replace invalid Hermes archive: " +
                    archive
                );
            }

            if (progress != null) {
                progress.onProgress(
                    "Downloading Hermes " +
                    targetName(layer) + " layer",
                    basePercent
                );
            }

            download(
                archive,
                new File(archive.getPath() + ".part"),
                layer.url,
                layer.md5,
                8,
                67,
                progress
            );
        }

        if (!hasValidDigest(archive, layer.md5)) {
            throw new IOException(
                "Hermes " + targetName(layer) +
                " archive failed MD5 verification"
            );
        }

        if (progress != null) {
            progress.onProgress(
                "Extracting Hermes " + targetName(layer) +
                " into Ubuntu",
                basePercent + 26
            );
        }

        /*
         * The archive is itself a rootfs overlay. Extract directly into the
         * installed Ubuntu rootfs so POSIX symlinks and permissions are
         * materialized by the native 7-Zip path that already passed the
         * Ubuntu ARM64 self-test on the M14.
         */
        Native7z.extract(
            context,
            archive,
            ubuntu.rootfs(),
            ""
        );

        if (progress != null) {
            progress.onProgress(
                "Verifying Hermes " + targetName(layer) +
                " runtime",
                basePercent + 29
            );
        }

        validateLayer(layer);

        writeText(
            new File(
                ubuntu.rootfs(),
                layer.marker
            ),
            "release=" + RELEASE_TAG + "\n" +
            "layer=" + layer.name().toLowerCase(Locale.US) + "\n" +
            "artifact=" + layer.artifact + "\n" +
            "md5=" + layer.md5 + "\n"
        );

        /*
         * Keep the successful layer out of app storage. If extraction fails,
         * the archive is deliberately retained so the next attempt can retry
         * without another download.
         */
        if (!archive.delete() && archive.isFile()) {
            throw new IOException(
                "Unable to remove Hermes archive cache: " +
                archive
            );
        }

        File partial =
            new File(archive.getPath() + ".part");
        partial.delete();

        if (progress != null) {
            progress.onProgress(
                "Hermes " + targetName(layer) + " installed",
                99
            );
        }
    }

    private void validateLayer(Layer layer) throws IOException {
        File rootfs = ubuntu.rootfs();

        if (!new File(
            rootfs,
            layer.marker
        ).exists()) {
            /*
             * The marker is intentionally checked by isInstalled() only
             * after validateLayer() finishes; no marker is needed here.
             */
        }

        if (layer.rank >= Layer.LITE.rank) {
            requireFile(
                new File(rootfs, "usr/local/bin/hermes"),
                "Hermes launcher"
            );
            requireFile(
                new File(
                    rootfs,
                    "usr/local/lib/hermes-agent"
                ),
                "Hermes agent root"
            );
        }

        if (layer.rank >= Layer.STANDARD.rank) {
            requireFile(
                new File(
                    rootfs,
                    "usr/local/lib/hermes-agent/venv/bin/python3"
                ),
                "Hermes bundled Python"
            );
        }

        if (layer.rank >= Layer.FULL.rank) {
            requireFile(
                new File(
                    rootfs,
                    "root/.hermes"
                ),
                "Hermes persistent home"
            );
        }
    }

    private boolean layerInstalled(Layer layer) {
        return new File(
            ubuntu.rootfs(),
            layer.marker
        ).isFile();
    }

    private void requireFile(
        File file,
        String label
    ) throws IOException {
        if (!file.exists()) {
            throw new IOException(
                label + " is missing after Hermes extraction: " +
                file
            );
        }
    }

    public void ensureRuntimeFiles() throws IOException {
        runtime.ensureLayout();

        if (!legacyHome.exists() &&
            !legacyHome.mkdirs() &&
            !legacyHome.isDirectory()) {
            throw new IOException(
                "Unable to create Hermes host state"
            );
        }

        if (!guestHome.exists() &&
            !guestHome.mkdirs() &&
            !guestHome.isDirectory()) {
            throw new IOException(
                "Unable to create Hermes guest home"
            );
        }

        File hostEnv = new File(
            legacyHome,
            ".env"
        );
        File guestEnv = new File(
            guestHome,
            ".env"
        );

        String existing;

        if (hostEnv.isFile()) {
            existing = readText(hostEnv).replace("\\n", "\n");
        } else if (guestEnv.isFile()) {
            existing = readText(guestEnv).replace("\\n", "\n");
        } else {
            existing = "";
        }

        String apiKey = findEnvValue(
            existing,
            "API_SERVER_KEY"
        );

        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = createApiKey();
        }

        String updated = upsertEnvValue(
            existing,
            "API_SERVER_ENABLED",
            "true"
        );
        updated = upsertEnvValue(
            updated,
            "API_SERVER_HOST",
            "127.0.0.1"
        );
        updated = upsertEnvValue(
            updated,
            "API_SERVER_PORT",
            Integer.toString(GATEWAY_PORT)
        );
        updated = upsertEnvValue(
            updated,
            "API_SERVER_KEY",
            apiKey
        );

        writeText(hostEnv, updated);
        writeText(guestEnv, updated);

        File hostConfig = new File(
            legacyHome,
            "config.yaml"
        );
        File guestConfig = new File(
            guestHome,
            "config.yaml"
        );

        if (hostConfig.isFile()) {
            copyFile(hostConfig, guestConfig);
        }
    }

    public Process startGateway() throws IOException {
        if (!isInstalled()) {
            throw new IOException(
                "Hermes Agent is not installed"
            );
        }

        ensureRuntimeFiles();

        String command =
            commonEnvironment() +
            "cd /root/workspace && " +
            "exec /usr/local/bin/hermes " +
            "gateway run --external-supervisor";

        return runtime.runInDistro(
            "ubuntu",
            command,
            runtimeBinds()
        );
    }

    public Process startDashboard() throws IOException {
        if (!isInstalled()) {
            throw new IOException(
                "Hermes Agent is not installed"
            );
        }

        ensureRuntimeFiles();
        prepareWebAssets(null);

        String command =
            commonEnvironment() +
            "export HERMES_WEB_DIST=/root/.hermes/web_dist; " +
            "cd /root/workspace && " +
            "exec /usr/local/bin/hermes " +
            "dashboard --host 127.0.0.1 --port " +
            DASHBOARD_PORT +
            " --no-open --skip-build";

        return runtime.runInDistro(
            "ubuntu",
            command,
            runtimeBinds()
        );
    }

    private String commonEnvironment() {
        return
            "export HOME=/root; " +
            "export USER=root; " +
            "export TERM=xterm-256color; " +
            "export LANG=en_US.UTF-8; " +
            "export HERMES_HOME=/root/.hermes; " +
            "export HERMES_BIN=/usr/local/bin/hermes; " +
            "export HERMES_AGENT_ROOT=/usr/local/lib/hermes-agent; " +
            "export HERMES_AGENT_BRIDGE_PYTHON=/usr/local/lib/hermes-agent/venv/bin/python3; " +
            "export PYTHONPATH=/usr/local/lib/hermes-agent; " +
            "export HERMES_DISABLE_LAZY_INSTALLS=1; " +
            "export PATH=/usr/local/lib/hermes-agent/venv/bin:/usr/local/bin:" +
            "/root/.hermes/node/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin; " +
            "export QROOT_STUB_BLOCK_TARGET_SECCOMP=1; " +
            "export QROOT_LOOPBACK_UNIX=1; " +
            "export QROOT_LOOPBACK_NAMESPACE=hermes-agent; " +
            "export HERMES_TUI_RPC_TIMEOUT_MS=600000; " +
            "export HERMES_TUI_STARTUP_TIMEOUT_MS=60000; " +
            "export HERMES_TUI_RPC_POOL_WORKERS=4; ";
    }

    private List<String> runtimeBinds() {
        List<String> binds = new ArrayList<>();

        /*
         * Keep the workspace shared between Bubblie and the guest, but do
         * not bind-mount ~/.hermes or /usr/local/lib/hermes-agent: those are
         * part of the prebuilt runtime layer itself.
         */
        if (!workspace.isDirectory()) {
            workspace.mkdirs();
        }

        binds.add(
            workspace.getAbsolutePath() +
            ":/root/workspace"
        );

        return binds;
    }

    private void prepareWebAssets(
        Progress progress
    ) throws IOException {
        /*
         * Prefer the web_dist bundled in the prebuilt layer. Only fall back
         * to Bubblie APK assets when the layer does not contain it.
         */
        if (new File(
            guestWeb,
            "index.html"
        ).isFile()) {
            return;
        }

        if (!guestWeb.exists() &&
            !guestWeb.mkdirs() &&
            !guestWeb.isDirectory()) {
            throw new IOException(
                "Unable to create Hermes dashboard asset directory"
            );
        }

        if (progress != null) {
            progress.onProgress(
                "Installing bundled Hermes dashboard assets",
                96
            );
        }

        copyAssetTree(
            context.getAssets(),
            "hermes-web",
            guestWeb
        );

        if (!new File(
            guestWeb,
            "index.html"
        ).isFile()) {
            throw new IOException(
                "Hermes dashboard assets are missing"
            );
        }
    }

    private void copyAssetTree(
        AssetManager assets,
        String assetPath,
        File destination
    ) throws IOException {
        String[] children =
            assets.list(assetPath);

        if (children == null ||
            children.length == 0) {
            try (
                InputStream in =
                    assets.open(assetPath);
                FileOutputStream out =
                    new FileOutputStream(destination)
            ) {
                copy(in, out);
            }
            return;
        }

        if (!destination.exists() &&
            !destination.mkdirs() &&
            !destination.isDirectory()) {
            throw new IOException(
                "Unable to create " + destination
            );
        }

        for (String child : children) {
            copyAssetTree(
                assets,
                assetPath + "/" + child,
                new File(destination, child)
            );
        }
    }

    private void validateGuestRuntime(
        Progress progress
    ) throws IOException {
        requireFile(
            new File(
                ubuntu.rootfs(),
                "usr/local/bin/hermes"
            ),
            "Hermes launcher"
        );
        requireFile(
            new File(
                ubuntu.rootfs(),
                "usr/local/lib/hermes-agent/venv/bin/python3"
            ),
            "Hermes Python"
        );

        String command =
            commonEnvironment() +
            "set -eu; " +
            "test -x /usr/local/bin/hermes; " +
            "test -x /usr/local/lib/hermes-agent/venv/bin/python3; " +
            "v=\"$(/usr/local/lib/hermes-agent/venv/bin/python3 --version)\"; " +
            "echo \"Bubblie_HERMES_PYTHON_OK $v\"; " +
            "/usr/local/bin/hermes --version";

        Process process = runtime.runInDistro(
            "ubuntu",
            command,
            runtimeBinds()
        );

        int exit = waitAndDrain(
            process,
            progress,
            "Hermes runtime check"
        );

        if (exit != 0) {
            throw new IOException(
                "Prebuilt Hermes runtime validation failed (exit " +
                exit + ")"
            );
        }
    }

    public String gatewayHealthUrl() {
        return "http://127.0.0.1:" +
            GATEWAY_PORT +
            "/health";
    }

    public String gatewayKey() throws IOException {
        ensureRuntimeFiles();

        File env = new File(
            guestHome,
            ".env"
        );

        String content = readText(env)
            .replace("\\n", "\n");

        for (String line :
            content.split("\\R")) {
            if (line.startsWith("API_SERVER_KEY=")) {
                return line.substring(
                    "API_SERVER_KEY=".length()
                ).trim();
            }
        }

        throw new IOException(
            "Bubblie API server key is missing"
        );
    }

    private static String findEnvValue(
        String content,
        String key
    ) {
        for (String line :
            content.split("\\R")) {
            if (line.startsWith(key + "=")) {
                return line.substring(
                    key.length() + 1
                ).trim();
            }
        }
        return null;
    }

    private static String upsertEnvValue(
        String content,
        String key,
        String value
    ) {
        String[] lines =
            content.split("\\R", -1);

        StringBuilder out =
            new StringBuilder();

        boolean replaced = false;

        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                if (!replaced) {
                    out.append(key)
                        .append("=")
                        .append(value)
                        .append('\n');
                    replaced = true;
                }
                continue;
            }

            if (!line.isEmpty() ||
                out.length() > 0) {
                out.append(line).append('\n');
            }
        }

        if (!replaced) {
            out.append(key)
                .append("=")
                .append(value)
                .append('\n');
        }

        return out.toString();
    }

    private String createApiKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);

        StringBuilder out =
            new StringBuilder(64);

        for (byte b : bytes) {
            out.append(
                String.format(
                    Locale.US,
                    "%02x",
                    b
                )
            );
        }

        return out.toString();
    }

    private void download(
        File target,
        File partial,
        String url,
        String expectedMd5,
        int basePercent,
        int percentSpan,
        Progress progress
    ) throws IOException {
        for (int attempt = 1; attempt <= 4; attempt++) {
            long existing =
                partial.isFile()
                    ? partial.length()
                    : 0L;

            HttpURLConnection conn = null;

            try {
                conn =
                    (HttpURLConnection)new URL(url)
                        .openConnection();

                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty(
                    "User-Agent",
                    "Bubblie/" + VERSION
                );
                conn.setRequestProperty(
                    "Accept-Encoding",
                    "identity"
                );

                if (existing > 0) {
                    conn.setRequestProperty(
                        "Range",
                        "bytes=" + existing + "-"
                    );
                }

                int code =
                    conn.getResponseCode();

                if (code == 416 && existing > 0) {
                    partial.delete();
                    continue;
                }

                boolean append =
                    existing > 0 &&
                    code == HttpURLConnection.HTTP_PARTIAL;

                if (code != HttpURLConnection.HTTP_OK &&
                    !append) {
                    throw new IOException(
                        "Hermes layer download failed: HTTP " +
                        code
                    );
                }

                long contentLength =
                    conn.getContentLengthLong();

                long total =
                    contentLength > 0
                        ? contentLength + (append ? existing : 0)
                        : -1L;

                if (!append) {
                    existing = 0L;
                }

                File parent =
                    partial.getParentFile();

                if (parent != null) {
                    mkdirs(parent);
                }

                try (
                    InputStream in =
                        conn.getInputStream();
                    FileOutputStream out =
                        new FileOutputStream(
                            partial,
                            append
                        )
                ) {
                    byte[] buffer =
                        new byte[131072];

                    long done = existing;
                    int n;

                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        done += n;

                        if (progress != null &&
                            total > 0) {

                            int pct =
                                basePercent +
                                (int)Math.min(
                                    percentSpan,
                                    (done * percentSpan) / total
                                );

                            progress.onProgress(
                                String.format(
                                    Locale.US,
                                    "Downloading Hermes layer %d%% (%d / %d MB)",
                                    (done * 100L) / total,
                                    done / (1024L * 1024L),
                                    total / (1024L * 1024L)
                                ),
                                pct
                            );
                        }
                    }

                    out.flush();
                }

                if (!hasValidDigest(
                    partial,
                    expectedMd5
                )) {
                    partial.delete();
                    throw new IOException(
                        "Hermes layer MD5 mismatch after download"
                    );
                }

                target.delete();

                if (!partial.renameTo(target)) {
                    throw new IOException(
                        "Unable to finalize Hermes layer archive"
                    );
                }

                return;
            } catch (IOException e) {
                if (attempt == 4) {
                    throw e;
                }
                if (progress != null) {
                    progress.onProgress("Download interrupted; retrying from saved bytes (attempt " +
                        (attempt + 1) + " of 4)", basePercent);
                }

                try {
                    Thread.sleep(
                        attempt * 1500L
                    );
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                        "Hermes layer download interrupted",
                        interrupted
                    );
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    private static boolean hasValidDigest(
        File file,
        String expected
    ) throws IOException {
        if (!file.isFile()) {
            return false;
        }

        return expected.equalsIgnoreCase(
            digestHex(file, "MD5")
        );
    }

    private static String digestHex(
        File file,
        String algorithm
    ) throws IOException {
        try {
            MessageDigest md =
                MessageDigest.getInstance(algorithm);

            try (
                InputStream in =
                    new FileInputStream(file)
            ) {
                byte[] buffer =
                    new byte[131072];

                int n;

                while ((n = in.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }

            StringBuilder out =
                new StringBuilder(
                    md.getDigestLength() * 2
                );

            for (byte b : md.digest()) {
                out.append(
                    String.format(
                        Locale.US,
                        "%02x",
                        b
                    )
                );
            }

            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(
                algorithm + " unavailable",
                e
            );
        }
    }

    private static int waitAndDrain(
        Process process,
        Progress progress,
        String label
    ) throws IOException {
        final StringBuilder tail =
            new StringBuilder(8192);

        Thread reader = new Thread(
            () -> {
                try (
                    BufferedReader in =
                        new BufferedReader(
                            new InputStreamReader(
                                process.getInputStream(),
                                StandardCharsets.UTF_8
                            )
                        )
                ) {
                    String line;

                    while ((line = in.readLine()) != null) {
                        synchronized (tail) {
                            if (tail.length() > 24576) {
                                tail.delete(
                                    0,
                                    tail.length() - 12288
                                );
                            }
                            tail.append(line).append('\n');
                        }

                        if (progress != null &&
                            !line.trim().isEmpty()) {
                            progress.onProgress(
                                label + ": " + line.trim(),
                                -1
                            );
                        }
                    }
                } catch (Exception ignored) {
                }
            },
            "caravel-hermes-runtime-check"
        );

        reader.start();

        try {
            int exit = process.waitFor();
            reader.join(5000);
            return exit;
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();

            throw new IOException(
                label + " interrupted",
                e
            );
        }
    }

    private static void copyFile(
        File source,
        File destination
    ) throws IOException {
        File parent =
            destination.getParentFile();

        if (parent != null) {
            mkdirs(parent);
        }

        try (
            InputStream in =
                new FileInputStream(source);
            FileOutputStream out =
                new FileOutputStream(destination)
        ) {
            copy(in, out);
        }
    }

    private static void copy(
        InputStream in,
        FileOutputStream out
    ) throws IOException {
        byte[] buffer =
            new byte[65536];

        int n;

        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }

        out.flush();
    }

    private static void writeText(
        File file,
        String text
    ) throws IOException {
        File parent =
            file.getParentFile();

        if (parent != null) {
            mkdirs(parent);
        }

        try (
            FileOutputStream out =
                new FileOutputStream(file)
        ) {
            out.write(
                text.getBytes(
                    StandardCharsets.UTF_8
                )
            );
            out.flush();
        }
    }

    private static String readText(
        File file
    ) throws IOException {
        long length = file.length();

        if (length > Integer.MAX_VALUE) {
            throw new IOException(
                "File is too large: " + file
            );
        }

        byte[] data =
            new byte[(int)length];

        try (
            FileInputStream in =
                new FileInputStream(file)
        ) {
            int offset = 0;

            while (offset < data.length) {
                int n =
                    in.read(
                        data,
                        offset,
                        data.length - offset
                    );

                if (n < 0) {
                    break;
                }

                offset += n;
            }
        }

        return new String(
            data,
            StandardCharsets.UTF_8
        );
    }

    private static String targetName(
        Layer layer
    ) {
        return layer.name()
            .substring(0, 1)
            .toUpperCase(Locale.US) +
            layer.name()
                .substring(1)
                .toLowerCase(Locale.US);
    }

    private static void deleteRecursive(
        File file
    ) throws IOException {
        if (file == null ||
            !file.exists()) {
            return;
        }

        if (file.isDirectory() &&
            !java.nio.file.Files.isSymbolicLink(
                file.toPath()
            )) {
            File[] children =
                file.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }

        if (!file.delete()) {
            throw new IOException(
                "Unable to delete " + file
            );
        }
    }

    private static void mkdirs(
        File dir
    ) throws IOException {
        if (dir == null ||
            dir.isDirectory()) {
            return;
        }

        if (!dir.mkdirs() &&
            !dir.isDirectory()) {
            throw new IOException(
                "Unable to create " + dir
            );
        }
    }

    public interface Progress {
        void onProgress(
            String message,
            int percent
        );
    }
}
