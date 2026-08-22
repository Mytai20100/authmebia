package com.authmebia.data;

import com.authmebia.AuthMeBia;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class PlayerDataStore {

    private final AuthMeBia plugin;
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public PlayerDataStore(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public File rootDir() {
        return new File(plugin.getDataFolder(), "data");
    }

    public File fileFor(UUID uuid) {
        return new File(new File(rootDir(), uuid.toString()), "data.yml");
    }

    private File legacyPlayerFile(UUID uuid) {
        return new File(new File(rootDir(), uuid.toString()), "player.yml");
    }

    private File legacyToastsFile(UUID uuid) {
        return new File(new File(rootDir(), uuid.toString()), "toasts.yml");
    }

    private File legacyDismissedFile(UUID uuid) {
        return new File(new File(rootDir(), uuid.toString()), "dismissed_screens.yml");
    }

    public YamlConfiguration load(UUID uuid) {
        File file = fileFor(uuid);
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file);
        }
        return migrateLegacy(uuid);
    }

    private YamlConfiguration migrateLegacy(UUID uuid) {
        File playerFile = legacyPlayerFile(uuid);
        File toastsFile = legacyToastsFile(uuid);
        File dismissedFile = legacyDismissedFile(uuid);

        boolean anyLegacy = playerFile.exists() || toastsFile.exists() || dismissedFile.exists();
        YamlConfiguration merged = new YamlConfiguration();
        if (!anyLegacy) return merged;

        if (playerFile.exists()) {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(playerFile);
            for (String key : legacy.getKeys(false)) {
                merged.set(key, legacy.get(key));
            }
        }
        if (toastsFile.exists()) {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(toastsFile);
            merged.set("toasts_shown", legacy.getStringList("toasts_shown"));
        }
        if (dismissedFile.exists()) {
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(dismissedFile);
            merged.set("dismissed", legacy.getStringList("dismissed"));
            if (!merged.contains("uuid")) {
                merged.set("uuid", legacy.getString("uuid", uuid.toString()));
            }
        }
        if (!merged.contains("uuid")) {
            merged.set("uuid", uuid.toString());
        }

        File file = fileFor(uuid);
        File parent = file.getParentFile();
        if (parent.exists() || parent.mkdirs()) {
            try {
                merged.save(file);
                plugin.getLogger().info("Migrated legacy player data files to data.yml for " + uuid);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to write migrated data.yml for " + uuid + ": " + e.getMessage());
            }
        } else {
            plugin.getLogger().warning("Failed to create data folder while migrating legacy files for " + uuid);
        }
        return merged;
    }

    public boolean save(UUID uuid, YamlConfiguration yaml) {
        File file = fileFor(uuid);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder for " + uuid);
            return false;
        }
        try {
            yaml.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save data.yml for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    public ReentrantLock lockFor(UUID uuid) {
        return locks.computeIfAbsent(uuid, k -> new ReentrantLock());
    }

    public File[] listPlayerDirs() {
        return rootDir().listFiles(File::isDirectory);
    }

    public boolean deleteIfEmpty(File dataFile) {
        boolean fileGone = !dataFile.exists() || dataFile.delete();
        if (!fileGone) return false;
        File dir = dataFile.getParentFile();
        String[] remaining = dir != null ? dir.list() : null;
        if (remaining != null && remaining.length == 0) {
            dir.delete();
        }
        return true;
    }
}
