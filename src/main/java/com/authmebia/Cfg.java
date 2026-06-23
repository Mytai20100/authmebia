package com.authmebia;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Cfg {

    private final AuthMeBia plugin;
    private FileConfiguration config;
    private static final MiniMessage MM = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolver(StandardTags.color())
                    .resolver(StandardTags.decorations())
                    .build())
            .build();

    public Cfg(AuthMeBia plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    public String lang() {
        String code = config.getString("lang", "en");
        if (code == null || code.isBlank()) return "en";
        return code.trim().toLowerCase();
    }

    public boolean dialogEnabled() {
        return config.getBoolean("dialog.enabled", true);
    }

    public boolean dialogPreSpawn() {
        return config.getBoolean("dialog.menu", true);
    }

    public int dialogMinProtocolVersion() {
        return config.getInt("dialog.min_protocol_version", 771);
    }

    public long dialogProtocolDetectWaitMillis() {
        long ms = config.getLong("dialog.protocol_detect_wait_ms", 500);
        if (ms < 0) return 0;
        if (ms > 5000) return 5000;
        return ms;
    }

    public Component registerTitle(String playerName) {
        return parse(replacePlayer(config.getString("dialog.register.title", "<#4287f5>Create Account</#4287f5>"), playerName));
    }

    public Component registerContent(String playerName) {
        return parse(replacePlayer(config.getString("dialog.register.content", ""), playerName));
    }

    public Component loginTitle(String playerName) {
        return parse(replacePlayer(config.getString("dialog.login.title", "<gold>Login</gold>"), playerName));
    }

    public Component loginContent(String playerName) {
        return parse(replacePlayer(config.getString("dialog.login.content", ""), playerName));
    }

    public Component logoutButton() {
        return parse(config.getString("dialog.logout_button", "<red>Logout</red>"));
    }

    public Component submitRegisterButton() {
        return parse(config.getString("dialog.submit_register_button", "<green>Register</green>"));
    }

    public Component submitLoginButton() {
        return parse(config.getString("dialog.submit_login_button", "<green>Login</green>"));
    }

    public boolean ruleEnabled() {
        return config.getBoolean("rule.enabled", false);
    }

    public Component ruleTitle(String playerName) {
        return parse(replacePlayer(config.getString("rule.title", "<gold>Server Rules</gold>"), playerName));
    }

    public Component ruleContent(String playerName) {
        return parse(replacePlayer(config.getString("rule.content", "Please agree to the rules before playing."), playerName));
    }

    public Component ruleCheckboxLabel() {
        return parse(config.getString("rule.checkbox_label", "<yellow>I have read and agree to the rules</yellow>"));
    }

    public Component ruleAgreeButton() {
        return parse(config.getString("rule.agree_button", "<green>Agree</green>"));
    }

    private String replacePlayer(String input, String playerName) {
        if (input == null) return null;
        String safeName = playerName == null ? "" : playerName;
        return input.replace("{player}", safeName);
    }

    public boolean discordEnabled() {
        return config.getBoolean("discord.enabled", false);
    }

    public String discordWebhook() {
        return config.getString("discord.webhook_url", "");
    }

    public boolean welcomeImageEnabled() {
        return config.getBoolean("welcome_image.enabled", false);
    }

    public boolean linksEnabled() {
        return config.getBoolean("links.enabled", false);
    }

    public LinkButton.Layout linksLayout() {
        return LinkButton.Layout.parse(config.getString("links.position"), LinkButton.Layout.GROUPED);
    }

    public int mainButtonWidth() {
        return clampWidth(config.getInt("dialog.button_width", 200));
    }

    public int inputWidth() {
        return clampWidth(config.getInt("dialog.input_width", 200));
    }

    public int linkButtonWidth() {
        return clampWidth(config.getInt("links.button_width", 200));
    }

    public boolean captchaEnabled() {
        return config.getBoolean("captcha.enabled", false);
    }

    public int captchaLength() {
        int length = config.getInt("captcha.length", 5);
        if (length < 4) return 4;
        if (length > 10) return 10;
        return length;
    }

    public Component captchaTitle() {
        return parse(config.getString("captcha.title", "<gold>Verification</gold>"));
    }

    public Component captchaContent(String code) {
        String raw = config.getString("captcha.content", "<gray>Type the code below to continue:</gray>\n<white><bold>{code}</bold></white>");
        return parse(raw.replace("{code}", code));
    }

    public String captchaInputLabel() {
        return config.getString("captcha.input_label", "Enter code");
    }

    public Component captchaSubmitButton() {
        return parse(config.getString("captcha.submit_button", "<green>Verify</green>"));
    }

    public long captchaTrustDurationSeconds() {
        long seconds = config.getLong("captcha.trust_duration_seconds", 18000);
        return Math.max(0, seconds);
    }

    public boolean loginAttemptsEnabled() {
        return config.getBoolean("login_attempts.enabled", false);
    }

    public int loginMaxTries() {
        int tries = config.getInt("login_attempts.max_tries", 5);
        return Math.max(1, tries);
    }

    public boolean ipBanEnabled() {
        return config.getBoolean("ip_ban.enabled", false);
    }

    public int ipBanThreshold() {
        int threshold = config.getInt("ip_ban.threshold", 10);
        return Math.max(1, threshold);
    }

    public long ipBanDurationSeconds(int level) {
        List<Integer> durations = config.getIntegerList("ip_ban.ban_durations_seconds");
        if (durations.isEmpty()) return 600;
        // level is 1-based (first offense = 1). Clamp to the last element
        // for any offense beyond the end of the list.
        int index = Math.min(level - 1, durations.size() - 1);
        if (index < 0) index = 0;
        return durations.get(index);
    }

    private int clampWidth(int width) {
        if (width < 1) return 1;
        if (width > 1024) return 1024;
        return width;
    }

    public List<LinkButton> linkButtons(String playerName) {
        List<LinkButton> result = new ArrayList<>();
        int defaultWidth = linkButtonWidth();
        for (Map<?, ?> map : config.getMapList("links.buttons")) {
            boolean enabled = readBoolean(map, "enabled", true);
            String labelRaw = replacePlayer(readString(map, "label", ""), playerName);
            Component label = parse(labelRaw);
            if (label == null) label = Component.text(labelRaw);

            String actionRaw = readString(map, "action", "open_url");
            LinkButton.Action action = "copy".equalsIgnoreCase(actionRaw)
                    ? LinkButton.Action.COPY
                    : LinkButton.Action.OPEN_URL;

            String value = replacePlayer(readString(map, "value", ""), playerName);
            int width = clampWidth(readInt(map, "width", defaultWidth));

            result.add(new LinkButton(enabled, label, action, value, width));
        }
        return result;
    }

    private int readInt(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private boolean readBoolean(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return fallback;
    }

    private String readString(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value != null ? value.toString() : fallback;
    }

    public boolean copyDefaultsIfMissing(String name) {
        return new java.io.File(plugin.getDataFolder(), name).exists();
    }

    private Component parse(String input) {
        if (input == null || input.isBlank()) return null;
        String normalized = input.replace("\\n", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 1) return MM.deserialize(lines[0]);
        Component result = MM.deserialize(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            result = result.append(Component.newline()).append(MM.deserialize(lines[i]));
        }
        return result;
    }
}
