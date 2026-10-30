package com.authmebia.dialog.customscreen;

import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.cfg.TitleScript;
import com.authmebia.dialog.Dialoglib;
import com.authmebia.dialog.util.IconSpec;
import com.authmebia.dialog.util.Util;
import com.authmebia.service.ConnectionState;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("UnstableApiUsage")
public record CustomScreen(
        String id,
        boolean enabled,
        Component title,
        Component content,
        boolean allowClose,
        int buttonWidth,
        List<Button> buttons,
        Trigger trigger,
        String soundOnShow,
        String iconName,
        Component checkboxLabel,
        CheckboxAction checkboxAction,
        TitleScript titleScript
) {

    public enum Trigger {
        COMMAND, POSTJOIN, PREJOIN;

        public static Trigger parse(String raw) {
            if (raw == null) return COMMAND;
            return switch (raw.trim().toLowerCase()) {
                case "postjoin" -> POSTJOIN;
                case "prejoin"  -> PREJOIN;
                default         -> COMMAND;
            };
        }
    }
    public enum CheckboxAction {
        CLOSE,
        SHOW;

        public static CheckboxAction parse(String raw) {
            if (raw == null) return SHOW;
            return switch (raw.trim().toLowerCase()) {
                case "close" -> CLOSE;
                default      -> SHOW;
            };
        }
    }

    public record Button(Component label, Action action, String value, int width, String sound) {
        public enum Action {
            CLOSE, OPEN_URL, COPY,
            COMMAND,
            CONSOLE;

            public static Action parse(String raw) {
                if (raw == null) return CLOSE;
                return switch (raw.trim().toLowerCase()) {
                    case "open_url" -> OPEN_URL;
                    case "copy"     -> COPY;
                    case "command"  -> COMMAND;
                    case "console"  -> CONSOLE;
                    default         -> CLOSE;
                };
            }
        }
    }

    public static boolean isAutoDismissedFor(com.authmebia.dialog.customscreen.CustomScreen screen, UUID uuid) {
        return screen.checkboxAction() == CheckboxAction.CLOSE
                && AuthMeBia.get().screenDismissStore().isDismissed(uuid, screen.id());
    }

    public static void showCustomScreen(Player player, com.authmebia.dialog.customscreen.CustomScreen screen, String playerName) {
        showCustomScreen(player, screen, playerName, null);
    }

    public static void showCustomScreen(Player player, com.authmebia.dialog.customscreen.CustomScreen screen,
                                        String playerName, Runnable afterDismiss) {
        if (!screen.enabled() || player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        try {
            if (screen.soundOnShow() != null) {
                Dialoglib.playSound(player, screen.soundOnShow());
            }

            List<ActionButton> buttons = buildCustomButtons(screen, uuid, playerName, null, afterDismiss);

            if (buttons.isEmpty()) {
                buttons.add(ActionButton.builder(Component.text("OK"))
                        .width(screen.buttonWidth())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, null, null, afterDismiss),
                                ClickCallback.Options.builder().build()))
                        .build());
            }

            playTitles(player, screen);
            IconTitle iconTitle = resolveIcon(screen, playerName);
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(Dialoglib.buildBase(iconTitle.title(), screen.content(), screen.allowClose(), checkboxInputs(screen),
                            null, iconTitle.leadingBody()))
                    .type(DialogType.multiAction(buttons, null, 1))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }

    public static void showCustomScreenBlocking(
            PlayerConfigurationConnection conn,
            com.authmebia.dialog.customscreen.CustomScreen screen, String playerName) {
        if (!screen.enabled()) return;
        if (!ConnectionState.isConnected(conn)) return;
        UUID uuid = conn.getProfile().getId();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            List<ActionButton> buttons = buildCustomButtons(screen, uuid, playerName, latch, null);

            if (buttons.isEmpty()) {
                buttons.add(ActionButton.builder(Component.text("OK"))
                        .width(screen.buttonWidth())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, null, latch, null),
                                ClickCallback.Options.builder().build()))
                        .build());
            }

            IconTitle iconTitle = resolveIcon(screen, playerName);
            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(Dialoglib.buildBase(iconTitle.title(), screen.content(), screen.allowClose(), checkboxInputs(screen),
                            null, iconTitle.leadingBody()))
                    .type(DialogType.multiAction(buttons, null, 1))
            ));
            Dialoglib.await(latch);
        } catch (NoClassDefFoundError ignored) {}
    }

    private static void playTitles(Player player, CustomScreen screen) {
        TitleScript script = screen.titleScript();
        if (script == null || script.isEmpty() || !player.isOnline()) return;
        AuthMeBia plugin = AuthMeBia.get();
        if (plugin == null) return;
        com.authmebia.AuthMe auth = plugin.authMeListener();
        if (auth == null || !auth.isAuthenticated(player)) return;
        java.util.Set<String> triggers = new java.util.HashSet<>();
        triggers.add("is_login");
        if (auth.isFirstJoinSession(player.getUniqueId())) {
            triggers.add("is_firstjoin");
        } else {
            triggers.add("is_rejoin");
        }
        if (auth.wasRegisteredThisSession(player.getUniqueId())) {
            triggers.add("is_registed");
        }
        plugin.titles().play(player, script, triggers);
    }

    private static final String DISMISS_INPUT_KEY = "dismiss_forever";

    @FunctionalInterface
    private interface CompletionAwareAction {
        void run(DialogResponseView response, Audience audience, Runnable completion);
    }

    private static List<DialogInput> checkboxInputs(com.authmebia.dialog.customscreen.CustomScreen screen) {
        if (screen.checkboxLabel() == null) return List.of();
        return List.of(DialogInput.bool(DISMISS_INPUT_KEY, screen.checkboxLabel())
                .initial(false)
                .build());
    }

    private static DialogActionCallback dismissAwareCallback(
            com.authmebia.dialog.customscreen.CustomScreen screen, UUID uuid,
            CompletionAwareAction action, CountDownLatch latchOrNull, Runnable afterDismiss) {
        return (DialogResponseView r, Audience a) -> {
            AtomicBoolean completed = new AtomicBoolean();
            Runnable completion = () -> {
                if (!completed.compareAndSet(false, true)) return;
                if (latchOrNull != null) latchOrNull.countDown();
                runAfterDismiss(afterDismiss);
            };
            try {
                if (screen.checkboxAction() == CheckboxAction.CLOSE && screen.checkboxLabel() != null) {
                    Boolean checked = r.getBoolean(DISMISS_INPUT_KEY);
                    if (Boolean.TRUE.equals(checked)) {
                        AuthMeBia plugin = AuthMeBia.get();
                        if (plugin != null) plugin.screenDismissStore().markDismissed(uuid, screen.id());
                    }
                }
                if (action == null) {
                    completion.run();
                } else {
                    action.run(r, a, completion);
                }
            } catch (Throwable t) {
                completion.run();
                throw t;
            }
        };
    }

    private static void runAfterDismiss(Runnable afterDismiss) {
        if (afterDismiss == null) return;
        try {
            afterDismiss.run();
        } catch (Throwable t) {
            AuthMeBia plugin = AuthMeBia.get();
            if (plugin != null) {
                plugin.getLogger().warning("Custom screen sequence failed: " + t.getMessage());
            }
        }
    }

    private record IconTitle(Component title, DialogBody leadingBody) {}

    private static IconTitle resolveIcon(com.authmebia.dialog.customscreen.CustomScreen screen, String playerName) {
        Component title = screen.title() != null ? screen.title() : Component.text("Notice");
        IconSpec icon = AuthMeBia.get().cfg().icon(screen.iconName());
        if (icon == null) return new IconTitle(title, null);

        Component inline = icon.toInlineComponent(playerName);
        if (inline != null) {
            return new IconTitle(inline.appendSpace().append(title), null);
        }
        return new IconTitle(title, icon.toItemBody());
    }

    private static List<ActionButton> buildCustomButtons(com.authmebia.dialog.customscreen.CustomScreen screen, UUID uuid,
                                                         String playerName, CountDownLatch latchOrNull,
                                                         Runnable afterDismiss) {
        List<ActionButton> buttons = new ArrayList<>();
        boolean hasAdvancingButton = false;
        for (com.authmebia.dialog.customscreen.CustomScreen.Button btn : screen.buttons()) {
            String value = btn.value() != null ? btn.value().replace("{player}", playerName) : "";
            DialogActionCallback soundCb = (r, a) -> {
                if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
            };
            if (btn.action() == com.authmebia.dialog.customscreen.CustomScreen.Button.Action.CLOSE
                    || btn.action() == com.authmebia.dialog.customscreen.CustomScreen.Button.Action.COMMAND
                    || btn.action() == com.authmebia.dialog.customscreen.CustomScreen.Button.Action.CONSOLE) {
                hasAdvancingButton = true;
            }
            buttons.add(switch (btn.action()) {
                case OPEN_URL -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.staticAction(
                                net.kyori.adventure.text.event.ClickEvent.openUrl(value)))
                        .build();
                case COPY -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.staticAction(
                                net.kyori.adventure.text.event.ClickEvent.copyToClipboard(value)))
                        .build();
                case COMMAND -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, (r, a, completion) -> {
                                    if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
                                    if (!(a instanceof Player p) || value.isBlank()) {
                                        completion.run();
                                        return;
                                    }
                                    String command = value.startsWith("/") ? value.substring(1) : value;
                                    boolean scheduled = Util.runOnMain(p, () -> {
                                        try {
                                            if (p.isOnline()) p.performCommand(command);
                                        } finally {
                                            completion.run();
                                        }
                                    });
                                    if (!scheduled) completion.run();
                                }, latchOrNull, afterDismiss),
                                ClickCallback.Options.builder().build()))
                        .build();
                case CONSOLE -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, (r, a, completion) -> {
                                    if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
                                    if (value.isBlank()) {
                                        completion.run();
                                        return;
                                    }
                                    String command = value.startsWith("/") ? value.substring(1) : value;
                                    boolean scheduled = Util.runGlobal(() -> {
                                        try {
                                            AuthMeBia plugin = AuthMeBia.get();
                                            if (plugin != null) plugin.getServer().dispatchCommand(
                                                    plugin.getServer().getConsoleSender(), command);
                                        } finally {
                                            completion.run();
                                        }
                                    });
                                    if (!scheduled) completion.run();
                                }, latchOrNull, afterDismiss),
                                ClickCallback.Options.builder().build()))
                        .build();
                case CLOSE -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, (r, a, completion) -> {
                                    try {
                                        Dialoglib.runCallback(soundCb, r, a);
                                    } finally {
                                        completion.run();
                                    }
                                }, latchOrNull, afterDismiss),
                                ClickCallback.Options.builder().build()))
                        .build();
            });
        }
        if (!hasAdvancingButton) {
            buttons.add(ActionButton.builder(Component.text("OK"))
                    .width(screen.buttonWidth())
                    .action(DialogAction.customClick(
                            dismissAwareCallback(screen, uuid, null, latchOrNull, afterDismiss),
                            ClickCallback.Options.builder().build()))
                    .build());
        }
        return buttons;
    }
}
