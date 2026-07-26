package com.authmebia.cmd.recover;

import com.authmebia.AuthMe;
import com.authmebia.AuthMeBia;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class Recover {

    private Recover() {}

    public static void execute(org.bukkit.command.CommandSender sender, UUID uuid, String name, AuthMeBia plugin) {
        AuthMe authMe = plugin.authMeListener();
        if (authMe != null && !authMe.isRegisteredByName(name)) {
            sender.sendMessage(Component.text(name + " is not registered with AuthMe, so there is no password to reset."));
            return;
        }

        plugin.recoverStore().flag(uuid, name);

        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null && authMe != null) {
            Runnable show = () -> com.authmebia.dialog.recover.Recover.showRecoverIngame(online, plugin.cfg(), authMe, () -> {
                plugin.recoverStore().clear(online.getUniqueId());
                if (!authMe.isAuthenticated(online)) authMe.runAsync(() -> authMe.login(online));
            });
            if (plugin.isFolia()) {
                online.getScheduler().run(plugin, t -> show.run(), null);
            } else {
                plugin.getServer().getScheduler().runTask(plugin, show);
            }
            sender.sendMessage(Component.text(name + " is online and has been shown the password reset dialog."));
        } else {
            sender.sendMessage(Component.text(name + " will be asked to set a new password on their next login."));
        }
    }
}
