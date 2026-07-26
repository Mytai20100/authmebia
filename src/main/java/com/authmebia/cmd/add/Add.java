package com.authmebia.cmd.add;

import com.authmebia.AuthMeBia;
import net.kyori.adventure.text.Component;
import java.util.UUID;

public final class Add {

    private Add() {}

    public static void execute(org.bukkit.command.CommandSender sender, UUID uuid, String name, AuthMeBia plugin) {
        boolean added = plugin.biaList().add(uuid, name);
        if (added) {
            sender.sendMessage(Component.text(name + " was added to the AuthMeBia bypass list."));
        } else {
            sender.sendMessage(Component.text(name + " is already on the AuthMeBia bypass list."));
        }
    }
}
