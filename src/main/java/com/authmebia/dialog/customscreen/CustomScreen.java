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

    /**
     * What happens to this screen for a player once they dismiss it,
     * depending on whether they ticked its checkbox_label checkbox first.
     * Only meaningful when checkboxLabel is non-null -- a screen with no
     * checkbox configured always behaves as SHOW every time, regardless
     * of this value.
     */
    public enum CheckboxAction {
        // Ticking the checkbox before dismissing permanently suppresses
        // this screen for that player (persisted -- see
        // ScreenDismissStore), across future joins and restarts. Leaving
        // it unticked is treated as "skip for now": the screen is not
        // marked dismissed and will show again next time it's triggered.
        CLOSE,
        // The checkbox is purely informational/cosmetic here -- ticked or
        // not, the screen always shows again next time it's triggered.
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

    /**
     * Whether this screen should be skipped for an automatic trigger
     * (postjoin/prejoin), because the player already ticked its checkbox
     * and dismissed it for good. Deliberately NOT checked inside
     * showCustomScreen()/showCustomScreenBlocking() themselves -- those
     * are also the entry point for "/bia screen <id> [player]", which is
     * an explicit admin action that must always show the screen
     * regardless of a player's past dismissal. Only the automatic
     * postjoin/prejoin call sites in AuthMe.java call this first.
     */
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

    /**
     * The DialogInput.bool() field for this screen's checkbox, if
     * configured. Uses a fixed field name (DISMISS_INPUT_KEY) since each
     * screen only ever has zero or one checkbox -- there's nothing to
     * disambiguate.
     */
    private static final String DISMISS_INPUT_KEY = "dismiss_forever";

    private static List<DialogInput> checkboxInputs(com.authmebia.dialog.customscreen.CustomScreen screen) {
        if (screen.checkboxLabel() == null) return List.of();
        return List.of(DialogInput.bool(DISMISS_INPUT_KEY, screen.checkboxLabel())
                .initial(false)
                .build());
    }

    /**
     * Wraps a button's own callback so that, right before running it,
     * this screen's checkbox (if any) is read from the dialog response
     * and -- only when checkbox_action is CLOSE and the box was ticked --
     * persisted via ScreenDismissStore so the screen never shows again
     * for this player. Leaving the box unticked (or the screen having no
     * checkbox at all, or checkbox_action: show) leaves nothing recorded,
     * so the screen shows again next time it's triggered, same as before
     * this feature existed.
     *
     * uuid is threaded through explicitly (rather than pulled off the
     * Audience parameter) because the pre-join blocking path only has a
     * PlayerConfigurationConnection, not yet a real Player -- see
     * showCustomScreenBlocking.
     */
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

    /**
     * Resolves screen.iconName() (a "custom_icons:" entry) against the
     * screen's title. SPRITE/PLAYER_HEAD icons are prepended straight into
     * the title Component; ITEM icons can't be inlined into text, so they
     * come back as a separate leading DialogBody instead (see IconSpec).
     * If iconName is unset or fails to resolve, this is a no-op and just
     * returns the screen's plain title with no leading body.
     */
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
                // OPEN_URL/COPY stay Paper's client-only staticAction --
                // there is no server-side equivalent of ClickEvent.openUrl
                // /copyToClipboard to call from inside a customClick
                // callback, and staticAction itself never round-trips to
                // the server at all. That means a checkbox on a screen
                // whose only button is OPEN_URL or COPY can never be read
                // (nothing ever calls the server to report it), so
                // checkbox_action: close only takes effect through a
                // close/command/console button. This is a real Paper API
                // limitation, not a bug in this class; see the
                // checkbox_action config comment, which calls this out so
                // admins don't configure a checkbox that can never fire.
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
