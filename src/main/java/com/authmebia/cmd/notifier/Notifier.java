package com.authmebia.cmd.notifier;

import com.authmebia.AuthMeBia;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

public final class Notifier {

    private Notifier() {}

    public static void execute(org.bukkit.command.CommandSender sender, String toastName, String playerName,
                                String showLiteral, String secondsRaw, AuthMeBia plugin) {
        if (!"show".equalsIgnoreCase(showLiteral)) {
            sender.sendMessage(Component.text(
                    "[notifier] Usage: /bia notifier <toast_name> <player> show <seconds>", NamedTextColor.RED));
            return;
        }

        Player target = plugin.getServer().getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("[notifier] Player '" + playerName + "' is not online.", NamedTextColor.RED));
            return;
        }

        Integer overrideSeconds = null;
        if (secondsRaw != null && !secondsRaw.isBlank()) {
            try {
                int parsed = Integer.parseInt(secondsRaw);
                if (parsed < 1) {
                    sender.sendMessage(Component.text("[notifier] Seconds must be at least 1.", NamedTextColor.RED));
                    return;
                }
                overrideSeconds = parsed;
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("[notifier] '" + secondsRaw + "' is not a valid number of seconds.", NamedTextColor.RED));
                return;
            }
        }

        var toastListener = plugin.toastListener();
        if (toastListener == null) {
            sender.sendMessage(Component.text("[notifier] Toast listener not available.", NamedTextColor.RED));
            return;
        }

        boolean found = toastListener.showToastByNameForTest(target, toastName, overrideSeconds);
        if (!found) {
            sender.sendMessage(Component.text(
                    "[notifier] No toast named '" + toastName + "' found in notifications.toasts.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text(
                "[notifier] Showed toast '" + toastName + "' to " + target.getName()
                        + (overrideSeconds != null ? " for " + overrideSeconds + "s" : "") + ".",
                NamedTextColor.YELLOW));
    }
}
