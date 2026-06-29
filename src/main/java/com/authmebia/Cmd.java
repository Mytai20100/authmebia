package com.authmebia;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
                    .requires(source -> source.getSender().isOp())
                    .executes(ctx -> { reload(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("rl")
                    .requires(source -> source.getSender().isOp())
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
                .then(Commands.literal("recover")
                    .requires(source -> source.getSender().hasPermission("bia.admin.recover"))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            recover(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("debug")
                    .requires(source -> source.getSender().isOp())
                    .then(Commands.argument("feature", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.word())
                            .executes(ctx -> {
                                debug(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "feature"),
                                    StringArgumentType.getString(ctx, "value")
                                );
                                return Command.SINGLE_SUCCESS;
                            }))))
                .then(Commands.literal("screen")
                    .requires(source -> source.getSender().isOp())
                    .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            showScreen(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "id"), null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("player", StringArgumentType.word())
                            .executes(ctx -> {
                                showScreen(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "id"),
                                    StringArgumentType.getString(ctx, "player"));
                                return Command.SINGLE_SUCCESS;
                            }))))
                .executes(ctx -> { help(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; })
                .build(),
            "AuthMeBia main command",
            java.util.List.of("bia")
        );
    }

    private void reload(org.bukkit.command.CommandSender sender) {
        plugin.cfg().reload();
        plugin.lang().reload();

        if (plugin.authMeListener() != null) {
            plugin.authMeListener().refreshAuthMeConfigCache();
        }

        ProtocolGate.reset();

        sender.sendMessage(Component.text("AuthMeBia config and lang reloaded."));
    }

    private void help(org.bukkit.command.CommandSender sender) {
        boolean op = sender.isOp();
        StringBuilder sb = new StringBuilder();
        sb.append("/bia reload | /bia rl  - Reload config (OP only)\n");
        sb.append("/bia info              - Plugin info\n");
        sb.append("/bia add <player>      - Add a player to the dialog bypass list\n");
        sb.append("/bia rm <player>       - Remove a player from the dialog bypass list\n");
        sb.append("/bia recover <player>  - Force a password reset on the player's next login\n");
        if (op) {
            sb.append("/bia debug <feature> <true|false|show>  - Test a feature (OP only)\n");
            sb.append("  Features: captcha, email, register, login, wait, recover, rule\n");
            sb.append("  Use 'show' with captcha/email to preview the dialog GUI\n");
            sb.append("/bia screen <id> [player]  - Show a custom screen to a player (OP only)\n");
        }
        sb.append("/bia help              - Show this help");
        sender.sendMessage(Component.text(sb.toString()));
    }

    private void info(org.bukkit.command.CommandSender sender) {
        String version = plugin.getDescription().getVersion();
        String platform = plugin.platformName();
        Plugin authme = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = plugin.getServer().getPluginManager().getPlugin("AuthMeReloaded");
        String authmeVer = authme != null ? authme.getDescription().getVersion() : "unknown";
        sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
            "<gold>AuthMeBia</gold> <white>v" + version + "</white>\n" +
            "<gray>Author: </gray><white>mytai20100</white>\n" +
            "<gray>GitHub: </gray><aqua><click:open_url:\'https://github.com/mytai20100/authmebia\'>https://github.com/mytai20100/authmebia</click></aqua>\n" +
            "<gray>Platform: </gray><white>" + platform + "</white>\n" +
            "<gray>AuthMe: </gray><white>" + authmeVer + "</white>"
        ));
    }

    private void debug(org.bukkit.command.CommandSender sender, String feature, String rawValue) {
        AuthMe authMe = plugin.authMeListener();
        Cfg cfg = plugin.cfg();
        boolean isShow = "show".equalsIgnoreCase(rawValue);
        boolean value = Boolean.parseBoolean(rawValue);

        switch (feature.toLowerCase()) {
            case "captcha" -> {
                if (isShow) {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage(Component.text("[debug] Must be a player to preview captcha GUI.", NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(Component.text("[debug] Showing captcha dialog...", NamedTextColor.YELLOW));
                    runOnPlayer(p, () -> Menu.showCaptchaIngame(p, cfg, plugin.lang(), plugin.captcha()));
                    return;
                }
                if (authMe != null) {
                    authMe.overrideCachedAuthMeCaptchaEnabled(value);
                    sender.sendMessage(Component.text(
                        "[debug] AuthMe captcha override set to " + value + ". "
                        + "AuthMeBia captcha.enabled=" + cfg.captchaEnabled() + ". "
                        + "captchaRequired() will return " + (cfg.captchaEnabled() && value),
                        NamedTextColor.YELLOW));
                } else {
                    sender.sendMessage(Component.text("[debug] AuthMe listener not available.", NamedTextColor.RED));
                }
            }
            case "email" -> {
                if (isShow) {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage(Component.text("[debug] Must be a player to preview email GUI.", NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(Component.text("[debug] Showing email verify dialog (dummy email, code: 123456)...", NamedTextColor.YELLOW));
                    runOnPlayer(p, () -> Menu.showEmailVerifyDebugIngame(p, cfg, plugin.lang()));
                    return;
                }
                if (authMe != null) {
                    authMe.overrideCachedEmailEnabled(value);
                    sender.sendMessage(Component.text(
                        "[debug] AuthMe email override set to " + value + ". "
                        + "AuthMeBia email.enabled=" + cfg.emailEnabled() + ". "
                        + "isEmailVerificationActive() will return " + (cfg.emailEnabled() && value),
                        NamedTextColor.YELLOW));
                } else {
                    sender.sendMessage(Component.text("[debug] AuthMe listener not available.", NamedTextColor.RED));
                }
            }
            case "register" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the register GUI.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("[debug] Showing register dialog...", NamedTextColor.YELLOW));
                Runnable show = () -> {
                    if (authMe != null) Menu.showRegisterIngame(p, cfg, plugin.lang(), authMe);
                };
                runOnPlayer(p, show);
            }
            case "login" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the login GUI.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("[debug] Showing login dialog...", NamedTextColor.YELLOW));
                Runnable show = () -> {
                    if (authMe != null) Menu.showLoginIngame(p, cfg, plugin.lang(), authMe, plugin.ipGuard());
                };
                runOnPlayer(p, show);
            }
            case "wait" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the wait dialog.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("[debug] Showing wait dialog...", NamedTextColor.YELLOW));
                runOnPlayer(p, () -> Menu.showWaitDialog(p, cfg));
            }
            case "recover" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the recover GUI.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("[debug] Showing recover dialog...", NamedTextColor.YELLOW));
                Runnable show = () -> {
                    if (authMe != null) {
                        Menu.showRecoverIngame(p, cfg, authMe, () ->
                            p.sendMessage(Component.text("[debug] Recover submitted.", NamedTextColor.GREEN)));
                    }
                };
                runOnPlayer(p, show);
            }
            case "rule" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("[debug] Must be a player to test GUI.", NamedTextColor.RED));
                    return;
                }
                if (!value) {
                    sender.sendMessage(Component.text("[debug] Use 'true' to show the rule dialog.", NamedTextColor.RED));
                    return;
                }
                sender.sendMessage(Component.text("[debug] Showing rule dialog... (result goes to console)", NamedTextColor.YELLOW));
                p.sendMessage(Component.text(
                    "[debug] Rule dialog content:\nTitle: " + cfg.ruleTitle(p.getName()).toString() + "\n"
                    + "Note: rule dialog is pre-spawn only. To test fully, set dialog.menu=true and rejoin.",
                    NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text(
                "[debug] Unknown feature '" + feature + "'. Valid features: captcha, email, register, login, wait, recover, rule",
                NamedTextColor.RED));
        }
    }

    private void showScreen(org.bukkit.command.CommandSender sender, String id, String targetName) {
        CustomScreen screen = plugin.cfg().customScreen(id);
        if (screen == null) {
            sender.sendMessage(Component.text(
                "No custom screen with id '" + id + "' found in config.yml.", NamedTextColor.RED));
            return;
        }

        Player target;
        if (targetName != null) {
            target = plugin.getServer().getPlayerExact(targetName);
            if (target == null) {
                sender.sendMessage(Component.text("Player '" + targetName + "' is not online.", NamedTextColor.RED));
                return;
            }
        } else {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Component.text("Console must specify a target player: /bia screen <id> <player>", NamedTextColor.RED));
                return;
            }
            target = p;
        }

        Player finalTarget = target;
        runOnPlayer(finalTarget, () -> Menu.showCustomScreen(finalTarget, screen, finalTarget.getName()));
        if (sender != target) {
            sender.sendMessage(Component.text("Screen '" + id + "' shown to " + target.getName() + ".", NamedTextColor.GREEN));
        }
    }

    private void runOnPlayer(Player player, Runnable task) {
        if (plugin.isFolia()) {
            player.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
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

    private void recover(org.bukkit.command.CommandSender sender, String name) {
        UUID uuid = resolveUuid(name);
        if (uuid == null) {
            sender.sendMessage(Component.text(
                "Could not resolve a UUID for '" + name + "'. The player must be online or have joined this server before."));
            return;
        }

        AuthMe authMe = plugin.authMeListener();
        if (authMe != null && !authMe.isRegisteredByName(name)) {
            sender.sendMessage(Component.text(name + " is not registered with AuthMe, so there is no password to reset."));
            return;
        }

        plugin.recoverStore().flag(uuid, name);

        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null && authMe != null) {
            Runnable show = () -> Menu.showRecoverIngame(online, plugin.cfg(), authMe, () -> {
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

    private UUID resolveUuid(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) return online.getUniqueId();

        OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
        if (cached != null) return cached.getUniqueId();

        return null;
    }
}
