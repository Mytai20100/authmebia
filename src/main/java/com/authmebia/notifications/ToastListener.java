package com.authmebia.notifications;

import com.authmebia.AuthMeBia;
import com.authmebia.dialog.Dialoglib;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RegisterEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows custom toast notifications (config: notifications.toasts) by
 * dynamically registering a hidden vanilla advancement per toast and briefly
 * granting/revoking it to the target player. This is the standard mechanism
 * for fully custom toast content on vanilla clients, since the toast popup
 * itself is client-rendered from advancement data (see doc comment on
 * showToast below).
 *
 * Session-scoped "first_message" / "first_advancement" checks are tracked
 * in-memory per online session; "first_register" / "first_login" are backed
 * by AuthMe's own events combined with persistent per-player state in
 * ToastStore so they only ever fire once, even across restarts.
 */
@SuppressWarnings("deprecation")
public final class ToastListener implements Listener {

    private final AuthMeBia plugin;
    private final ToastStore store;
    private final Set<UUID> messagedThisSession = ConcurrentHashMap.newKeySet();
    private final Set<UUID> advancementThisSession = ConcurrentHashMap.newKeySet();
    private final Set<String> registeredAdvancementKeys = new HashSet<>();

    public ToastListener(AuthMeBia plugin) {
        this.plugin = plugin;
        this.store = new ToastStore(plugin);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        messagedThisSession.remove(uuid);
        advancementThisSession.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthMeRegister(RegisterEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        fireAll(player, Toast.Check.FIRST_REGISTER);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthMeLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        fireAll(player, Toast.Check.FIRST_LOGIN);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!messagedThisSession.add(uuid)) return;
        fireAll(player, Toast.Check.FIRST_MESSAGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!advancementThisSession.add(uuid)) return;
        fireAll(player, Toast.Check.FIRST_ADVANCEMENT);
    }

    private void fireAll(Player player, Toast.Check check) {
        com.authmebia.cfg.Cfg.withPlayerContext(player.getName(), () -> {
            for (Toast toast : plugin.cfg().toasts()) {
                if (toast.check() != check) continue;
                if (store.hasShown(player.getUniqueId(), toast.name())) continue;
                store.markShown(player.getUniqueId(), toast.name());
                showToast(player, toast, null);
            }
        });
    }

    /**
     * Looks up a configured toast by its "name" field and shows it to the
     * given player immediately, bypassing the normal check/first-time logic
     * entirely. Used by /bia notifier for manually testing a toast's
     * appearance without needing to actually trigger the real event (e.g.
     * registering again) and without marking it as permanently shown in
     * ToastStore, so the real trigger still fires normally afterward.
     *
     * Returns false if no toast with that name exists in config.yml.
     */
    public boolean showToastByNameForTest(Player player, String toastName, Integer overrideDelaySeconds) {
        boolean[] found = {false};
        com.authmebia.cfg.Cfg.withPlayerContext(player.getName(), () -> {
            for (Toast toast : plugin.cfg().toasts()) {
                if (!toast.name().equalsIgnoreCase(toastName)) continue;
                showToast(player, toast, overrideDelaySeconds);
                found[0] = true;
                return;
            }
        });
        return found[0];
    }

    /**
     * Clears the record of toasts that failed to register this run, so a
     * fixed config.yml gets retried on the next attempt instead of being
     * silently skipped forever until a full server restart. Called from
     * /bia reload.
     */
    public void resetFailedAdvancements() {
        registeredAdvancementKeys.clear();
    }

