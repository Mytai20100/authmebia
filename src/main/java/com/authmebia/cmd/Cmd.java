package com.authmebia.cmd;

import com.authmebia.AuthMeBia;
import com.authmebia.cmd.add.Add;
import com.authmebia.cmd.debug.Debug;
import com.authmebia.cmd.help.Help;
import com.authmebia.cmd.info.Info;
import com.authmebia.cmd.recover.Recover;
import com.authmebia.cmd.reload.Reload;
import com.authmebia.cmd.rm.Remove;
import com.authmebia.cmd.screen.Screen;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

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
                    .requires(source -> source.getSender().isOp())
                    .executes(ctx -> { Reload.execute(ctx.getSource().getSender(), plugin); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("rl")
                    .requires(source -> source.getSender().isOp())
                    .executes(ctx -> { Reload.execute(ctx.getSource().getSender(), plugin); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("help")
                    .executes(ctx -> { Help.execute(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("info")
                    .executes(ctx -> { Info.execute(ctx.getSource().getSender(), plugin); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("add")
                    .requires(source -> source.getSender().hasPermission("authmebia.bypass"))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ctx.getSource().getSender().getServer().getOnlinePlayers()
                                .forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            UUID uuid = resolveUuid(name);
                            if (uuid == null) {
                                ctx.getSource().getSender().sendMessage(
                                    net.kyori.adventure.text.Component.text(
                                        "Could not resolve a UUID for '" + name + "'. The player must be online or have joined this server before."));
                                return Command.SINGLE_SUCCESS;
                            }
                            Add.execute(ctx.getSource().getSender(), uuid, name, plugin);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("rm")
                    .requires(source -> source.getSender().hasPermission("authmebia.bypass"))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ctx.getSource().getSender().getServer().getOnlinePlayers()
                                .forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            UUID uuid = resolveUuid(name);
                            if (uuid == null) {
                                ctx.getSource().getSender().sendMessage(
                                    net.kyori.adventure.text.Component.text("Could not resolve a UUID for '" + name + "'."));
                                return Command.SINGLE_SUCCESS;
                            }
                            Remove.execute(ctx.getSource().getSender(), uuid, name, plugin);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("recover")
                    .requires(source -> source.getSender().hasPermission("bia.admin.recover"))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ctx.getSource().getSender().getServer().getOnlinePlayers()
                                .forEach(p -> builder.suggest(p.getName()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            UUID uuid = resolveUuid(name);
                            if (uuid == null) {
                                ctx.getSource().getSender().sendMessage(
                                    net.kyori.adventure.text.Component.text(
                                        "Could not resolve a UUID for '" + name + "'. The player must be online or have joined this server before."));
                                return Command.SINGLE_SUCCESS;
                            }
                            Recover.execute(ctx.getSource().getSender(), uuid, name, plugin);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("debug")
                    .requires(source -> source.getSender().isOp())
                    .then(Commands.argument("feature", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String f : new String[]{"captcha", "email", "register", "login", "wait", "recover", "rule"})
                                builder.suggest(f);
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("value", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                builder.suggest("true");
                                builder.suggest("false");
                                builder.suggest("show");
                                return builder.buildFuture();
                            })
                            .executes(ctx -> {
                                Debug.execute(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "feature"),
                                    StringArgumentType.getString(ctx, "value"),
                                    plugin, this::runOnPlayer);
                                return Command.SINGLE_SUCCESS;
                            }))))
                .then(Commands.literal("notifier")
                    .requires(source -> source.getSender().isOp())
                    .then(Commands.argument("toast", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            plugin.cfg().toasts().forEach(t -> builder.suggest(t.name()));
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                ctx.getSource().getSender().getServer().getOnlinePlayers()
                                    .forEach(p -> builder.suggest(p.getName()));
                                return builder.buildFuture();
                            })
                            .then(Commands.argument("show", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("show");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    com.authmebia.cmd.notifier.Notifier.execute(
                                        ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "toast"),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "show"),
                                        null,
                                        plugin);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("seconds", StringArgumentType.word())
                                    .executes(ctx -> {
                                        com.authmebia.cmd.notifier.Notifier.execute(
                                            ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "toast"),
                                            StringArgumentType.getString(ctx, "player"),
                                            StringArgumentType.getString(ctx, "show"),
                                            StringArgumentType.getString(ctx, "seconds"),
                                            plugin);
                                        return Command.SINGLE_SUCCESS;
                                    }))))))
                .then(Commands.literal("screen")
                    .requires(source -> source.getSender().isOp())
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            plugin.cfg().customScreens().forEach(s -> builder.suggest(s.id()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            Screen.execute(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "id"), null, plugin, this::runOnPlayer);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                ctx.getSource().getSender().getServer().getOnlinePlayers()
                                    .forEach(p -> builder.suggest(p.getName()));
                                return builder.buildFuture();
                            })
                            .executes(ctx -> {
                                Screen.execute(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "player"),
                                    plugin, this::runOnPlayer);
                                return Command.SINGLE_SUCCESS;
                            }))))
                .executes(ctx -> { Help.execute(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                .build(),
            "AuthMeBia main command",
            java.util.List.of("bia")
        );
    }

    private void runOnPlayer(Player player, Runnable task) {
        if (plugin.isFolia()) {
            player.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
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
