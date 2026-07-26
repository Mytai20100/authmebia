package com.authmebia.dialog.twofactor;

import com.authmebia.AuthMe;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.util.Util;
import com.authmebia.lang.Lang;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class TwoFactor {

    private TwoFactor() {}

    public static boolean show2FABlocking(PlayerConfigurationConnection conn, String name, Cfg cfg, Lang lang, AuthMe authMe) {
        return Cfg.withPlayerContextValue(name, () -> show2FABlockingInner(conn, name, cfg, lang, authMe));
    }

    private static boolean show2FABlockingInner(PlayerConfigurationConnection conn, String name, Cfg cfg, Lang lang, AuthMe authMe) {
        AtomicReference<String> error = new AtomicReference<>(null);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            AtomicBoolean disconnect = new AtomicBoolean(false);

            String inputLabel = error.get() != null
                    ? cfg.totp2faInputLabel() + "  [" + error.get() + "]"
                    : cfg.totp2faInputLabel();

            DialogActionCallback verifyCb = (r, a) -> {
                try {
                    String code = r.getText("totp_code");
                    if (code != null && authMe.checkTotpCode(name, code.trim())) {
                        success.set(true);
                    } else {
                        error.set(cfg.totp2faWrongCodeError());
                    }
                } finally {
                    latch.countDown();
                }
            };

            DialogActionCallback logoutCb = (r, a) -> { disconnect.set(true); latch.countDown(); };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.totp2faTitle(), cfg.totp2faContent(), false,
                            List.of(DialogInput.text("totp_code", Component.text(inputLabel))
                                    .maxLength(16).width(cfg.inputWidth()).build())))
                    .type(buildType(cfg, name,
                            List.of(btn(cfg, cfg.totp2faSubmitButton(), cfg.totp2faSubmitSound(), verifyCb)),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
            ));

            await(latch);
            if (disconnect.get()) return false;
            if (success.get()) return true;
        }
        return false;
    }

    public static void show2FAIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, Runnable onSuccess) {
        Cfg.withPlayerContext(player.getName(), () -> show2FAIngame(player, cfg, lang, authMe, onSuccess, null));
    }

    private static void show2FAIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, Runnable onSuccess, String error) {
        try {
            String name = player.getName();
            String ip = Util.ipOf(player);
            String inputLabel = error != null
                    ? cfg.totp2faInputLabel() + "  [" + error + "]"
                    : cfg.totp2faInputLabel();

            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.totp2faTitle(), cfg.totp2faContent(), false,
                            List.of(DialogInput.text("totp_code", Component.text(inputLabel))
                                    .maxLength(16).width(cfg.inputWidth()).build())))
                    .type(buildType(cfg, name,
                            List.of(btn(cfg, cfg.totp2faSubmitButton(), cfg.totp2faSubmitSound(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String code = r.getText("totp_code");
                                if (code != null && authMe.checkTotpCode(name, code.trim())) {
                                    onSuccess.run();
                                } else {
                                    show2FAIngame(p, cfg, lang, authMe, onSuccess, cfg.totp2faWrongCodeError());
                                }
                            })),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), (r, a) -> {
                                if (a instanceof Player p) p.kick(lang.disconnectLogout(name, ip));
                            })))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }
}
