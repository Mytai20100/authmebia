package com.authmebia.dialog.email;

import com.authmebia.AuthMe;
import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Dialoglib;
import com.authmebia.dialog.register.Register;
import com.authmebia.dialog.util.Util;
import com.authmebia.lang.Lang;
import com.authmebia.listeners.ipguard.IpGuard;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class Email {

    private static final Map<UUID, EmailSession> EMAIL_SESSIONS = new ConcurrentHashMap<>();

    private static final class EmailSession {
        String email;
        String password;
        String code;
        long lastSent;
        final AtomicInteger wrongTries = new AtomicInteger(0);
    }

    private Email() {}

    public static void clearSession(UUID uuid) {
        EMAIL_SESSIONS.remove(uuid);
    }

    public static void startEmailVerifyIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, String email, String password) {
        EmailSession s = new EmailSession();
        s.email = email;
        s.password = password;
        s.code = Util.genNumericCode(cfg.emailCodeLength());
        s.lastSent = System.currentTimeMillis();
        EMAIL_SESSIONS.put(player.getUniqueId(), s);
        authMe.runAsync(() -> {
            boolean sent = authMe.sendVerificationEmail(player.getName(), email, s.code);
            Util.runOnMain(player, () -> {
                if (sent) {
                    showEmailVerifyIngame(player, cfg, lang, authMe, null);
                } else {
                    EMAIL_SESSIONS.remove(player.getUniqueId());
                    player.sendMessage(cfg.emailSendFailedMessage());
                    Cfg.withPlayerContext(player.getName(), () ->
                            Register.showRegisterIngame(player, cfg, lang, authMe, new java.util.concurrent.atomic.AtomicReference<>(null)));
                }
            });
        });
    }

    public static void showEmailVerifyIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, String codeErr) {
        Cfg.withPlayerContext(player.getName(), () -> showEmailVerifyIngameInner(player, cfg, lang, authMe, codeErr));
    }

    private static void showEmailVerifyIngameInner(Player player, Cfg cfg, Lang lang, AuthMe authMe, String codeErr) {
        UUID uuid = player.getUniqueId();
        EmailSession s = EMAIL_SESSIONS.get(uuid);
        if (s == null) return;
        int remaining = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - s.lastSent) / 1000);
        String codeLabel = codeErr != null ? cfg.emailCodeLabel() + "  [" + codeErr + "]" : cfg.emailCodeLabel();
        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress() : null;
        IpGuard ipGuard = AuthMeBia.get().ipGuard();
        DialogActionCallback resendCb = (r, a) -> {
            if (!(a instanceof Player p)) return;
            int rem = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - s.lastSent) / 1000);
            if (rem <= 0) {
                s.code = Util.genNumericCode(cfg.emailCodeLength());
                s.lastSent = System.currentTimeMillis();
                authMe.runAsync(() -> authMe.sendVerificationEmail(p.getName(), s.email, s.code));
            }
            showEmailVerifyIngame(p, cfg, lang, authMe, null);
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
                                            ipGuard.clearFailures(ip);
                                            EMAIL_SESSIONS.remove(uuid);
                                            authMe.pendingEmail.put(uuid, s.email);
                                            authMe.runAsync(() -> authMe.registerAndLogin(p, s.password));
                                            return;
                                        }
                                        ipGuard.recordFailure(ip, cfg, lang);
                                        if (cfg.otpAttemptsEnabled() && s.wrongTries.incrementAndGet() >= cfg.otpMaxTries()) {
                                            EMAIL_SESSIONS.remove(uuid);
                                            p.kick(lang.disconnectTooManyAttempts(p.getName(), ip));
                                            return;
                                        }
                                        showEmailVerifyIngame(p, cfg, lang, authMe, cfg.emailWrongCodeError());
                                    }),
                                    resendBtn(cfg, remaining, resendCb)),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), (r, a) -> {
                                EMAIL_SESSIONS.remove(uuid);
                                if (a instanceof Player p) p.kick(lang.disconnectLogout(p.getName(), ip));
                            })))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }
}
