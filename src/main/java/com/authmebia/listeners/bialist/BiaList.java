package com.authmebia.listeners.bialist;

import com.authmebia.AuthMeBia;
import com.authmebia.data.PlayerDataStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class BiaList {

    private final AuthMeBia plugin;
    private final PlayerDataStore store;
    private final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();

    public BiaList(AuthMeBia plugin, PlayerDataStore store) {
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
                if (yaml.getBoolean("bypass", false)) bypassed.add(uuid);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bypass list entry with invalid folder name: " + dir.getName());
            }
        }
    }

    public boolean isBypassed(UUID uuid) {
        return uuid != null && bypassed.contains(uuid);
    }

    public Set<UUID> all() {
        return Set.copyOf(bypassed);
    }

    public boolean add(UUID uuid, String name) {
        if (bypassed.contains(uuid)) return false;

        ReentrantLock lock = store.lockFor(uuid);
        lock.lock();
        try {
            YamlConfiguration yaml = store.load(uuid);
            yaml.set("name", name);
            yaml.set("uuid", uuid.toString());
            yaml.set("added", Instant.now().toString());
            yaml.set("bypass", true);

            if (!store.save(uuid, yaml)) {
                plugin.getLogger().warning("Failed to save bypass list entry for " + name);
                return false;
            }
        } finally {
            lock.unlock();
        }

        bypassed.add(uuid);
        return true;
    }

    public boolean remove(UUID uuid) {
        boolean wasBypassed = bypassed.remove(uuid);

        ReentrantLock lock = store.lockFor(uuid);
        lock.lock();
        try {
            File file = store.fileFor(uuid);
            YamlConfiguration yaml = store.load(uuid);
            boolean hadAnyData = file.exists() || yaml.contains("bypass") || yaml.contains("recover");
            if (!hadAnyData) return wasBypassed;

            if (yaml.getBoolean("recover", false)) {
                yaml.set("bypass", false);
                yaml.set("added", null);
                store.save(uuid, yaml);
                return true;
            }

            yaml.set("bypass", false);
            yaml.set("added", null);
            yaml.set("name", null);
            boolean stillHasData = yaml.contains("recover") || yaml.contains("toasts_shown") || yaml.contains("dismissed");
            if (stillHasData) {
                store.save(uuid, yaml);
            } else if (!store.deleteIfEmpty(file)) {
                plugin.getLogger().warning("Failed to delete bypass list entry for " + uuid);
            }
        } finally {
            lock.unlock();
        }
        return true;
    }
}
