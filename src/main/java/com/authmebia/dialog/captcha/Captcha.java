package com.authmebia.dialog.captcha;

import com.authmebia.cfg.Cfg;
import com.authmebia.lang.Lang;
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

@SuppressWarnings("UnstableApiUsage")
public final class Captcha {

    private Captcha() {}

    public static boolean showCaptchaBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang,
                                              com.authmebia.listeners.captcha.Captcha captcha) {
        return Cfg.withPlayerContextValue(conn.getProfile().getName(),
                () -> showCaptchaBlockingInner(conn, cfg, lang, captcha));
    }

    private static boolean showCaptchaBlockingInner(PlayerConfigurationConnection conn, Cfg cfg, Lang lang,
                                              com.authmebia.listeners.captcha.Captcha captcha) {
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
                            List.of(btn(cfg, cfg.captchaSubmitButton(), cfg.captchaSubmitSound(), submit)),
                            null, 1))
            ));

            await(latch);
            if (verified.get()) return true;
        }
        return false;
    }

    public static void showCaptchaIngame(Player player, Cfg cfg, Lang lang,
                                         com.authmebia.listeners.captcha.Captcha captcha) {
        Cfg.withPlayerContext(player.getName(), () ->
                showCaptchaIngameLoop(player, cfg, lang, captcha, captcha.generate(cfg.captchaLength()), null));
    }

    private static void showCaptchaIngameLoop(Player player, Cfg cfg, Lang lang,
                                              com.authmebia.listeners.captcha.Captcha captcha, String code, String error) {
        try {
            String inputLabel = error != null
                    ? cfg.captchaInputLabel() + "  [" + error + "]"
                    : cfg.captchaInputLabel();

            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.captchaTitle(), cfg.captchaContent(code), false,
                            List.of(DialogInput.text("code", Component.text(inputLabel))
                                    .maxLength(16).width(cfg.inputWidth()).build())))
                    .type(DialogType.multiAction(
                            List.of(btn(cfg, cfg.captchaSubmitButton(), cfg.captchaSubmitSound(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String typed = r.getText("code");
                                if (captcha.matches(typed, code)) {
                                    p.sendMessage(net.kyori.adventure.text.Component.text(
                                            "[debug] Captcha verified!", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                                } else {
                                    showCaptchaIngameLoop(p, cfg, lang, captcha,
                                            captcha.generate(cfg.captchaLength()),
                                            lang.errorCaptchaIncorrect());
                                }
                            })),
                            null, 1))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }
}
