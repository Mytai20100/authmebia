package com.authmebia.dialog.forgot;

import com.authmebia.AuthMe;
import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Dialoglib;
import com.authmebia.dialog.recover.Recover;
import com.authmebia.dialog.util.Util;
import com.authmebia.lang.Lang;
import com.authmebia.listeners.ipguard.IpGuard;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class Forgot {

    private static final Map<UUID, ForgotPasswordSession> FORGOT_PASSWORD_SESSIONS = new ConcurrentHashMap<>();

    private static final class ForgotPasswordSession {
        String email;
        String code;
        long lastSent;
    }

    private Forgot() {}

    public static void clearSession(UUID uuid) {
        FORGOT_PASSWORD_SESSIONS.remove(uuid);
    }

    // --- Blocking versions (called from Login) ---

    public static boolean forgotPasswordBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang, AuthMe authMe, String name) {
        String storedEmail = authMe.getStoredEmail(name);
        if (storedEmail == null || storedEmail.isBlank()) {
            return forgotPasswordNoEmailBlocking(conn, cfg, lang, name);
        }

        String enteredEmail = forgotPasswordEmailBlocking(conn, cfg, lang, name, storedEmail);
        if (enteredEmail == null) return false;

        if (!forgotPasswordVerifyBlocking(conn, cfg, lang, authMe, name, enteredEmail)) {
            return false;
        }

        String newPass = Recover.showRecoverBlocking(conn, cfg);
        if (newPass == null) return false;
        authMe.changePassword(name, newPass);
        return true;
    }

    private static boolean forgotPasswordNoEmailBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang, String name) {
        if (!conn.isConnected()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean disconnect = new AtomicBoolean(false);

        DialogActionCallback backCb = (r, a) -> latch.countDown();
        DialogActionCallback logoutCb = (r, a) -> { disconnect.set(true); latch.countDown(); };

        conn.getAudience().showDialog(Dialog.create(d -> d
                .empty()
                .base(buildBase(cfg.forgotPasswordEmailTitle(), cfg.forgotPasswordNoEmailMessage(), false, List.of()))
                .type(buildType(cfg, name,
                        List.of(btn(cfg, cfg.submitLoginButton(), cfg.loginSubmitSound(), backCb)),
                        btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
        ));

        await(latch);
        return !disconnect.get();
    }

    private static String forgotPasswordEmailBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang, String name, String storedEmail) {
        AtomicReference<String> error = new AtomicReference<>(null);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> matched = new AtomicReference<>(null);
            AtomicBoolean disconnect = new AtomicBoolean(false);

            String emailLabel = error.get() != null
                    ? cfg.forgotPasswordEmailLabel() + "  [" + error.get() + "]"
                    : cfg.forgotPasswordEmailLabel();

            DialogActionCallback submit = (DialogResponseView r, Audience a) -> {
                try {
                    String typed = r.getText("email");
                    if (typed == null || !Util.isValidEmail(typed.trim()) || !storedEmail.equalsIgnoreCase(typed.trim())) {
                        error.set(cfg.forgotPasswordInvalidEmailError());
                        return;
                    }
                    error.set(null);
                    matched.set(typed.trim());
                } finally {
                    latch.countDown();
                }
            };

            DialogActionCallback logoutCb = (r, a) -> { disconnect.set(true); latch.countDown(); };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.forgotPasswordEmailTitle(), cfg.forgotPasswordEmailContent(), false,
                            List.of(DialogInput.text("email", Component.text(emailLabel)).maxLength(254).width(cfg.inputWidth()).build())))
                    .type(buildType(cfg, name,
                            List.of(btn(cfg, cfg.forgotPasswordSubmitButton(), cfg.forgotPasswordSubmitSound(), submit)),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
            ));

            await(latch);
            if (disconnect.get()) return null;
            if (matched.get() != null) return matched.get();
        }
        return null;
    }

    private static boolean forgotPasswordVerifyBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang, AuthMe authMe, String name, String email) {
        AtomicReference<String> codeRef = new AtomicReference<>(Util.genNumericCode(cfg.emailCodeLength()));
        if (!authMe.sendVerificationEmail(name, email, codeRef.get())) {
            AuthMeBia.get().getLogger().warning("Failed to email forgot-password code to " + email + " for " + name);
            return false;
        }
        long[] lastSent = { System.currentTimeMillis() };
        AtomicReference<String> error = new AtomicReference<>(null);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean verified = new AtomicBoolean(false);
            AtomicBoolean logout = new AtomicBoolean(false);
            AtomicBoolean resend = new AtomicBoolean(false);

            int remaining = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - lastSent[0]) / 1000);
            String codeLabel = error.get() != null ? cfg.emailCodeLabel() + "  [" + error.get() + "]" : cfg.emailCodeLabel();

            DialogActionCallback verifyCb = (r, a) -> {
                try {
                    String typed = r.getText("code");
                    if (codeRef.get().equals(typed == null ? null : typed.trim())) {
                        verified.set(true);
                    } else {
                        error.set(cfg.emailWrongCodeError());
                    }
                } finally {
                    latch.countDown();
                }
            };
            DialogActionCallback resendCb = (r, a) -> { resend.set(true); latch.countDown(); };
            DialogActionCallback logoutCb = (r, a) -> { logout.set(true); latch.countDown(); };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.emailVerifyTitle(), cfg.emailVerifyContent(email, remaining), false,
                            List.of(DialogInput.text("code", Component.text(codeLabel)).maxLength(16).width(cfg.inputWidth()).build())))
                    .type(buildType(cfg, name,
                            List.of(btn(cfg, cfg.emailVerifyButton(), cfg.emailVerifySound(), verifyCb),
                                    resendBtn(cfg, remaining, resendCb)),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
            ));

            await(latch);
            if (logout.get()) return false;
            if (verified.get()) return true;
            if (resend.get()) {
                int rem = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - lastSent[0]) / 1000);
                if (rem <= 0) {
                    codeRef.set(Util.genNumericCode(cfg.emailCodeLength()));
                    authMe.sendVerificationEmail(name, email, codeRef.get());
                    lastSent[0] = System.currentTimeMillis();
                    error.set(null);
                }
            }
        }
        return false;
    }

    // --- In-game versions ---

    public static void forgotPasswordIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard, AtomicInteger wrongTries) {
        String name = player.getName();
        String storedEmail = authMe.getStoredEmail(name);
        if (storedEmail == null || storedEmail.isBlank()) {
            forgotPasswordNoEmailIngame(player, cfg, lang, authMe, ipGuard, wrongTries);
            return;
        }
        forgotPasswordEmailIngame(player, cfg, lang, authMe, ipGuard, wrongTries, storedEmail, null);
    }

    private static void forgotPasswordNoEmailIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard, AtomicInteger wrongTries) {
        try {
            String ip = Util.ipOf(player);
            boolean allowClose = cfg.dialogAllowClose();
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.forgotPasswordEmailTitle(), cfg.forgotPasswordNoEmailMessage(), allowClose, List.of()))
                    .type(buildType(cfg, player.getName(),
                            List.of(btn(cfg, cfg.submitLoginButton(), cfg.loginSubmitSound(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                Dialoglib.clearEscapeGuard(p.getUniqueId());
                                com.authmebia.dialog.login.Login.showLoginIngame(p, cfg, lang, authMe, ipGuard, wrongTries);
                            })),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), (r, a) -> {
                                if (a instanceof Player p) {
                                    Dialoglib.clearEscapeGuard(p.getUniqueId());
                                    p.kick(lang.disconnectLogout(p.getName(), ip));
                                }
                            })))
            ));
            // See Dialoglib.escapeGuard() for the mechanism and its limits.
            // isStillPending here just re-checks the player is still
            // unauthenticated -- this dialog itself has no other state to
            // track (it is a dead-end notice, not a multi-step form).
            Dialoglib.escapeGuard(player, !allowClose, cfg.dialogReopenDelayTicks(),
                    () -> player.isOnline() && !authMe.isAuthenticated(player),
                    () -> forgotPasswordNoEmailIngame(player, cfg, lang, authMe, ipGuard, wrongTries));
        } catch (NoClassDefFoundError ignored) {}
    }

    private static void forgotPasswordEmailIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                                                   IpGuard ipGuard, AtomicInteger wrongTries, String storedEmail, String error) {
        try {
            String name = player.getName();
            String ip = Util.ipOf(player);
            String emailLabel = error != null
                    ? cfg.forgotPasswordEmailLabel() + "  [" + error + "]"
                    : cfg.forgotPasswordEmailLabel();

            boolean allowClose = cfg.dialogAllowClose();
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.forgotPasswordEmailTitle(), cfg.forgotPasswordEmailContent(), allowClose,
                            List.of(DialogInput.text("email", Component.text(emailLabel)).maxLength(254).width(cfg.inputWidth()).build())))
                    .type(buildType(cfg, name,
                            List.of(btn(cfg, cfg.forgotPasswordSubmitButton(), cfg.forgotPasswordSubmitSound(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String typed = r.getText("email");
                                if (typed == null || !Util.isValidEmail(typed.trim()) || !storedEmail.equalsIgnoreCase(typed.trim())) {
                                    forgotPasswordEmailIngame(p, cfg, lang, authMe, ipGuard, wrongTries, storedEmail, cfg.forgotPasswordInvalidEmailError());
                                    return;
                                }
                                Dialoglib.clearEscapeGuard(p.getUniqueId());
                                startForgotPasswordVerifyIngame(p, cfg, lang, authMe, ipGuard, wrongTries, typed.trim());
                            })),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), (r, a) -> {
                                if (a instanceof Player p) {
                                    Dialoglib.clearEscapeGuard(p.getUniqueId());
                                    p.kick(lang.disconnectLogout(p.getName(), ip));
                                }
                            })))
            ));
            Dialoglib.escapeGuard(player, !allowClose, cfg.dialogReopenDelayTicks(),
                    () -> player.isOnline() && !authMe.isAuthenticated(player),
                    () -> forgotPasswordEmailIngame(player, cfg, lang, authMe, ipGuard, wrongTries, storedEmail, error));
        } catch (NoClassDefFoundError ignored) {}
    }

    private static void startForgotPasswordVerifyIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                                                         IpGuard ipGuard, AtomicInteger wrongTries, String email) {
        ForgotPasswordSession s = new ForgotPasswordSession();
        s.email = email;
        s.code = Util.genNumericCode(cfg.emailCodeLength());
        s.lastSent = System.currentTimeMillis();
        FORGOT_PASSWORD_SESSIONS.put(player.getUniqueId(), s);
        authMe.runAsync(() -> {
            boolean sent = authMe.sendVerificationEmail(player.getName(), email, s.code);
            Util.runOnMain(player, () -> {
                if (sent) {
                    showForgotPasswordVerifyIngame(player, cfg, lang, authMe, ipGuard, wrongTries, null);
                } else {
                    FORGOT_PASSWORD_SESSIONS.remove(player.getUniqueId());
                    player.sendMessage(cfg.emailSendFailedMessage());
                    com.authmebia.dialog.login.Login.showLoginIngame(player, cfg, lang, authMe, ipGuard, wrongTries);
                }
            });
        });
    }

    private static void showForgotPasswordVerifyIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                                                        IpGuard ipGuard, AtomicInteger wrongTries, String codeErr) {
        UUID uuid = player.getUniqueId();
        ForgotPasswordSession s = FORGOT_PASSWORD_SESSIONS.get(uuid);
        if (s == null) return;
        int remaining = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - s.lastSent) / 1000);
        String codeLabel = codeErr != null ? cfg.emailCodeLabel() + "  [" + codeErr + "]" : cfg.emailCodeLabel();
        String ip = Util.ipOf(player);

        DialogActionCallback resendCb = (r, a) -> {
            if (!(a instanceof Player p)) return;
            int rem = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - s.lastSent) / 1000);
            if (rem <= 0) {
                s.code = Util.genNumericCode(cfg.emailCodeLength());
                s.lastSent = System.currentTimeMillis();
                authMe.runAsync(() -> authMe.sendVerificationEmail(p.getName(), s.email, s.code));
            }
            showForgotPasswordVerifyIngame(p, cfg, lang, authMe, ipGuard, wrongTries, null);
        };

        try {
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.emailVerifyTitle(), cfg.emailVerifyContent(s.email, remaining), false,
                            List.of(DialogInput.text("code", Component.text(codeLabel)).maxLength(16).width(cfg.inputWidth()).build())))
                    .type(buildType(cfg, player.getName(),
                            List.of(
                                    btn(cfg, cfg.emailVerifyButton(), cfg.emailVerifySound(), (r, a) -> {
                                        if (!(a instanceof Player p)) return;
                                        String typed = r.getText("code");
                                        if (s.code.equals(typed == null ? null : typed.trim())) {
                                            FORGOT_PASSWORD_SESSIONS.remove(uuid);
                                            Recover.showRecoverIngame(p, cfg, authMe, () -> com.authmebia.dialog.login.Login.showLoginIngame(p, cfg, lang, authMe, ipGuard, wrongTries));
                                        } else {
                                            showForgotPasswordVerifyIngame(p, cfg, lang, authMe, ipGuard, wrongTries, cfg.emailWrongCodeError());
                                        }
                                    }),
                                    resendBtn(cfg, remaining, resendCb)),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), (r, a) -> {
                                FORGOT_PASSWORD_SESSIONS.remove(uuid);
                                if (a instanceof Player p) p.kick(lang.disconnectLogout(p.getName(), ip));
                            })))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }
}
