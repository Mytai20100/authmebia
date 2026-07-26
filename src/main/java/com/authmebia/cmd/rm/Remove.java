package com.authmebia.cmd.rm;

import com.authmebia.AuthMeBia;
import net.kyori.adventure.text.Component;
import java.util.UUID;

public final class Remove {

    private Remove() {}

    public static void execute(org.bukkit.command.CommandSender sender, UUID uuid, String name, AuthMeBia plugin) {
        boolean removed = plugin.biaList().remove(uuid);
        if (removed) {
            sender.sendMessage(Component.text(name + " was removed from the AuthMeBia bypass list."));
        } else {
            sender.sendMessage(Component.text(name + " was not on the AuthMeBia bypass list."));
        }
    }
}
