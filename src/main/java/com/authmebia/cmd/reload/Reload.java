package com.authmebia.cmd.reload;

import com.authmebia.AuthMeBia;
import com.authmebia.listeners.version.Version;
import net.kyori.adventure.text.Component;

public final class Reload {

    private Reload() {}

    public static void execute(org.bukkit.command.CommandSender sender, AuthMeBia plugin) {
        plugin.cfg().reload();
        plugin.lang().reload();

        if (plugin.authMeListener() != null) {
            plugin.authMeListener().refreshAuthMeConfigCache();
        }

        Version.reset();

        if (plugin.toastListener() != null) {
            plugin.toastListener().resetFailedAdvancements();
        }

        sender.sendMessage(Component.text("AuthMeBia config and lang reloaded."));
    }
}
