package com.authmebia.api;

import com.authmebia.AuthMeBia;
import com.authmebia.Discord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;

public class Welcome {

    private final AuthMeBia plugin;

    public Welcome(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public void handle(Player player) {
        if (!plugin.cfg().welcomeImageEnabled()) return;

        try {
            File jsonFile = new File(plugin.getDataFolder(), "welcome.json");
            if (!jsonFile.exists()) return;

            String raw = Files.readString(jsonFile.toPath());
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();

            JsonObject sizeObj = root.getAsJsonObject("welcome_size");
            int width = sizeObj.get("width").getAsInt();
            int height = sizeObj.get("height").getAsInt();
            int radial = root.has("radial") ? root.get("radial").getAsInt() : 0;

            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            // Dispose is in a finally block so native graphics resources are
            // always released even if a layer throws an exception mid-draw.
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                if (radial > 0) {
                    g.setClip(new RoundRectangle2D.Double(0, 0, width, height, radial, radial));
                }

                JsonArray layers = root.getAsJsonArray("layers");
                layers.asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .sorted((a, b) -> {
                            int za = a.has("z") ? a.get("z").getAsInt() : 0;
                            int zb = b.has("z") ? b.get("z").getAsInt() : 0;
                            return Integer.compare(za, zb);
                        })
                        .forEach(layer -> drawLayer(g, layer, player, width, height));
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", out);
            byte[] imageBytes = out.toByteArray();

            if (plugin.cfg().discordEnabled()) {
                String webhook = plugin.cfg().discordWebhook();
                if (!webhook.isEmpty()) {
                    Discord.sendImage(plugin.httpClient(), webhook, imageBytes, "welcome_" + player.getName() + ".png", "");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Welcome image error: " + e.getMessage());
        }
    }

    private void drawLayer(Graphics2D g, JsonObject layer, Player player, int canvasW, int canvasH) {
        try {
            String name = layer.has("name") ? layer.get("name").getAsString() : "";
            int x = layer.has("x") ? layer.get("x").getAsInt() : 0;
            int y = layer.has("y") ? layer.get("y").getAsInt() : 0;
            int size = layer.has("size") ? layer.get("size").getAsInt() : 64;

            if (name.equals("background")) {
                if (layer.has("color")) {
                    g.setColor(Color.decode(layer.get("color").getAsString()));
                    g.fillRect(0, 0, canvasW, canvasH);
                }
                if (layer.has("patch")) {
                    String patch = layer.get("patch").getAsString();
                    File f = new File(plugin.getDataFolder(), patch);
                    if (f.exists()) {
                        BufferedImage bg = ImageIO.read(f);
                        g.drawImage(bg, 0, 0, canvasW, canvasH, null);
                    }
                } else if (layer.has("url")) {
                    BufferedImage bg = fetchImage(layer.get("url").getAsString());
                    if (bg != null) g.drawImage(bg, 0, 0, canvasW, canvasH, null);
                }
                return;
            }

            if (layer.has("patch")) {
                String patch = layer.get("patch").getAsString();
                if (patch.equals("{player_avatar}")) {
                    BufferedImage avatar = fetchPlayerHead(player);
                    if (avatar != null) g.drawImage(avatar, x, y, size, size, null);
                } else {
                    File f = new File(plugin.getDataFolder(), patch);
                    if (f.exists()) {
                        BufferedImage img = ImageIO.read(f);
                        g.drawImage(img, x, y, size, size, null);
                    }
                }
            } else if (layer.has("url")) {
                BufferedImage img = fetchImage(layer.get("url").getAsString());
                if (img != null) g.drawImage(img, x, y, size, size, null);
            }

            if (layer.has("color") && layer.has("font")) {
                String text = name.equals("player_name") ? player.getName() : name;
                g.setColor(Color.decode(layer.get("color").getAsString()));
                String font = layer.get("font").getAsString();
                g.setFont(new Font(font, Font.PLAIN, size));
                g.drawString(text, x, y + size);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Layer draw error: " + e.getMessage());
        }
    }

    private BufferedImage fetchPlayerHead(Player player) {
        // Prefer the skin the player's client actually sent for this session.
        // This works correctly in both online-mode and offline-mode servers,
        // since the skin texture URL comes from the player's profile/session,
        // not from a third-party lookup keyed by name (which fails for
        // offline-mode UUIDs and for renamed accounts).
        try {
            org.bukkit.profile.PlayerTextures textures = player.getPlayerProfile().getTextures();
            URL skinUrl = textures != null ? textures.getSkin() : null;
            if (skinUrl != null) {
                BufferedImage fullSkin = fetchImageFromUrl(skinUrl);
                if (fullSkin != null) {
                    return cropHead(fullSkin);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Skin texture read error: " + e.getMessage());
        }

        // Fallback: only reached if the profile has no skin texture cached yet
        // (rare, e.g. very first tick after join). Falls back to UUID-based
        // lookup, since crafatar does not support name-based lookups.
        try {
            String url = "https://crafatar.com/avatars/" + player.getUniqueId() + "?size=64&overlay";
            return fetchImage(url);
        } catch (Exception e) {
            return null;
        }
    }

    private BufferedImage fetchImageFromUrl(URL url) {
        try {
            Request req = new Request.Builder().url(url.toString()).build();
            try (Response resp = plugin.httpClient().newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    return ImageIO.read(resp.body().byteStream());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Skin image fetch error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Crops the 8x8 head region out of a full skin texture and scales it up.
     * Supports both legacy 64x32 and modern 64x64 skin layouts.
     * Renders the base head layer plus the hat/overlay layer (for players
     * using hats, hair, etc. on the second skin layer).
     */
    private BufferedImage cropHead(BufferedImage skin) {
        int headSize = 8;
        BufferedImage head = new BufferedImage(headSize, headSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D hg = head.createGraphics();
        try {
            // Base head layer.
            hg.drawImage(skin, 0, 0, headSize, headSize, 8, 8, 16, 16, null);
            // Hat overlay layer (only present on 64x64 skins).
            if (skin.getHeight() >= 64) {
                hg.drawImage(skin, 0, 0, headSize, headSize, 40, 8, 48, 16, null);
            }
        } finally {
            hg.dispose();
        }
        return head;
    }

    private BufferedImage fetchImage(String url) {
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = plugin.httpClient().newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    return ImageIO.read(resp.body().byteStream());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Image fetch error: " + e.getMessage());
        }
        return null;
    }
}
