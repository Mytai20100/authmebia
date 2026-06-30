package com.authmebia;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@SuppressWarnings("UnstableApiUsage")
public final class AuthInput {

    private static final long DIALOG_AWAIT_SECONDS = 30L;
    private static final java.util.Map<UUID, StringBuilder> PIN_SESSIONS = new ConcurrentHashMap<>();
    private static final java.util.Map<UUID, int[]> SLIDER_SESSIONS = new ConcurrentHashMap<>();

    private AuthInput() {}

    public static void clearSession(UUID uuid) {
        PIN_SESSIONS.remove(uuid);
        SLIDER_SESSIONS.remove(uuid);
    }

    public static String collectPinBlocking(PlayerConfigurationConnection conn, Cfg cfg, String statusLine) {
        int length = cfg.pinLength();
        StringBuilder sb = new StringBuilder();
        AtomicReference<String> result = new AtomicReference<>(null);
        AtomicBoolean logout = new AtomicBoolean(false);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            conn.getAudience().showDialog(buildPinDialog(cfg, length, sb, statusLine,
                    digit -> { if (sb.length() < length) sb.append(digit); latch.countDown(); },
                    () -> { if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1); latch.countDown(); },
                    () -> {
                        if (sb.length() == length) result.set(sb.toString());
                        latch.countDown();
                    },
                    () -> { logout.set(true); latch.countDown(); }));
            await(latch);
            if (logout.get()) return null;
            if (result.get() != null) return result.get();
        }
        return null;
    }

    public static String collectSliderBlocking(PlayerConfigurationConnection conn, Cfg cfg, String statusLine) {
        int length = cfg.sliderLength();
        int[] digits = new int[length];
        AtomicReference<String> result = new AtomicReference<>(null);
        AtomicBoolean logout = new AtomicBoolean(false);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            conn.getAudience().showDialog(buildSliderDialog(cfg, length, digits, statusLine,
                    pos -> { digits[pos] = (digits[pos] + 1) % 10; latch.countDown(); },
                    pos -> { digits[pos] = (digits[pos] + 9) % 10; latch.countDown(); },
                    () -> { result.set(digitsToString(digits)); latch.countDown(); },
                    () -> { logout.set(true); latch.countDown(); }));
            await(latch);
            if (logout.get()) return null;
            if (result.get() != null) return result.get();
        }
        return null;
    }

    public static void showPinIngame(Player player, Cfg cfg, String statusLine,
                                     Consumer<String> onConfirm, Runnable onLogout) {
        PIN_SESSIONS.put(player.getUniqueId(), new StringBuilder());
        renderPinIngame(player, cfg, statusLine, onConfirm, onLogout);
    }

    private static void renderPinIngame(Player player, Cfg cfg, String statusLine,
                                        Consumer<String> onConfirm, Runnable onLogout) {
        UUID uuid = player.getUniqueId();
        StringBuilder sb = PIN_SESSIONS.get(uuid);
        if (sb == null) return; // session cleared (player quit / completed)
        int length = cfg.pinLength();

        try {
            player.showDialog(buildPinDialog(cfg, length, sb, statusLine,
                    digit -> {
                        if (sb.length() < length) sb.append(digit);
                        renderPinIngame(player, cfg, statusLine, onConfirm, onLogout);
                    },
                    () -> {
                        if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                        renderPinIngame(player, cfg, statusLine, onConfirm, onLogout);
                    },
                    () -> {
                        if (sb.length() != length) {
                            renderPinIngame(player, cfg, statusLine, onConfirm, onLogout);
                            return;
                        }
                        String code = sb.toString();
                        PIN_SESSIONS.remove(uuid);
                        onConfirm.accept(code);
                    },
                    () -> { PIN_SESSIONS.remove(uuid); onLogout.run(); }));
        } catch (NoClassDefFoundError ignored) {}
    }

    public static void showSliderIngame(Player player, Cfg cfg, String statusLine,
                                        Consumer<String> onConfirm, Runnable onLogout) {
        SLIDER_SESSIONS.put(player.getUniqueId(), new int[cfg.sliderLength()]);
        renderSliderIngame(player, cfg, statusLine, onConfirm, onLogout);
    }

    private static void renderSliderIngame(Player player, Cfg cfg, String statusLine,
                                           Consumer<String> onConfirm, Runnable onLogout) {
        UUID uuid = player.getUniqueId();
        int[] digits = SLIDER_SESSIONS.get(uuid);
        if (digits == null) return;
        int length = cfg.sliderLength();

        try {
            player.showDialog(buildSliderDialog(cfg, length, digits, statusLine,
                    pos -> {
                        digits[pos] = (digits[pos] + 1) % 10;
                        renderSliderIngame(player, cfg, statusLine, onConfirm, onLogout);
                    },
                    pos -> {
                        digits[pos] = (digits[pos] + 9) % 10;
                        renderSliderIngame(player, cfg, statusLine, onConfirm, onLogout);
                    },
                    () -> {
                        String code = digitsToString(digits);
                        SLIDER_SESSIONS.remove(uuid);
                        onConfirm.accept(code);
                    },
                    () -> { SLIDER_SESSIONS.remove(uuid); onLogout.run(); }));
        } catch (NoClassDefFoundError ignored) {}
    }

    private static Dialog buildPinDialog(Cfg cfg, int length, StringBuilder sb, String statusLine,
                                         Consumer<Integer> onDigit, Runnable onDelete,
                                         Runnable onConfirm, Runnable onLogout) {
        int width = cfg.pinButtonWidth();
        String sound = cfg.pinButtonSound();
        List<ActionButton> buttons = new ArrayList<>(12);
        for (int n = 1; n <= 9; n++) {
            int value = n;
            buttons.add(numButton(width, Component.text(Integer.toString(value)), sound, () -> onDigit.accept(value)));
        }
        buttons.add(numButton(width, Component.text("0"), sound, () -> onDigit.accept(0)));
        buttons.add(numButton(width, cfg.pinDeleteButton(), sound, onDelete));
        buttons.add(numButton(width, cfg.pinConfirmButton(), sound, onConfirm));

        Component body = pinProgress(sb.length(), length, statusLine);
        ActionButton logout = numButton(width, cfg.logoutButton(), cfg.logoutSound(), onLogout);

        return Dialog.create(d -> d.empty()
                .base(buildBase(cfg.pinTitle(), body))
                .type(DialogType.multiAction(buttons, logout, 3)));
    }

    private static Dialog buildSliderDialog(Cfg cfg, int length, int[] digits, String statusLine,
                                            Consumer<Integer> onPlus, Consumer<Integer> onMinus,
                                            Runnable onConfirm, Runnable onLogout) {
        int width = cfg.sliderButtonWidth();
        String sound = cfg.sliderButtonSound();
        List<ActionButton> buttons = new ArrayList<>(length * 3 + 1);
        for (int i = 0; i < length; i++) {
            int pos = i;
            buttons.add(numButton(width, Component.text("▲"), sound, () -> onPlus.accept(pos))); // ▲
        }
        for (int i = 0; i < length; i++) {
            int pos = i;
            buttons.add(numButton(width, Component.text(Integer.toString(digits[pos])), sound, () -> {}));
        }
        for (int i = 0; i < length; i++) {
            int pos = i;
            buttons.add(numButton(width, Component.text("▼"), sound, () -> onMinus.accept(pos))); // ▼
        }
        buttons.add(numButton(width, cfg.sliderConfirmButton(), sound, onConfirm));

        Component body = sliderBody(statusLine);
        ActionButton logout = numButton(width, cfg.logoutButton(), cfg.logoutSound(), onLogout);

        return Dialog.create(d -> d.empty()
                .base(buildBase(cfg.sliderTitle(), body))
                .type(DialogType.multiAction(buttons, logout, length)));
    }

    private static DialogBase buildBase(Component title, Component body) {
        DialogBase.Builder builder = DialogBase.builder(title)
                .canCloseWithEscape(false)
                .inputs(List.of());
        if (body != null) {
            builder.body(List.of(DialogBody.plainMessage(body)));
        }
        return builder.build();
    }

    private static ActionButton numButton(int width, Component label, Runnable action) {
        DialogActionCallback cb = (DialogResponseView r, Audience a) -> action.run();
        return ActionButton.builder(label)
                .width(width)
                .action(DialogAction.customClick(cb, ClickCallback.Options.builder().build()))
                .build();
    }

    private static ActionButton numButton(int width, Component label, String sound, Runnable action) {
        if (sound == null || sound.isBlank()) return numButton(width, label, action);
        DialogActionCallback cb = (DialogResponseView r, Audience a) -> {
            if (a instanceof Player p) Menu.playSound(p, sound);
            action.run();
        };
        return ActionButton.builder(label)
                .width(width)
                .action(DialogAction.customClick(cb, ClickCallback.Options.builder().build()))
                .build();
    }

    private static Component pinProgress(int entered, int length, String statusLine) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < length; i++) {
            dots.append(i < entered ? '●' : '○');
            if (i < length - 1) dots.append(' ');
        }
        Component result = Component.text(dots.toString());
        if (statusLine != null && !statusLine.isBlank()) {
            result = result.append(Component.newline()).append(Component.text(statusLine));
        }
        return result;
    }

    private static Component sliderBody(String statusLine) {
        if (statusLine == null || statusLine.isBlank()) return null;
        return Component.text(statusLine);
    }

    private static String digitsToString(int[] digits) {
        StringBuilder sb = new StringBuilder(digits.length);
        for (int d : digits) sb.append(d);
        return sb.toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(DIALOG_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
