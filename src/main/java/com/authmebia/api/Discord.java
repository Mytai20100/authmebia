package com.authmebia.api;

import com.authmebia.AuthMeBia;
import com.google.gson.JsonArray;
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

    /**
     * Message flag that switches the message to the Components V2 layout.
     * While it is set Discord only accepts the {@code components} field, so
     * text/embeds/files can no longer be sent alongside it.
     */
    private static final int IS_COMPONENTS_V2 = 1 << 15;

    /** Discord rejects text display content longer than this. */
    private static final int MAX_TEXT_DISPLAY = 4000;

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

    /**
     * Sends a Components V2 payload. Incoming webhooks only honour the
     * {@code components} field when {@code with_components=true} is present,
     * so the flag is appended to the webhook URL here.
     */
    public static void sendComponents(OkHttpClient client, String webhookUrl, JsonObject payload) {
        if (payload == null) return;
        String url = webhookUrl == null ? "" : webhookUrl.trim();
        if (url.isEmpty()) return;
        url = url + (url.contains("?") ? "&" : "?") + "with_components=true&wait=true";
        send(client, url, payload.toString());
    }

    /**
     * Sends a Components V2 payload with one attached file. The payload must
     * reference the file through {@code attachment://<filename>}. Attaching the
     * file keeps the message independent from third-party image hosts.
     */
    public static void sendComponents(OkHttpClient client, String webhookUrl, JsonObject payload,
                                      byte[] file, String filename) {
        if (payload == null || file == null || file.length == 0) {
            sendComponents(client, webhookUrl, payload);
            return;
        }
        String url = webhookUrl == null ? "" : webhookUrl.trim();
        if (!isValidWebhook(url)) {
            warn("Invalid Discord webhook URL; expected an https URL");
            return;
        }
        String safeName = filename == null || filename.isBlank() ? "attachment.png" : filename;
        url = url + (url.contains("?") ? "&" : "?") + "with_components=true&wait=true";

        JsonObject body = payload.deepCopy();
        JsonArray attachments = new JsonArray();
        JsonObject attachment = new JsonObject();
        attachment.addProperty("id", 0);
        attachment.addProperty("filename", safeName);
        attachments.add(attachment);
        body.add("attachments", attachments);

        okhttp3.MultipartBody multipart = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("payload_json", body.toString())
                .addFormDataPart("files[0]", safeName,
                        RequestBody.create(file, MediaType.get("image/png")))
                .build();
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .post(multipart)
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

    /**
     * Builds a Components V2 message: a container holding the optional title,
     * a separator and the content, with the player's avatar as a thumbnail.
     *
     * @param title     already rendered markdown, may be blank
     * @param content   already rendered markdown, may be blank
     * @param avatarUrl image URL or {@code attachment://} reference used as
     *                  the thumbnail, may be null
     * @return the payload, or {@code null} when there is nothing to show
     */
    public static JsonObject componentsV2(String title, String content, String avatarUrl) {
        boolean hasTitle = title != null && !title.isBlank();
        boolean hasContent = content != null && !content.isBlank();
        if (!hasTitle && !hasContent) return null;
        boolean hasAvatar = avatarUrl != null && !avatarUrl.isBlank();

        JsonArray inner = new JsonArray();
        if (hasTitle && hasContent) {
            inner.add(textDisplay(title));
            inner.add(separator());
            inner.add(hasAvatar
                    ? sectionWithThumbnail(textDisplay(content), avatarUrl)
                    : textDisplay(content));
        } else if (hasTitle) {
            // The avatar token can also be placed in the title. Attach the
            // thumbnail to whichever text block is the only one present.
            inner.add(hasAvatar
                    ? sectionWithThumbnail(textDisplay(title), avatarUrl)
                    : textDisplay(title));
        } else {
            inner.add(hasAvatar
                    ? sectionWithThumbnail(textDisplay(content), avatarUrl)
                    : textDisplay(content));
        }

        JsonObject container = new JsonObject();
        container.addProperty("type", 17);
        container.add("components", inner);

        JsonArray components = new JsonArray();
        components.add(container);

        JsonObject payload = new JsonObject();
        payload.addProperty("flags", IS_COMPONENTS_V2);
        payload.add("components", components);
        return payload;
    }

    private static JsonObject textDisplay(String content) {
        JsonObject text = new JsonObject();
        text.addProperty("type", 10);
        text.addProperty("content", truncate(content, MAX_TEXT_DISPLAY));
        return text;
    }

    private static JsonObject separator() {
        JsonObject separator = new JsonObject();
        separator.addProperty("type", 14);
        separator.addProperty("divider", true);
        separator.addProperty("spacing", 1);
        return separator;
    }

    private static JsonObject sectionWithThumbnail(JsonObject text, String avatarUrl) {
        JsonObject section = new JsonObject();
        section.addProperty("type", 9);
        JsonArray sectionComponents = new JsonArray();
        sectionComponents.add(text);
        section.add("components", sectionComponents);

        JsonObject media = new JsonObject();
        media.addProperty("url", avatarUrl);
        JsonObject thumbnail = new JsonObject();
        thumbnail.addProperty("type", 11);
        thumbnail.add("media", media);
        section.add("accessory", thumbnail);
        return section;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Sends an image as a Components V2 message: an optional caption, a
     * separator and a media gallery that displays the attached file.
     */
    public static void sendImage(OkHttpClient client, String webhookUrl, byte[] imageBytes,
                                 String filename, String content) {
        if (imageBytes == null || imageBytes.length == 0) return;
        String safeName = filename == null || filename.isBlank() ? "image.png" : filename;

        JsonArray inner = new JsonArray();
        if (content != null && !content.isBlank()) {
            inner.add(textDisplay(content));
            inner.add(separator());
        }
        inner.add(mediaGallery("attachment://" + safeName));

        JsonObject container = new JsonObject();
        container.addProperty("type", 17);
        container.add("components", inner);

        JsonArray components = new JsonArray();
        components.add(container);

        JsonObject payload = new JsonObject();
        payload.addProperty("flags", IS_COMPONENTS_V2);
        payload.add("components", components);

        sendComponents(client, webhookUrl, payload, imageBytes, safeName);
    }

    private static JsonObject mediaGallery(String url) {
        JsonObject media = new JsonObject();
        media.addProperty("url", url);
        JsonObject item = new JsonObject();
        item.add("media", media);

        JsonArray items = new JsonArray();
        items.add(item);

        JsonObject gallery = new JsonObject();
        gallery.addProperty("type", 12);
        gallery.add("items", items);
        return gallery;
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
