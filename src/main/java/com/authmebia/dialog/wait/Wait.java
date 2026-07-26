package com.authmebia.dialog.wait;

import com.authmebia.cfg.Cfg;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import org.bukkit.entity.Player;

import java.util.List;

import static com.authmebia.dialog.Dialoglib.*;

@SuppressWarnings("UnstableApiUsage")
public final class Wait {

    private Wait() {}

    public static void showWaitDialog(Player player, Cfg cfg) {
        Cfg.withPlayerContext(player.getName(), () -> {
            try {
                player.showDialog(Dialog.create(d -> d
                        .empty()
                        .base(buildBase(cfg.authWaitTitle(), cfg.authWaitContent(), false, List.of()))
                        .type(DialogType.notice())));
            } catch (NoClassDefFoundError ignored) {}
        });
    }

    public static void showWaitDialogBlocking(PlayerConfigurationConnection conn, Cfg cfg, int seconds) {
        if (!conn.isConnected()) return;
        Cfg.withPlayerContext(conn.getProfile().getName(), () -> {
            try {
                conn.getAudience().showDialog(Dialog.create(d -> d
                        .empty()
                        .base(buildBase(cfg.authWaitTitle(), cfg.authWaitContent(), false, List.of()))
                        .type(DialogType.notice())));
                long ms = Math.max(1000L, seconds * 1000L);
                java.util.concurrent.locks.LockSupport.parkNanos(ms * 1_000_000L);
            } catch (NoClassDefFoundError ignored) {}
        });
    }
}
