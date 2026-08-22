package com.authmebia.dialog.premium;

import com.authmebia.AuthMe;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.util.Util;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class Premium {

    private Premium() {}

    public static void showResetIngame(Player player, Cfg cfg, AuthMe authMe, Runnable onSuccess) {
        Cfg.withPlayerContext(player.getName(), () -> showResetIngame(player, cfg, authMe, onSuccess, false));
    }

    private static void showResetIngame(Player player, Cfg cfg, AuthMe authMe, Runnable onSuccess, boolean mismatch) {
        try {
            String newLabel = mismatch
                    ? cfg.premiumResetNewPasswordLabel() + "  [mismatch]"
                    : cfg.premiumResetNewPasswordLabel();
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.premiumResetTitle(), cfg.premiumResetContent(), false,
                            List.of(
                                    DialogInput.text("new_password", Component.text(newLabel)).maxLength(64).width(cfg.inputWidth()).build(),
                                    DialogInput.text("confirm_password", Component.text(cfg.premiumResetConfirmPasswordLabel())).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, player.getName(),
                            List.of(btn(cfg, cfg.premiumResetSubmitButton(), cfg.premiumResetSubmitSound(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String pass = r.getText("new_password");
                                String confirm = r.getText("confirm_password");
                                if (pass == null || pass.isBlank() || !pass.equals(confirm)) {
                                    p.sendMessage(cfg.premiumResetMismatchMessage());
                                    showResetIngame(p, cfg, authMe, onSuccess, true);
                                    return;
                                }
                                authMe.runAsync(() -> {
                                    authMe.changePassword(p.getName(), pass);
                                    Util.runOnMain(p, () -> {
                                        p.sendMessage(cfg.premiumResetSuccessMessage(p.getName()));
                                        onSuccess.run();
                                    });
                                });
                            })),
                            null))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }
}
