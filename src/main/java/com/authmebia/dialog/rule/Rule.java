package com.authmebia.dialog.rule;

import com.authmebia.cfg.Cfg;
import com.authmebia.service.ConnectionState;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class Rule {

    private Rule() {}

    public static boolean showRuleBlocking(PlayerConfigurationConnection conn, Cfg cfg) {
        String playerName = conn.getProfile().getName();
        return Cfg.withPlayerContextValue(playerName, () -> showRuleBlockingInner(conn, cfg, playerName));
    }

    private static boolean showRuleBlockingInner(PlayerConfigurationConnection conn, Cfg cfg, String playerName) {

        while (ConnectionState.isConnected(conn)) {
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
                            List.of(btn(cfg, cfg.ruleAgreeButton(), cfg.ruleAgreeSound(), agreeCb)),
                            null))
            ));

            await(latch);
            if (agreed.get()) return true;
        }
        return false;
    }

    public static void showRuleDebugIngame(Player player, Cfg cfg) {
        String playerName = player.getName();
        Cfg.withPlayerContext(playerName, () -> showRuleDebugIngameInner(player, cfg, playerName));
    }

    private static void showRuleDebugIngameInner(Player player, Cfg cfg, String playerName) {
        player.showDialog(Dialog.create(d -> d
                .empty()
                .base(buildBase(cfg.ruleTitle(playerName), cfg.ruleContent(playerName),
                        // The real pre-join dialog cannot be closed with ESC. For a
                        // debug preview that would trap the admin, so allow it here.
                        true,
                        List.of(
                                DialogInput.bool("agree", cfg.ruleCheckboxLabel())
                                        .initial(false)
                                        .build()
                        )))
                .type(buildType(cfg, playerName,
                        List.of(btn(cfg, cfg.ruleAgreeButton(), cfg.ruleAgreeSound(), (r, a) -> {
                            boolean agreed = Boolean.TRUE.equals(r.getBoolean("agree"));
                            if (a instanceof Player p) {
                                p.sendMessage(Component.text(
                                        agreed
                                                ? "[debug] Rule agreed (preview only, nothing was saved)."
                                                : "[debug] Rule dialog submitted without ticking the checkbox.",
                                        agreed ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                            }
                        })),
                        null))
        ));
    }
}
