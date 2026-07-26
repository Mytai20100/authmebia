package com.authmebia.dialog.recover;

import com.authmebia.AuthMe;
import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Mode;
import com.authmebia.dialog.shared.AuthInput;
import com.authmebia.dialog.util.Util;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.authmebia.dialog.Dialoglib.*;

/**
 * Bug fix (see task 2.5): showRecoverBlocking/showRecoverIngame previously
 * always rendered the two-field text password dialog regardless of
 * auth_mode.mode, so PIN/slider servers still saw a text-entry recovery
 * dialog. Both entry points now branch on cfg.authMode() first, exactly the
 * way Login.java and Register.java already do, and reuse AuthInput's
 * PIN/slider collectors instead of duplicating that logic.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Recover {

    private Recover() {}

    public static String showRecoverBlocking(PlayerConfigurationConnection conn, Cfg cfg) {
        return Cfg.withPlayerContextValue(conn.getProfile().getName(), () -> {
            Mode mode = cfg.authMode();
            if (mode == Mode.PIN) return AuthInput.collectPinBlocking(conn, cfg, null);
            if (mode == Mode.SLIDER) return AuthInput.collectSliderBlocking(conn, cfg, null);
            return showRecoverPasswordBlocking(conn, cfg);
        });
    }

    private static String showRecoverPasswordBlocking(PlayerConfigurationConnection conn, Cfg cfg) {
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
                            List.of(btn(cfg, cfg.recoverSubmitButton(), cfg.recoverSubmitSound(), submit)),
                            btn(cfg, cfg.logoutButton(), cfg.logoutSound(), logoutCb)))
            ));

            await(latch);
            if (disconnect.get()) return null;
            if (result.get() != null) return result.get();
        }
        return null;
    }

    public static void showRecoverIngame(Player player, Cfg cfg, AuthMe authMe, Runnable onSuccess) {
        Cfg.withPlayerContext(player.getName(), () -> {
            Mode mode = cfg.authMode();
            if (mode == Mode.PIN || mode == Mode.SLIDER) {
                showRecoverNumericIngame(player, cfg, authMe, onSuccess, mode, null);
                return;
            }
            showRecoverPasswordIngame(player, cfg, authMe, onSuccess, false);
        });
    }

    private static void showRecoverNumericIngame(Player player, Cfg cfg, AuthMe authMe, Runnable onSuccess,
                                                  Mode mode, String statusLine) {
        String name = player.getName();
        String ip = Util.ipOf(player);
        java.util.function.Consumer<String> onConfirm = code -> authMe.runAsync(() -> {
            authMe.changePassword(name, code);
            Util.runOnMain(player, () -> {
                player.sendMessage(cfg.recoverSuccessMessage(name));
                onSuccess.run();
            });
        });
        Runnable onLogout = () -> player.kick(AuthMeBia.get().lang().disconnectLogout(name, ip));
        if (mode == Mode.PIN) {
            AuthInput.showPinIngame(player, cfg, statusLine, onConfirm, onLogout);
        } else {
            AuthInput.showSliderIngame(player, cfg, statusLine, onConfirm, onLogout);
        }
    }

    /**
     * Same as showRecoverNumericIngame, but for clients too old to render
     * dialogs. Only called when auth_mode.mode is pin or slider.
     */
    public static void showRecoverInventoryFallback(Player player, Cfg cfg, AuthMe authMe, Runnable onSuccess) {
        Cfg.withPlayerContext(player.getName(), () -> {
            String name = player.getName();
            String ip = Util.ipOf(player);
            java.util.function.Consumer<String> onConfirm = code -> authMe.runAsync(() -> {
                authMe.changePassword(name, code);
                Util.runOnMain(player, () -> {
                    player.sendMessage(cfg.recoverSuccessMessage(name));
                    onSuccess.run();
                });
            });
            Runnable onLogout = () -> player.kick(AuthMeBia.get().lang().disconnectLogout(name, ip));
            com.authmebia.dialog.shared.InventoryAuthInput.open(player, cfg, null, onConfirm, onLogout);
        });
    }

    private static void showRecoverPasswordIngame(Player player, Cfg cfg, AuthMe authMe, Runnable onSuccess, boolean mismatch) {
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
                            List.of(btn(cfg, cfg.recoverSubmitButton(), cfg.recoverSubmitSound(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String pass = r.getText("new_password");
                                String confirm = r.getText("confirm_password");
                                if (pass == null || pass.isBlank() || !pass.equals(confirm)) {
                                    p.sendMessage(cfg.recoverMismatchMessage());
                                    showRecoverPasswordIngame(p, cfg, authMe, onSuccess, true);
                                    return;
                                }
                                authMe.runAsync(() -> {
                                    authMe.changePassword(p.getName(), pass);
                                    Util.runOnMain(p, () -> {
                                        p.sendMessage(cfg.recoverSuccessMessage(p.getName()));
                                        onSuccess.run();
                                    });
                                });
                            })),
                            null))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }
}
