package com.authmebia.cmd.titles;

import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.notifications.CustomTitle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.BiConsumer;

public final class Titles {

    private static final double MIN_SECONDS = 0.1;
    private static final double MAX_SECONDS = 60.0;

    private Titles() {}

    public static void execute(CommandSender sender, String titleName, String playerName,
                               String secondsRaw, AuthMeBia plugin,
                               BiConsumer<Player, Runnable> runOnPlayer) {
        Player target = plugin.getServer().getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(Component.text(
                    "[titles] Player '" + playerName + "' is not online.", NamedTextColor.RED));
            return;
        }

        Long overrideTicks = null;
        if (secondsRaw != null && !secondsRaw.isBlank()) {
            double seconds;
            try {
                seconds = Double.parseDouble(secondsRaw);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text(
                        "[titles] '" + secondsRaw + "' is not a valid number of seconds.", NamedTextColor.RED));
                return;
            }
            if (!Double.isFinite(seconds) || seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
                sender.sendMessage(Component.text(
                        "[titles] Seconds must be between " + MIN_SECONDS + " and " + MAX_SECONDS + ".",
                        NamedTextColor.RED));
                return;
            }
            overrideTicks = Math.max(1L, Math.round(seconds * 20.0));
        }

        CustomTitle[] titleHolder = new CustomTitle[1];
        try {
            Cfg.withPlayerContext(target.getName(),
                    () -> titleHolder[0] = plugin.cfg().customTitle(titleName));
        } catch (RuntimeException e) {
            sender.sendMessage(Component.text(
                    "[titles] Custom title '" + titleName + "' is invalid: " + e.getMessage(),
                    NamedTextColor.RED));
            return;
        }

        CustomTitle title = titleHolder[0];
        if (title == null || title.isEmpty()) {
            sender.sendMessage(Component.text(
                    "[titles] No custom title named '" + titleName + "' found in custom_titles.",
                    NamedTextColor.RED));
            return;
        }

        long delayTicks = overrideTicks == null ? 0L : overrideTicks;
        runOnPlayer.accept(target, () -> plugin.titles().preview(target, titleName, delayTicks));

        if (sender != target) {
            sender.sendMessage(Component.text(
                    "[titles] Showed custom title '" + titleName + "' to " + target.getName()
                            + (overrideTicks != null ? " for " + secondsRaw + "s" : "") + ".",
                    NamedTextColor.YELLOW));
        }
    }
}
