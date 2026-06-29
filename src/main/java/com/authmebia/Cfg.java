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

    public boolean dialogAllowClose() {
        return config.getBoolean("dialog.allow_close", true);
    }

    public AuthMode authMode() {
        return AuthMode.parse(config.getString("auth_mode.mode", "password"));
    }

    private int clampCodeLength(int length) {
        if (length < 4) return 4;
        if (length > 8) return 8;
        return length;
    }

    public int pinLength() {
        return clampCodeLength(config.getInt("auth_mode.pin.length", 4));
    }

    public Component pinTitle() {
        return parse(config.getString("auth_mode.pin.title", "<gold>Enter your PIN</gold>"));
    }

    public Component pinConfirmButton() {
        return parse(config.getString("auth_mode.pin.confirm_button", "<green>Confirm</green>"));
    }

    public Component pinDeleteButton() {
        return parse(config.getString("auth_mode.pin.delete_button", "<red>⌫ Delete</red>"));
    }

    public int pinButtonWidth() {
        return clampWidth(config.getInt("auth_mode.pin.button_width", 100));
    }

    public int sliderLength() {
        return clampCodeLength(config.getInt("auth_mode.slider.length", 4));
    }

    public Component sliderTitle() {
        return parse(config.getString("auth_mode.slider.title", "<gold>Enter your code</gold>"));
    }

    public Component sliderConfirmButton() {
        return parse(config.getString("auth_mode.slider.confirm_button", "<green>Confirm</green>"));
    }

    public int sliderButtonWidth() {
        return clampWidth(config.getInt("auth_mode.slider.button_width", 100));
    }

    public boolean authWaitEnabled() {
        return config.getBoolean("auth_wait.wait", true);
    }

    public boolean authWaitPreJoin() {
        return config.getBoolean("auth_wait.prejoin", true);
    }

    public int authWaitSeconds() {
        int seconds = config.getInt("auth_wait.time", 3);
        if (seconds < 1) return 1;
        if (seconds > 10) return 10;
        return seconds;
    }

    public Component authWaitTitle() {
        return parse(config.getString("auth_wait.title", "<gold>Please wait</gold>"));
    }

    public Component authWaitContent() {
        return parse(config.getString("auth_wait.content", "<gray>Logging you in, please wait...</gray>"));
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

    public boolean emailEnabled() {
        return config.getBoolean("email.enabled", false);
    }

    public int emailCodeLength() {
        int length = config.getInt("email.code_length", 6);
        if (length < 4) return 4;
        if (length > 8) return 8;
        return length;
    }

    public int emailResendCooldown() {
        int seconds = config.getInt("email.resend_cooldown", 60);
        if (seconds < 10) return 10;
        if (seconds > 600) return 600;
        return seconds;
    }

    public String emailFieldLabel() {
        return config.getString("email.field_label", "Email");
    }

    public Component emailVerifyTitle() {
        return parse(config.getString("email.verify_title", "<gold>Verify your email</gold>"));
    }

    public Component emailVerifyContent(String email, int cooldownRemaining) {
        String raw = config.getString("email.verify_content",
                "<gray>A code was sent to {email}.\nResend available in {cooldown}s.</gray>");
        raw = raw.replace("{email}", email == null ? "" : email)
                 .replace("{cooldown}", Integer.toString(Math.max(0, cooldownRemaining)));
        return parse(raw);
    }

    public String emailCodeLabel() {
        return config.getString("email.code_label", "Code");
    }

    public Component emailVerifyButton() {
        return parse(config.getString("email.verify_button", "<green>Verify</green>"));
    }

    public Component emailResendButton() {
        return parse(config.getString("email.resend_button", "<yellow>Resend code</yellow>"));
    }

    public Component emailInvalidEmailMessage() {
        return parse(config.getString("email.invalid_email_message",
                "<red>Please enter a valid email address.</red>"));
    }

    public Component emailSendFailedMessage() {
        return parse(config.getString("email.send_failed_message",
                "<red>Could not send the email. Please contact an admin.</red>"));
    }

    public String emailWrongCodeError() {
        return config.getString("email.wrong_code_error", "Incorrect code");
    }

    public boolean loginAttemptsEnabled() {
        return config.getBoolean("login_attempts.enabled", false);
    }

    public int loginMaxTries() {
        int tries = config.getInt("login_attempts.max_tries", 5);
        return Math.max(1, tries);
    }

    public boolean loginTimeoutEnabled() {
        return config.getBoolean("login_timeout.enabled", false);
    }

    public int loginTimeoutSeconds() {
        int seconds = config.getInt("login_timeout.seconds", 60);
        if (seconds == 0) return 0;
        if (seconds < 10) return 10;
        if (seconds > 3600) return 3600;
        return seconds;
    }

    public Component loginTimeoutKickMessage() {
        return parse(config.getString("login_timeout.kick_message",
                "<red>Authentication timed out. Please reconnect.</red>"));
    }

    public Component recoverTitle() {
        return parse(config.getString("recover.title", "<gold>Reset Password</gold>"));
    }

    public Component recoverContent() {
        return parse(config.getString("recover.content",
                "<gray>An admin has requested you set a new password.</gray>"));
    }

    public String recoverNewPasswordLabel() {
        return config.getString("recover.new_password_label", "New password");
    }

    public String recoverConfirmPasswordLabel() {
        return config.getString("recover.confirm_password_label", "Confirm password");
    }

    public Component recoverSubmitButton() {
        return parse(config.getString("recover.submit_button", "<green>Set Password</green>"));
    }

    public Component recoverSuccessMessage(String playerName) {
        return parse(replacePlayer(config.getString("recover.success_message",
                "<green>Password updated successfully.</green>"), playerName));
    }

    public Component recoverMismatchMessage() {
        return parse(config.getString("recover.mismatch_message",
                "<red>Passwords do not match. Try again.</red>"));
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

    // --- TOTP 2FA ---

    public boolean totp2faEnabled() {
        return config.getBoolean("totp_2fa.enabled", false);
    }

    public Component totp2faTitle() {
        return parse(config.getString("totp_2fa.title", "<gold>Two-Factor Authentication</gold>"));
    }

    public Component totp2faContent() {
        return parse(config.getString("totp_2fa.content", "<gray>Enter your 6-digit authenticator code:</gray>"));
    }

    public String totp2faInputLabel() {
        return config.getString("totp_2fa.input_label", "Authenticator code");
    }

    public Component totp2faSubmitButton() {
        return parse(config.getString("totp_2fa.submit_button", "<green>Verify</green>"));
    }

    public String totp2faWrongCodeError() {
        return config.getString("totp_2fa.wrong_code_error", "Invalid code");
    }

    // --- Custom screens ---

    public CustomScreen customScreen(String id) {
        for (Map<?, ?> map : config.getMapList("custom_screens")) {
            if (id.equalsIgnoreCase(readString(map, "id", ""))) {
                return buildCustomScreen(map);
            }
        }
        return null;
    }

    public List<CustomScreen> customScreens() {
        List<CustomScreen> result = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("custom_screens")) {
            CustomScreen s = buildCustomScreen(map);
            if (s != null) result.add(s);
        }
        return result;
    }

    private CustomScreen buildCustomScreen(Map<?, ?> map) {
        String id = readString(map, "id", "");
        if (id.isBlank()) return null;
        Component title = parse(readString(map, "title", "Notice"));
        if (title == null) title = net.kyori.adventure.text.Component.text("Notice");
        Component content = parse(readString(map, "content", ""));
        boolean allowClose = readBoolean(map, "allow_close", true);
        int defaultWidth = clampWidth(readInt(map, "button_width", mainButtonWidth()));

        List<CustomScreen.Button> buttons = new ArrayList<>();
        Object rawBtns = map.get("buttons");
        if (rawBtns instanceof List<?> btnList) {
            for (Object o : btnList) {
                if (!(o instanceof Map<?, ?> bm)) continue;
                Component label = parse(readString(bm, "label", "OK"));
                if (label == null) label = net.kyori.adventure.text.Component.text("OK");
                CustomScreen.Button.Action action = switch (readString(bm, "action", "close").toLowerCase()) {
                    case "open_url" -> CustomScreen.Button.Action.OPEN_URL;
                    case "copy"     -> CustomScreen.Button.Action.COPY;
                    default         -> CustomScreen.Button.Action.CLOSE;
                };
                String value = readString(bm, "value", "");
                int width = clampWidth(readInt(bm, "width", defaultWidth));
                buttons.add(new CustomScreen.Button(label, action, value, width));
            }
        }
        return new CustomScreen(id, title, content, allowClose, defaultWidth, buttons);
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
