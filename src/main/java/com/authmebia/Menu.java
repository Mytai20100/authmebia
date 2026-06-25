package com.authmebia;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("UnstableApiUsage")
public class Menu {

    private static final long DIALOG_AWAIT_SECONDS = 30L;

    private Menu() {}

    public static boolean showCaptchaBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang, Captcha captcha) {
        AtomicReference<String> code = new AtomicReference<>(captcha.generate(cfg.captchaLength()));
        AtomicReference<String> error = new AtomicReference<>(null);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean verified = new AtomicBoolean(false);

            String inputLabel = error.get() != null
                    ? cfg.captchaInputLabel() + "  [" + error.get() + "]"
                    : cfg.captchaInputLabel();

            DialogActionCallback submit = (DialogResponseView r, Audience a) -> {
                try {
                    String typed = r.getText("code");
                    if (captcha.matches(typed, code.get())) {
                        verified.set(true);
                        error.set(null);
                    } else {
                        error.set(lang.errorCaptchaIncorrect());
                        code.set(captcha.generate(cfg.captchaLength()));
                    }
                } finally {
                    latch.countDown();
                }
            };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.captchaTitle(), cfg.captchaContent(code.get()), false,
                            List.of(
                                    DialogInput.text("code", Component.text(inputLabel)).maxLength(16).width(cfg.inputWidth()).build()
                            )))
                    .type(DialogType.multiAction(
                            List.of(btn(cfg, cfg.captchaSubmitButton(), submit)),
                            null, 1))
            ));

            await(latch);
            if (verified.get()) return true;
        }
        return false;
    }

    public static String showRegisterBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang) {
        AuthMode mode = cfg.authMode();
        if (mode == AuthMode.PIN) return AuthInput.collectPinBlocking(conn, cfg, null);
        if (mode == AuthMode.SLIDER) return AuthInput.collectSliderBlocking(conn, cfg, null);

        AuthMe authMe = AuthMeBia.get().authMeListener();
        boolean emailActive = authMe != null && authMe.isEmailVerificationActive();

        AtomicReference<String> error = new AtomicReference<>(null);
        AtomicReference<String> emailErr = new AtomicReference<>(null);
        String playerName = conn.getProfile().getName();
        java.util.UUID uuid = conn.getProfile().getId();

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>(null);
            AtomicReference<String> emailRef = new AtomicReference<>(null);
            AtomicBoolean disconnect = new AtomicBoolean(false);

            String passLabel = error.get() != null ? "Password  [" + error.get() + "]" : "Password";
            String emailLabel = emailErr.get() != null
                    ? cfg.emailFieldLabel() + "  [" + emailErr.get() + "]" : cfg.emailFieldLabel();

            DialogActionCallback submit = (DialogResponseView r, Audience a) -> {
                try {
                    String pass = r.getText("password");
                    String confirm = r.getText("confirm");
                    if (pass == null || pass.isBlank()) {
                        error.set(lang.errorPasswordEmpty());
                        return;
                    }
                    if (!pass.equals(confirm)) {
                        error.set(lang.errorPasswordsMismatch());
                        return;
                    }
                    if (emailActive) {
                        String email = r.getText("email");
                        if (email == null || !isValidEmail(email.trim())) {
                            emailErr.set("invalid");
                            error.set(null);
                            return;
                        }
                        emailRef.set(email.trim());
                    }
                    error.set(null);
                    emailErr.set(null);
                    result.set(pass);
                } finally {
                    latch.countDown();
                }
            };

            DialogActionCallback logoutCb = (r, a) -> {
                disconnect.set(true);
                latch.countDown();
            };

            List<DialogInput> inputs = new ArrayList<>();
            inputs.add(DialogInput.text("password", Component.text(passLabel)).maxLength(64).width(cfg.inputWidth()).build());
            inputs.add(DialogInput.text("confirm", Component.text("Confirm Password")).maxLength(64).width(cfg.inputWidth()).build());
            if (emailActive) {
                inputs.add(DialogInput.text("email", Component.text(emailLabel)).maxLength(254).width(cfg.inputWidth()).build());
            }

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.registerTitle(playerName), cfg.registerContent(playerName), false, inputs))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.submitRegisterButton(), submit)),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));

            await(latch);
            if (disconnect.get()) return null;
            if (result.get() != null) {
                if (!emailActive) return result.get();
                if (!verifyEmailBlocking(conn, cfg, playerName, emailRef.get())) {
                    return null;
                }
                if (uuid != null) authMe.pendingEmail.put(uuid, emailRef.get());
                return result.get();
            }
        }
        return null;
    }

    private static boolean verifyEmailBlocking(PlayerConfigurationConnection conn, Cfg cfg, String name, String email) {
        AuthMe authMe = AuthMeBia.get().authMeListener();
        AtomicReference<String> codeRef = new AtomicReference<>(genNumericCode(cfg.emailCodeLength()));
        if (!authMe.sendVerificationEmail(name, email, codeRef.get())) {
            AuthMeBia.get().getLogger().warning("Failed to email verification code to " + email + " for " + name);
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
                            List.of(btn(cfg, cfg.emailVerifyButton(), verifyCb), btn(cfg, cfg.emailResendButton(), resendCb)),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));

            await(latch);
            if (logout.get()) return false;
            if (verified.get()) return true;
            if (resend.get()) {
                int rem = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - lastSent[0]) / 1000);
                if (rem <= 0) {
                    codeRef.set(genNumericCode(cfg.emailCodeLength()));
                    authMe.sendVerificationEmail(name, email, codeRef.get());
                    lastSent[0] = System.currentTimeMillis();
                    error.set(null);
                }
            }
        }
        return false;
    }

    static boolean isValidEmail(String email) {
        return email != null && email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }

    private static final java.security.SecureRandom CODE_RANDOM = new java.security.SecureRandom();

    static String genNumericCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(CODE_RANDOM.nextInt(10));
        return sb.toString();
    }

    public static boolean showLoginBlocking(PlayerConfigurationConnection conn, String name, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard, String ip) {
        AuthMode mode = cfg.authMode();
        if (mode != AuthMode.PASSWORD) {
            return loginNumericBlocking(conn, name, cfg, lang, authMe, ipGuard, ip, mode);
        }

        AtomicReference<String> error = new AtomicReference<>(null);
        AtomicInteger wrongTries = new AtomicInteger(0);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            AtomicBoolean disconnect = new AtomicBoolean(false);
            AtomicBoolean kicked = new AtomicBoolean(false);

            String passLabel = error.get() != null ? "Password  [" + error.get() + "]" : "Password";

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

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.loginTitle(name), cfg.loginContent(name), false,
                            List.of(
                                    DialogInput.text("password", Component.text(passLabel)).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, name,
                            List.of(btn(cfg, cfg.submitLoginButton(), loginCb)),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));

            await(latch);
            if (kicked.get()) return false;
            if (disconnect.get()) return false;
            if (success.get()) return true;
        }
        return false;
    }

    private static boolean loginNumericBlocking(PlayerConfigurationConnection conn, String name, Cfg cfg,
                                                Lang lang, AuthMe authMe, IpGuard ipGuard, String ip, AuthMode mode) {
        int tries = 0;
        String status = null;
        while (conn.isConnected()) {
            String code = mode == AuthMode.PIN
                    ? AuthInput.collectPinBlocking(conn, cfg, status)
                    : AuthInput.collectSliderBlocking(conn, cfg, status);
            if (code == null) return false; // logout / disconnect

            if (authMe.checkPassword(name, code)) {
                ipGuard.clearFailures(ip);
                return true;
            }

            status = lang.errorWrongPassword();
            ipGuard.recordFailure(ip, cfg, lang);
            if (cfg.loginAttemptsEnabled() && ++tries >= cfg.loginMaxTries()) {
                return false; // caller disconnects with the login-failed reason
            }
        }
        return false;
    }

    public static String showRecoverBlocking(PlayerConfigurationConnection conn, Cfg cfg) {
        String playerName = conn.getProfile().getName();
        AtomicReference<String> error = new AtomicReference<>(null);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>(null);
            AtomicBoolean disconnect = new AtomicBoolean(false);

            String newLabel = error.get() != null
                    ? cfg.recoverNewPasswordLabel() + "  [" + error.get() + "]"
                    : cfg.recoverNewPasswordLabel();

            DialogActionCallback submit = (DialogResponseView r, Audience a) -> {
                try {
                    String pass = r.getText("new_password");
                    String confirm = r.getText("confirm_password");
                    if (pass == null || pass.isBlank()) {
                        error.set("empty");
                        return;
                    }
                    if (!pass.equals(confirm)) {
                        error.set("mismatch");
                        return;
                    }
                    error.set(null);
                    result.set(pass);
                } finally {
                    latch.countDown();
                }
            };

            DialogActionCallback logoutCb = (r, a) -> { disconnect.set(true); latch.countDown(); };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.recoverTitle(), cfg.recoverContent(), false,
                            List.of(
                                    DialogInput.text("new_password", Component.text(newLabel)).maxLength(64).width(cfg.inputWidth()).build(),
                                    DialogInput.text("confirm_password", Component.text(cfg.recoverConfirmPasswordLabel())).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.recoverSubmitButton(), submit)),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));

            await(latch);
            if (disconnect.get()) return null;
            if (result.get() != null) return result.get();
        }
        return null;
    }

    public static boolean showRuleBlocking(PlayerConfigurationConnection conn, Cfg cfg) {
        String playerName = conn.getProfile().getName();

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean agreed = new AtomicBoolean(false);

            DialogActionCallback agreeCb = (DialogResponseView r, Audience a) -> {
                try {
                    Boolean checked = r.getBoolean("agree");
                    if (Boolean.TRUE.equals(checked)) {
                        agreed.set(true);
                    }
                } finally {
                    latch.countDown();
                }
            };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.ruleTitle(playerName), cfg.ruleContent(playerName), false,
                            List.of(
                                    DialogInput.bool("agree", cfg.ruleCheckboxLabel())
                                            .initial(false)
                                            .build()
                            )))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.ruleAgreeButton(), agreeCb)),
                            null))
            ));

            await(latch);
            if (agreed.get()) return true;
        }
        return false;
    }

    public static void showRegisterIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe) {
        AuthMode mode = cfg.authMode();
        if (mode == AuthMode.PIN || mode == AuthMode.SLIDER) {
            showRegisterNumericIngame(player, cfg, lang, authMe, mode);
            return;
        }
        showRegisterIngame(player, cfg, lang, authMe, new AtomicReference<>(null));
    }

    private static void showRegisterNumericIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, AuthMode mode) {
        String name = player.getName();
        String ip = ipOf(player);
        // PIN/slider register goes through registerAndLoginNumeric so AuthMe's
        // password-length validation is bypassed for the short numeric code.
        java.util.function.Consumer<String> onConfirm =
                code -> authMe.runAsync(() -> authMe.registerAndLoginNumeric(player, code));
        Runnable onLogout = () -> player.kick(lang.disconnectLogout(name, ip));
        if (mode == AuthMode.PIN) {
            AuthInput.showPinIngame(player, cfg, null, onConfirm, onLogout);
        } else {
            AuthInput.showSliderIngame(player, cfg, null, onConfirm, onLogout);
        }
    }

    private static void showRegisterIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, AtomicReference<String> lastError) {
        try {
            String playerName = player.getName();
            String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : null;
            boolean emailActive = authMe.isEmailVerificationActive();

            DialogActionCallback logoutCb = (r, a) -> {
                if (a instanceof Player p) p.kick(lang.disconnectLogout(p.getName(), ip));
            };

            String passLabel = lastError.get() != null ? "Password  [" + lastError.get() + "]" : "Password";

            List<DialogInput> inputs = new ArrayList<>();
            inputs.add(DialogInput.text("password", Component.text(passLabel)).maxLength(64).width(cfg.inputWidth()).build());
            inputs.add(DialogInput.text("confirm", Component.text("Confirm Password")).maxLength(64).width(cfg.inputWidth()).build());
            if (emailActive) {
                inputs.add(DialogInput.text("email", Component.text(cfg.emailFieldLabel())).maxLength(254).width(cfg.inputWidth()).build());
            }

            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.registerTitle(playerName), cfg.registerContent(playerName), cfg.dialogAllowClose(), inputs))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.submitRegisterButton(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String pass = r.getText("password");
                                String confirm = r.getText("confirm");
                                if (pass == null || pass.isBlank()) {
                                    lastError.set(lang.errorPasswordEmpty());
                                    showRegisterIngame(p, cfg, lang, authMe, lastError);
                                    return;
                                }
                                if (!pass.equals(confirm)) {
                                    lastError.set(lang.errorPasswordsMismatch());
                                    showRegisterIngame(p, cfg, lang, authMe, lastError);
                                    return;
                                }
                                if (emailActive) {
                                    String email = r.getText("email");
                                    if (email == null || !isValidEmail(email.trim())) {
                                        p.sendMessage(cfg.emailInvalidEmailMessage());
                                        showRegisterIngame(p, cfg, lang, authMe, lastError);
                                        return;
                                    }
                                    startEmailVerifyIngame(p, cfg, lang, authMe, email.trim(), pass);
                                    return;
                                }
                                // Runs on the main thread; push the AuthMe
                                // register+await off-tick to avoid freezing it.
                                authMe.runAsync(() -> authMe.registerAndLogin(p, pass));
                            })),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }

    private static final java.util.Map<java.util.UUID, EmailSession> EMAIL_SESSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class EmailSession {
        String email;
        String password;
        String code;
        long lastSent;
    }

    public static void clearEmailSession(java.util.UUID uuid) {
        EMAIL_SESSIONS.remove(uuid);
    }

    private static void startEmailVerifyIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, String email, String password) {
        EmailSession s = new EmailSession();
        s.email = email;
        s.password = password;
        s.code = genNumericCode(cfg.emailCodeLength());
        s.lastSent = System.currentTimeMillis();
        EMAIL_SESSIONS.put(player.getUniqueId(), s);
        authMe.runAsync(() -> {
            boolean sent = authMe.sendVerificationEmail(player.getName(), email, s.code);
            runOnMain(player, () -> {
                if (sent) {
                    showEmailVerifyIngame(player, cfg, lang, authMe, null);
                } else {
                    EMAIL_SESSIONS.remove(player.getUniqueId());
                    player.sendMessage(cfg.emailSendFailedMessage());
                    showRegisterIngame(player, cfg, lang, authMe, new AtomicReference<>(null));
                }
            });
        });
    }

    private static void showEmailVerifyIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, String codeErr) {
        java.util.UUID uuid = player.getUniqueId();
        EmailSession s = EMAIL_SESSIONS.get(uuid);
        if (s == null) return; // session cleared (quit / completed)
        int remaining = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - s.lastSent) / 1000);
        String codeLabel = codeErr != null ? cfg.emailCodeLabel() + "  [" + codeErr + "]" : cfg.emailCodeLabel();
        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress() : null;
        try {
            player.showDialog(Dialog.create(d -> d
                    .empty()
                                    .base(buildBase(cfg.emailVerifyTitle(), cfg.emailVerifyContent(s.email, remaining), false,
                            List.of(DialogInput.text("code", Component.text(codeLabel)).maxLength(16).width(cfg.inputWidth()).build())))
                    .type(buildType(cfg, player.getName(),
                            List.of(
                                    btn(cfg, cfg.emailVerifyButton(), (r, a) -> {
                                        if (!(a instanceof Player p)) return;
                                        String typed = r.getText("code");
                                        if (s.code.equals(typed == null ? null : typed.trim())) {
                                            EMAIL_SESSIONS.remove(uuid);
                                            authMe.pendingEmail.put(uuid, s.email);
                                            authMe.runAsync(() -> authMe.registerAndLogin(p, s.password));
                                        } else {
                                            showEmailVerifyIngame(p, cfg, lang, authMe, cfg.emailWrongCodeError());
                                        }
                                    }),
                                    btn(cfg, cfg.emailResendButton(), (r, a) -> {
                                        if (!(a instanceof Player p)) return;
                                        int rem = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - s.lastSent) / 1000);
                                        if (rem <= 0) {
                                            s.code = genNumericCode(cfg.emailCodeLength());
                                            s.lastSent = System.currentTimeMillis();
                                            authMe.runAsync(() -> authMe.sendVerificationEmail(p.getName(), s.email, s.code));
                                        }
                                        showEmailVerifyIngame(p, cfg, lang, authMe, null);
                                    })),
                            btn(cfg, cfg.logoutButton(), (r, a) -> {
                                EMAIL_SESSIONS.remove(uuid);
                                if (a instanceof Player p) p.kick(lang.disconnectLogout(p.getName(), ip));
                            })))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }

    public static void showLoginIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard) {
        AuthMode mode = cfg.authMode();
        if (mode == AuthMode.PIN || mode == AuthMode.SLIDER) {
            showLoginNumericIngame(player, cfg, lang, authMe, ipGuard, mode, new AtomicInteger(0), null);
            return;
        }
        showLoginIngame(player, cfg, lang, authMe, ipGuard, new AtomicInteger(0));
    }

    private static void showLoginNumericIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard,
                                               AuthMode mode, AtomicInteger wrongTries, String status) {
        String name = player.getName();
        String ip = ipOf(player);
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
            authMe.login(player);
        };
        Runnable onLogout = () -> player.kick(lang.disconnectLogout(name, ip));
        if (mode == AuthMode.PIN) {
            AuthInput.showPinIngame(player, cfg, status, onConfirm, onLogout);
        } else {
            AuthInput.showSliderIngame(player, cfg, status, onConfirm, onLogout);
        }
    }

    public static void showRecoverIngame(Player player, Cfg cfg, AuthMe authMe, Runnable onSuccess) {
        showRecoverIngame(player, cfg, authMe, onSuccess, false);
    }

    private static void showRecoverIngame(Player player, Cfg cfg, AuthMe authMe, Runnable onSuccess, boolean mismatch) {
        try {
            String newLabel = mismatch
                    ? cfg.recoverNewPasswordLabel() + "  [mismatch]"
                    : cfg.recoverNewPasswordLabel();
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.recoverTitle(), cfg.recoverContent(), false,
                            List.of(
                                    DialogInput.text("new_password", Component.text(newLabel)).maxLength(64).width(cfg.inputWidth()).build(),
                                    DialogInput.text("confirm_password", Component.text(cfg.recoverConfirmPasswordLabel())).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, player.getName(),
                            List.of(btn(cfg, cfg.recoverSubmitButton(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String pass = r.getText("new_password");
                                String confirm = r.getText("confirm_password");
                                if (pass == null || pass.isBlank() || !pass.equals(confirm)) {
                                    p.sendMessage(cfg.recoverMismatchMessage());
                                    showRecoverIngame(p, cfg, authMe, onSuccess, true);
                                    return;
                                }
                                authMe.runAsync(() -> {
                                    authMe.changePassword(p.getName(), pass);
                                    runOnMain(p, () -> {
                                        p.sendMessage(cfg.recoverSuccessMessage(p.getName()));
                                        onSuccess.run();
                                    });
                                });
                            })),
                            null))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }

    private static void runOnMain(Player player, Runnable task) {
        if (AuthMeBia.get().isFolia()) {
            player.getScheduler().run(AuthMeBia.get(), t -> task.run(), null);
        } else {
            AuthMeBia.get().getServer().getScheduler().runTask(AuthMeBia.get(), task);
        }
    }

    private static String ipOf(Player player) {
        return player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress() : null;
    }

    private static void showLoginIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard, AtomicInteger wrongTries) {
        try {
            String playerName = player.getName();
            String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : null;

            DialogActionCallback logoutCb = (r, a) -> {
                if (a instanceof Player p) p.kick(lang.disconnectLogout(p.getName(), ip));
            };

            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.loginTitle(playerName), cfg.loginContent(playerName), cfg.dialogAllowClose(),
                            List.of(
                                    DialogInput.text("password", Component.text("Password")).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.submitLoginButton(), (r, a) -> {
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
                                authMe.login(p);
                            })),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }

    public static void showWaitDialog(Player player, Cfg cfg) {
        try {
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.authWaitTitle(), cfg.authWaitContent(), false, List.of()))
                    .type(DialogType.notice())));
        } catch (NoClassDefFoundError ignored) {}
    }

    public static void showWaitDialogBlocking(io.papermc.paper.connection.PlayerConfigurationConnection conn,
                                              Cfg cfg, int seconds) {
        if (!conn.isConnected()) return;
        try {
            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.authWaitTitle(), cfg.authWaitContent(), false, List.of()))
                    .type(DialogType.notice())));
            long ms = Math.max(1000L, seconds * 1000L);
            java.util.concurrent.locks.LockSupport.parkNanos(ms * 1_000_000L);
        } catch (NoClassDefFoundError ignored) {}
    }

    private static DialogBase buildBase(Component title, Component content, boolean escape, List<DialogInput> inputs) {
        DialogBase.Builder builder = DialogBase.builder(title)
                .canCloseWithEscape(escape)
                .inputs(inputs);

        if (content != null) {
            List<DialogBody> bodies = new ArrayList<>();
            bodies.add(DialogBody.plainMessage(content));
            builder.body(bodies);
        }

        return builder.build();
    }

    private static ActionButton btn(Cfg cfg, Component label, DialogActionCallback cb) {
        return ActionButton.builder(label)
                .width(cfg.mainButtonWidth())
                .action(DialogAction.customClick(cb, ClickCallback.Options.builder().build()))
                .build();
    }

    private static List<ActionButton> linkActionButtons(Cfg cfg, String playerName) {
        List<ActionButton> result = new ArrayList<>();
        if (!cfg.linksEnabled()) return result;

        for (LinkButton link : cfg.linkButtons(playerName)) {
            if (!link.enabled()) continue;
            String value = link.value();
            if (value == null || value.isBlank()) continue;

            ClickEvent clickEvent = link.action() == LinkButton.Action.COPY
                    ? ClickEvent.copyToClipboard(value)
                    : ClickEvent.openUrl(value);

            result.add(ActionButton.builder(link.label())
                    .width(link.width())
                    .action(DialogAction.staticAction(clickEvent))
                    .build());
        }
        return result;
    }

    private static DialogType buildType(Cfg cfg, String playerName, List<ActionButton> submitButtons, ActionButton logoutButton) {
        List<ActionButton> links = linkActionButtons(cfg, playerName);
        LinkButton.Layout layout = cfg.linksLayout();

        if (links.isEmpty()) {
            List<ActionButton> all = new ArrayList<>(submitButtons);
            if (logoutButton != null) all.add(logoutButton);
            return DialogType.multiAction(all, null, 1);
        }

        if (layout == LinkButton.Layout.SEPARATED) {
            List<ActionButton> all = new ArrayList<>(submitButtons.size() + links.size());
            all.addAll(submitButtons);
            all.addAll(links);
            return DialogType.multiAction(all, logoutButton, 1);
        }

        List<ActionButton> all = new ArrayList<>(submitButtons.size() + 1 + links.size());
        all.addAll(submitButtons);
        if (logoutButton != null) all.add(logoutButton);
        all.addAll(links);
        return DialogType.multiAction(all, null, links.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(DIALOG_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
