package org.caravel.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.StatFs;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

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

        // Android's SELinux policy blocks hard-link creation by an
        // untrusted application. Ubuntu Base contains hard-link entries,
        // so extract them as ordinary file copies instead. This keeps the
        // rootfs semantically usable while avoiding any privileged syscall.
        File canonicalDestination = destination.getCanonicalFile();
        java.util.ArrayList<String> pendingHardLinks =
            new java.util.ArrayList<>();
        java.util.ArrayList<String> pendingHardLinkTargets =
            new java.util.ArrayList<>();

        try (
            InputStream fileIn = new FileInputStream(tarball);
            InputStream gzipIn = new GzipCompressorInputStream(fileIn);
            TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)
        ) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[131072];

            while ((entry = tarIn.getNextTarEntry()) != null) {
                if (entry.isGlobalPaxHeader() ||
                    entry.isPaxHeader() ||
                    entry.isGNULongNameEntry() ||
                    entry.isGNULongLinkEntry()) {
                    continue;
                }

                File output = safeArchivePath(
                    canonicalDestination,
                    entry.getName()
                );

                if (entry.isDirectory()) {
                    mkdirs(output);
                    applyMode(output, entry.getMode());
                    continue;
                }

                File parent = output.getParentFile();
                mkdirs(parent);
                deleteIfPresent(output);

                if (entry.isSymbolicLink()) {
                    String link = entry.getLinkName();
                    if (link == null || link.isEmpty()) {
                        throw new IOException(
                            "Ubuntu archive contains an empty symbolic link: " +
                            entry.getName()
                        );
                    }
                    Os.symlink(link, output.getAbsolutePath());
                    continue;
                }

                if (entry.isLink()) {
                    File target = safeArchivePath(
                        canonicalDestination,
                        entry.getLinkName()
                    );
                    if (target.isFile()) {
                        copyFile(target, output, buffer);
                    } else {
                        pendingHardLinks.add(output.getAbsolutePath());
                        pendingHardLinkTargets.add(target.getAbsolutePath());
                    }
                    continue;
                }

                if (entry.isFile()) {
                    try (FileOutputStream out = new FileOutputStream(output)) {
                        int n;
                        while ((n = tarIn.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                        }
                    }
                    applyMode(output, entry.getMode());
                    continue;
                }

                if (entry.isFIFO() ||
                    entry.isCharacterDevice() ||
                    entry.isBlockDevice()) {
                    // Device/FIFO nodes cannot be created safely by the
                    // Android app. PRoot does not require them for the
                    // initial Ubuntu Base installation, so leave these
                    // entries absent.
                    continue;
                }

                throw new IOException(
                    "Unsupported Ubuntu archive entry: " + entry.getName()
                );
            }
        }

        boolean changed = true;
        while (!pendingHardLinks.isEmpty() && changed) {
            changed = false;
            for (int i = pendingHardLinks.size() - 1; i >= 0; i--) {
                File target = new File(pendingHardLinkTargets.get(i));
                if (target.isFile()) {
                    File output = new File(pendingHardLinks.get(i));
                    copyFile(target, output, new byte[131072]);
                    pendingHardLinks.remove(i);
                    pendingHardLinkTargets.remove(i);
                    changed = true;
                }
            }
        }

        if (!pendingHardLinks.isEmpty()) {
            throw new IOException(
                "Ubuntu archive contains unresolved hard links: " +
                pendingHardLinks.get(0)
            );
        }
    }

    private static File safeArchivePath(
        File root,
        String entryName
    ) throws IOException {
        if (entryName == null || entryName.isEmpty()) {
            throw new IOException("Ubuntu archive contains an empty path");
        }

        String normalized = entryName.replace('\\\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        java.nio.file.Path rootPath = root.toPath().toAbsolutePath().normalize();
        java.nio.file.Path result = rootPath.resolve(normalized).normalize();
        if (!result.startsWith(rootPath)) {
            throw new IOException(
                "Unsafe Ubuntu archive path: " + entryName
            );
        }
        return result.toFile();
    }

    private static void copyFile(
        File source,
        File destination,
        byte[] buffer
    ) throws IOException {
        File parent = destination.getParentFile();
        mkdirs(parent);
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        }
        destination.setLastModified(source.lastModified());
    }

    private static void applyMode(File file, int mode) {
        if (mode <= 0) {
            return;
        }
        try {
            Os.chmod(
                file.getAbsolutePath(),
                mode & 07777
            );
        } catch (Exception ignored) {
            // File content remains valid even if Android rejects a mode.
        }
    }

    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() ||
            java.nio.file.Files.isSymbolicLink(file.toPath())) {
            if (file.isDirectory() &&
                !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                deleteRecursive(file);
            } else if (!file.delete()) {
                throw new IOException("Unable to replace " + file);
            }
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

    private static String shellQuote(String value) {
        return "'" +
            value.replace("'", "'\\''") +
            "'";
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
