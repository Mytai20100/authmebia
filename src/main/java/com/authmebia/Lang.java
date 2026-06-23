package com.authmebia;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Lang {

    private static final MiniMessage MM = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolver(StandardTags.color())
                    .resolver(StandardTags.decorations())
                    .build())
            .build();

    private final AuthMeBia plugin;
    private YamlConfiguration data;

    public Lang(AuthMeBia plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String code = plugin.cfg().lang();
        File file = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Lang file not found: lang/" + code + ".yml, falling back to en");
            file = new File(plugin.getDataFolder(), "lang/en.yml");
        }
        if (file.exists()) {
            data = YamlConfiguration.loadConfiguration(file);
        } else {
            data = loadBuiltin("lang/en.yml");
            plugin.getLogger().warning("Built-in lang/en.yml also missing from disk; using embedded fallback.");
        }
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
