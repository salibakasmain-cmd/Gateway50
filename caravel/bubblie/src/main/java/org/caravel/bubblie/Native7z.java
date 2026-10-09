package org.caravel.bubblie;

import android.content.Context;

import java.io.File;
import java.io.IOException;

public final class Native7z {
    static {
        System.loadLibrary("bubblie_7z_jni");
    }

    private Native7z() {}

    public static void extract(
        Context context,
        File archive,
        File destination,
        String password
    ) throws IOException {
        if (context == null) {
            throw new IOException("Bubblie context is null");
        }

        if (archive == null || !archive.isFile()) {
            throw new IOException(
                "7-Zip archive does not exist: " +
                (archive == null ? "null" : archive)
            );
        }

        if (destination == null) {
            throw new IOException("7-Zip destination is null");
        }

        if (!destination.isDirectory() &&
            !destination.mkdirs() &&
            !destination.isDirectory()) {
            throw new IOException(
                "Unable to create 7-Zip destination: " +
                destination
            );
        }

        File lib7z = new File(
            context.getApplicationInfo().nativeLibraryDir,
            "lib7z.so"
        );

        if (!lib7z.isFile()) {
            throw new IOException(
                "Bubblie 7-Zip runtime library is missing: " +
                lib7z
            );
        }

        nativeExtract(
            archive.getAbsolutePath(),
            destination.getAbsolutePath(),
            password == null ? "" : password,
            lib7z.getAbsolutePath()
        );
    }

    private static native void nativeExtract(
        String archivePath,
        String destinationPath,
        String password,
        String sevenZipLibraryPath
    );
}
