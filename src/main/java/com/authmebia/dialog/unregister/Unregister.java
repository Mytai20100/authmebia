package com.authmebia.dialog.unregister;

import com.authmebia.AuthMe;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.util.Util;
import com.authmebia.lang.Lang;
import com.authmebia.listeners.ipguard.IpGuard;
import io.papermc.paper.dialog.Dialog;
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

/**
 * Dialog replacement for AuthMe's chat-based /unreg verification code.
 *
 * <p>When an account has an email and AuthMe would normally generate a code and
 * ask the player to run {@code /email code <code>}, this dialog sends the code
 * and verifies it. After a successful verification the original /unreg command
 * is replayed, so AuthMe still checks the password before deleting the account.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class Unregister {

    private static final Map<UUID, UnregisterSession> SESSIONS = new ConcurrentHashMap<>();

    private static final class UnregisterSession {
        String email;
        String code;
        String command;
        long lastSent;
        final AtomicInteger wrongTries = new AtomicInteger(0);
    }

    private Unregister() {}

    public static void clearSession(UUID uuid) {
        if (uuid != null) SESSIONS.remove(uuid);
    }

    public static void start(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                             IpGuard ipGuard, String command) {
        if (player == null || !player.isOnline() || command == null || command.isBlank()) return;
        String email = authMe.getStoredEmail(player.getName());
        if (email == null || email.isBlank()) return;

        UUID uuid = player.getUniqueId();
        UnregisterSession session = new UnregisterSession();
        session.email = email;
        session.code = Util.genNumericCode(cfg.emailCodeLength());
        session.command = command;
        session.lastSent = System.currentTimeMillis();
        SESSIONS.put(uuid, session);

        String name = player.getName();
        authMe.runAsync(() -> {
            boolean sent = authMe.sendVerificationEmail(name, email, session.code);
            Util.runOnMain(player, () -> {
                if (sent) {
                    show(player, cfg, lang, authMe, ipGuard, null);
                } else {
                    SESSIONS.remove(uuid);
                    player.sendMessage(cfg.emailSendFailedMessage());
                }
            });
        });
    }

    private static void show(Player player, Cfg cfg, Lang lang, AuthMe authMe,
                             IpGuard ipGuard, String codeErr) {
        UUID uuid = player.getUniqueId();
        UnregisterSession session = SESSIONS.get(uuid);
        if (session == null || !player.isOnline()) return;

        int remaining = (int) Math.max(0,
                cfg.emailResendCooldown() - (System.currentTimeMillis() - session.lastSent) / 1000);
        String codeLabel = codeErr != null
                ? cfg.emailCodeLabel() + "  [" + codeErr + "]"
                : cfg.emailCodeLabel();
        String ip = Util.ipOf(player);

        DialogActionCallback resendCb = (r, a) -> {
            if (!(a instanceof Player p)) return;
            int rem = (int) Math.max(0,
                    cfg.emailResendCooldown() - (System.currentTimeMillis() - session.lastSent) / 1000);
            if (rem <= 0) {
                session.code = Util.genNumericCode(cfg.emailCodeLength());
                session.lastSent = System.currentTimeMillis();
                authMe.runAsync(() -> authMe.sendVerificationEmail(p.getName(), session.email, session.code));
            }
            show(p, cfg, lang, authMe, ipGuard, null);
        };

        try {
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.emailVerifyTitle(),
                            cfg.emailVerifyContent(session.email, remaining), false,
                            List.of(DialogInput.text("code", Component.text(codeLabel))
                                    .maxLength(16).width(cfg.inputWidth()).build())))
                    .type(buildType(cfg, player.getName(),
                            List.of(
                                    btn(cfg, cfg.emailVerifyButton(), cfg.emailVerifySound(), (r, a) -> {
                                        if (!(a instanceof Player p)) return;
                                        String typed = r.getText("code");
                                        if (session.code.equals(typed == null ? null : typed.trim())) {
                                            ipGuard.clearFailures(ip);
                                            SESSIONS.remove(uuid);
                                            p.closeDialog();
                                            if (authMe.markVerified(p.getName())) {
                                                authMe.beginUnregisterReplay(uuid);
                                                boolean scheduled = Util.runOnMain(p, () -> {
                                                    try {
                                                        if (p.isOnline()) p.performCommand(session.command);
                                                    } finally {
                                                        authMe.endUnregisterReplay(uuid);
                                                    }
                                                });
                                                if (!scheduled) {
                                                    authMe.endUnregisterReplay(uuid);
                                                    authMe.forceUnregister(p);
                                                }
                                            } else {
                                                // Reflection failed, but the code was
                                                // verified: still remove the account.
                                                authMe.forceUnregister(p);
                                            }
                                            return;
                                        }
                                        ipGuard.recordFailure(ip, cfg, lang);
                                        if (cfg.otpAttemptsEnabled()
                                                && session.wrongTries.incrementAndGet() >= cfg.otpMaxTries()) {
                                            SESSIONS.remove(uuid);
                                            p.kick(lang.disconnectTooManyAttempts(p.getName(), ip));
                                            return;
                                        }
                                        show(p, cfg, lang, authMe, ipGuard, cfg.emailWrongCodeError());
                                    }),
                                    resendBtn(cfg, remaining, resendCb)),
                            btn(cfg, cfg.emailCancelButton(), "", (r, a) -> {
                                SESSIONS.remove(uuid);
                                if (a instanceof Player p) p.closeDialog();
                            })))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }
}
