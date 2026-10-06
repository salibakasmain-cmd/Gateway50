package org.caravel.android;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PrCliRuntime {
    private final Context context;
    private final File prefixDir;
    private final File homeDir;
    private final File cacheDir;
    private final File nativeDir;
    private final File prCli;

    public PrCliRuntime(Context context) {
        this.context = context.getApplicationContext();

        File filesDir =
            this.context.getFilesDir();

        prefixDir =
            new File(filesDir, "usr");

        homeDir =
            new File(filesDir, "home");
        cacheDir = this.context.getCacheDir();
        nativeDir = new File(
            this.context.getApplicationInfo().nativeLibraryDir
        );
        prCli = new File(
            prefixDir,
            "bin/pr-cli"
        );
    }

    public File prefixDir() {
        return prefixDir;
    }

    public File homeDir() {
        return homeDir;
    }

    public File cacheDir() {
        return cacheDir;
    }

    public File nativeDir() {
        return nativeDir;
    }

    public void ensureLayout() throws IOException {
        createDir(prefixDir);
        createDir(new File(prefixDir, "bin"));
        createDir(homeDir);

        createDir(
            new File(
                prefixDir,
                "var/lib/pr/installed-rootfs"
            )
        );

        createDir(
            new File(
                prefixDir,
                "var/lib/pr/dlcache"
            )
        );

        ensureNativeSymlink(
            "pr-cli",
            "libpr-cli.so"
        );
        ensureNativeSymlink(
            "proot",
            "libproot.so"
        );
        ensureNativeSymlink(
            "busybox",
            "libbusybox.so"
        );
    }

    public Process runInDistro(
        String distro,
        String command
    ) throws IOException {
        return runInDistro(
            distro,
            command,
            null
        );
    }

    public Process runInDistro(
        String distro,
        String command,
        List<String> customBinds
    ) throws IOException {
        ensureLayout();

        if (!prCli.exists()) {
            throw new IOException(
                "CARAVEL pr-cli runtime is not available"
            );
        }

        if (distro == null ||
            distro.trim().isEmpty()) {
            throw new IOException(
                "Distribution name is missing"
            );
        }

        if (command == null ||
            command.trim().isEmpty()) {
            throw new IOException(
                "Guest command is empty"
            );
        }

        File rootfs = new File(
            prefixDir,
            "var/lib/pr/installed-rootfs/" +
            distro
        );

        if (!rootfs.isDirectory()) {
            throw new IOException(
                "Ubuntu rootfs is not installed: " +
                rootfs
            );
        }

        List<String> args = new ArrayList<>();
        args.add(prCli.getAbsolutePath());
        args.add("login");
        args.add(distro);
        args.add("--user");
        args.add("root");

        if (customBinds != null) {
            for (String bind : customBinds) {
                if (bind == null ||
                    bind.isEmpty()) {
                    continue;
                }

                int colon =
                    bind.indexOf(':');

                if (colon <= 0 ||
                    colon == bind.length() - 1) {
                    throw new IOException(
                        "Invalid custom bind: " + bind
                    );
                }

                args.add("--custom-bind");
                args.add(bind);
            }
        }

        args.add("--");
        args.add(command);

        File workspace = new File(
            context.getFilesDir(),
            "caravel/workspace"
        );
        createDir(workspace);

        ProcessBuilder pb =
            new ProcessBuilder(args);

        pb.directory(workspace);
        pb.redirectErrorStream(true);

        Map<String, String> env =
            pb.environment();

        env.put(
            "APP_PREFIX",
            prefixDir.getAbsolutePath()
        );
        env.put(
            "APP_HOME",
            homeDir.getAbsolutePath()
        );
        env.put(
            "APP_PACKAGE",
            context.getPackageName()
        );
        env.put(
            "PATH",
            prefixDir.getAbsolutePath() +
            "/bin:/system/bin:/system/xbin"
        );
        env.put(
            "PROOT_NO_SECCOMP",
            "1"
        );
        env.put(
            "PROOT_TMP_DIR",
            cacheDir.getAbsolutePath()
        );
        env.put(
            "TMPDIR",
            cacheDir.getAbsolutePath()
        );
        env.put(
            "PROOT_DISTRO_KERNEL_RELEASE",
            "6.17.0-android"
        );
        env.put(
            "TERM",
            "xterm-256color"
        );
        env.put(
            "LANG",
            "en_US.UTF-8"
        );
        env.put(
            "HOME",
            homeDir.getAbsolutePath()
        );

        return pb.start();
    }

    private void ensureNativeSymlink(
        String name,
        String library
    ) throws IOException {
        File link = new File(
            prefixDir,
            "bin/" + name
        );
        File target = new File(
            nativeDir,
            library
        );

        if (!target.isFile()) {
            throw new IOException(
                library +
                " is missing from " +
                nativeDir
            );
        }

        try {
            if (java.nio.file.Files.isSymbolicLink(
                link.toPath()
            )) {
                if (link.getCanonicalPath()
                    .equals(target.getCanonicalPath())) {
                    return;
                }
                java.nio.file.Files.deleteIfExists(
                    link.toPath()
                );
            } else if (link.exists()) {
                java.nio.file.Files.deleteIfExists(
                    link.toPath()
                );
            }

            Os.symlink(
                target.getAbsolutePath(),
                link.getAbsolutePath()
            );
        } catch (Exception e) {
            throw new IOException(
                "Unable to link " +
                name +
                " to " +
                library,
                e
            );
        }
    }

    private static void createDir(File dir)
        throws IOException {
        if (dir.isDirectory()) {
            return;
        }

        if (!dir.mkdirs() &&
            !dir.isDirectory()) {
            throw new IOException(
                "Unable to create " + dir
            );
        }
    }
}
