package com.authmebia;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BiaList {

    private final AuthMeBia plugin;
    private final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();

    public BiaList(AuthMeBia plugin) {
        this.plugin = plugin;
        load();
    }

    private File rootDir() {
        return new File(plugin.getDataFolder(), "data");
    }

    private File fileFor(UUID uuid) {
        return new File(new File(rootDir(), uuid.toString()), "player.yml");
    }

    private void load() {
        File[] dirs = rootDir().listFiles(File::isDirectory);
        if (dirs == null) return;

        for (File dir : dirs) {
            File file = new File(dir, "player.yml");
            if (!file.exists()) continue;
            try {
                UUID uuid = UUID.fromString(dir.getName());
                if (isBypassEntry(file)) bypassed.add(uuid);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping bypass list entry with invalid folder name: " + dir.getName());
            }
        }
    }

    private boolean isBypassEntry(File file) {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        return yaml.getBoolean("bypass", !yaml.contains("recover"));
    }

    public boolean isBypassed(UUID uuid) {
        return uuid != null && bypassed.contains(uuid);
    }

    public Set<UUID> all() {
        return Set.copyOf(bypassed);
    }

    public boolean add(UUID uuid, String name) {
        if (bypassed.contains(uuid)) return false;

        File file = fileFor(uuid);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Failed to create bypass list folder for " + uuid);
            return false;
        }

        YamlConfiguration yaml = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        yaml.set("name", name);
        yaml.set("uuid", uuid.toString());
        yaml.set("added", Instant.now().toString());
        yaml.set("bypass", true);

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save bypass list entry for " + name + ": " + e.getMessage());
            return false;
        }

        bypassed.add(uuid);
        return true;
    }

    public boolean remove(UUID uuid) {
        boolean wasBypassed = bypassed.remove(uuid);

        File file = fileFor(uuid);
        boolean fileExisted = file.exists();
        if (!fileExisted) return wasBypassed;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        if (yaml.getBoolean("recover", false)) {
            yaml.set("bypass", false);
            yaml.set("added", null);
            try {
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to update bypass entry for " + uuid + ": " + e.getMessage());
            }
            return true;
        }

        if (!file.delete()) {
            plugin.getLogger().warning("Failed to delete bypass list file for " + uuid);
        }
        File dir = file.getParentFile();
        String[] remaining = dir != null ? dir.list() : null;
        if (remaining != null && remaining.length == 0) {
            dir.delete();
        }
        return true;
    }
}
