package com.authmebia.cmd.debug;

import com.authmebia.AuthMe;
import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.captcha.Captcha;
import com.authmebia.dialog.emailverify.EmailVerify;
import com.authmebia.dialog.login.Login;
import com.authmebia.dialog.recover.Recover;
import com.authmebia.dialog.register.Register;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

public final class Debug {

    private Debug() {}

    public static void execute(org.bukkit.command.CommandSender sender, String feature, String rawValue,
                                AuthMeBia plugin, java.util.function.BiConsumer<Player, Runnable> runOnPlayer) {
        AuthMe authMe = plugin.authMeListener();
        Cfg cfg = plugin.cfg();
        boolean isShow = "show".equalsIgnoreCase(rawValue);
        boolean value = Boolean.parseBoolean(rawValue);

        switch (feature.toLowerCase()) {
            case "captcha" -> {
                if (isShow) {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage(Component.text("[debug] Must be a player to preview captcha GUI.", NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(Component.text("[debug] Showing captcha dialog...", NamedTextColor.YELLOW));
                    runOnPlayer.accept(p, () -> Captcha.showCaptchaIngame(p, cfg, plugin.lang(), plugin.captcha()));
                    return;
                }
                if (authMe != null) {
                    authMe.overrideCachedAuthMeCaptchaEnabled(value);
                    sender.sendMessage(Component.text(
                        "[debug] AuthMe captcha override set to " + value + ". "
                        + "AuthMeBia captcha.enabled=" + cfg.captchaEnabled() + ". "
                        + "captchaRequired() will return " + (cfg.captchaEnabled() && value),
                        NamedTextColor.YELLOW));
                } else {
                    sender.sendMessage(Component.text("[debug] AuthMe listener not available.", NamedTextColor.RED));
                }
            }
            case "email" -> {
                if (isShow) {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage(Component.text("[debug] Must be a player to preview email GUI.", NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(Component.text("[debug] Showing email verify dialog (dummy email, code: 123456)...", NamedTextColor.YELLOW));
                    runOnPlayer.accept(p, () -> EmailVerify.showEmailVerifyDebugIngame(p, cfg, plugin.lang()));
                    return;
                }
                if (authMe != null) {
                    authMe.overrideCachedEmailEnabled(value);
                    sender.sendMessage(Component.text(
                        "[debug] AuthMe email override set to " + value + ". "
                        + "AuthMeBia email.enabled=" + cfg.emailEnabled() + ". "
                        + "isEmailVerificationActive() will return " + (cfg.emailEnabled() && value),
                        NamedTextColor.YELLOW));
                } else {
                    sender.sendMessage(Component.text("[debug] AuthMe listener not available.", NamedTextColor.RED));
                }
            }
            case "register" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the register GUI.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("[debug] Showing register dialog...", NamedTextColor.YELLOW));
                Runnable show = () -> {
                    if (authMe != null) Register.showRegisterIngame(p, cfg, plugin.lang(), authMe);
                };
                runOnPlayer.accept(p, show);
            }
            case "login" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the login GUI.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("[debug] Showing login dialog...", NamedTextColor.YELLOW));
                Runnable show = () -> {
                    if (authMe != null) Login.showLoginIngame(p, cfg, plugin.lang(), authMe, plugin.ipGuard());
                };
                runOnPlayer.accept(p, show);
            }
            case "recover" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the recover GUI.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("[debug] Showing recover dialog...", NamedTextColor.YELLOW));
                Runnable show = () -> {
                    if (authMe != null) {
                        Recover.showRecoverIngame(p, cfg, authMe, () ->
                            p.sendMessage(Component.text("[debug] Recover submitted.", NamedTextColor.GREEN)));
                    }
                };
                runOnPlayer.accept(p, show);
            }
            case "rule" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the rule dialog.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text(
                    "[debug] Rule dialog content:\nTitle: " + Cfg.withPlayerContextValue(p.getName(), () -> cfg.ruleTitle(p.getName()).toString()) + "\n"
                    + "Note: rule dialog is pre-spawn only. To test fully, set dialog.menu=true and rejoin.",
                    NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text(
                "[debug] Unknown feature '" + feature + "'. Valid features: captcha, email, register, login, recover, rule",
                NamedTextColor.RED));
        }
    }
}
