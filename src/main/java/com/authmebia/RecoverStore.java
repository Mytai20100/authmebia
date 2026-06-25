package com.authmebia;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RecoverStore {

    private final AuthMeBia plugin;
    private final Set<UUID> flagged = ConcurrentHashMap.newKeySet();

    public RecoverStore(AuthMeBia plugin) {
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
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                if (yaml.getBoolean("recover", false)) {
                    flagged.add(UUID.fromString(dir.getName()));
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
        File file = fileFor(uuid);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder for " + uuid);
            return false;
        }

        YamlConfiguration yaml = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        if (name != null) yaml.set("name", name);
        yaml.set("uuid", uuid.toString());
        yaml.set("recover", true);
        yaml.set("recover_requested", Instant.now().toString());

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to flag " + uuid + " for recovery: " + e.getMessage());
            return false;
        }
        flagged.add(uuid);
        return true;
    }

    public void clear(UUID uuid) {
        flagged.remove(uuid);
        File file = fileFor(uuid);
        if (!file.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("recover", null);
        yaml.set("recover_requested", null);

        boolean stillBypass = yaml.getBoolean("bypass", false);
        if (!stillBypass) {
            if (file.delete()) {
                File dir = file.getParentFile();
                String[] remaining = dir != null ? dir.list() : null;
                if (remaining != null && remaining.length == 0) dir.delete();
            }
            return;
        }

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to clear recover flag for " + uuid + ": " + e.getMessage());
        }
    }
}
