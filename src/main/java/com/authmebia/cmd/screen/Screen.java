package com.authmebia.cmd.screen;

import com.authmebia.AuthMeBia;
import com.authmebia.dialog.customscreen.CustomScreen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.BiConsumer;

public final class Screen {

    private Screen() {}

    public static void execute(org.bukkit.command.CommandSender sender, String id, String targetName,
                                AuthMeBia plugin, BiConsumer<Player, Runnable> runOnPlayer) {
        Player target;
        if (targetName != null) {
            target = plugin.getServer().getPlayerExact(targetName);
            if (target == null) {
                sender.sendMessage(Component.text("Player '" + targetName + "' is not online.", NamedTextColor.RED));
                return;
            }
        } else {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Console must specify a target player: /bia screen <id> <player>", NamedTextColor.RED));
                return;
            }
            target = p;
        }

        Player finalTarget = target;
        CustomScreen[] screenHolder = new CustomScreen[1];
        com.authmebia.cfg.Cfg.withPlayerContext(finalTarget.getName(), () -> screenHolder[0] = plugin.cfg().customScreen(id));
        CustomScreen screen = screenHolder[0];

        if (screen == null) {
            sender.sendMessage(Component.text(
                "No custom screen with id '" + id + "' found in config.yml.", NamedTextColor.RED));
            return;
        }
        if (!screen.enabled()) {
            sender.sendMessage(Component.text(
                "Screen '" + id + "' is disabled (enabled: false in config.yml).", NamedTextColor.RED));
            return;
        }

        // Manual "/bia screen <id> [player]" always shows the screen,
        // regardless of checkbox_action: close having previously
        // suppressed it for this player -- see
        // CustomScreen.isAutoDismissedFor()'s doc comment for why that
        // check is deliberately not inside showCustomScreen() itself.
        runOnPlayer.accept(finalTarget, () -> CustomScreen.showCustomScreen(finalTarget, screen, finalTarget.getName()));
        if (sender != target) {
            sender.sendMessage(Component.text("Screen '" + id + "' shown to " + target.getName() + ".", NamedTextColor.GREEN));
        }
    }

    /**
     * "/bia screen reset <player> [id]" -- clears a permanently-dismissed
     * checkbox_action: close screen for a player, so it will show again
     * next time it's triggered. With no id, clears every screen dismissal
     * for that player at once. Works for offline players too (uuid is
     * resolved the same way as the rest of this plugin's admin commands),
     * since a player might reasonably want this fixed for someone who
     * isn't currently online.
     */
    public static void executeReset(org.bukkit.command.CommandSender sender, String targetName, String idOrNull,
                                     AuthMeBia plugin, UUID uuid) {
        if (uuid == null) {
            sender.sendMessage(Component.text(
                "Could not resolve a player UUID for '" + targetName + "'.", NamedTextColor.RED));
            return;
        }

        if (idOrNull != null) {
            com.authmebia.cfg.Cfg.withPlayerContext(targetName, () -> {
                CustomScreen screen = plugin.cfg().customScreen(idOrNull);
                if (screen == null) {
                    sender.sendMessage(Component.text(
                        "No custom screen with id '" + idOrNull + "' found in config.yml.", NamedTextColor.RED));
                    return;
                }
                plugin.screenDismissStore().clear(uuid, idOrNull);
                sender.sendMessage(Component.text(
                    "Screen '" + idOrNull + "' will show again for " + targetName + ".", NamedTextColor.GREEN));
            });
            return;
        }

        plugin.screenDismissStore().clear(uuid, null);
        sender.sendMessage(Component.text(
            "All dismissed screens reset for " + targetName + ".", NamedTextColor.GREEN));
    }
}