    /**
     * Vanilla clients only render toast popups from advancement grants; there
     * is no direct packet/API for an arbitrary toast. We register a hidden,
     * zero-requirement advancement (parent-less, not shown in the tree) with
     * the configured title/description/icon, grant it instantly, then revoke
     * it a tick later so it does not persist in the player's advancement
     * list. If advancement registration fails for any reason (older API,
     * plugin conflicts), the toast is skipped entirely rather than throwing.
     *
     * Client-side limitation, not a bug: every advancement toast has a
     * second, smaller line above display.title that the client draws
     * entirely on its own from the frame type ("task" -> "Advancement
     * Made!", localized into the viewing player's own client language).
     * There is no field in the advancement JSON that controls it, so
     * toast.title() below only ever reaches the larger line.
     */
    private void showToast(Player player, Toast toast, Integer overrideDelaySeconds) {
        try {
            Advancement advancement = getOrCreateAdvancement(toast);
            if (advancement == null) return;

            if (toast.sound() != null && !toast.sound().isBlank()) {
                Dialoglib.playSound(player, toast.sound());
            }

            var progress = player.getAdvancementProgress(advancement);
            var remaining = progress.getRemainingCriteria();
            if (remaining.isEmpty()) {
                plugin.getLogger().warning("Toast '" + toast.name() + "' has no remaining criteria to award for "
                        + player.getName() + " -- the advancement is already marked complete for this player "
                        + "(likely a leftover from a previous test that didn't get revoked). The client only "
                        + "shows a toast when criteria go from incomplete to complete, so nothing will appear. "
                        + "Revoking now so the next attempt works.");
                for (String criterion : progress.getAwardedCriteria()) {
                    progress.revokeCriteria(criterion);
                }
                progress = player.getAdvancementProgress(advancement);
                remaining = progress.getRemainingCriteria();
            }
            for (String criterion : remaining) {
                progress.awardCriteria(criterion);
            }
            plugin.getLogger().info("Toast '" + toast.name() + "' awarded " + remaining.size()
                    + " criteria to " + player.getName() + " (key: " + advancement.getKey() + "). "
                    + "If no toast appears on screen, the server-side award succeeded but the client did not "
                    + "render it -- check that the player is not filtering advancement toasts and that the "
                    + "resource pack / game settings allow them.");

            int seconds = overrideDelaySeconds != null ? overrideDelaySeconds : toast.delaySeconds();
            long delayTicks = Math.max(1L, seconds * 20L);
            if (plugin.isFolia()) {
                player.getScheduler().runDelayed(plugin, t -> revoke(player, advancement), null, delayTicks);
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> revoke(player, advancement), delayTicks);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to show toast '" + toast.name() + "': " + t.getMessage());
        }
    }

    private void revoke(Player player, Advancement advancement) {
        if (!player.isOnline()) return;
        try {
            var progress = player.getAdvancementProgress(advancement);
            for (String criterion : progress.getAwardedCriteria()) {
                progress.revokeCriteria(criterion);
            }
        } catch (Throwable ignored) {}
    }

    private Advancement getOrCreateAdvancement(Toast toast) {
        String keyStr = "authmebia_toast_" + toast.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        NamespacedKey key = new NamespacedKey(plugin, keyStr);

        Advancement existing = org.bukkit.Bukkit.getAdvancement(key);
        if (existing != null) return existing;

        if (!registeredAdvancementKeys.add(keyStr)) {
            Advancement cached = org.bukkit.Bukkit.getAdvancement(key);
            if (cached == null) {
                plugin.getLogger().warning("Toast '" + toast.name() + "' already failed to register once this "
                        + "run (see the earlier warning for why) and has not been retried. Run /bia reload to "
                        + "retry after fixing the config, or restart the server.");
            }
            return cached;
        }

        try {
            String json = buildAdvancementJson(toast);
            Advancement loaded = org.bukkit.Bukkit.getUnsafe().loadAdvancement(key, json);
            if (loaded == null) {
                plugin.getLogger().warning("Bukkit.getUnsafe().loadAdvancement returned null for toast '"
                        + toast.name() + "' (key: " + key + "). The advancement JSON may be invalid, "
                        + "or an advancement with this key may already be registered by another plugin/reload. "
                        + "This toast will not display.");
            }
            return loaded;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not register advancement for toast '" + toast.name()
                    + "'; this toast will be skipped: " + t.getMessage());
            return null;
        }
    }

    private String buildAdvancementJson(Toast toast) {
        String titlePlain = plainText(toast.title());
        String descPlain = plainText(toast.content());
        String icon = toast.icon() == null || toast.icon().isBlank() ? "minecraft:paper" : toast.icon();
        String frame = toast.frame() == null ? Toast.Frame.TASK.jsonValue() : toast.frame().jsonValue();

        return "{"
                + "\"criteria\":{\"trigger\":{\"trigger\":\"minecraft:tick\"}},"
                + "\"requirements\":[[\"trigger\"]],"
                + "\"display\":{"
                + "\"icon\":{\"id\":\"" + escape(icon) + "\"},"
                + "\"title\":{\"text\":\"" + escape(titlePlain) + "\"},"
                + "\"description\":{\"text\":\"" + escape(descPlain) + "\"},"
                + "\"frame\":\"" + frame + "\","
                + "\"show_toast\":true,"
                + "\"announce_to_chat\":false,"
                + "\"hidden\":true"
                + "}"
                + "}";
    }

    private String plainText(net.kyori.adventure.text.Component component) {
        if (component == null) return "";
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
