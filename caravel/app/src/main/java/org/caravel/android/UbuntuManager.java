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
    public static final String VERSION = "24.04.3";

    private static final long MIN_FREE_BYTES =
        2L * 1024L * 1024L * 1024L;

    public static final String URL =
        "https://github.com/goldenduo/XermesRelease/releases/download/" +
        "v2026.09.02-model-startup/ubuntu.7z";

    public static final String SHA256 =
        "01049b0d56fb5e8d8fc8483756bf43144942ab50a00978c7f531e6f9ec1462a8";

    private final Context context;
    private final File root;
    private final File prefix;
    private final File rootfs;
    private final File cache;
    private final File archive;
    private final File partial;

    public UbuntuManager(Context context) {
        this.context = context.getApplicationContext();

        this.root = this.context.getFilesDir();
        this.prefix = new File(
            this.context.getFilesDir(),
            "usr"
        );

        this.rootfs = new File(
            prefix,
            "var/lib/pr/installed-rootfs/ubuntu"
        );

        this.cache = new File(
            prefix,
            "var/lib/pr/dlcache"
        );

        this.archive = new File(
            cache,
            "ubuntu.7z"
        );

        this.partial = new File(
            cache,
            "ubuntu.7z.part"
        );
    }

    public File rootfs() {
        return rootfs;
    }

    public File prefixDir() {
        return prefix;
    }

    public boolean isInstalled() {
        return new File(
            rootfs,
            ".ubuntu_installed"
        ).isFile();
    }

    public void install(
        Progress progress
    ) throws IOException {
        createDirectories();

        if (isInstalled()) {
            if (progress != null) {
                progress.onProgress(
                    "Ubuntu Base 24.04.3 already installed",
                    100
                );
            }
            return;
        }

        checkStorage();

        if (progress != null) {
            progress.onProgress(
                "Preparing Ubuntu Base 24.04.3",
                0
            );
        }

        if (!archive.isFile()) {
            download(
                archive,
                progress
            );
        } else if (progress != null) {
            progress.onProgress(
                "Using cached Ubuntu 7-Zip archive",
                35
            );
        }

        try {
            if (progress != null) {
                progress.onProgress(
                    "Verifying Ubuntu 7-Zip archive",
                    45
                );
            }

            verifySha256(
                archive,
                SHA256
            );
        } catch (IOException e) {
            archive.delete();
            throw e;
        }

        File staging = new File(
            prefix,
            "var/lib/pr/installed-rootfs/ubuntu.new"
        );

        deleteRecursive(staging);

        if (!staging.mkdirs() &&
            !staging.isDirectory()) {
            throw new IOException(
                "Unable to create staging rootfs"
            );
        }

        if (progress != null) {
            progress.onProgress(
                "Extracting Ubuntu rootfs with native 7-Zip",
                50
            );
        }

        Native7z.extract(
            context,
            archive,
            staging,
            ""
        );

        if (progress != null) {
            progress.onProgress(
                "Preparing Ubuntu rootfs",
                85
            );
        }

        prepareRootfs(staging);

        File old = new File(
            prefix,
            "var/lib/pr/installed-rootfs/ubuntu.old"
        );

        deleteRecursive(old);

        if (rootfs.exists() &&
            !rootfs.renameTo(old)) {
            throw new IOException(
                "Unable to stage existing Ubuntu rootfs"
            );
        }

        if (!staging.renameTo(rootfs)) {
            if (old.exists()) {
                old.renameTo(rootfs);
            }

            throw new IOException(
                "Unable to activate Ubuntu rootfs"
            );
        }

        try {
            String verification =
                verifyInstalledRootfs();

            writeText(
                new File(
                    rootfs,
                    ".ubuntu_installed"
                ),
                "Ubuntu Base " +
                VERSION +
                " arm64
" +
                verification +
                "
"
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

    private void checkStorage()
        throws IOException {
        StatFs stat = new StatFs(
            context.getFilesDir()
                .getAbsolutePath()
        );

        long available =
            stat.getAvailableBlocksLong() *
            stat.getBlockSizeLong();

        if (available <
            MIN_FREE_BYTES) {
            throw new IOException(
                "Not enough free storage. CARAVEL needs at least " +
                (MIN_FREE_BYTES /
                    (1024L * 1024L)) +
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
                if (output.length() <
                    4096) {
                    output.append(
                        new String(
                            buffer,
                            0,
                            Math.min(
                                n,
                                4096 -
                                    output.length()
                            ),
                            StandardCharsets.UTF_8
                        )
                    );
                }
            }
        }

        try {
            int exit =
                process.waitFor();

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

    private void createDirectories()
        throws IOException {
        mkdirs(root);
        mkdirs(prefix);
        mkdirs(cache);
        mkdirs(
            new File(
                prefix,
                "var/lib/pr"
            )
        );
        mkdirs(rootfs.getParentFile());
    }

    private void download(
        File target,
        Progress progress
    ) throws IOException {
        partial.delete();

        HttpURLConnection conn =
            (HttpURLConnection)new URL(URL)
                .openConnection();

        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty(
            "User-Agent",
            "CARAVEL/0.4.1"
        );

        try {
            int code =
                conn.getResponseCode();

            if (code !=
                HttpURLConnection.HTTP_OK) {
                throw new IOException(
                    "Ubuntu download failed: HTTP " +
                    code
                );
            }

            long total =
                conn.getContentLengthLong();

            try (
                InputStream in =
                    conn.getInputStream();
                FileOutputStream out =
                    new FileOutputStream(
                        partial
                    )
            ) {
                byte[] buffer =
                    new byte[131072];
                long done = 0;
                int n;

                while ((n = in.read(buffer)) != -1) {
                    out.write(
                        buffer,
                        0,
                        n
                    );

                    done += n;

                    if (progress != null &&
                        total > 0) {
                        int pct =
                            (int)Math.min(
                                34,
                                (done * 34L) /
                                    total
                            );

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

    private void verifySha256(
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
                    md.update(
                        buffer,
                        0,
                        n
                    );
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
                    "Ubuntu SHA-256 mismatch"
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

    private void prepareRootfs(
        File dir
    ) throws IOException {
        File etc =
            new File(dir, "etc");
        mkdirs(etc);

        File resolv =
            new File(
                etc,
                "resolv.conf"
            );

        if (resolv.exists() ||
            java.nio.file.Files.isSymbolicLink(
                resolv.toPath()
            )) {
            java.nio.file.Files.deleteIfExists(
                resolv.toPath()
            );
        }

        writeText(
            resolv,
            buildResolvConf()
        );

        File environment =
            new File(
                etc,
                "environment"
            );

        if (!environment.isFile()) {
            writeText(
                environment,
                ""
            );
        }

        mkdirs(new File(dir, "tmp"));
        mkdirs(new File(dir, "var/tmp"));

        File l2s =
            new File(dir, ".l2s");
        mkdirs(l2s);

        File aptConfigDir =
            new File(
                dir,
                "etc/apt/apt.conf.d"
            );
        mkdirs(aptConfigDir);

        File aptSandbox =
            new File(
                aptConfigDir,
                "99-caravel-rootless"
            );

        writeText(
            aptSandbox,
            "APT::Sandbox::User \"root\";\n"
        );
    }

    private String buildResolvConf() {
        StringBuilder out =
            new StringBuilder();

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
                cm != null &&
                network != null
                    ? cm.getLinkProperties(
                        network
                    )
                    : null;

            if (lp != null) {
                for (
                    java.net.InetAddress dns :
                    lp.getDnsServers()
                ) {
                    if (dns != null &&
                        !dns.getHostAddress().isEmpty()) {
                        out.append(
                            "nameserver "
                        )
                        .append(
                            dns.getHostAddress()
                        )
                        .append('\n');
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (out.length() == 0) {
            out.append(
                "nameserver 1.1.1.1\n"
            )
            .append(
                "nameserver 8.8.8.8\n"
            );
        }

        return out.toString();
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
                "Unable to create " +
                dir
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
                "Unable to delete " +
                file
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
