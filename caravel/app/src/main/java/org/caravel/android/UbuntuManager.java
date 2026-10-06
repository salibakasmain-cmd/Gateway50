package org.caravel.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class UbuntuManager {
    public static final String VERSION = "24.04.5";
    private static final long MIN_FREE_BYTES = 2L * 1024L * 1024L * 1024L;

    public static final String URL =
        "https://cdimages.ubuntu.com/ubuntu-base/releases/24.04/release/" +
        "ubuntu-base-24.04.5-base-arm64.tar.gz";

    public static final String SHA256 =
        "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2";

    private final Context context;
    private final File root;
    private final File prefix;
    private final File rootfs;
    private final File cache;
    private final File archive;
    private final File partial;

    public UbuntuManager(Context context) {
        this.context = context.getApplicationContext();

        this.root =
            this.context.getFilesDir();

        this.prefix =
            new File(this.context.getFilesDir(), "usr");

        this.rootfs =
            new File(
                prefix,
                "var/lib/pr/installed-rootfs/ubuntu"
            );

        this.cache =
            new File(
                prefix,
                "var/lib/pr/dlcache"
            );
        this.archive = new File(
            cache,
            "ubuntu-base-24.04.5-base-arm64.tar.gz"
        );
        this.partial = new File(archive.getPath() + ".part");
    }

    public File rootfs() {
        return rootfs;
    }

    public File prefixDir() {
        return prefix;
    }

    public boolean isInstalled() {
        return new File(rootfs, ".ubuntu_installed").isFile();
    }

    public void install(Progress progress) throws IOException {
        createDirectories();

        if (isInstalled()) {
            if (progress != null) {
                progress.onProgress("Ubuntu Base 24.04.5 already installed", 100);
            }
            return;
        }

        checkStorage();

        if (progress != null) {
            progress.onProgress("Preparing Ubuntu Base 24.04.5", 0);
        }

        if (!archive.isFile()) {
            download(archive, progress);
        } else if (progress != null) {
            progress.onProgress("Using cached Ubuntu archive", 35);
        }

        try {
            if (progress != null) {
                progress.onProgress("Verifying Ubuntu archive", 45);
            }
            verifySha256(archive, SHA256);
        } catch (IOException e) {
            archive.delete();
            throw e;
        }

        File staging = new File(
            prefix,
            "var/lib/pr/installed-rootfs/ubuntu.new"
        );
        deleteRecursive(staging);

        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Unable to create staging rootfs");
        }

        if (progress != null) {
            progress.onProgress("Extracting Ubuntu rootfs", 50);
        }

        extractWithBusybox(archive, staging);
        prepareRootfs(staging);

        File old = new File(
            prefix,
            "var/lib/pr/installed-rootfs/ubuntu.old"
        );
        deleteRecursive(old);

        if (rootfs.exists() && !rootfs.renameTo(old)) {
            throw new IOException("Unable to stage existing Ubuntu rootfs");
        }

        if (!staging.renameTo(rootfs)) {
            if (old.exists()) {
                old.renameTo(rootfs);
            }
            throw new IOException("Unable to activate Ubuntu rootfs");
        }

        try {
            String verification = verifyInstalledRootfs();

            writeText(
                new File(rootfs, ".ubuntu_installed"),
                "Ubuntu Base " +
                VERSION +
                " arm64\n" +
                verification +
                "\n"
            );

            deleteRecursive(old);
            archive.delete();
            partial.delete();

            if (progress != null) {
                progress.onProgress(
                    "Ubuntu Base " +
                    VERSION +
                    " verified",
                    100
                );
            }
        } catch (IOException e) {
            deleteRecursive(rootfs);

            if (old.exists() &&
                !old.renameTo(rootfs)) {
                throw new IOException(
                    "Ubuntu verification failed and previous rootfs could not be restored",
                    e
                );
            }

            throw e;
        }
    }

    private void checkStorage() throws IOException {
        StatFs stat =
            new StatFs(
                context.getFilesDir().getAbsolutePath()
            );

        long available =
            stat.getAvailableBlocksLong() *
            stat.getBlockSizeLong();

        if (available < MIN_FREE_BYTES) {
            throw new IOException(
                "Not enough free storage. CARAVEL needs at least " +
                (MIN_FREE_BYTES / (1024L * 1024L)) +
                " MiB before installing Ubuntu"
            );
        }
    }

    private String verifyInstalledRootfs()
        throws IOException {

        PrCliRuntime runtime =
            new PrCliRuntime(context);

        Process process =
            runtime.runInDistro(
                "ubuntu",
                "set -eu; " +
                "test -f /etc/passwd; " +
                "test -x /bin/sh; " +
                "test -x /bin/bash; " +
                "test -d /usr; " +
                "printf 'CARAVEL_UBUNTU_OK '; " +
                "uname -m"
            );

        StringBuilder output =
            new StringBuilder();

        try (
            InputStream in =
                process.getInputStream()
        ) {
            byte[] buffer =
                new byte[4096];
            int n;

            while ((n = in.read(buffer)) != -1) {
                if (output.length() < 4096) {
                    output.append(
                        new String(
                            buffer,
                            0,
                            Math.min(
                                n,
                                4096 - output.length()
                            ),
                            StandardCharsets.UTF_8
                        )
                    );
                }
            }
        }

        try {
            int exit = process.waitFor();

            if (exit != 0) {
                throw new IOException(
                    "Ubuntu PRoot self-test failed (exit " +
                    exit +
                    "): " +
                    output.toString().trim()
                );
            }
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException(
                "Ubuntu PRoot self-test interrupted",
                e
            );
        }

        String result =
            output.toString().trim();

        if (!result.contains(
            "CARAVEL_UBUNTU_OK"
        )) {
            throw new IOException(
                "Ubuntu PRoot self-test returned unexpected output: " +
                result
            );
        }

        return result;
    }

    private void createDirectories() throws IOException {
        mkdirs(root);
        mkdirs(prefix);
        mkdirs(cache);
        mkdirs(new File(prefix, "var/lib/pr"));
        mkdirs(rootfs.getParentFile());
    }

    private void download(File target, Progress progress) throws IOException {
        partial.delete();

        HttpURLConnection conn =
            (HttpURLConnection)new URL(URL).openConnection();

        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CARAVEL/0.3");

        try {
            int code = conn.getResponseCode();

            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException(
                    "Ubuntu download failed: HTTP " + code
                );
            }

            long total = conn.getContentLengthLong();

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(partial)) {

                byte[] buffer = new byte[131072];
                long done = 0;
                int n;

                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    done += n;

                    if (progress != null && total > 0) {
                        int pct = (int)Math.min(34, (done * 34L) / total);
                        progress.onProgress(
                            String.format(
                                Locale.US,
                                "Downloading Ubuntu %d%%",
                                (pct * 100) / 34
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
                    "Unable to finalize Ubuntu archive"
                );
            }
        } catch (IOException e) {
            partial.delete();
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    private void verifySha256(File file, String expected)
        throws IOException {

        try {
            MessageDigest md =
                MessageDigest.getInstance("SHA-256");

            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[131072];
                int n;

                while ((n = in.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }

            StringBuilder actual = new StringBuilder(64);

            for (byte b : md.digest()) {
                actual.append(
                    String.format(Locale.US, "%02x", b)
                );
            }

            if (!expected.equals(actual.toString())) {
                throw new IOException("Ubuntu SHA-256 mismatch");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private void extractWithBusybox(
        File tarball,
        File destination
    ) throws IOException {

        ProcessBuilder pb = new ProcessBuilder(
            new File(
                context.getApplicationInfo().nativeLibraryDir,
                "libbusybox.so"
            ).getAbsolutePath(),
            "tar",
            "-xzf",
            tarball.getAbsolutePath(),
            "-C",
            destination.getAbsolutePath()
        );
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            String output = readProcessOutput(process);
            int exit = process.waitFor();

            if (exit != 0) {
                throw new IOException(
                    "Ubuntu extraction failed (exit " + exit + ")" +
                    (output.isEmpty() ? "" : ": " + tail(output))
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(
                "Ubuntu extraction interrupted",
                e
            );
        }
    }

    private void prepareRootfs(File dir) throws IOException {
        File etc = new File(dir, "etc");
        mkdirs(etc);

        File resolv = new File(etc, "resolv.conf");

        if (resolv.exists() ||
            java.nio.file.Files.isSymbolicLink(resolv.toPath())) {
            java.nio.file.Files.deleteIfExists(resolv.toPath());
        }

        writeText(resolv, buildResolvConf());

        File environment = new File(etc, "environment");

        if (!environment.isFile()) {
            writeText(environment, "");
        }

        File tmp = new File(dir, "tmp");
        mkdirs(tmp);

        File varTmp = new File(dir, "var/tmp");
        mkdirs(varTmp);

        File l2s = new File(dir, ".l2s");
        mkdirs(l2s);

        File aptConfigDir = new File(dir, "etc/apt/apt.conf.d");
        mkdirs(aptConfigDir);

        File aptSandbox = new File(
            aptConfigDir,
            "99-caravel-rootless"
        );
        writeText(
            aptSandbox,
            "APT::Sandbox::User \"root\";\n"
        );
    }

    private String buildResolvConf() {
        StringBuilder out = new StringBuilder();

        try {
            ConnectivityManager cm =
                (ConnectivityManager)
                    context.getSystemService(
                        Context.CONNECTIVITY_SERVICE
                    );

            Network network =
                cm != null
                    ? cm.getActiveNetwork()
                    : null;

            LinkProperties lp =
                cm != null && network != null
                    ? cm.getLinkProperties(network)
                    : null;

            if (lp != null) {
                for (java.net.InetAddress dns :
                    lp.getDnsServers()) {
                    if (dns != null &&
                        !dns.getHostAddress().isEmpty()) {
                        out.append("nameserver ")
                            .append(dns.getHostAddress())
                            .append('\n');
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back below; the rootfs must still get a resolver file.
        }

        if (out.length() == 0) {
            out.append("nameserver 1.1.1.1\n")
                .append("nameserver 8.8.8.8\n");
        }

        return out.toString();
    }

    private static String readProcessOutput(Process process)
        throws IOException {

        StringBuilder output = new StringBuilder();

        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int n;

            while ((n = in.read(buffer)) != -1) {
                if (output.length() < 32768) {
                    output.append(
                        new String(
                            buffer,
                            0,
                            Math.min(n, 32768 - output.length()),
                            StandardCharsets.UTF_8
                        )
                    );
                }
            }
        }

        return output.toString().trim();
    }

    private static String tail(String text) {
        int max = 1000;
        return text.length() <= max
            ? text
            : text.substring(text.length() - max);
    }

    private static void writeText(
        File file,
        String text
    ) throws IOException {

        File parent = file.getParentFile();

        if (parent != null) {
            mkdirs(parent);
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir == null || dir.isDirectory()) {
            return;
        }

        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException(
                "Unable to create " + dir
            );
        }
    }

    private static void deleteRecursive(File file)
        throws IOException {

        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory() &&
            !java.nio.file.Files.isSymbolicLink(file.toPath())) {

            File[] children = file.listFiles();

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

    public interface Progress {
        void onProgress(String message, int percent);
    }
}
