package com.authmebia.service;

import com.authmebia.AuthMeBia;
import com.authmebia.listeners.floodgate.Floodgate;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PlayerLookupService {

    private static final String PRIMARY_URL =
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final String FALLBACK_URL =
            "https://api.mojang.com/users/profiles/minecraft/";
    private static final Pattern HEX_ID = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final AuthMeBia plugin;

    public PlayerLookupService(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public enum Type {
        PREMIUM,
        CRACKED,
        BEDROCK,
        UNKNOWN
    }

    public record Identity(UUID uuid, Type type) {}

    private record Attempt(int status, String body, IOException error) {}

    public Identity resolveKnown(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        UUID uuid = online == null ? null : online.getUniqueId();
        if (uuid == null) {
            OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
            if (cached != null) uuid = cached.getUniqueId();
        }
        if (uuid == null) return null;
        return new Identity(uuid, classify(uuid, name));
    }

    public Type classify(UUID uuid, String name) {
        if (uuid == null) return Type.UNKNOWN;
        try {
            if (Floodgate.isFloodgatePlayer(uuid)) {
                return Type.BEDROCK;
            }
        } catch (Throwable ignored) {
        }
        if (uuid.version() == 4) {
            return Type.PREMIUM;
        }
        if (uuid.version() == 3) {
            UUID offline = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            if (offline.equals(uuid)) {
                return Type.CRACKED;
            }
        }
        return Type.UNKNOWN;
    }

    public Identity lookupMojang(String name) {
        UUID offline = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        String userAgent = "mytai20100/authmebia/" + plugin.getPluginMeta().getVersion();
        Attempt primary = attempt(PRIMARY_URL + name, userAgent);
        if (primary.error() != null || primary.status() == 403 || primary.status() == 429
                || primary.status() >= 500) {
            Attempt fallback = attempt(FALLBACK_URL + name, userAgent);
            return classifyAttempt(fallback, offline);
        }
        return classifyAttempt(primary, offline);
    }

    private Identity classifyAttempt(Attempt attempt, UUID offline) {
        if (attempt.status() == 200) {
            UUID uuid = parseUuid(attempt.body());
            if (uuid != null) {
                return new Identity(uuid, Type.PREMIUM);
            }
            return new Identity(offline, Type.UNKNOWN);
        }
        if (attempt.status() == 204 || attempt.status() == 404) {
            return new Identity(offline, Type.CRACKED);
        }
        return new Identity(offline, Type.UNKNOWN);
    }

    private UUID parseUuid(String body) {
        try {
            com.google.gson.JsonElement element =
                    com.google.gson.JsonParser.parseString(body);
            if (!element.isJsonObject()) return null;
            com.google.gson.JsonObject object = element.getAsJsonObject();
            com.google.gson.JsonElement idElement = object.get("id");
            if (idElement == null || idElement.isJsonNull() || !idElement.isJsonPrimitive()) {
                return null;
            }
            String id = idElement.getAsString();
            if (id == null || !HEX_ID.matcher(id).matches()) return null;
            String dashed = id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                    + id.substring(12, 16) + "-" + id.substring(16, 20) + "-"
                    + id.substring(20, 32);
            UUID uuid = UUID.fromString(dashed);
            if (!uuid.toString().replace("-", "").equalsIgnoreCase(id)) return null;
            return uuid;
        } catch (Throwable t) {
            return null;
        }
    }

    private Attempt attempt(String url, String userAgent) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            return new Attempt(0, null, new IOException("Invalid lookup URL"));
        }
        Request request = new Request.Builder()
                .url(parsed)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .get()
                .build();
        try (Response response = plugin.httpClient().newCall(request).execute()) {
            String body = response.body() == null ? "" : response.peekBody(65536).string();
            return new Attempt(response.code(), body, null);
        } catch (IOException e) {
            return new Attempt(0, null, e);
        }
    }
}
