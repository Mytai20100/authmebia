package com.authmebia.dialog.register;

import com.authmebia.AuthMe;
import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Mode;
import com.authmebia.dialog.Dialoglib;
import com.authmebia.dialog.email.Email;
import com.authmebia.dialog.shared.AuthInput;
import com.authmebia.dialog.util.Util;
import com.authmebia.lang.Lang;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
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
import java.util.concurrent.atomic.AtomicReference;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class Register {

    private Register() {}

    public static String showRegisterBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang) {
        return Cfg.withPlayerContextValue(conn.getProfile().getName(),
                () -> showRegisterBlockingInner(conn, cfg, lang));
    }

    private static String showRegisterBlockingInner(PlayerConfigurationConnection conn, Cfg cfg, Lang lang) {
        Mode mode = cfg.authMode();
        if (mode == Mode.PIN) return AuthInput.collectPinBlocking(conn, cfg, null);
        if (mode == Mode.SLIDER) return AuthInput.collectSliderBlocking(conn, cfg, null);

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

            String passLabel = error.get() != null ? cfg.passwordLabel() + "  [" + error.get() + "]" : cfg.passwordLabel();
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
                        if (email == null || !Util.isValidEmail(email.trim())) {
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
            inputs.add(DialogInput.text("confirm", Component.text(cfg.confirmPasswordLabel())).maxLength(64).width(cfg.inputWidth()).build());
            if (emailActive) {
                inputs.add(DialogInput.text("email", Component.text(emailLabel)).maxLength(254).width(cfg.inputWidth()).build());
            }

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.registerTitle(playerName), cfg.registerContent(playerName), false, inputs,
                            DialogAfterAction.WAIT_FOR_RESPONSE))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.submitRegisterButton(), cfg.registerSubmitSound(), submit)),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
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
        AtomicReference<String> codeRef = new AtomicReference<>(Util.genNumericCode(cfg.emailCodeLength()));
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

    public static void showRegisterIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe) {
        Cfg.withPlayerContext(player.getName(), () -> {
            Mode mode = cfg.authMode();
            if (mode == Mode.PIN || mode == Mode.SLIDER) {
                showRegisterNumericIngame(player, cfg, lang, authMe, mode);
                return;
            }
            showRegisterIngame(player, cfg, lang, authMe, new AtomicReference<>(null));
        });
    }

    public static void showRegisterIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, AtomicReference<String> lastError) {
        try {
            String playerName = player.getName();
            String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : null;
            boolean emailActive = authMe.isEmailVerificationActive();

            DialogActionCallback logoutCb = (r, a) -> {
                if (a instanceof Player p) {
                    Dialoglib.clearEscapeGuard(p.getUniqueId());
                    p.kick(lang.disconnectLogout(p.getName(), ip));
                }
            };

            String passLabel = lastError.get() != null ? cfg.passwordLabel() + "  [" + lastError.get() + "]" : cfg.passwordLabel();

            List<DialogInput> inputs = new ArrayList<>();
            inputs.add(DialogInput.text("password", Component.text(passLabel)).maxLength(64).width(cfg.inputWidth()).build());
            inputs.add(DialogInput.text("confirm", Component.text(cfg.confirmPasswordLabel())).maxLength(64).width(cfg.inputWidth()).build());
            if (emailActive) {
                inputs.add(DialogInput.text("email", Component.text(cfg.emailFieldLabel())).maxLength(254).width(cfg.inputWidth()).build());
            }

            boolean allowClose = cfg.dialogAllowClose();
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.registerTitle(playerName), cfg.registerContent(playerName), allowClose, inputs,
                            DialogAfterAction.WAIT_FOR_RESPONSE))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.submitRegisterButton(), cfg.registerSubmitSound(), (r, a) -> {
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
                                    if (email == null || !Util.isValidEmail(email.trim())) {
                                        p.sendMessage(cfg.emailInvalidEmailMessage());
                                        showRegisterIngame(p, cfg, lang, authMe, lastError);
                                        return;
                                    }
                                    Dialoglib.clearEscapeGuard(p.getUniqueId());
                                    Email.startEmailVerifyIngame(p, cfg, lang, authMe, email.trim(), pass);
                                    return;
                                }
                                Dialoglib.clearEscapeGuard(p.getUniqueId());
                                authMe.runAsync(() -> authMe.registerAndLogin(p, pass));
                            })),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
            ));

            // See Dialoglib.escapeGuard() / Login.showLoginIngame for the
            // same pattern and its caveats (no real close event exists).
            Dialoglib.escapeGuard(player, !allowClose, cfg.dialogReopenDelayTicks(),
                    () -> player.isOnline() && !authMe.isRegisteredByName(player.getName()),
                    () -> showRegisterIngame(player, cfg, lang, authMe, lastError));
        } catch (NoClassDefFoundError ignored) {}
    }

    private static void showRegisterNumericIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, Mode mode) {
        String name = player.getName();
        String ip = Util.ipOf(player);
        java.util.function.Consumer<String> onConfirm =
                code -> authMe.runAsync(() -> authMe.registerAndLoginNumeric(player, code));
        Runnable onLogout = () -> player.kick(lang.disconnectLogout(name, ip));
        if (mode == Mode.PIN) {
            AuthInput.showPinIngame(player, cfg, null, onConfirm, onLogout);
        } else {
            AuthInput.showSliderIngame(player, cfg, null, onConfirm, onLogout);
        }
    }

    /**
     * Same as showRegisterNumericIngame, but for clients too old to render
     * dialogs. Only called when auth_mode.mode is pin or slider.
     */
    public static void showRegisterInventoryFallback(Player player, Cfg cfg, Lang lang, AuthMe authMe) {
        String name = player.getName();
        String ip = Util.ipOf(player);
        java.util.function.Consumer<String> onConfirm =
                code -> authMe.runAsync(() -> authMe.registerAndLoginNumeric(player, code));
        Runnable onLogout = () -> player.kick(lang.disconnectLogout(name, ip));
        com.authmebia.dialog.shared.InventoryAuthInput.open(player, cfg, null, onConfirm, onLogout);
    }
}
