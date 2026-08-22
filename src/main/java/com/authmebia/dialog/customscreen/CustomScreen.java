package com.authmebia.dialog.customscreen;

import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Dialoglib;
import com.authmebia.dialog.util.IconSpec;
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
        CheckboxAction checkboxAction
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
        if (!screen.enabled()) return;
        UUID uuid = player.getUniqueId();
        try {
            if (screen.soundOnShow() != null) {
                Dialoglib.playSound(player, screen.soundOnShow());
            }

            List<ActionButton> buttons = buildCustomButtons(screen, uuid, playerName, null);

            if (buttons.isEmpty()) {
                buttons.add(ActionButton.builder(Component.text("OK"))
                        .width(screen.buttonWidth())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, null, null), ClickCallback.Options.builder().build()))
                        .build());
            }

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
        if (!conn.isConnected()) return;
        UUID uuid = conn.getProfile().getId();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            List<ActionButton> buttons = buildCustomButtons(screen, uuid, playerName, latch);

            if (buttons.isEmpty()) {
                buttons.add(ActionButton.builder(Component.text("OK"))
                        .width(screen.buttonWidth())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, null, latch), ClickCallback.Options.builder().build()))
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

    private static final String DISMISS_INPUT_KEY = "dismiss_forever";

    private static List<DialogInput> checkboxInputs(com.authmebia.dialog.customscreen.CustomScreen screen) {
        if (screen.checkboxLabel() == null) return List.of();
        return List.of(DialogInput.bool(DISMISS_INPUT_KEY, screen.checkboxLabel())
                .initial(false)
                .build());
    }

    private static DialogActionCallback dismissAwareCallback(
            com.authmebia.dialog.customscreen.CustomScreen screen, UUID uuid,
            DialogActionCallback inner, CountDownLatch latchOrNull) {
        return (DialogResponseView r, Audience a) -> {
            try {
                if (screen.checkboxAction() == CheckboxAction.CLOSE && screen.checkboxLabel() != null) {
                    Boolean checked = r.getBoolean(DISMISS_INPUT_KEY);
                    if (Boolean.TRUE.equals(checked)) {
                        AuthMeBia.get().screenDismissStore().markDismissed(uuid, screen.id());
                    }
                }
                if (inner != null) {
                    Dialoglib.runCallback(inner, r, a);
                }
            } finally {
                if (latchOrNull != null) latchOrNull.countDown();
            }
        };
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
                                                         String playerName, CountDownLatch latchOrNull) {
        List<ActionButton> buttons = new ArrayList<>();
        for (com.authmebia.dialog.customscreen.CustomScreen.Button btn : screen.buttons()) {
            String value = btn.value() != null ? btn.value().replace("{player}", playerName) : "";
            DialogActionCallback soundCb = (r, a) -> {
                if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
            };
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
                                dismissAwareCallback(screen, uuid, (r, a) -> {
                                    if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
                                    if (a instanceof Player p && !value.isBlank()) {
                                        p.performCommand(value.startsWith("/") ? value.substring(1) : value);
                                    }
                                }, latchOrNull),
                                ClickCallback.Options.builder().build()))
                        .build();
                case CONSOLE -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, (r, a) -> {
                                    if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
                                    if (!value.isBlank()) {
                                        AuthMeBia.get().getServer().dispatchCommand(
                                                AuthMeBia.get().getServer().getConsoleSender(),
                                                value.startsWith("/") ? value.substring(1) : value);
                                    }
                                }, latchOrNull),
                                ClickCallback.Options.builder().build()))
                        .build();
                case CLOSE -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.customClick(
                                dismissAwareCallback(screen, uuid, soundCb, latchOrNull),
                                ClickCallback.Options.builder().build()))
                        .build();
            });
        }
        return buttons;
    }
}
