package com.authmebia.dialog.emailverify;

import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.email.Email;
import com.authmebia.dialog.forgot.Forgot;
import com.authmebia.lang.Lang;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class EmailVerify {

    private EmailVerify() {}

    public static void clearEmailSession(UUID uuid) {
        Email.clearSession(uuid);
        Forgot.clearSession(uuid);
    }

    public static void showEmailVerifyDebugIngame(Player player, Cfg cfg, Lang lang) {
        final String dummyEmail = "debug@example.com";
        final String debugCode = "123456";
        Cfg.withPlayerContext(player.getName(), () ->
                showEmailVerifyDebugLoop(player, cfg, lang, dummyEmail, debugCode, System.currentTimeMillis(), null));
    }

    private static void showEmailVerifyDebugLoop(Player player, Cfg cfg, Lang lang,
                                                  String email, String code, long lastSent, String codeErr) {
        try {
            int remaining = (int) Math.max(0, cfg.emailResendCooldown() - (System.currentTimeMillis() - lastSent) / 1000);
            String codeLabel = codeErr != null
                    ? cfg.emailCodeLabel() + "  [" + codeErr + "]"
                    : cfg.emailCodeLabel();

            DialogActionCallback resendCb = (r, a) -> {
                if (!(a instanceof Player p)) return;
                p.sendMessage(Component.text(
                        "[debug] Resend clicked (debug code: " + code + ")",
                        NamedTextColor.YELLOW));
                showEmailVerifyDebugLoop(p, cfg, lang, email, code, System.currentTimeMillis(), null);
            };

            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.emailVerifyTitle(), cfg.emailVerifyContent(email, remaining), false,
                            List.of(DialogInput.text("code", Component.text(codeLabel))
                                    .maxLength(16).width(cfg.inputWidth()).build())))
                    .type(DialogType.multiAction(
                            List.of(
                                    btn(cfg, cfg.emailVerifyButton(), cfg.emailVerifySound(), (r, a) -> {
                                        if (!(a instanceof Player p)) return;
                                        String typed = r.getText("code");
                                        if (code.equals(typed == null ? null : typed.trim())) {
                                            p.sendMessage(Component.text(
                                                    "[debug] Email verified!", NamedTextColor.GREEN));
                                        } else {
                                            p.sendMessage(Component.text(
                                                    "[debug] Wrong code. Debug code is: " + code,
                                                    NamedTextColor.RED));
                                            showEmailVerifyDebugLoop(p, cfg, lang, email, code, lastSent, cfg.emailWrongCodeError());
                                        }
                                    }),
                                    resendBtn(cfg, remaining, resendCb)
                            ),
                            null, 1))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }
}
