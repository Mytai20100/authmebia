package com.authmebia.dialog;

import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.shared.LinkButton;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("UnstableApiUsage")
public final class Dialoglib {
    public static final long DIALOG_AWAIT_SECONDS = 30L;
    private static final java.lang.reflect.Method CALLBACK_SAM = findCallbackSam();
    private static final Map<UUID, EscapeGuard> ESCAPE_GUARDS = new ConcurrentHashMap<>();

    private Dialoglib() {}

    public static DialogBase buildBase(Component title, Component content, boolean escape, List<DialogInput> inputs) {
        return buildBase(title, content, escape, inputs, DialogAfterAction.CLOSE);
    }
    public static DialogBase buildBase(Component title, Component content, boolean escape,
                                        List<DialogInput> inputs, DialogAfterAction afterAction) {
        return buildBase(title, content, escape, inputs, afterAction, null);
    }
    public static DialogBase buildBase(Component title, Component content, boolean escape,
                                        List<DialogInput> inputs, DialogAfterAction afterAction,
                                        DialogBody leadingBody) {
        DialogBase.Builder builder = DialogBase.builder(title)
                .canCloseWithEscape(escape)
                .afterAction(afterAction == null ? DialogAfterAction.CLOSE : afterAction)
                .inputs(inputs);
        if (content != null || leadingBody != null) {
            List<DialogBody> bodies = new ArrayList<>();
            if (leadingBody != null) bodies.add(leadingBody);
            if (content != null) bodies.add(DialogBody.plainMessage(content));
            builder.body(bodies);
        }
        return builder.build();
    }

    public static ActionButton btn(Cfg cfg, Component label, DialogActionCallback cb) {
        return ActionButton.builder(label)
                .width(cfg.mainButtonWidth())
                .action(DialogAction.customClick(cb, ClickCallback.Options.builder().build()))
                .build();
    }

    public static ActionButton btn(Cfg cfg, Component label, String sound, DialogActionCallback cb) {
        if (sound == null || sound.isBlank()) return btn(cfg, label, cb);
        return ActionButton.builder(label)
                .width(cfg.mainButtonWidth())
                .action(DialogAction.customClick((r, a) -> {
                    if (a instanceof Player p) playSound(p, sound);
                    invokeCallback(cb, r, a);
                }, ClickCallback.Options.builder().build()))
                .build();
    }

    public static ActionButton resendBtn(Cfg cfg, int remaining, DialogActionCallback resendCb) {
        if (remaining > 0) {
            return ActionButton.builder(cfg.emailResendButtonCooldown(remaining))
                    .width(cfg.mainButtonWidth())
                    .action(null)
                    .build();
        }
        return btn(cfg, cfg.emailResendButton(), cfg.emailResendSound(), resendCb);
    }

    public static DialogType buildType(Cfg cfg, String playerName, List<ActionButton> submitButtons, ActionButton logoutButton) {
        List<ActionButton> links = linkActionButtons(cfg, playerName);
        LinkButton.Layout layout = cfg.linksLayout();
        boolean horizontal = cfg.mainButtonLayout() == LinkButton.ButtonRowLayout.HORIZONTAL;

        if (links.isEmpty()) {
            List<ActionButton> all = new ArrayList<>(submitButtons);
            if (logoutButton != null) all.add(logoutButton);
            return DialogType.multiAction(all, null, horizontal ? all.size() : 1);
        }

        if (layout == LinkButton.Layout.SEPARATED) {
            List<ActionButton> all = new ArrayList<>(submitButtons.size() + links.size());
            all.addAll(submitButtons);
            all.addAll(links);
            return DialogType.multiAction(all, logoutButton, horizontal ? all.size() : 1);
        }

        List<ActionButton> all = new ArrayList<>(submitButtons.size() + 1 + links.size());
        all.addAll(submitButtons);
        if (logoutButton != null) all.add(logoutButton);
        all.addAll(links);
        return DialogType.multiAction(all, null, horizontal ? all.size() : links.size());
    }

    public static void await(CountDownLatch latch) {
        try {
            latch.await(DIALOG_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    public static void escapeGuard(Player player, boolean escape, long checkDelayTicks,
                                    java.util.function.BooleanSupplier isStillPending, Runnable reopen) {
        UUID uuid = player.getUniqueId();
        if (!escape) {
            ESCAPE_GUARDS.remove(uuid);
            return;
        }
        EscapeGuard guard = new EscapeGuard();
        ESCAPE_GUARDS.put(uuid, guard);
        long myToken = guard.token.get();

        AuthMeBia plugin = AuthMeBia.get();
        Runnable check = () -> {
            EscapeGuard current = ESCAPE_GUARDS.get(uuid);
            if (current == null || current.token.get() != myToken) return;
            if (!player.isOnline()) return;
            if (!isStillPending.getAsBoolean()) return;
            reopen.run();
        };
        if (plugin.isFolia()) {
            player.getScheduler().runDelayed(plugin, t -> check.run(), null, Math.max(1L, checkDelayTicks));
        } else {
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, check, Math.max(1L, checkDelayTicks));
        }
    }
    public static void clearEscapeGuard(UUID uuid) {
        ESCAPE_GUARDS.remove(uuid);
    }

    private static final class EscapeGuard {
        final AtomicLong token = new AtomicLong(System.nanoTime());
    }

    public static void playSound(Player player, String soundConfig) {
        if (soundConfig == null || soundConfig.isBlank()) return;
        try {
            String[] parts = soundConfig.trim().split("\\s+");
            String keyStr = parts[0];
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1.0f;
            float pitch  = parts.length > 2 ? Float.parseFloat(parts[2]) : 1.0f;
            int colon = keyStr.indexOf(':');
            String namespace = colon > 0 ? keyStr.substring(0, colon) : "minecraft";
            String path      = colon > 0 ? keyStr.substring(colon + 1) : keyStr;
            player.playSound(Sound.sound(Key.key(namespace, path), Sound.Source.MASTER, volume, pitch));
        } catch (Exception ignored) {}
    }

    private static List<ActionButton> linkActionButtons(Cfg cfg, String playerName) {
        List<ActionButton> result = new ArrayList<>();
        if (!cfg.linksEnabled()) return result;
        for (LinkButton link : cfg.linkButtons(playerName)) {
            if (!link.enabled()) continue;
            String value = link.value();
            if (value == null || value.isBlank()) continue;
            ClickEvent clickEvent = link.action() == LinkButton.Action.COPY
                    ? ClickEvent.copyToClipboard(value)
                    : ClickEvent.openUrl(value);
            result.add(ActionButton.builder(link.label())
                    .width(link.width())
                    .action(DialogAction.staticAction(clickEvent))
                    .build());
        }
        return result;
    }

    private static java.lang.reflect.Method findCallbackSam() {
        for (java.lang.reflect.Method m : DialogActionCallback.class.getMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())
                    && m.getDeclaringClass() != Object.class) {
                return m;
            }
        }
        return null;
    }

    private static void invokeCallback(DialogActionCallback cb, DialogResponseView r, Audience a) {
        if (CALLBACK_SAM == null) return;
        try { CALLBACK_SAM.invoke(cb, r, a); }
        catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
        } catch (IllegalAccessException ignored) {}
    }

    public static void runCallback(DialogActionCallback cb, DialogResponseView r, Audience a) {
        invokeCallback(cb, r, a);
    }
}
