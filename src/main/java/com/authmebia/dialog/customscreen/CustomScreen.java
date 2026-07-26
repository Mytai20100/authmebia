package com.authmebia.dialog.customscreen;

import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Dialoglib;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static com.authmebia.dialog.Dialoglib.buildBase;

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
        String soundOnShow
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

    public static void showCustomScreen(Player player, com.authmebia.dialog.customscreen.CustomScreen screen, String playerName) {
        if (!screen.enabled()) return;
        try {
            if (screen.soundOnShow() != null) {
                Dialoglib.playSound(player, screen.soundOnShow());
            }

            List<ActionButton> buttons = buildCustomButtons(screen, player, playerName, null);

            if (buttons.isEmpty()) {
                buttons.add(ActionButton.builder(Component.text("OK"))
                        .width(screen.buttonWidth())
                        .action(DialogAction.customClick((r, a) -> {}, ClickCallback.Options.builder().build()))
                        .build());
            }

            Component title = screen.title() != null ? screen.title() : Component.text("Notice");
            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(title, screen.content(), screen.allowClose(), List.of()))
                    .type(DialogType.multiAction(buttons, null, 1))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }

    public static void showCustomScreenBlocking(
            PlayerConfigurationConnection conn,
            com.authmebia.dialog.customscreen.CustomScreen screen, String playerName) {
        if (!screen.enabled()) return;
        if (!conn.isConnected()) return;
        try {
            CountDownLatch latch = new CountDownLatch(1);
            List<ActionButton> buttons = buildCustomButtons(screen, null, playerName, latch);

            if (buttons.isEmpty()) {
                buttons.add(ActionButton.builder(Component.text("OK"))
                        .width(screen.buttonWidth())
                        .action(DialogAction.customClick((r, a) -> latch.countDown(), ClickCallback.Options.builder().build()))
                        .build());
            }

            Component title = screen.title() != null ? screen.title() : Component.text("Notice");
            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(title, screen.content(), screen.allowClose(), List.of()))
                    .type(DialogType.multiAction(buttons, null, 1))
            ));
            Dialoglib.await(latch);
        } catch (NoClassDefFoundError ignored) {}
    }

    private static List<ActionButton> buildCustomButtons(com.authmebia.dialog.customscreen.CustomScreen screen, Player playerOrNull,
                                                         String playerName, CountDownLatch latchOrNull) {
        List<ActionButton> buttons = new ArrayList<>();
        for (com.authmebia.dialog.customscreen.CustomScreen.Button btn : screen.buttons()) {
            String value = btn.value() != null ? btn.value().replace("{player}", playerName) : "";
            DialogActionCallback soundCb = (r, a) -> {
                if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
                if (latchOrNull != null) latchOrNull.countDown();
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
                        .action(DialogAction.customClick((r, a) -> {
                            if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
                            if (a instanceof Player p && !value.isBlank()) {
                                p.performCommand(value.startsWith("/") ? value.substring(1) : value);
                            }
                            if (latchOrNull != null) latchOrNull.countDown();
                        }, ClickCallback.Options.builder().build()))
                        .build();
                case CONSOLE -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.customClick((r, a) -> {
                            if (btn.sound() != null && a instanceof Player p) Dialoglib.playSound(p, btn.sound());
                            if (!value.isBlank()) {
                                AuthMeBia.get().getServer().dispatchCommand(
                                        AuthMeBia.get().getServer().getConsoleSender(),
                                        value.startsWith("/") ? value.substring(1) : value);
                            }
                            if (latchOrNull != null) latchOrNull.countDown();
                        }, ClickCallback.Options.builder().build()))
                        .build();
                case CLOSE -> ActionButton.builder(btn.label())
                        .width(btn.width())
                        .action(DialogAction.customClick(soundCb, ClickCallback.Options.builder().build()))
                        .build();
            });
        }
        return buttons;
    }
}
