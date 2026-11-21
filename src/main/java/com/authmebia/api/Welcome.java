package com.authmebia.api;

import com.authmebia.AuthMeBia;
import com.authmebia.api.Discord;
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
    public void handle(Player player, boolean firstJoin) {
        handle(player, firstJoin, false);
    }

    /**
     * @param force when true the {@code enabled} toggles are ignored so the
     *              debug command can preview a notification; a webhook URL is
     *              still required.
     */
    public void handle(Player player, boolean firstJoin, boolean force) {
        boolean imageEnabled = plugin.cfg().welcomeImageEnabled();
        boolean discordEnabled = force ? hasWebhook() : plugin.cfg().discordNotifyEnabled(firstJoin);
        if (!imageEnabled) {
            if (discordEnabled) sendDiscordMessage(player, firstJoin);
            return;
        }
        try {
            File jsonFile = new File(plugin.getDataFolder(), "welcome.json");
            if (!jsonFile.exists()) {
                if (discordEnabled) {
                    plugin.getLogger().warning("welcome.json is missing; falling back to the Discord text message");
                    sendDiscordMessage(player, firstJoin);
                } else {
                    plugin.getLogger().warning("welcome.json is missing; skipping welcome image");
                }
                return;
            }
            String raw = Files.readString(jsonFile.toPath());
            if (raw.contains("{player_avatar}")) {
                // Skin plugins often apply the profile texture shortly after
                // the player joins; give them a moment before drawing.
                waitForSkinTexture(player);
            }
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject sizeObj = root.getAsJsonObject("welcome_size");
            int width = sizeObj.get("width").getAsInt();
            int height = sizeObj.get("height").getAsInt();
            int radial = root.has("radial") ? root.get("radial").getAsInt() : 0;
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
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
            if (discordEnabled) {
                String webhook = plugin.cfg().discordWebhook();
                if (!webhook.isEmpty()) {
                    Discord.sendImage(plugin.httpClient(), webhook, imageBytes,
                            "welcome_" + player.getName() + ".png",
                            caption(player, firstJoin));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Welcome image error: " + e.getMessage());
            if (discordEnabled) sendDiscordMessage(player, firstJoin);
        }
    }

    private void sendDiscordMessage(Player player, boolean firstJoin) {
        String webhook = plugin.cfg().discordWebhook();
        if (webhook == null || webhook.isBlank()) return;
        String rawTitle = plugin.cfg().discordNotifyTitle(firstJoin, player.getName());
        String rawContent = plugin.cfg().discordNotifyContent(firstJoin, player.getName());
        boolean showAvatar = hasAvatarToken(rawTitle) || hasAvatarToken(rawContent);
        String title = DiscordMarkdown.render(stripAvatar(rawTitle));
        String content = DiscordMarkdown.render(stripAvatar(rawContent));
        byte[] avatar = null;
        if (showAvatar) {
            waitForSkinTexture(player);
            avatar = playerHeadPng(player);
            if (avatar == null) {
                plugin.getLogger().fine("Discord notification for " + player.getName()
                        + " has no avatar: the player profile has no skin texture. "
                        + "On an offline-mode server install a skin plugin so the profile carries one.");
            }
        }
        JsonObject payload = Discord.componentsV2(title, content,
                avatar != null ? "attachment://player_head.png" : null);
        if (avatar != null) {
            Discord.sendComponents(plugin.httpClient(), webhook.trim(), payload,
                    avatar, "player_head.png");
        } else {
            Discord.sendComponents(plugin.httpClient(), webhook.trim(), payload);
        }
    }

    private String caption(Player player, boolean firstJoin) {
        String title = DiscordMarkdown.render(stripAvatar(
                plugin.cfg().discordNotifyTitle(firstJoin, player.getName())));
        String content = DiscordMarkdown.render(stripAvatar(
                plugin.cfg().discordNotifyContent(firstJoin, player.getName())));
        String caption = title.isBlank() ? content
                : content.isBlank() ? title : title + "\n" + content;
        return caption.length() <= 2000 ? caption : caption.substring(0, 2000);
    }

    private static boolean hasAvatarToken(String raw) {
        return raw != null && raw.contains("{player_avatar}");
    }

    private static String stripAvatar(String raw) {
        return raw == null ? "" : raw.replace("{player_avatar}", "").trim();
    }

    /**
     * Renders the player's head from the skin texture already attached to the
     * profile and returns it as a PNG. The image is uploaded with the webhook
     * message, so notifications do not depend on an external avatar service.
     */
    private byte[] playerHeadPng(Player player) {
        BufferedImage head = fetchPlayerHead(player);
        if (head == null) return null;
        int size = 128;
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(head, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            plugin.getLogger().warning("Player head render error: " + e.getMessage());
            return null;
        }
    }

    private boolean hasWebhook() {
        String webhook = plugin.cfg().discordWebhook();
        return webhook != null && !webhook.isBlank();
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
    private boolean hasSkinTexture(Player player) {
        try {
            org.bukkit.profile.PlayerTextures textures = player.getPlayerProfile().getTextures();
            return textures != null && textures.getSkin() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Skin plugins can fill the player profile a moment after the join. Both
     * notification paths run on async scheduler threads, so waiting briefly
     * here is safe and keeps the avatar out of a race with those plugins.
     */
    private void waitForSkinTexture(Player player) {
        if (hasSkinTexture(player) || org.bukkit.Bukkit.isPrimaryThread()) return;
        for (int attempt = 0; attempt < 4 && player.isOnline(); attempt++) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (hasSkinTexture(player)) return;
        }
    }

    private BufferedImage fetchPlayerHead(Player player) {
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
        // No skin texture is available (common on offline-mode servers without
        // a skin plugin). Omit the thumbnail instead of relying on a third-party
        // avatar service that may be unreachable or rate-limited.
        return null;
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
    private BufferedImage cropHead(BufferedImage skin) {
        int headSize = 8;
        BufferedImage head = new BufferedImage(headSize, headSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D hg = head.createGraphics();
        try {
            hg.drawImage(skin, 0, 0, headSize, headSize, 8, 8, 16, 16, null);
            // The hat overlay exists in both the modern 64x64 format and the
            // legacy 64x32 format (at 40,8 in both). A height check of 64
            // would skip it for legacy skins.
            if (skin.getWidth() >= 64 && skin.getHeight() >= 32) {
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
