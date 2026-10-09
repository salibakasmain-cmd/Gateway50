package org.caravel.bubblie;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Whitelisted, versioned Hermes configuration backup. Secrets are intentionally excluded. */
public final class BackupManager {
    public static final String MIME = "application/gzip";
    public static final String DEFAULT_NAME = "hermes-config.tar.gz";
    private static final int MAX_ARCHIVE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private final Context context;

    public BackupManager(Context context) { this.context = context.getApplicationContext(); }

    public String backup(Uri destination) throws Exception {
        File config = configFile();
        JSONObject manifest = new JSONObject()
            .put("format", "bubblie-hermes-config")
            .put("schema", 1)
            .put("hermes_release", HermesManager.RELEASE_TAG)
            .put("secrets_included", false)
            .put("created_at", System.currentTimeMillis());
        try (OutputStream raw = requireOutput(destination);
             GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            writeEntry(gzip, "bubblie-backup.json", manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            if (config.isFile()) writeEntry(gzip, "hermes/config.yaml", Files.readAllBytes(config.toPath()));
            gzip.write(new byte[1024]);
            gzip.finish();
        }
        return config.isFile()
            ? "Configuration backed up. API keys were excluded for security."
            : "Backup created with metadata only. No Hermes configuration exists yet.";
    }

    public Preview inspect(Uri source) throws Exception {
        Map<String, byte[]> entries = readArchive(source);
        byte[] rawManifest = entries.get("bubblie-backup.json");
        if (rawManifest == null) throw new IOException("Not a Bubblie configuration backup");
        JSONObject manifest = new JSONObject(new String(rawManifest, StandardCharsets.UTF_8));
        if (!"bubblie-hermes-config".equals(manifest.optString("format")) || manifest.optInt("schema") != 1)
            throw new IOException("Unsupported backup schema");
        String release = manifest.optString("hermes_release", "unknown");
        return new Preview(release, entries.containsKey("hermes/config.yaml"), entries);
    }

    public String restore(Preview preview) throws Exception {
        byte[] config = preview.entries.get("hermes/config.yaml");
        if (config == null) return "Backup contains no Hermes configuration.";
        File target = configFile();
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Unable to create Hermes configuration directory");
        File safety = new File(parent, "config.before-restore.yaml");
        if (target.isFile()) copy(target, safety);
        File staged = new File(parent, "config.restore.tmp");
        try (FileOutputStream output = new FileOutputStream(staged)) { output.write(config); output.getFD().sync(); }
        if (target.exists() && !target.delete()) throw new IOException("Unable to replace existing configuration");
        if (!staged.renameTo(target)) {
            if (safety.isFile()) copy(safety, target);
            throw new IOException("Unable to finalize restored configuration");
        }
        return "Configuration restored. Re-enter the provider API key in Settings, then restart the agent.";
    }

    public static final class Preview {
        public final String hermesRelease;
        public final boolean hasConfig;
        private final Map<String, byte[]> entries;
        Preview(String release, boolean config, Map<String, byte[]> entries) {
            this.hermesRelease = release; this.hasConfig = config; this.entries = entries;
        }
    }

    private Map<String, byte[]> readArchive(Uri uri) throws Exception {
        Map<String, byte[]> result = new HashMap<>();
        try (InputStream source = requireInput(uri); LimitedInputStream limited = new LimitedInputStream(source, MAX_ARCHIVE_BYTES);
             GZIPInputStream input = new GZIPInputStream(limited)) {
            byte[] header = new byte[512];
            while (readFully(input, header, 0, 512) == 512) {
                if (allZero(header)) break;
                String name = string(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                int type = header[156] & 0xff;
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) throw new IOException("Unsafe archive path");
                if (!("bubblie-backup.json".equals(name) || "hermes/config.yaml".equals(name)))
                    throw new IOException("Unexpected backup member: " + name);
                if (type != 0 && type != '0') throw new IOException("Unsupported archive member type");
                if (size < 0 || size > MAX_ENTRY_BYTES) throw new IOException("Backup member is too large");
                byte[] data = new byte[(int)size];
                if (readFully(input, data, 0, data.length) != data.length) throw new IOException("Truncated backup member");
                result.put(name, data);
                long padding = (512 - (size % 512)) % 512;
                skipFully(input, padding);
            }
        }
        return result;
    }

    private File configFile() { return new File(context.getFilesDir(), "bubblie/hermes/home/config.yaml"); }
    private OutputStream requireOutput(Uri uri) throws IOException { OutputStream out = context.getContentResolver().openOutputStream(uri, "w"); if (out == null) throw new IOException("Destination is unavailable"); return out; }
    private InputStream requireInput(Uri uri) throws IOException { InputStream in = context.getContentResolver().openInputStream(uri); if (in == null) throw new IOException("Backup is unavailable"); return in; }
    private static void copy(File from, File to) throws IOException { try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) { byte[] b = new byte[32768]; int n; while ((n = in.read(b)) >= 0) out.write(b, 0, n); out.getFD().sync(); } }

