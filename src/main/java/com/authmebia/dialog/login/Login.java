package com.authmebia.dialog.login;

import com.authmebia.AuthMe;
import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Dialoglib;
import com.authmebia.dialog.Mode;
import com.authmebia.dialog.forgot.Forgot;
import com.authmebia.dialog.recover.Recover;
import com.authmebia.dialog.shared.AuthInput;
import com.authmebia.dialog.twofactor.TwoFactor;
import com.authmebia.dialog.util.Util;
import com.authmebia.lang.Lang;
import com.authmebia.listeners.ipguard.IpGuard;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class Login {

    private Login() {}

    public static boolean showLoginBlocking(PlayerConfigurationConnection conn, String name, Cfg cfg, Lang lang, AuthMe authMe,
                                             com.authmebia.listeners.ipguard.IpGuard ipGuard, String ip) {
        return Cfg.withPlayerContextValue(name, () -> showLoginBlockingInner(conn, name, cfg, lang, authMe, ipGuard, ip));
    }

    private static boolean showLoginBlockingInner(PlayerConfigurationConnection conn, String name, Cfg cfg, Lang lang, AuthMe authMe,
                                             com.authmebia.listeners.ipguard.IpGuard ipGuard, String ip) {
        Mode mode = cfg.authMode();
        if (mode != Mode.PASSWORD) {
            return loginNumericBlocking(conn, name, cfg, lang, authMe, ipGuard, ip, mode);
        }

        AtomicReference<String> error = new AtomicReference<>(null);
        AtomicInteger wrongTries = new AtomicInteger(0);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            AtomicBoolean disconnect = new AtomicBoolean(false);
            AtomicBoolean kicked = new AtomicBoolean(false);

            String passLabel = error.get() != null ? cfg.loginPasswordLabel() + "  [" + error.get() + "]" : cfg.loginPasswordLabel();

            DialogActionCallback loginCb = (DialogResponseView r, Audience a) -> {
                try {
                    String pass = r.getText("password");
                    if (pass == null || pass.isBlank()) {
                        error.set(lang.errorPasswordEmpty());
                        return;
                    }
                    if (!authMe.checkPassword(name, pass)) {
                        error.set(lang.errorWrongPassword());
                        ipGuard.recordFailure(ip, AuthMeBia.get().cfg(), AuthMeBia.get().lang());

                        if (AuthMeBia.get().cfg().loginAttemptsEnabled() && wrongTries.incrementAndGet() >= AuthMeBia.get().cfg().loginMaxTries()) {
                            kicked.set(true);
                        }
                        return;
                    }
                    success.set(true);
                    ipGuard.clearFailures(ip);
                } finally {
                    latch.countDown();
                }
            };

            DialogActionCallback logoutCb = (r, a) -> {
                disconnect.set(true);
                latch.countDown();
            };

            AtomicBoolean forgotPassword = new AtomicBoolean(false);
            DialogActionCallback forgotPasswordCb = (r, a) -> {
                forgotPassword.set(true);
                latch.countDown();
            };

            List<ActionButton> loginButtons = new ArrayList<>();
            loginButtons.add(btn(cfg, cfg.submitLoginButton(), cfg.loginSubmitSound(), loginCb));
            if (cfg.forgotPasswordEnabled()) {
                loginButtons.add(btn(cfg, cfg.forgotPasswordButton(), cfg.forgotPasswordButtonSound(), forgotPasswordCb));
            }

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.loginTitle(name), cfg.loginContent(name), false,
                            List.of(
                                    DialogInput.text("password", Component.text(passLabel)).maxLength(64).width(cfg.inputWidth()).build()
                            ), DialogAfterAction.WAIT_FOR_RESPONSE))
                    .type(buildType(cfg, name, loginButtons,
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
            ));

            await(latch);
            if (kicked.get()) return false;
            if (disconnect.get()) return false;
            if (forgotPassword.get()) {
                if (!Forgot.forgotPasswordBlocking(conn, cfg, lang, authMe, name)) {
                    return false;
                }
                continue;
            }
            if (success.get()) {
                if (cfg.totp2faEnabled() && authMe.hasTotpEnabled(name)) {
                    return TwoFactor.show2FABlocking(conn, name, cfg, lang, authMe);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean loginNumericBlocking(PlayerConfigurationConnection conn, String name, Cfg cfg,
                                                Lang lang, AuthMe authMe, com.authmebia.listeners.ipguard.IpGuard ipGuard, String ip, Mode mode) {
        int tries = 0;
        String status = null;
        while (conn.isConnected()) {
            String code = mode == Mode.PIN
                    ? AuthInput.collectPinBlocking(conn, cfg, status)
                    : AuthInput.collectSliderBlocking(conn, cfg, status);
            if (code == null) return false;

            if (authMe.checkPassword(name, code)) {
                ipGuard.clearFailures(ip);
                if (cfg.totp2faEnabled() && authMe.hasTotpEnabled(name)) {
                    return TwoFactor.show2FABlocking(conn, name, cfg, lang, authMe);
                }
                return true;
            }

            status = lang.errorWrongPassword();
            ipGuard.recordFailure(ip, cfg, lang);
            if (cfg.loginAttemptsEnabled() && ++tries >= cfg.loginMaxTries()) {
                return false;
            }
        }
        return false;
    }

    public static void showLoginIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                                        com.authmebia.listeners.ipguard.IpGuard ipGuard) {
        Cfg.withPlayerContext(player.getName(), () -> {
            Mode mode = cfg.authMode();
            if (mode == Mode.PIN || mode == Mode.SLIDER) {
                showLoginNumericIngame(player, cfg, lang, authMe, ipGuard, mode, new AtomicInteger(0), null);
                return;
            }
            showLoginIngame(player, cfg, lang, authMe, ipGuard, new AtomicInteger(0));
        });
    }

    public static void showLoginInventoryFallback(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                                                    com.authmebia.listeners.ipguard.IpGuard ipGuard) {
        Cfg.withPlayerContext(player.getName(), () ->
                showLoginInventoryFallback(player, cfg, lang, authMe, ipGuard, new AtomicInteger(0), null));
    }

    private static void showLoginInventoryFallback(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                                                     com.authmebia.listeners.ipguard.IpGuard ipGuard,
                                                     AtomicInteger wrongTries, String status) {
        String name = player.getName();
        String ip = Util.ipOf(player);
        java.util.function.Consumer<String> onConfirm = code -> {
            if (!authMe.checkPassword(name, code)) {
                ipGuard.recordFailure(ip, cfg, lang);
                if (cfg.loginAttemptsEnabled() && wrongTries.incrementAndGet() >= cfg.loginMaxTries()) {
                    player.kick(lang.disconnectTooManyAttempts(name, ip));
                    return;
                }
                player.sendMessage(lang.messageWrongPassword());
                showLoginInventoryFallback(player, cfg, lang, authMe, ipGuard, wrongTries, lang.errorWrongPassword());
                return;
            }
            ipGuard.clearFailures(ip);
            if (cfg.totp2faEnabled() && authMe.hasTotpEnabled(name)) {
                TwoFactor.show2FAIngame(player, cfg, lang, authMe, () -> authMe.login(player));
            } else {
                authMe.login(player);
            }
        };
        Runnable onLogout = () -> player.kick(lang.disconnectLogout(name, ip));
        com.authmebia.dialog.shared.InventoryAuthInput.open(player, cfg, status, onConfirm, onLogout);
    }

    private static void showLoginNumericIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                                                com.authmebia.listeners.ipguard.IpGuard ipGuard,
                                                Mode mode, AtomicInteger wrongTries, String status) {
        String name = player.getName();
        String ip = Util.ipOf(player);
        java.util.function.Consumer<String> onConfirm = code -> {
            if (!authMe.checkPassword(name, code)) {
                ipGuard.recordFailure(ip, cfg, lang);
                if (cfg.loginAttemptsEnabled() && wrongTries.incrementAndGet() >= cfg.loginMaxTries()) {
                    player.kick(lang.disconnectTooManyAttempts(name, ip));
                    return;
                }
                player.sendMessage(lang.messageWrongPassword());
                showLoginNumericIngame(player, cfg, lang, authMe, ipGuard, mode, wrongTries, lang.errorWrongPassword());
                return;
            }
            ipGuard.clearFailures(ip);
            if (cfg.totp2faEnabled() && authMe.hasTotpEnabled(name)) {
                TwoFactor.show2FAIngame(player, cfg, lang, authMe, () -> authMe.login(player));
            } else {
                authMe.login(player);
            }
        };
        Runnable onLogout = () -> player.kick(lang.disconnectLogout(name, ip));
        if (mode == Mode.PIN) {
            AuthInput.showPinIngame(player, cfg, status, onConfirm, onLogout);
        } else {
            AuthInput.showSliderIngame(player, cfg, status, onConfirm, onLogout);
        }
    }

    public static void showLoginIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                                        com.authmebia.listeners.ipguard.IpGuard ipGuard, AtomicInteger wrongTries) {
        try {
            String playerName = player.getName();
            String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : null;

            DialogActionCallback logoutCb = (r, a) -> {
                if (a instanceof Player p) {
                    Dialoglib.clearEscapeGuard(p.getUniqueId());
                    p.kick(lang.disconnectLogout(p.getName(), ip));
                }
            };

            List<ActionButton> loginButtons = new ArrayList<>();
            loginButtons.add(btn(cfg, cfg.submitLoginButton(), cfg.loginSubmitSound(), (r, a) -> {
                if (!(a instanceof Player p)) return;
                String pass = r.getText("password");
                if (pass == null || pass.isBlank() || !authMe.checkPassword(p.getName(), pass)) {
                    ipGuard.recordFailure(ip, cfg, lang);
                    if (cfg.loginAttemptsEnabled() && wrongTries.incrementAndGet() >= cfg.loginMaxTries()) {
                        p.kick(lang.disconnectTooManyAttempts(p.getName(), ip));
                        return;
                    }
                    p.sendMessage(lang.messageWrongPassword());
                    showLoginIngame(p, cfg, lang, authMe, ipGuard, wrongTries);
                    return;
                }
                ipGuard.clearFailures(ip);
                Dialoglib.clearEscapeGuard(p.getUniqueId());
                if (cfg.totp2faEnabled() && authMe.hasTotpEnabled(p.getName())) {
                    TwoFactor.show2FAIngame(p, cfg, lang, authMe, () -> authMe.login(p));
                } else {
                    authMe.login(p);
                }
            }));
            if (cfg.forgotPasswordEnabled()) {
                loginButtons.add(btn(cfg, cfg.forgotPasswordButton(), cfg.forgotPasswordButtonSound(), (r, a) -> {
                    if (!(a instanceof Player p)) return;
                    // Disarm before leaving: the Forgot Password sub-flow is
                    // also legitimately unauthenticated for a while, and
                    // Forgot.java arms its own guard on its own dialogs, so
                    // leaving this one armed would let its delayed check
                    // yank the player back to the login dialog mid-flow.
                    Dialoglib.clearEscapeGuard(p.getUniqueId());
                    Cfg.withPlayerContext(p.getName(), () -> Forgot.forgotPasswordIngame(p, cfg, lang, authMe, ipGuard, wrongTries));
                }));
            }

            boolean allowClose = cfg.dialogAllowClose();
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.loginTitle(playerName), cfg.loginContent(playerName), allowClose,
                            List.of(
                                    DialogInput.text("password", Component.text(cfg.loginPasswordLabel())).maxLength(64).width(cfg.inputWidth()).build()
                            ), DialogAfterAction.WAIT_FOR_RESPONSE))
                    .type(buildType(cfg, playerName, loginButtons,
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
            ));

            Dialoglib.escapeGuard(player, !allowClose, cfg.dialogReopenDelayTicks(),
                    () -> player.isOnline() && !authMe.isAuthenticated(player),
                    () -> showLoginIngame(player, cfg, lang, authMe, ipGuard, wrongTries));
        } catch (NoClassDefFoundError ignored) {}
    }
}
