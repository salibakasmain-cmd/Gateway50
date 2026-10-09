package org.caravel.bubblie;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Small authenticated client for the loopback Hermes OpenAI-compatible API. */
public final class GatewayClient {
    public interface StreamListener {
        void onText(String delta);
        void onTool(String name, String state);
    }

    public static final class Message {
        public final String role;
        public final String content;
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private final HermesManager hermes;
    private final ProviderStore provider;

    public GatewayClient(Context context) {
        hermes = new HermesManager(context.getApplicationContext());
        provider = ProviderStore.getInstance(context);
    }

    public boolean health() {
        HttpURLConnection connection = null;
        try {
            connection = open("/health", "GET", 2500, 2500);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public String chat(List<Message> history, StreamListener listener) throws Exception {
        if (!provider.isConfigured()) {
            throw new IOException("Configure a provider in Settings first");
        }
        JSONObject body = new JSONObject();
        body.put("model", provider.model());
        body.put("stream", true);
        JSONArray messages = new JSONArray();
        for (Message item : history) {
            messages.put(new JSONObject().put("role", item.role).put("content", item.content));
        }
        body.put("messages", messages);

        HttpURLConnection connection = open("/v1/chat/completions", "POST", 8000, 0);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(request);
        }

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            String error = readLimited(connection.getErrorStream(), 4096);
            throw new IOException("Gateway request failed (HTTP " + code + ")" +
                (error.isEmpty() ? "" : ": " + safeError(error)));
        }

        StringBuilder answer = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject event;
                try {
                    event = new JSONObject(data);
                } catch (Exception ignored) {
                    continue;
                }
                JSONArray choices = event.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) continue;
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) continue;
                String text = delta.optString("content", "");
                if (!text.isEmpty()) {
                    answer.append(text);
                    listener.onText(text);
                }
                JSONArray tools = delta.optJSONArray("tool_calls");
                if (tools != null) {
                    for (int i = 0; i < tools.length(); i++) {
                        JSONObject call = tools.optJSONObject(i);
                        JSONObject fn = call == null ? null : call.optJSONObject("function");
                        String name = fn == null ? "" : fn.optString("name", "");
                        if (!name.isEmpty()) listener.onTool(name, "running");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
        if (answer.length() == 0) {
            throw new IOException("Hermes completed without a text response");
        }
        return answer.toString();
    }

    private HttpURLConnection open(String path, String method, int connectMs, int readMs)
        throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + HermesManager.GATEWAY_PORT + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + hermes.gatewayKey());
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        return connection;
    }

    private static String readLimited(InputStream stream, int max) throws IOException {
        if (stream == null) return "";
        byte[] buffer = new byte[1024];
        StringBuilder result = new StringBuilder();
        int count;
        while (result.length() < max && (count = stream.read(buffer)) >= 0) {
            result.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static String safeError(String raw) {
        // Provider errors can echo credentials or request details. Keep only a generic JSON message.
        try {
            String message = new JSONObject(raw).optString("message", "");
            if (message.isEmpty()) {
                JSONObject error = new JSONObject(raw).optJSONObject("error");
                if (error != null) message = error.optString("message", "");
            }
            return message.isEmpty() ? "See provider configuration" : cap(message, 240);
        } catch (Exception ignored) {
            return "See provider configuration";
        }
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