    private static void writeEntry(OutputStream out, String name, byte[] data) throws IOException {
        byte[] h = new byte[512];
        put(h, 0, 100, name); put(h, 100, 8, "0000600\0"); put(h, 108, 8, "0000000\0"); put(h, 116, 8, "0000000\0");
        put(h, 124, 12, String.format(java.util.Locale.US, "%011o", data.length));
        put(h, 136, 12, String.format(java.util.Locale.US, "%011o", System.currentTimeMillis() / 1000));
        for (int i = 148; i < 156; i++) h[i] = 32; h[156] = '0'; put(h, 257, 6, "ustar\0"); put(h, 263, 2, "00");
        long sum = 0; for (byte value : h) sum += value & 0xff; put(h, 148, 8, String.format(java.util.Locale.US, "%06o\0 ", sum));
        out.write(h); out.write(data); int pad = (int)((512 - (data.length % 512)) % 512); if (pad > 0) out.write(new byte[pad]);
    }
    private static void put(byte[] target, int at, int len, String value) { byte[] data = value.getBytes(StandardCharsets.US_ASCII); System.arraycopy(data, 0, target, at, Math.min(data.length, len)); }
    private static String string(byte[] b, int at, int len) { int end = at; while (end < at + len && b[end] != 0) end++; return new String(b, at, end - at, StandardCharsets.US_ASCII); }
    private static long parseOctal(byte[] b, int at, int len) { String s = string(b, at, len).trim(); return s.isEmpty() ? 0 : Long.parseLong(s, 8); }
    private static boolean allZero(byte[] b) { for (byte value : b) if (value != 0) return false; return true; }
    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException { int done = 0; while (done < len) { int n = in.read(b, off + done, len - done); if (n < 0) break; done += n; } return done; }
    private static void skipFully(InputStream in, long count) throws IOException { while (count > 0) { long n = in.skip(count); if (n <= 0) { if (in.read() < 0) throw new IOException("Truncated archive padding"); n = 1; } count -= n; } }

    private static final class LimitedInputStream extends InputStream implements AutoCloseable {
        private final InputStream source; private long left;
        LimitedInputStream(InputStream source, long max) { this.source = source; this.left = max; }
        @Override public int read() throws IOException { if (left <= 0) throw new IOException("Backup archive exceeds size limit"); int v = source.read(); if (v >= 0) left--; return v; }
        @Override public int read(byte[] b, int o, int l) throws IOException { if (left <= 0) throw new IOException("Backup archive exceeds size limit"); int n = source.read(b, o, (int)Math.min(l, left)); if (n > 0) left -= n; return n; }
        @Override public long skip(long n) throws IOException { long v = source.skip(Math.min(n, left)); left -= v; return v; }
        @Override public void close() throws IOException { source.close(); }
    }
}
