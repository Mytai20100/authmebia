package com.authmebia.cmd.screen;

import com.authmebia.AuthMeBia;
import com.authmebia.dialog.customscreen.CustomScreen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

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

        runOnPlayer.accept(finalTarget, () -> CustomScreen.showCustomScreen(finalTarget, screen, finalTarget.getName()));
        if (sender != target) {
            sender.sendMessage(Component.text("Screen '" + id + "' shown to " + target.getName() + ".", NamedTextColor.GREEN));
        }
    }
}
