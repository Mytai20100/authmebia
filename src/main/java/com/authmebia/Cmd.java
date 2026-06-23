package com.authmebia;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public class Cmd {

    private final AuthMeBia plugin;

    public Cmd(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        commands.register(
            Commands.literal("authmebia")
                .then(Commands.literal("reload")
                    .executes(ctx -> { reload(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("rl")
                    .executes(ctx -> { reload(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("help")
                    .executes(ctx -> { help(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("info")
                    .executes(ctx -> { info(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("add")
                    .requires(source -> source.getSender().hasPermission("authmebia.bypass"))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            addBypass(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("rm")
                    .requires(source -> source.getSender().hasPermission("authmebia.bypass"))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            removeBypass(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .executes(ctx -> { help(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                .build(),
            "AuthMeBia main command",
            java.util.List.of("bia")
        );
    }

    private void reload(org.bukkit.command.CommandSender sender) {
        plugin.cfg().reload();
        plugin.lang().reload();

        // Re-read AuthMe's config.yml so cached values (blind effect, captcha
        // state) stay in sync after an admin edits AuthMe between reloads.
        if (plugin.authMeListener() != null) {
            plugin.authMeListener().refreshAuthMeConfigCache();
        }

        // Reset the ViaVersion lookup cache in case ViaVersion was
        // installed or removed since the last enable/reload.
        ProtocolGate.reset();

        sender.sendMessage(Component.text("AuthMeBia config and lang reloaded."));
    }

    private void help(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(Component.text(
            "/bia reload | /bia rl  - Reload config\n" +
            "/bia info              - Plugin info\n" +
            "/bia add <player>      - Add a player to the dialog bypass list\n" +
            "/bia rm <player>       - Remove a player from the dialog bypass list\n" +
            "/bia help              - Show this help"
        ));
    }

    private void info(org.bukkit.command.CommandSender sender) {
        String version = plugin.getDescription().getVersion();
        String platform = plugin.platformName();
        Plugin authme = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = plugin.getServer().getPluginManager().getPlugin("AuthMeReloaded");
        String authmeVer = authme != null ? authme.getDescription().getVersion() : "unknown";
        sender.sendMessage(Component.text(
            "AuthMeBia v" + version + "\n" +
            "Platform: " + platform + "\n" +
            "AuthMe: " + authmeVer
        ));
    }

    private void addBypass(org.bukkit.command.CommandSender sender, String name) {
        UUID uuid = resolveUuid(name);
        if (uuid == null) {
            sender.sendMessage(Component.text(
                "Could not resolve a UUID for '" + name + "'. The player must be online or have joined this server before."));
            return;
        }

        boolean added = plugin.biaList().add(uuid, name);
        if (added) {
            sender.sendMessage(Component.text(name + " was added to the AuthMeBia bypass list."));
        } else {
            sender.sendMessage(Component.text(name + " is already on the AuthMeBia bypass list."));
        }
    }

    private void removeBypass(org.bukkit.command.CommandSender sender, String name) {
        UUID uuid = resolveUuid(name);
        if (uuid == null) {
            sender.sendMessage(Component.text("Could not resolve a UUID for '" + name + "'."));
            return;
        }

        boolean removed = plugin.biaList().remove(uuid);
        if (removed) {
            sender.sendMessage(Component.text(name + " was removed from the AuthMeBia bypass list."));
        } else {
            sender.sendMessage(Component.text(name + " was not on the AuthMeBia bypass list."));
        }
    }

    private UUID resolveUuid(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) return online.getUniqueId();

        OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
        if (cached != null) return cached.getUniqueId();

        return null;
    }
}
