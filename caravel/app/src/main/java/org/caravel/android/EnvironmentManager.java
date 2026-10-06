package org.caravel.android;

import android.content.Context;

import java.io.File;

public final class EnvironmentManager {
    public enum ComponentState { MISSING, READY }

    private final File root;
    private final File prefix;
    private final File ubuntu;
    private final File hermes;

    public EnvironmentManager(Context context) {
        root = context.getFilesDir();
        prefix = new File(root, "usr");
        ubuntu = new File(
            prefix,
            "var/lib/pr/installed-rootfs/ubuntu"
        );
        hermes = new File(
            context.getFilesDir(),
            "caravel/hermes/source"
        );
    }

    public File root() { return root; }
    public File prefix() { return prefix; }
    public File ubuntuRootfs() { return ubuntu; }

    public ComponentState ubuntuState() {
        return marker(
            ubuntu,
            ".ubuntu_installed"
        )
            ? ComponentState.READY
            : ComponentState.MISSING;
    }

    public ComponentState hermesState() {
        return marker(
            hermes,
            ".hermes_installed"
        )
            ? ComponentState.READY
            : ComponentState.MISSING;
    }

    public ComponentState nodeState() {
        return new File(
            root,
            "caravel/hermes/home/.caravel_pm_node_ready"
        ).isFile()
            ? ComponentState.READY
            : ComponentState.MISSING;
    }

    private static boolean marker(
        File dir,
        String name
    ) {
        return new File(dir, name).isFile();
    }
}
