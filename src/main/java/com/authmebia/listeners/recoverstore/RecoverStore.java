package com.authmebia.listeners.recoverstore;

import com.authmebia.AuthMeBia;
import com.authmebia.data.PlayerDataStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class RecoverStore {

    private final AuthMeBia plugin;
    private final PlayerDataStore store;
    private final Set<UUID> flagged = ConcurrentHashMap.newKeySet();

    public RecoverStore(AuthMeBia plugin, PlayerDataStore store) {
        this.plugin = plugin;
        this.store = store;
        load();
    }

    private void load() {
        File[] dirs = store.listPlayerDirs();
        if (dirs == null) return;
        for (File dir : dirs) {
            try {
                UUID uuid = UUID.fromString(dir.getName());
                YamlConfiguration yaml = store.load(uuid);
                if (yaml.getBoolean("recover", false)) {
                    flagged.add(uuid);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping recover entry with invalid folder name: " + dir.getName());
            }
        }
    }

    public boolean isFlagged(UUID uuid) {
        return uuid != null && flagged.contains(uuid);
    }

    public boolean flag(UUID uuid, String name) {
        ReentrantLock lock = store.lockFor(uuid);
        lock.lock();
        try {
            YamlConfiguration yaml = store.load(uuid);
            if (name != null) yaml.set("name", name);
            yaml.set("uuid", uuid.toString());
            yaml.set("recover", true);
            yaml.set("recover_requested", Instant.now().toString());

            if (!store.save(uuid, yaml)) {
                plugin.getLogger().warning("Failed to flag " + uuid + " for recovery");
                return false;
            }
        } finally {
            lock.unlock();
        }
        flagged.add(uuid);
        return true;
    }

    public void clear(UUID uuid) {
        flagged.remove(uuid);

        ReentrantLock lock = store.lockFor(uuid);
        lock.lock();
        try {
            File file = store.fileFor(uuid);
            YamlConfiguration yaml = store.load(uuid);
            boolean hadAnyData = file.exists() || yaml.contains("recover");
            if (!hadAnyData) return;

            yaml.set("recover", null);
            yaml.set("recover_requested", null);

            boolean stillHasData = yaml.getBoolean("bypass", false)
                    || yaml.contains("toasts_shown") || yaml.contains("dismissed");
            if (stillHasData) {
                if (!store.save(uuid, yaml)) {
                    plugin.getLogger().warning("Failed to clear recover flag for " + uuid);
                }
                return;
            }

            if (!store.deleteIfEmpty(file)) {
                plugin.getLogger().warning("Failed to clear recover flag for " + uuid);
            }
        } finally {
            lock.unlock();
        }
    }
}
