package com.authmebia;

import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

public class Discord {

    private static final MediaType JSON = MediaType.get("application/json");

    private Discord() {}

    public static void send(OkHttpClient client, String webhookUrl, String payload) {
        RequestBody body = RequestBody.create(payload, JSON);
        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                AuthMeBia.get().getLogger().warning("Discord webhook failed: " + response.code());
            }
        } catch (IOException e) {
            AuthMeBia.get().getLogger().warning("Discord webhook error: " + e.getMessage());
        }
    }

    public static void sendImage(OkHttpClient client, String webhookUrl, byte[] imageBytes, String filename, String content) {
        // Build the JSON payload with Gson so special characters in content
        // (quotes, backslashes, newlines) are escaped correctly instead of
        // being concatenated as raw strings, which would produce malformed JSON.
        JsonObject payloadJson = new JsonObject();
        payloadJson.addProperty("content", content);

        okhttp3.MultipartBody body = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("payload_json", payloadJson.toString())
                .addFormDataPart("file", filename,
                        RequestBody.create(imageBytes, MediaType.get("image/png")))
                .build();

        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                AuthMeBia.get().getLogger().warning("Discord image webhook failed: " + response.code());
            }
        } catch (IOException e) {
            AuthMeBia.get().getLogger().warning("Discord image webhook error: " + e.getMessage());
        }
    }
}
