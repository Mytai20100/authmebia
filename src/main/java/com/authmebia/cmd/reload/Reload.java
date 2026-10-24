package com.authmebia.cmd.reload;

import com.authmebia.AuthMeBia;
import com.authmebia.listeners.version.Version;
import net.kyori.adventure.text.Component;

import java.util.Map;

public final class Reload {

    private Reload() {}

    public static void execute(org.bukkit.command.CommandSender sender, AuthMeBia plugin) {
        try {
            reload(plugin);
            sender.sendMessage(Component.text("AuthMeBia config and lang reloaded."));
        } catch (Throwable t) {
            plugin.getLogger().warning("Reload failed: " + t.getMessage());
            sender.sendMessage(plugin.lang().component("command.reload_failed",
                    "Reload failed. Check the console for details.", Map.of()));
        }
    }

    public static void reload(AuthMeBia plugin) {
        plugin.cfg().reload();
        plugin.lang().reload();

        if (plugin.authMeListener() != null) {
            plugin.authMeListener().refreshAuthMeConfigCache();
        }

        Version.reset();

        if (plugin.toastListener() != null) {
            plugin.toastListener().resetFailedAdvancements();
        }
    }
}
