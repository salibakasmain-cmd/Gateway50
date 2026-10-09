package org.caravel.bubblie;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ProviderStore {
    private static final String PREFS = "bubblie_provider";
    private static final String KEY_NAME = "bubblie_provider_aes_v1";
    private static final String FIELD_PROVIDER = "provider";
    private static final String FIELD_MODEL = "model";
    private static final String FIELD_BASE_URL = "base_url";
    private static final String FIELD_API_MODE = "api_mode";
    private static final String FIELD_KEY_CIPHER = "api_key_ciphertext";
    private static final String FIELD_KEY_IV = "api_key_iv";
    private static final int GCM_TAG_BITS = 128;

    private final Context context;
    private final SharedPreferences prefs;

    private ProviderStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static ProviderStore getInstance(Context context) {
        return new ProviderStore(context);
    }

    public synchronized boolean isConfigured() {
        return nonEmpty(FIELD_PROVIDER) &&
            nonEmpty(FIELD_MODEL) &&
            nonEmpty(FIELD_BASE_URL) &&
            nonEmpty(FIELD_API_MODE) &&
            prefs.contains(FIELD_KEY_CIPHER) &&
            prefs.contains(FIELD_KEY_IV);
    }

    public synchronized void saveCustomProvider(
        String model, String baseUrl, String apiKey, String apiMode
    ) throws Exception {
        String cleanModel = require(model, "model");
        String cleanBaseUrl = normalizeBaseUrl(require(baseUrl, "base URL"));
        String cleanKey;
        if ((apiKey == null || apiKey.trim().isEmpty()) && isConfigured()) {
            cleanKey = decryptKey();
        } else {
            cleanKey = require(apiKey, "API key");
        }
        String cleanMode = normalizeApiMode(apiMode);
        byte[][] sealed = encrypt(cleanKey);

        prefs.edit()
            .putString(FIELD_PROVIDER, "custom")
            .putString(FIELD_MODEL, cleanModel)
            .putString(FIELD_BASE_URL, cleanBaseUrl)
            .putString(FIELD_API_MODE, cleanMode)
            .putString(FIELD_KEY_CIPHER, java.util.Base64.getEncoder().encodeToString(sealed[0]))
            .putString(FIELD_KEY_IV, java.util.Base64.getEncoder().encodeToString(sealed[1]))
            .apply();

        persistHermesFiles();
    }

    public synchronized void persistHermesFiles() throws Exception {
        if (!isConfigured()) return;

        String provider = prefs.getString(FIELD_PROVIDER, "custom");
        String model = prefs.getString(FIELD_MODEL, "");
        String baseUrl = prefs.getString(FIELD_BASE_URL, "");
        String apiMode = prefs.getString(FIELD_API_MODE, "chat_completions");
        String apiKey = decryptKey();

        File home = new File(context.getFilesDir(), "bubblie/hermes/home");
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IllegalStateException("Unable to create Hermes home");
        }

        writeFile(new File(home, "config.yaml"),
            "model:\n" +
            "  provider: " + yamlQuote(provider) + "\n" +
            "  default: " + yamlQuote(model) + "\n" +
            "  base_url: " + yamlQuote(baseUrl) + "\n" +
            "  api_key: \"${Bubblie_PROVIDER_API_KEY}\"\n" +
            "  api_mode: " + yamlQuote(apiMode) + "\n");

        File env = new File(home, ".env");
        String existing = env.isFile()
            ? new String(java.nio.file.Files.readAllBytes(env.toPath()), StandardCharsets.UTF_8)
            : "";
        writeFile(env, upsert(existing, "Bubblie_PROVIDER_API_KEY", apiKey));
    }

    public synchronized String model() {
        return prefs.getString(FIELD_MODEL, "");
    }

    public synchronized String baseUrl() {
        return prefs.getString(FIELD_BASE_URL, "");
    }

    public synchronized String apiMode() {
        return prefs.getString(FIELD_API_MODE, "chat_completions");
    }

    /** Authenticated provider probe. The decrypted key never leaves this method except on TLS. */
    public synchronized String testConnection() throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Provider is not configured");
        String root = baseUrl();
        String endpoint = root.endsWith("/v1") ? root + "/models" : root + "/models";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Authorization", "Bearer " + decryptKey());
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) return "Provider authenticated (HTTP " + code + ")";
            if (code == 401 || code == 403) throw new IllegalStateException("Provider rejected the API key (HTTP " + code + ")");
            throw new IllegalStateException("Provider test failed (HTTP " + code + ")");
        } finally {
            connection.disconnect();
        }
    }

    public synchronized void clear() {
        prefs.edit().clear().apply();
        File home = new File(
            context.getFilesDir(),
            "bubblie/hermes/home"
        );

        File config = new File(home, "config.yaml");
        if (config.isFile()) config.delete();

        File env = new File(home, ".env");
        if (env.isFile()) env.delete();
    }

    private synchronized String decryptKey() throws Exception {
        String cipherText = prefs.getString(FIELD_KEY_CIPHER, null);
        String ivText = prefs.getString(FIELD_KEY_IV, null);
        if (cipherText == null || ivText == null) {
            throw new IllegalStateException("Provider API key is not configured");
        }
        byte[] cipherBytes = java.util.Base64.getDecoder().decode(cipherText);
        byte[] iv = java.util.Base64.getDecoder().decode(ivText);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
    }

    private byte[][] encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        return new byte[][] {
            cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)),
            cipher.getIV()
        };
    }

    private SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_NAME)) {
            KeyStore.Entry entry = store.getEntry(KEY_NAME, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
            KEY_NAME,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }

    private static String normalizeApiMode(String value) {
        String mode = value == null ? "" : value.trim();
        if (mode.isEmpty()) return "chat_completions";
        if (!"chat_completions".equals(mode) && !"codex_responses".equals(mode)) {
            throw new IllegalArgumentException("Unsupported Hermes API mode: " + mode);
        }
        return mode;
    }

    private static String normalizeBaseUrl(String value) {
        String url = value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.isEmpty()) throw new IllegalArgumentException("Base URL is empty");
        return url;
    }

    private boolean nonEmpty(String field) {
        String value = prefs.getString(field, "");
        return value != null && !value.trim().isEmpty();
    }

    private static String require(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String yamlQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String upsert(
        String content,
        String key,
        String value
    ) {
        StringBuilder out = new StringBuilder();
        boolean found = false;

        String normalized =
            content
                .replace("\r\n", "\n")
                .replace("\r", "\n");

        for (String line :
            normalized.split("\n", -1)) {

            if (line.startsWith(key + "=")) {
                if (!found) {
                    out.append(key)
                        .append("=")
                        .append(value)
                        .append('\n');
                    found = true;
                }
            } else if (!line.isEmpty() ||
                out.length() > 0) {
                out.append(line)
                    .append('\n');
            }
        }

        if (!found) {
            out.append(key)
                .append("=")
                .append(value)
                .append('\n');
        }

        return out.toString();
    }

    private static void writeFile(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Unable to create " + parent);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}