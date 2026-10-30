package com.authmebia.api;

import com.authmebia.AuthMeBia;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;

public class Discord {

    private static final MediaType JSON = MediaType.get("application/json");

    private Discord() {}

    public static void send(OkHttpClient client, String webhookUrl, String payload) {
        String url = webhookUrl == null ? "" : webhookUrl.trim();
        if (!isValidWebhook(url)) {
            warn("Invalid Discord webhook URL; expected an https URL");
            return;
        }
        try {
            RequestBody body = RequestBody.create(payload, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    warn("Discord webhook failed: " + response.code());
                }
            }
        } catch (IllegalArgumentException e) {
            warn("Discord webhook URL is invalid");
        } catch (IOException e) {
            warn("Discord webhook error: " + e.getMessage());
        }
    }

    public static void sendImage(OkHttpClient client, String webhookUrl, byte[] imageBytes, String filename, String content) {
        String url = webhookUrl == null ? "" : webhookUrl.trim();
        if (!isValidWebhook(url)) {
            warn("Invalid Discord webhook URL; expected an https URL");
            return;
        }
        try {
            JsonObject payloadJson = new JsonObject();
            payloadJson.addProperty("content", content);

            okhttp3.MultipartBody body = new okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("payload_json", payloadJson.toString())
                    .addFormDataPart("file", filename,
                            RequestBody.create(imageBytes, MediaType.get("image/png")))
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    warn("Discord webhook failed: " + response.code());
                }
            }
        } catch (IllegalArgumentException e) {
            warn("Discord webhook URL is invalid");
        } catch (IOException e) {
            warn("Discord webhook error: " + e.getMessage());
        }
    }

    private static boolean isValidWebhook(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) return false;
        try {
            URI uri = URI.create(webhookUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void warn(String message) {
        AuthMeBia plugin = AuthMeBia.get();
        if (plugin != null) plugin.getLogger().warning(message);
    }
}
