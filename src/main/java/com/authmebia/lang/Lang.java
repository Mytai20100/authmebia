package com.authmebia.lang;

import com.authmebia.AuthMeBia;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class Lang {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final AuthMeBia plugin;
    private YamlConfiguration data;
    private YamlConfiguration embedded;

    public Lang(AuthMeBia plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        embedded = loadBuiltin("lang/en.yml");
        String code = plugin.cfg().lang();
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Lang file not found: lang/" + code + ".yml, falling back to en");
            file = new File(plugin.getDataFolder(), "lang/en.yml");
        }
        if (file.exists()) {
            data = YamlConfiguration.loadConfiguration(file);
        } else if (embedded != null) {
            data = embedded;
            plugin.getLogger().warning("Built-in lang/en.yml also missing from disk; using embedded fallback.");
        } else {
            data = new YamlConfiguration();
            plugin.getLogger().warning("Built-in lang/en.yml also missing from disk; using embedded fallback.");
        }
    }

    public Component component(String key, String fallback, Map<String, String> values) {
        String raw = resolve(key, fallback);
        String substituted = substitute(raw, values);
        try {
            return MM.deserialize(substituted);
        } catch (Throwable t) {
            return Component.text(substituted);
        }
    }

    public String plain(String key, String fallback, Map<String, String> values) {
        String raw = resolve(key, fallback);
        String substituted = substitute(raw, values);
        try {
            return PlainTextComponentSerializer.plainText().serialize(MM.deserialize(substituted));
        } catch (Throwable t) {
            return substituted;
        }
    }

    private String resolve(String key, String fallback) {
        String value = data != null ? data.getString(key) : null;
        if (value == null && embedded != null) {
            value = embedded.getString(key);
        }
        if (value == null) {
            value = fallback;
        }
        return value == null ? "" : value;
    }

    private String substitute(String raw, Map<String, String> values) {
        if (values == null || values.isEmpty()) return raw;
        String result = raw;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null) continue;
            String value = entry.getValue() == null ? "" : entry.getValue();
            String safe = MM.escapeTags(value);
            result = result.replace("{" + entry.getKey() + "}", safe);
        }
        return result;
    }

    private YamlConfiguration loadBuiltin(String resource) {
        InputStream in = plugin.getResource(resource);
        if (in == null) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public Component disconnectVerificationFailed(String ip) {
        return parse("disconnect.verification_failed", null, ip);
    }

    public Component disconnectRegistrationCancelled(String ip) {
        return parse("disconnect.registration_cancelled", null, ip);
    }

    public Component disconnectMustAgreeRules(String ip) {
        return parse("disconnect.must_agree_rules", null, ip);
    }

    public Component disconnectLoginFailed(String ip) {
        return parse("disconnect.login_failed", null, ip);
    }

    public Component disconnectAlreadyConnecting(String ip) {
        return parse("disconnect.already_connecting", null, ip);
    }

    public Component disconnectLogout(String player, String ip) {
        return parse("disconnect.logout", player, ip);
    }

    public Component disconnectTooManyAttempts(String player, String ip) {
        return parse("disconnect.too_many_attempts", player, ip);
    }

    public Component disconnectIpBanned(String player, String ip) {
        return parse("disconnect.ip_banned", player, ip);
    }

    public String errorWrongPassword() {
        return raw("error.wrong_password", "Wrong password");
    }

    public String errorPasswordEmpty() {
        return raw("error.password_empty", "Password cannot be empty");
    }

    public String errorPasswordsMismatch() {
        return raw("error.passwords_mismatch", "Passwords do not match");
    }

    public String errorCaptchaIncorrect() {
        return raw("error.captcha_incorrect", "Incorrect code");
    }

    public Component messageWrongPassword() {
        return parse("message.wrong_password", null, null);
    }

    public Component messagePasswordEmptyOrMismatch() {
        return parse("message.password_empty_or_mismatch", null, null);
    }

    public String ipBanReason(String ip) {
        String raw = data.getString("disconnect.ip_banned",
                "Too many failed login attempts from {player_ip}.");
        return applyPlaceholders(raw, null, ip);
    }

    private Component parse(String key, String player, String ip) {
        String raw = data.getString(key, key);
        raw = applyPlaceholders(raw, player, ip);
        return MM.deserialize(raw);
    }

    private String raw(String key, String fallback) {
        return data.getString(key, fallback);
    }

    private String applyPlaceholders(String input, String player, String ip) {
        if (input == null) return "";
        if (player != null) input = input.replace("{player}", player);
        if (ip != null) input = input.replace("{player_ip}", ip);
        return input;
    }
}
