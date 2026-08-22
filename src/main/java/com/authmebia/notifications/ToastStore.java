package com.authmebia.notifications;

import com.authmebia.AuthMeBia;
import com.authmebia.data.PlayerDataStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class ToastStore {

    private final AuthMeBia plugin;
    private final PlayerDataStore store;
    private final java.util.Map<UUID, Set<String>> shown = new ConcurrentHashMap<>();

    public ToastStore(AuthMeBia plugin) {
        this.plugin = plugin;
        this.store = plugin.playerDataStore();
    }

    public boolean hasShown(UUID uuid, String toastName) {
        Set<String> cached = shown.get(uuid);
        if (cached != null) return cached.contains(toastName);

        YamlConfiguration yaml = store.load(uuid);
        Set<String> onDisk = ConcurrentHashMap.newKeySet();
        onDisk.addAll(yaml.getStringList("toasts_shown"));
        shown.put(uuid, onDisk);
        return onDisk.contains(toastName);
    }

    public void markShown(UUID uuid, String toastName) {
        Set<String> cached = shown.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (!cached.add(toastName)) return;

        ReentrantLock lock = store.lockFor(uuid);
        lock.lock();
        try {
            YamlConfiguration yaml = store.load(uuid);
            yaml.set("toasts_shown", new java.util.ArrayList<>(cached));
            if (!store.save(uuid, yaml)) {
                plugin.getLogger().warning("Failed to record shown toast '" + toastName + "' for " + uuid);
            }
        } finally {
            lock.unlock();
        }
    }
}
