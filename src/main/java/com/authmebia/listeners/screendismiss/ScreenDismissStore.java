package com.authmebia.listeners.screendismiss;

import com.authmebia.AuthMeBia;
import com.authmebia.data.PlayerDataStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.ReentrantLock;

public final class ScreenDismissStore {

    private final AuthMeBia plugin;
    private final PlayerDataStore store;
    // uuid -> set of custom_screens ids permanently dismissed by that player.
    private final Map<UUID, Set<String>> dismissed = new ConcurrentHashMap<>();

    public ScreenDismissStore(AuthMeBia plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public boolean isDismissed(UUID uuid, String screenId) {
        if (uuid == null || screenId == null) return false;
        Set<String> set = dismissed.computeIfAbsent(uuid, this::load);
        return set.contains(screenId.toLowerCase(java.util.Locale.ROOT));
    }

    private Set<String> load(UUID uuid) {
        Set<String> set = new CopyOnWriteArraySet<>();
        try {
            YamlConfiguration yaml = store.load(uuid);
            List<String> ids = yaml.getStringList("dismissed");
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    set.add(id.toLowerCase(java.util.Locale.ROOT));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read dismissed screens for " + uuid + ": " + e.getMessage());
        }
        return set;
    }

    public void markDismissed(UUID uuid, String screenId) {
        if (uuid == null || screenId == null || screenId.isBlank()) return;
        String key = screenId.toLowerCase(java.util.Locale.ROOT);
        dismissed.computeIfAbsent(uuid, this::load).add(key);

        ReentrantLock lock = store.lockFor(uuid);
        lock.lock();
        try {
            YamlConfiguration yaml = store.load(uuid);
            List<String> ids = new java.util.ArrayList<>(yaml.getStringList("dismissed"));
            if (!ids.contains(key)) {
                ids.add(key);
            }
            yaml.set("dismissed", ids);
            yaml.set("uuid", uuid.toString());
            if (!store.save(uuid, yaml)) {
                plugin.getLogger().warning("Failed to persist dismissed screen '" + screenId + "' for " + uuid);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears one screen's dismissal (or, with screenId null, every
     * screen's dismissal) for this player, so the screen(s) will show
     * again on their next qualifying trigger. Used by
     * /bia screen reset &lt;player&gt; [id].
     */
    public void clear(UUID uuid, String screenId) {
        if (uuid == null) return;

        ReentrantLock lock = store.lockFor(uuid);
        lock.lock();
        try {
            File file = store.fileFor(uuid);
            YamlConfiguration yaml = store.load(uuid);
            boolean hadAnyData = file.exists() || yaml.contains("dismissed");

            if (screenId == null) {
                dismissed.remove(uuid);
                if (!hadAnyData) return;
                yaml.set("dismissed", null);
                boolean stillHasData = yaml.contains("bypass") || yaml.contains("recover") || yaml.contains("toasts_shown");
                if (stillHasData) {
                    store.save(uuid, yaml);
                } else if (!store.deleteIfEmpty(file)) {
                    plugin.getLogger().warning("Failed to clear dismissed screens for " + uuid);
                }
                return;
            }

            String key = screenId.toLowerCase(java.util.Locale.ROOT);
            Set<String> set = dismissed.get(uuid);
            if (set != null) set.remove(key);
            if (!hadAnyData) return;

            List<String> ids = new java.util.ArrayList<>(yaml.getStringList("dismissed"));
            ids.removeIf(key::equalsIgnoreCase);
            yaml.set("dismissed", ids);
            if (!store.save(uuid, yaml)) {
                plugin.getLogger().warning("Failed to clear dismissed screen '" + screenId + "' for " + uuid);
            }
        } finally {
            lock.unlock();
        }
    }
}
