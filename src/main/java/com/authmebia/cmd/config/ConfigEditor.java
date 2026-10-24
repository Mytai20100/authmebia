package com.authmebia.cmd.config;

import com.authmebia.AuthMeBia;
import com.authmebia.dialog.Dialoglib;
import com.authmebia.dialog.util.IconSpec;
import com.authmebia.dialog.util.Util;
import com.authmebia.service.ConfigFile;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConfigEditor {

    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder()
            .lifetime(Duration.ofMinutes(10)).build();
    private static final int TEXT_INPUT_WIDTH = 500;
    private static final int TEXT_INPUT_HEIGHT = 100;

    private ConfigEditor() {}

    public static void execute(CommandSender sender, AuthMeBia plugin) {
        try {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.lang().component("command.player_only",
                        "This command must be run by a player.", Map.of()));
                return;
            }
            if (!player.hasPermission("authmebia.config")) {
                sender.sendMessage(plugin.lang().component("command.no_permission",
                        "You do not have permission to do that.", Map.of()));
                return;
            }
            ConfigFile configFile = new ConfigFile(plugin);
            runAsync(plugin, () -> {
                try {
                    ConfigFile.Snapshot snapshot = configFile.read();
                    ConfigFile.Validation validation = configFile.validate(snapshot.text());
                    Component error = validation.valid() ? null : error(plugin, validation);
                    Util.runOnMain(player, () -> {
                        try {
                            openEditor(plugin, player, configFile, snapshot, error);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Config editor open failed: " + t.getMessage());
                        }
                    });
                } catch (Throwable t) {
                    plugin.getLogger().warning("Config editor read failed: " + t.getMessage());
                    fail(plugin, player, "config_editor.read_failed",
                            "Could not read config.yml.", Map.of());
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Config editor command failed: " + t.getMessage());
        }
    }

    private static void openEditor(AuthMeBia plugin, Player player, ConfigFile configFile,
                                   ConfigFile.Snapshot snapshot, Component error) {
        if (ConfigFile.estimatePayload(snapshot.text()) > ConfigFile.SAFE_PAYLOAD_BYTES) {
            openPicker(plugin, player, configFile, snapshot, error);
        } else {
            openFull(plugin, player, configFile, snapshot, error);
        }
    }

    private static void openFull(AuthMeBia plugin, Player player, ConfigFile configFile,
                                 ConfigFile.Snapshot snapshot, Component error) {
        int maxLength = maxLength(snapshot.text());
        Component body = body(plugin, "config_editor.full_instructions",
                "Edit config.yml below. Save and exit validates the whole file.", error);
        Component label = plugin.lang().component("config_editor.input_label", "config.yml", Map.of());
        DialogInput input = DialogInput.text("content", TEXT_INPUT_WIDTH, label, true, snapshot.text(),
                maxLength, TextDialogInput.MultilineOptions.create(null, TEXT_INPUT_HEIGHT));

        DialogActionCallback saveAction = (DialogResponseView response, Audience audience) -> {
            try {
                if (!(audience instanceof Player p) || !p.isOnline()) return;
                if (!p.hasPermission("authmebia.config")) {
                    p.sendMessage(plugin.lang().component("command.no_permission",
                            "You do not have permission to do that.", Map.of()));
                    return;
                }
                String edited = response.getText("content");
                if (edited == null) {
                    openFull(plugin, p, configFile, snapshot,
                            plugin.lang().component("config_editor.invalid_input",
                                    "The editor returned no text.", Map.of()));
                    return;
                }
                if (ConfigFile.estimatePayload(edited) > ConfigFile.SAFE_PAYLOAD_BYTES) {
                    openPicker(plugin, p, configFile,
                            new ConfigFile.Snapshot(edited, snapshot.eol(), snapshot.hash()),
                            plugin.lang().component("config_editor.too_large",
                                    "The file is too large for one dialog. Choose a section to edit.",
                                    Map.of()));
                    return;
                }
                ConfigFile.Validation validation = configFile.validate(edited);
                if (!validation.valid()) {
                    openFull(plugin, p, configFile,
                            new ConfigFile.Snapshot(edited, snapshot.eol(), snapshot.hash()),
                            error(plugin, validation));
                    return;
                }
                save(plugin, p, configFile, snapshot, edited, null, null);
            } catch (Throwable t) {
                plugin.getLogger().warning("Config editor save callback failed: " + t.getMessage());
            }
        };

        showEditor(plugin, player, body, input, saveAction);
    }

    private static void openPicker(AuthMeBia plugin, Player player, ConfigFile configFile,
                                   ConfigFile.Snapshot snapshot, Component error) {
        List<ConfigFile.Block> blocks = configFile.blocks(snapshot.text());
        List<ActionButton> buttons = new ArrayList<>();
        for (ConfigFile.Block block : blocks) {
            Component label = withIcon(plugin, "config_section",
                    plugin.lang().component("config_editor.section_button",
                            "{key}", Map.of("key", block.key())));
            buttons.add(ActionButton.builder(label).width(200)
                    .action(DialogAction.customClick((DialogResponseView response, Audience audience) -> {
                        try {
                            if (!(audience instanceof Player p) || !p.isOnline()) return;
                            if (!p.hasPermission("authmebia.config")) {
                                p.sendMessage(plugin.lang().component("command.no_permission",
                                        "You do not have permission to do that.", Map.of()));
                                return;
                            }
                            openSection(plugin, p, configFile, snapshot, block, block.text(), null);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Config section open failed: " + t.getMessage());
                        }
                    }, OPTIONS)).build());
        }
        Component exitLabel = withIcon(plugin, "ui_exit",
                plugin.lang().component("config_editor.exit", "Exit", Map.of()));
        buttons.add(ActionButton.builder(exitLabel).width(200)
                .action(DialogAction.customClick((DialogResponseView response, Audience audience) -> {
                }, OPTIONS)).build());
        Component title = plugin.lang().component("config_editor.section_title",
                "bia config - choose a section", Map.of());
        Component body = body(plugin, "config_editor.section_instructions",
                "Choose a top-level section to edit.", error);
        player.showDialog(Dialog.create(d -> d.empty()
                .base(Dialoglib.buildBase(title, body, true, List.of()))
                .type(DialogType.multiAction(buttons, null, 3))));
    }

    private static void openSection(AuthMeBia plugin, Player player, ConfigFile configFile,
                                    ConfigFile.Snapshot snapshot, ConfigFile.Block block,
                                    String sectionText, Component error) {
        if (ConfigFile.estimatePayload(sectionText) > ConfigFile.SAFE_PAYLOAD_BYTES) {
            String full = configFile.reassemble(snapshot.text(), block, sectionText);
            openPicker(plugin, player, configFile,
                    new ConfigFile.Snapshot(full, snapshot.eol(), snapshot.hash()),
                    plugin.lang().component("config_editor.too_large",
                            "The section is too large for one dialog.", Map.of()));
            return;
        }
        int maxLength = maxLength(sectionText);
        Component body = body(plugin, "config_editor.section_instructions",
                "Edit this section below. Save and exit validates the whole file.", error);
        Component label = plugin.lang().component("config_editor.section_label",
                "{key}", Map.of("key", block.key()));
        DialogInput input = DialogInput.text("content", TEXT_INPUT_WIDTH, label, true, sectionText,
                maxLength, TextDialogInput.MultilineOptions.create(null, TEXT_INPUT_HEIGHT));

        DialogActionCallback saveAction = (DialogResponseView response, Audience audience) -> {
            try {
                if (!(audience instanceof Player p) || !p.isOnline()) return;
                if (!p.hasPermission("authmebia.config")) {
                    p.sendMessage(plugin.lang().component("command.no_permission",
                            "You do not have permission to do that.", Map.of()));
                    return;
                }
                String edited = response.getText("content");
                if (edited == null) {
                    openSection(plugin, p, configFile, snapshot, block, sectionText,
                            plugin.lang().component("config_editor.invalid_input",
                                    "The editor returned no text.", Map.of()));
                    return;
                }
                if (ConfigFile.estimatePayload(edited) > ConfigFile.SAFE_PAYLOAD_BYTES) {
                    String full = configFile.reassemble(snapshot.text(), block, edited);
                    openPicker(plugin, p, configFile,
                            new ConfigFile.Snapshot(full, snapshot.eol(), snapshot.hash()),
                            plugin.lang().component("config_editor.too_large",
                                    "The section is too large for one dialog.", Map.of()));
                    return;
                }
                String full = configFile.reassemble(snapshot.text(), block, edited);
                ConfigFile.Validation validation = configFile.validate(full);
                if (!validation.valid()) {
                    openSection(plugin, p, configFile, snapshot, block, edited,
                            error(plugin, validation, block));
                    return;
                }
                save(plugin, p, configFile, snapshot, full, block, edited);
            } catch (Throwable t) {
                plugin.getLogger().warning("Config section save callback failed: " + t.getMessage());
            }
        };

        showEditor(plugin, player, body, input, saveAction);
    }

    private static void save(AuthMeBia plugin, Player player, ConfigFile configFile,
                             ConfigFile.Snapshot snapshot, String text,
                             ConfigFile.Block block, String editedSection) {
        runAsync(plugin, () -> {
            try {
                configFile.writeAtomic(text, snapshot.hash());
                Util.runOnMain(player, () -> {
                    try {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.lang().component("config_editor.saved",
                                    "config.yml saved.", Map.of()));
                        }
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Config editor confirmation failed: " + t.getMessage());
                    }
                });
            } catch (ConfigFile.ConfigException e) {
                Util.runOnMain(player, () -> {
                    try {
                        if (!player.isOnline()) return;
                        if (ConfigFile.KIND_CHANGED.equals(e.kind())) {
                            player.sendMessage(plugin.lang().component("config_editor.changed_on_disk",
                                    "config.yml changed on disk. Reopen /bia config.", Map.of()));
                        } else if (block != null) {
                            openSection(plugin, player, configFile, snapshot, block, editedSection,
                                    error(plugin, new ConfigFile.Validation(false, e.getMessage(),
                                            e.line(), e.snippet()), block));
                        } else {
                            openFull(plugin, player, configFile,
                                    new ConfigFile.Snapshot(text, snapshot.eol(), snapshot.hash()),
                                    error(plugin, new ConfigFile.Validation(false, e.getMessage(),
                                            e.line(), e.snippet())));
                        }
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Config editor error reply failed: " + t.getMessage());
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().warning("Config editor write failed: " + t.getMessage());
                fail(plugin, player, "config_editor.write_failed",
                        "Could not save config.yml.", Map.of());
            }
        });
    }

    private static void showEditor(AuthMeBia plugin, Player player, Component body,
                                   DialogInput input, DialogActionCallback saveAction) {
        Component title = plugin.lang().component("config_editor.title", "bia config", Map.of());
        Component saveLabel = withIcon(plugin, "ui_save",
                plugin.lang().component("config_editor.save_exit", "Save & exit", Map.of()));
        Component exitLabel = withIcon(plugin, "ui_exit",
                plugin.lang().component("config_editor.exit", "Exit", Map.of()));
        ActionButton save = ActionButton.builder(saveLabel).width(200)
                .action(DialogAction.customClick(saveAction, OPTIONS)).build();
        ActionButton exit = ActionButton.builder(exitLabel).width(200)
                .action(DialogAction.customClick((DialogResponseView response, Audience audience) -> {
                }, OPTIONS)).build();
        player.showDialog(Dialog.create(d -> d.empty()
                .base(Dialoglib.buildBase(title, body, true, List.of(input)))
                .type(DialogType.multiAction(List.of(save, exit), null, 2))));
    }

    private static Component withIcon(AuthMeBia plugin, String iconName, Component label) {
        try {
            IconSpec spec = plugin.cfg().icon(iconName);
            if (spec == null) return label;
            Component icon = spec.toInlineComponent(null);
            if (icon == null) return label;
            return icon.append(Component.space()).append(label);
        } catch (Throwable t) {
            return label;
        }
    }

    private static Component body(AuthMeBia plugin, String key, String fallback, Component error) {
        Component body = plugin.lang().component(key, fallback, Map.of());
        if (error != null) {
            body = body.append(Component.newline()).append(error);
        }
        return body;
    }

    private static Component error(AuthMeBia plugin, ConfigFile.Validation validation) {
        return error(plugin, validation, null);
    }

    private static Component error(AuthMeBia plugin, ConfigFile.Validation validation,
                                   ConfigFile.Block block) {
        String line = validation.line() > 0
                ? Integer.toString(validation.line())
                : plugin.lang().plain("config_editor.unknown_line", "unknown", Map.of());
        String reason = validation.reason() == null || validation.reason().isBlank()
                ? plugin.lang().plain("config_editor.unknown_reason", "invalid YAML", Map.of())
                : validation.reason();
        if (block != null) {
            String sectionLine = validation.line() > 0
                    ? Integer.toString(Math.max(1, validation.line() - block.startLine() + 1))
                    : plugin.lang().plain("config_editor.unknown_line", "unknown", Map.of());
            return plugin.lang().component("config_editor.section_validation_error",
                    "<red>Section line {section_line} (file line {file_line}): {reason}</red>",
                    Map.of("section_line", sectionLine, "file_line", line, "reason", reason));
        }
        return plugin.lang().component("config_editor.validation_error",
                "<red>Line {line}: {reason}</red>", Map.of("line", line, "reason", reason));
    }

    private static int maxLength(String text) {
        long desired = (long) text.length() + 1024L;
        return (int) Math.min(ConfigFile.SAFE_PAYLOAD_BYTES, Math.max(2048L, desired));
    }

    private static void fail(AuthMeBia plugin, Player player, String key, String fallback,
                             Map<String, String> values) {
        Util.runOnMain(player, () -> {
            try {
                if (player.isOnline()) {
                    player.sendMessage(plugin.lang().component(key, fallback, values));
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Config editor reply failed: " + t.getMessage());
            }
        });
    }

    private static void runAsync(AuthMeBia plugin, Runnable task) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }
}
