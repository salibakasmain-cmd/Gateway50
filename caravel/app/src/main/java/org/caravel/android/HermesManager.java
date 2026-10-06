package org.caravel.android;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HermesManager {
    public static final String VERSION = "0.21.3";
    public static final String COMMIT =
        "345cd2b057a452236de401d3534b8502a7465e8d";

    public static final String SOURCE_URL =
        "https://codeload.github.com/NousResearch/hermes-agent/tar.gz/" +
        COMMIT;

    public static final String NODE_TARGET_MAJOR = "22";

    public static final int GATEWAY_PORT = 8642;
    public static final int DASHBOARD_PORT = 9119;

    private final Context context;
    private final UbuntuManager ubuntu;
    private final PrCliRuntime runtime;

    private final File root;
    private final File source;
    private final File web;
    private final File home;
    private final File archive;
    private final File partial;

    public HermesManager(Context context) {
        this.context = context.getApplicationContext();
        this.ubuntu = new UbuntuManager(this.context);
        this.runtime = new PrCliRuntime(this.context);

        this.root = new File(
            this.context.getFilesDir(),
            "caravel/hermes"
        );
        this.source = new File(root, "source");
        this.home = new File(root, "home");
        this.web = new File(home, "web_dist");

        this.archive = new File(
            root,
            "hermes-" + VERSION + ".tar.gz"
        );
        this.partial = new File(
            archive.getPath() + ".part"
        );
    }

    public boolean isInstalled() {
        return new File(
            source,
            ".hermes_installed"
        ).isFile()
            && new File(
                home,
                ".caravel_pm_node_ready"
            ).isFile()
            && new File(
                ubuntu.rootfs(),
                "usr/local/bin/hermes"
            ).isFile()
            && new File(
                ubuntu.rootfs(),
                "usr/local/lib/hermes-venv/bin/hermes"
            ).isFile();
    }

    public File sourceDir() {
        return source;
    }

    public File webDir() {
        return web;
    }

    public File homeDir() {
        return home;
    }

    public void install(Progress progress) throws IOException {
        if (!ubuntu.isInstalled()) {
            throw new IOException(
                "Ubuntu must be installed before Hermes"
            );
        }

        runtime.ensureLayout();
        root.mkdirs();
        home.mkdirs();
        source.getParentFile().mkdirs();

        prepareWebAssets(progress);
        ensureRuntimeFiles();

        if (isInstalled()) {
            if (progress != null) {
                progress.onProgress(
                    "Hermes Agent " + VERSION +
                    " already installed",
                    100
                );
            }
            return;
        }

        if (progress != null) {
            progress.onProgress(
                "Downloading Hermes Agent " + VERSION,
                0
            );
        }

        download(archive, progress);

        File staging = new File(
            root,
            "source.new"
        );
        deleteRecursive(staging);

        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException(
                "Unable to create Hermes staging directory"
            );
        }

        if (progress != null) {
            progress.onProgress(
                "Extracting Hermes source",
                35
            );
        }

        extractArchive(archive, staging);

        File pyproject = new File(
            staging,
            "pyproject.toml"
        );

        if (!pyproject.isFile()) {
            throw new IOException(
                "Hermes source archive is missing pyproject.toml"
            );
        }

        String metadata = readText(pyproject);

        if (!metadata.contains(
            "version = \"" + VERSION + "\""
        )) {
            throw new IOException(
                "Hermes source version mismatch"
            );
        }

        File old = new File(
            root,
            "source.old"
        );
        deleteRecursive(old);

        if (source.exists() &&
            !source.renameTo(old)) {
            throw new IOException(
                "Unable to stage existing Hermes source"
            );
        }

        boolean activated = false;

        try {
            if (!staging.renameTo(source)) {
                throw new IOException(
                    "Unable to activate Hermes source"
                );
            }

            activated = true;

            if (progress != null) {
                progress.onProgress(
                    "Installing Hermes in Ubuntu",
                    50
                );
            }

            installIntoUbuntu(progress);
            ensureRuntimeFiles();

            writeText(
                new File(
                    source,
                    ".hermes_installed"
                ),
                "Hermes Agent " + VERSION + "\n" +
                "commit=" + COMMIT + "\n"
            );

            deleteRecursive(old);

            archive.delete();
            partial.delete();

            if (progress != null) {
                progress.onProgress(
                    "Hermes Agent installed",
                    100
                );
            }
        } catch (IOException e) {
            if (activated) {
                try {
                    deleteRecursive(source);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }

            if (old.exists() &&
                !old.renameTo(source)) {
                e.addSuppressed(
                    new IOException(
                        "Unable to restore previous Hermes source"
                    )
                );
            }

            throw e;
        }
    }

    public void ensureRuntimeFiles() throws IOException {
        runtime.ensureLayout();

        if (!home.exists() && !home.mkdirs()) {
            throw new IOException(
                "Unable to create Hermes home"
            );
        }

        File env = new File(
            home,
            ".env"
        );

        String existing = env.isFile()
            ? readText(env).replace("\\n", "\n")
            : "";

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

        writeText(env, updated);
    }

    public Process startGateway() throws IOException {
        if (!isInstalled()) {
            throw new IOException(
                "Hermes Agent is not installed"
            );
        }

        ensureRuntimeFiles();

        String command =
            "export HERMES_HOME=/root/.hermes; " +
            "export PATH=/root/.hermes/node/bin:/usr/local/lib/hermes-agent/venv/bin:" +
            "/usr/local/bin:$PATH; " +
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

        String command =
            "export HERMES_HOME=/root/.hermes; " +
            "export PATH=/root/.hermes/node/bin:/usr/local/lib/hermes-venv/bin:" +
            "/usr/local/bin:$PATH; " +
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

    public String gatewayHealthUrl() {
        return "http://127.0.0.1:" +
            GATEWAY_PORT +
            "/health";
    }

    public String gatewayKey() throws IOException {
        ensureRuntimeFiles();

        File env = new File(
            home,
            ".env"
        );

        String content = readText(env).replace("\\n", "\n");

        for (String line : content.split("\\R")) {
            if (line.startsWith("API_SERVER_KEY=")) {
                return line.substring(
                    "API_SERVER_KEY=".length()
                ).trim();
            }
        }

        throw new IOException(
            "CARAVEL API server key is missing"
        );
    }

    private List<String> runtimeBinds() {
        List<String> binds = new ArrayList<>();

        binds.add(
            source.getAbsolutePath() +
            ":/usr/local/lib/hermes-agent"
        );
        binds.add(
            home.getAbsolutePath() +
            ":/root/.hermes"
        );

        return binds;
    }

    private static String findEnvValue(
        String content,
        String key
    ) {
        for (String line : content.split("\\R")) {
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
        String[] lines = content.split("\\R", -1);
        StringBuilder out = new StringBuilder();
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

            if (!line.isEmpty() || out.length() > 0) {
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

    private void installIntoUbuntu(
        Progress progress
    ) throws IOException {
        String command =
            "set -e; " +
            "export HERMES_HOME=/root/.hermes; " +
            "export HERMES_NODE_TARGET_MAJOR=" +
            NODE_TARGET_MAJOR +
            "; " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get update; " +
            "apt-get install -y " +
            "ca-certificates curl python3 python3-venv " +
            "python3-pip git ripgrep tar gzip bash; " +
            "mkdir -p /usr/local/lib/hermes-agent; " +
            "rm -rf /usr/local/lib/hermes-venv; " +
            "python3 -m venv /usr/local/lib/hermes-venv; " +
            "/usr/local/lib/hermes-venv/bin/python -m pip " +
            "install --no-cache-dir --upgrade pip; " +
            "/usr/local/lib/hermes-venv/bin/python -m pip " +
            "install --no-cache-dir " +
            "-e '/usr/local/lib/hermes-agent[web,pty]'; " +
            "ln -sf /usr/local/lib/hermes-venv/bin/hermes " +
            "/usr/local/bin/hermes; " +
            "test -x /usr/local/bin/hermes; " +
            "source /usr/local/lib/hermes-agent/scripts/lib/node-bootstrap.sh; " +
            "ensure_node; " +
            "test -x /root/.hermes/node/bin/node; " +
            "test -x /root/.hermes/node/bin/npm; " +
            "v=\"$(/root/.hermes/node/bin/node --version)\"; " +
            "case \"$v\" in v${HERMES_NODE_TARGET_MAJOR}.*) ;; " +
            "*) echo \"Unexpected Node version: $v\" >&2; exit 1 ;; esac; " +
            "echo \"CARAVEL_NODE_OK $v\"; " +
            "touch /root/.hermes/.caravel_pm_node_ready; " +
            "test -x /usr/local/bin/hermes; " +
            "/usr/local/lib/hermes-venv/bin/python --version; " +
            "/usr/local/bin/hermes --version";

        Process process = runtime.runInDistro(
            "ubuntu",
            command,
            runtimeBinds()
        );

        int exit = waitAndDrain(
            process,
            progress,
            "Hermes setup"
        );

        if (exit != 0) {
            throw new IOException(
                "Hermes dependency installation failed (exit " +
                exit +
                ")"
            );
        }

        if (progress != null) {
            progress.onProgress(
                "Hermes dependencies and managed Node installed",
                90
            );
        }
    }

    private void prepareWebAssets(
        Progress progress
    ) throws IOException {

        File marker = new File(
            web,
            ".caravel-web-" + COMMIT
        );

        if (marker.isFile() &&
            new File(web, "index.html").isFile()) {
            return;
        }

        deleteRecursive(web);

        if (!web.mkdirs() && !web.isDirectory()) {
            throw new IOException(
                "Unable to create Hermes web asset directory"
            );
        }

        if (progress != null) {
            progress.onProgress(
                "Installing Hermes dashboard assets",
                20
            );
        }

        copyAssetTree(
            context.getAssets(),
            "hermes-web",
            web
        );

        if (!new File(web, "index.html").isFile()) {
            throw new IOException(
                "Hermes dashboard assets are incomplete"
            );
        }

        writeText(
            marker,
            COMMIT + "\n"
        );
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
            !destination.mkdirs()) {

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

    private void extractArchive(
        File tarball,
        File destination
    ) throws IOException {
        /*
         * Android's native BusyBox tar is not reliable for this workload:
         * on some devices it is terminated by the Android seccomp policy.
         *
         * CARAVEL already bundles the native 7-Zip/bit7z extraction path
         * used successfully for Ubuntu. Use that same path for Hermes.
         *
         * GitHub codeload tarballs contain a single top-level directory
         * (hermes-agent-<commit>). Native7z extracts it faithfully, so
         * flatten that directory into the requested staging directory.
         */
        File unpacked = new File(
            destination.getParentFile(),
            destination.getName() + ".unpacked"
        );

        deleteRecursive(unpacked);

        if (!unpacked.mkdirs() && !unpacked.isDirectory()) {
            throw new IOException(
                "Unable to create Hermes extraction workspace"
            );
        }

        try {
            Native7z.extract(
                context,
                tarball,
                unpacked,
                ""
            );

            File[] children = unpacked.listFiles();

            if (children == null || children.length == 0) {
                throw new IOException(
                    "Hermes source archive extracted no files"
                );
            }

            File sourceRoot = null;

            if (children.length == 1 &&
                children[0].isDirectory() &&
                !java.nio.file.Files.isSymbolicLink(
                    children[0].toPath()
                )) {
                sourceRoot = children[0];
            }

            File[] entries =
                sourceRoot != null
                    ? sourceRoot.listFiles()
                    : children;

            if (entries == null || entries.length == 0) {
                throw new IOException(
                    "Hermes source archive has no source files"
                );
            }

            for (File entry : entries) {
                File target = new File(
                    destination,
                    entry.getName()
                );

                if (!entry.renameTo(target)) {
                    throw new IOException(
                        "Unable to activate extracted Hermes entry: " +
                        entry.getName()
                    );
                }
            }
        } finally {
            deleteRecursive(unpacked);
        }
    }

    private void download(
        File target,
        Progress progress
    ) throws IOException {

        partial.delete();

        HttpURLConnection conn =
            (HttpURLConnection)new URL(
                SOURCE_URL
            ).openConnection();

        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty(
            "User-Agent",
            "CARAVEL/" + VERSION
        );

        try {
            int code =
                conn.getResponseCode();

            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                    "Hermes source download failed: HTTP " +
                    code
                );
            }

            long total =
                conn.getContentLengthLong();

            try (
                InputStream in =
                    conn.getInputStream();
                FileOutputStream out =
                    new FileOutputStream(partial)
            ) {
                byte[] buffer =
                    new byte[131072];

                long done = 0;
                int n;

                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    done += n;

                    if (progress != null &&
                        total > 0) {

                        int pct =
                            (int)Math.min(
                                19,
                                (done * 19L) / total
                            );

                        progress.onProgress(
                            String.format(
                                Locale.US,
                                "Downloading Hermes %d%%",
                                (pct * 100) / 19
                            ),
                            pct
                        );
                    }
                }

                out.flush();
            }

            target.delete();

            if (!partial.renameTo(target)) {
                throw new IOException(
                    "Unable to finalize Hermes archive"
                );
            }
        } catch (IOException e) {
            partial.delete();
            throw e;
        } finally {
            conn.disconnect();
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
                            new java.io.InputStreamReader(
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
                                label + ": " +
                                line.trim(),
                                -1
                            );
                        }
                    }
                } catch (Exception ignored) {
                }
            },
            "caravel-process-output"
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

    private static void verifySha256(
        File file,
        String expected
    ) throws IOException {

        try {
            MessageDigest md =
                MessageDigest.getInstance(
                    "SHA-256"
                );

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

            StringBuilder actual =
                new StringBuilder(64);

            for (byte b : md.digest()) {
                actual.append(
                    String.format(
                        Locale.US,
                        "%02x",
                        b
                    )
                );
            }

            if (!expected.equals(
                actual.toString()
            )) {
                throw new IOException(
                    "SHA-256 mismatch"
                );
            }
        } catch (
            java.security.NoSuchAlgorithmException e
        ) {
            throw new IOException(
                "SHA-256 unavailable",
                e
            );
        }
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
