package com.authmebia.cmd.settings;

import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.cfg.CfgSnapshot;
import com.authmebia.cmd.reload.Reload;
import com.authmebia.dialog.Dialoglib;
import com.authmebia.dialog.util.IconSpec;
import com.authmebia.dialog.util.Util;
import com.authmebia.service.ConfigFile;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Settings {

    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder()
            .lifetime(Duration.ofMinutes(10)).build();

    private Settings() {}

    public static void execute(CommandSender sender, AuthMeBia plugin) {
        try {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.lang().component("command.player_only",
                        "This command must be run by a player.", Map.of()));
                return;
            }
            if (!player.hasPermission("authmebia.settings")) {
                sender.sendMessage(plugin.lang().component("command.no_permission",
                        "You do not have permission to do that.", Map.of()));
                return;
            }
            ConfigFile configFile = new ConfigFile(plugin);
            runAsync(plugin, () -> {
                try {
                    ConfigFile.Snapshot snapshot = configFile.read();
                    ConfigFile.Validation validation = configFile.validate(snapshot.text());
                    if (!validation.valid()) {
                        fail(plugin, player, "settings.file_invalid",
                                "config.yml is invalid. Use /bia config to fix it.", Map.of());
                        return;
                    }
                    Util.runOnMain(player, () -> {
                        try {
                            showHome(plugin, player, new Session(configFile, snapshot));
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Settings dialog failed: " + t.getMessage());
                        }
                    });
                } catch (Throwable t) {
                    plugin.getLogger().warning("Settings config read failed: " + t.getMessage());
                    fail(plugin, player, "settings.file_invalid",
                            "config.yml is invalid. Use /bia config to fix it.", Map.of());
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Settings command failed: " + t.getMessage());
        }
    }

    private static void showHome(AuthMeBia plugin, Player player, Session session) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Category category : Category.values()) {
            Component label = withIcon(plugin, category.icon(),
                    plugin.lang().component(category.key(), category.fallback(), Map.of()));
            buttons.add(ActionButton.builder(label).width(200)
                    .action(DialogAction.customClick((DialogResponseView response, Audience audience) -> {
                        try {
                            if (!(audience instanceof Player p) || !p.isOnline()) return;
                            if (!permission(plugin, p)) return;
                            session.reload = reload(response, session.reload);
                            openCategory(plugin, p, session, category);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Settings category open failed: " + t.getMessage());
                        }
                    }, OPTIONS)).build());
        }

        Component saveLabel = withIcon(plugin, "ui_save",
                plugin.lang().component("settings.save_exit", "Save & exit", Map.of()));
        buttons.add(ActionButton.builder(saveLabel).width(200)
                .action(DialogAction.customClick((DialogResponseView response, Audience audience) -> {
                    try {
                        if (!(audience instanceof Player p) || !p.isOnline()) return;
                        if (!permission(plugin, p)) return;
                        session.reload = reload(response, session.reload);
                        saveAll(plugin, p, session);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Settings save failed: " + t.getMessage());
                    }
                }, OPTIONS)).build());

        Component exitLabel = withIcon(plugin, "ui_exit",
                plugin.lang().component("settings.exit", "Exit", Map.of()));
        buttons.add(ActionButton.builder(exitLabel).width(200)
                .action(DialogAction.customClick((DialogResponseView response, Audience audience) -> {
                }, OPTIONS)).build());

        Component title = plugin.lang().component("settings.title", "bia settings", Map.of());
        Component body = plugin.lang().component("settings.home_instructions",
                "Choose a category to edit. Changes are kept until you use Save & exit.",
                Map.of());
        Component reloadLabel = label(plugin, "settings.label_reload",
                "Reload plugin after saving", "settings.hover_reload",
                "Reloads config and lang immediately after a successful save.");
        DialogInput reloadInput = DialogInput.bool("reload", reloadLabel)
                .initial(session.reload).build();

        player.showDialog(Dialog.create(d -> d.empty()
                .base(Dialoglib.buildBase(title, body, true, List.of(reloadInput)))
                .type(DialogType.multiAction(buttons, null, 3))));
    }

    private static void openCategory(AuthMeBia plugin, Player player, Session session,
                                     Category category) throws Exception {
        Values values = readValues(plugin, session);
        List<DialogInput> inputs = inputsFor(plugin, values, category);
        Component title = plugin.lang().component("settings.category_title",
                "bia settings - {category}",
                Map.of("category", plugin.lang().plain(category.key(), category.fallback(), Map.of())));
        Component body = plugin.lang().component("settings.category_instructions",
                "Edit this category, then choose Save & back. Changes are kept until Save & exit.",
                Map.of());

        DialogActionCallback saveBack = (DialogResponseView response, Audience audience) -> {
            try {
                if (!(audience instanceof Player p) || !p.isOnline()) return;
                if (!permission(plugin, p)) return;
                Map<String, String> changes = collectChanges(response, values);
                session.changes.putAll(changes);
                showHome(plugin, p, session);
            } catch (Throwable t) {
                plugin.getLogger().warning("Settings category save failed: " + t.getMessage());
            }
        };
        DialogActionCallback back = (DialogResponseView response, Audience audience) -> {
            try {
                if (!(audience instanceof Player p) || !p.isOnline()) return;
                if (!permission(plugin, p)) return;
                showHome(plugin, p, session);
            } catch (Throwable t) {
                plugin.getLogger().warning("Settings back failed: " + t.getMessage());
            }
        };

        Component saveLabel = withIcon(plugin, "ui_save",
                plugin.lang().component("settings.save_back", "Save & back", Map.of()));
        Component backLabel = withIcon(plugin, "ui_back",
                plugin.lang().component("settings.back", "Back", Map.of()));
        ActionButton saveButton = ActionButton.builder(saveLabel).width(200)
                .action(DialogAction.customClick(saveBack, OPTIONS)).build();
        ActionButton backButton = ActionButton.builder(backLabel).width(200)
                .action(DialogAction.customClick(back, OPTIONS)).build();

        player.showDialog(Dialog.create(d -> d.empty()
                .base(Dialoglib.buildBase(title, body, true, inputs))
                .type(DialogType.multiAction(List.of(saveButton, backButton), null, 2))));
    }

    private static void saveAll(AuthMeBia plugin, Player player, Session session) {
        if (session.changes.isEmpty()) {
            player.sendMessage(plugin.lang().component("settings.nothing_changed",
                    "No settings were changed.", Map.of()));
            return;
        }
        Map<String, String> changes = new LinkedHashMap<>(session.changes);
        boolean reload = session.reload;
        runAsync(plugin, () -> {
            try {
                String updated = session.snapshot.text();
                for (Map.Entry<String, String> entry : changes.entrySet()) {
                    updated = session.configFile.setScalar(updated, entry.getKey(), entry.getValue());
                }
                session.configFile.writeAtomic(updated, session.snapshot.hash());
                Util.runOnMain(player, () -> {
                    try {
                        if (!player.isOnline()) return;
                        if (reload) {
                            try {
                                Reload.reload(plugin);
                                player.sendMessage(plugin.lang().component("settings.reloaded",
                                        "Settings saved and plugin reloaded.", Map.of()));
                                return;
                            } catch (Throwable t) {
                                plugin.getLogger().warning(
                                        "Reload after settings save failed: " + t.getMessage());
                            }
                        }
                        player.sendMessage(plugin.lang().component("settings.saved",
                                "Settings saved.", Map.of()));
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Settings confirmation failed: " + t.getMessage());
                    }
                });
            } catch (ConfigFile.ConfigException e) {
                String key = ConfigFile.KIND_CHANGED.equals(e.kind())
                        ? "settings.conflict" : "settings.write_failed";
                String fallback = ConfigFile.KIND_CHANGED.equals(e.kind())
                        ? "config.yml changed on disk. Reopen /bia settings."
                        : "Could not save config.yml.";
                fail(plugin, player, key, fallback, Map.of());
            } catch (Throwable t) {
                plugin.getLogger().warning("Settings save failed: " + t.getMessage());
                fail(plugin, player, "settings.write_failed",
                        "Could not save config.yml.", Map.of());
            }
        });
    }

    private static List<DialogInput> inputsFor(AuthMeBia plugin, Values values, Category category) {
        return switch (category) {
            case GENERAL -> List.of(
                    choice(plugin, "menu", "settings.label_menu",
                            "Show the dialog before spawn", "settings.hover_menu",
                            "pre_spawn means the dialog blocks join; post_spawn means it appears after joining.",
                            List.of(new Option("pre_spawn", plugin.lang().component("settings.option_pre_spawn",
                                            "Before spawn", Map.of())),
                                    new Option("post_spawn", plugin.lang().component("settings.option_post_spawn",
                                            "After spawn", Map.of()))),
                            values.preSpawn() ? "pre_spawn" : "post_spawn", "pre_spawn"),
                    bool(plugin, "rule", "settings.label_rule",
                            "Rules dialog", "settings.hover_rule",
                            "Requires new players to accept the server rules.",
                            values.rule()));
            case AUTO -> List.of(
                    bool(plugin, "session_autologin", "settings.label_session_autologin",
                            "Session auto-login", "settings.hover_session_autologin",
                            "Skips the dialog when AuthMe would accept the player's existing session.",
                            values.sessionAutologin()),
                    bool(plugin, "premium_autologin", "settings.label_premium_autologin",
                            "Premium auto-login", "settings.hover_premium_autologin",
                            "Skips the dialog for a premium UUID that matches the stored account.",
                            values.premiumAutologin()),
                    bool(plugin, "bedrock_autologin", "settings.label_bedrock_autologin",
                            "Bedrock auto-login", "settings.hover_bedrock_autologin",
                            "Skips the dialog for Bedrock players. Requires Floodgate.",
                            values.bedrockAutologin()),
                    choice(plugin, "bedrock_mode", "settings.label_bedrock_mode",
                            "Bedrock trust mode", "settings.hover_bedrock_mode",
                            "link trusts a linked Java account; geyser trusts any Bedrock connection.",
                            List.of(new Option("link", plugin.lang().component("settings.option_link",
                                            "Link", Map.of())),
                                    new Option("geyser", plugin.lang().component("settings.option_geyser",
                                            "Geyser", Map.of()))),
                            values.bedrockGeyser() ? "geyser" : "link", "link"));
            case SECURITY -> List.of(
                    bool(plugin, "totp_2fa", "settings.label_totp_2fa",
                            "Two-factor authentication", "settings.hover_totp_2fa",
                            "Shows the AuthMe TOTP code dialog after a correct password.",
                            values.totp2fa()),
                    bool(plugin, "email", "settings.label_email",
                            "Email verification", "settings.hover_email",
                            "Requires email verification after registration when AuthMe email is configured.",
                            values.email()),
                    bool(plugin, "login_timeout", "settings.label_login_timeout",
                            "Login timeout", "settings.hover_login_timeout",
                            "Kicks players who do not authenticate within the configured time.",
                            values.loginTimeout()),
                    bool(plugin, "otp_attempts", "settings.label_otp_attempts",
                            "OTP attempt limit", "settings.hover_otp_attempts",
                            "Limits wrong 2FA and email code attempts.",
                            values.otpAttempts()),
                    bool(plugin, "login_attempts", "settings.label_login_attempts",
                            "Login attempt limit", "settings.hover_login_attempts",
                            "Limits wrong password attempts per login.",
                            values.loginAttempts()),
                    bool(plugin, "captcha", "settings.label_captcha",
                            "Captcha", "settings.hover_captcha",
                            "Shows the AuthMeBia captcha before registration or login.",
                            values.captcha()));
            case APPEARANCE -> List.of(
                    bool(plugin, "links", "settings.label_links",
                            "Link buttons", "settings.hover_links",
                            "Shows the configured link buttons in the login and register dialogs.",
                            values.links()),
                    choice(plugin, "links_position", "settings.label_links_position",
                            "Link button layout", "settings.hover_links_position",
                            "Grouped keeps links with the main buttons; separated puts them on their own row.",
                            List.of(new Option("grouped", plugin.lang().component("settings.option_grouped",
                                            "Grouped", Map.of())),
                                    new Option("separated", plugin.lang().component("settings.option_separated",
                                            "Separated", Map.of()))),
                            values.linksSeparated() ? "separated" : "grouped", "grouped"),
                    bool(plugin, "welcome_image", "settings.label_welcome_image",
                            "Welcome image", "settings.hover_welcome_image",
                            "Generates and optionally posts the welcome image after first login.",
                            values.welcomeImage()),
                    bool(plugin, "discord", "settings.label_discord",
                            "Discord webhook", "settings.hover_discord",
                            "Sends the welcome image to the configured Discord webhook.",
                            values.discord()));
            case AUTH -> List.of(
                    choice(plugin, "auth_mode", "settings.label_auth_mode",
                            "Login input mode", "settings.hover_auth_mode",
                            "Password uses a text field; pin and slider use the numeric entry dialogs.",
                            List.of(new Option("password", plugin.lang().component("settings.option_password",
                                            "Password", Map.of())),
                                    new Option("pin", plugin.lang().component("settings.option_pin",
                                            "PIN", Map.of())),
                                    new Option("slider", plugin.lang().component("settings.option_slider",
                                            "Slider", Map.of()))),
                            modeId(values.authMode()), "password"),
                    bool(plugin, "forgot_password", "settings.label_forgot_password",
                            "Forgot password button", "settings.hover_forgot_password",
                            "Shows the self-service password reset button when email is configured.",
                            values.forgot()));
        };
    }

    private static Map<String, String> collectChanges(DialogResponseView response, Values values) {
        Map<String, String> changes = new LinkedHashMap<>();
        String menu = choiceValue(response, "menu", "pre_spawn", "post_spawn");
        if (menu != null) {
            boolean pre = "pre_spawn".equals(menu);
            if (pre != values.preSpawn()) {
                changes.put("dialog.menu", Boolean.toString(pre));
            }
        }
        Boolean session = response.getBoolean("session_autologin");
        if (session != null && session != values.sessionAutologin()) {
            changes.put("auto.session_autologin", session.toString());
        }
        Boolean premium = response.getBoolean("premium_autologin");
        if (premium != null && premium != values.premiumAutologin()) {
            changes.put("auto.premium_autologin", premium.toString());
        }
        Boolean bedrock = response.getBoolean("bedrock_autologin");
        if (bedrock != null && bedrock != values.bedrockAutologin()) {
            changes.put("auto.bedrock_autologin", bedrock.toString());
        }
        String bedrockMode = choiceValue(response, "bedrock_mode", "link", "geyser");
        if (bedrockMode != null) {
            boolean geyser = "geyser".equals(bedrockMode);
            if (geyser != values.bedrockGeyser()) {
                changes.put("auto.bedrock_mode", bedrockMode);
            }
        }
        addBool(response, changes, "totp_2fa", "totp_2fa.enabled", values.totp2fa());
        addBool(response, changes, "email", "email.enabled", values.email());
        addBool(response, changes, "login_timeout", "login_timeout.enabled", values.loginTimeout());
        addBool(response, changes, "otp_attempts", "otp_attempts.enabled", values.otpAttempts());
        addBool(response, changes, "login_attempts", "login_attempts.enabled", values.loginAttempts());
        addBool(response, changes, "captcha", "captcha.enabled", values.captcha());
        addBool(response, changes, "links", "links.enabled", values.links());
        String links = choiceValue(response, "links_position", "grouped", "separated");
        if (links != null) {
            boolean separated = "separated".equals(links);
            if (separated != values.linksSeparated()) {
                changes.put("links.position", links);
            }
        }
        addBool(response, changes, "welcome_image", "welcome_image.enabled", values.welcomeImage());
        addBool(response, changes, "discord", "discord.enabled", values.discord());
        addBool(response, changes, "rule", "rule.enabled", values.rule());
        String auth = choiceValue(response, "auth_mode", "password", "pin", "slider");
        if (auth != null && !auth.equals(modeId(values.authMode()))) {
            changes.put("auth_mode.mode", auth);
        }
        addBool(response, changes, "forgot_password",
                "dialog.login.forgot_password.enabled", values.forgot());
        return changes;
    }

    private static Values readValues(AuthMeBia plugin, Session session) throws Exception {
        String text = session.snapshot.text();
        for (Map.Entry<String, String> entry : session.changes.entrySet()) {
            text = session.configFile.setScalar(text, entry.getKey(), entry.getValue());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return values(CfgSnapshot.of(plugin, yaml));
    }

    private static Values values(Cfg view) {
        return new Values(
                view.dialogPreSpawn(),
                view.sessionAutologinEnabled(),
                view.premiumAutologinEnabled(),
                view.bedrockAutologinEnabled(),
                view.bedrockAutoLoginMode() == com.authmebia.cfg.BedrockCfg.AutoLoginMode.GEYSER,
                view.totp2faEnabled(),
                view.emailEnabled(),
                view.loginTimeoutEnabled(),
                view.otpAttemptsEnabled(),
                view.loginAttemptsEnabled(),
                view.captchaEnabled(),
                view.linksEnabled(),
                view.linksLayout() == com.authmebia.dialog.shared.LinkButton.Layout.SEPARATED,
                view.welcomeImageEnabled(),
                view.discordEnabled(),
                view.ruleEnabled(),
                view.authMode(),
                view.forgotPasswordEnabled());
    }

    private static boolean permission(AuthMeBia plugin, Player player) {
        if (player.hasPermission("authmebia.settings")) return true;
        player.sendMessage(plugin.lang().component("command.no_permission",
                "You do not have permission to do that.", Map.of()));
        return false;
    }

    private static boolean reload(DialogResponseView response, boolean fallback) {
        Boolean value = response.getBoolean("reload");
        return value == null ? fallback : value;
    }

    private static void fail(AuthMeBia plugin, Player player, String key, String fallback,
                             Map<String, String> values) {
        Util.runOnMain(player, () -> {
            try {
                player.sendMessage(plugin.lang().component(key, fallback, values));
            } catch (Throwable t) {
                plugin.getLogger().warning("Settings reply failed: " + t.getMessage());
            }
        });
    }

    private static void addBool(DialogResponseView response, Map<String, String> changes,
                                String inputKey, String path, boolean current) {
        Boolean value = response.getBoolean(inputKey);
        if (value != null && value != current) {
            changes.put(path, value.toString());
        }
    }

    private static String choiceValue(DialogResponseView response, String key, String... allowed) {
        String value = response.getText(key);
        if (value == null) return null;
        for (String option : allowed) {
            if (option.equals(value)) return value;
        }
        return null;
    }

    private static String modeId(com.authmebia.dialog.Mode mode) {
        return switch (mode) {
            case PIN -> "pin";
            case SLIDER -> "slider";
            case PASSWORD -> "password";
        };
    }

    private static DialogInput bool(AuthMeBia plugin, String key, String labelKey, String labelFallback,
                                    String hoverKey, String hoverFallback, boolean initial) {
        Component label = label(plugin, labelKey, labelFallback, hoverKey, hoverFallback);
        return DialogInput.bool(key, label).initial(initial).build();
    }

    private static DialogInput choice(AuthMeBia plugin, String key, String labelKey, String labelFallback,
                                      String hoverKey, String hoverFallback, List<Option> options,
                                      String current, String defaultId) {
        String selected = current;
        boolean found = false;
        for (Option option : options) {
            if (option.id().equals(selected)) {
                found = true;
                break;
            }
        }
        if (!found) selected = defaultId;
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (Option option : options) {
            entries.add(SingleOptionDialogInput.OptionEntry.create(
                    option.id(), option.label(), option.id().equals(selected)));
        }
        Component label = label(plugin, labelKey, labelFallback, hoverKey, hoverFallback);
        return DialogInput.singleOption(key, label, entries).width(300).build();
    }

    private static Component withIcon(AuthMeBia plugin, String iconName, Component label) {
        try {
            IconSpec spec = plugin.cfg().icon(iconName);
            if (spec == null) return label;
            Component icon = spec.toInlineComponent(null);
            if (icon == null) return label;
            return icon.append(Component.space()).append(label);
        } catch (Throwable t) {
            return label;
        }
    }

    private static Component label(AuthMeBia plugin, String labelKey, String labelFallback,
                                   String hoverKey, String hoverFallback) {
        Component base = plugin.lang().component(labelKey, labelFallback, Map.of());
        Component hover = plugin.lang().component(hoverKey, hoverFallback, Map.of());
        return base.hoverEvent(HoverEvent.showText(hover));
    }

    private static void runAsync(AuthMeBia plugin, Runnable task) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    private enum Category {
        GENERAL("settings.category_general", "General", "settings_general"),
        AUTO("settings.category_auto", "Auto-login", "settings_auto"),
        SECURITY("settings.category_security", "Security", "settings_security"),
        APPEARANCE("settings.category_appearance", "Appearance & links", "settings_appearance"),
        AUTH("settings.category_auth", "Authentication", "settings_auth");

        private final String key;
        private final String fallback;
        private final String icon;

        Category(String key, String fallback, String icon) {
            this.key = key;
            this.fallback = fallback;
            this.icon = icon;
        }

        private String key() {
            return key;
        }

        private String fallback() {
            return fallback;
        }

        private String icon() {
            return icon;
        }
    }

    private record Option(String id, Component label) {}

    private static final class Session {
        private final ConfigFile configFile;
        private final ConfigFile.Snapshot snapshot;
        private final Map<String, String> changes = new LinkedHashMap<>();
        private boolean reload = true;

        private Session(ConfigFile configFile, ConfigFile.Snapshot snapshot) {
            this.configFile = configFile;
            this.snapshot = snapshot;
        }
    }

    private record Values(boolean preSpawn, boolean sessionAutologin, boolean premiumAutologin,
                          boolean bedrockAutologin, boolean bedrockGeyser, boolean totp2fa,
                          boolean email, boolean loginTimeout, boolean otpAttempts,
                          boolean loginAttempts, boolean captcha, boolean links, boolean linksSeparated,
                          boolean welcomeImage, boolean discord, boolean rule,
                          com.authmebia.dialog.Mode authMode, boolean forgot) {}
}
