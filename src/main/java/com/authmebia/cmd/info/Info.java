package com.authmebia.cmd.info;

import com.authmebia.AuthMeBia;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.Plugin;

public final class Info {

    private Info() {}

    public static void execute(org.bukkit.command.CommandSender sender, AuthMeBia plugin) {
        String version = plugin.getDescription().getVersion();
        String platform = plugin.platformName();
        Plugin authme = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = plugin.getServer().getPluginManager().getPlugin("AuthMeReloaded");
        String authmeVer = authme != null ? authme.getDescription().getVersion() : "unknown";
        sender.sendMessage(MiniMessage.miniMessage().deserialize(
            "<gold>AuthMeBia</gold> <white>v" + version + "</white>\n" +
            "<gray>Author: </gray><white>mytai20100</white>\n" +
            "<gray>GitHub: </gray><aqua><click:open_url:'https://github.com/mytai20100/authmebia'>https://github.com/mytai20100/authmebia</click></aqua>\n" +
            "<gray>Platform: </gray><white>" + platform + "</white>\n" +
            "<gray>AuthMe: </gray><white>" + authmeVer + "</white>"
        ));
    }
}
