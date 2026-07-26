package com.authmebia.notifications;

import com.authmebia.AuthMeBia;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which "first_*" toasts (see Toast.Check) have already been shown to
 * a given player, so they only fire once ever, persisted across restarts.
 *
 * IMPORTANT: this intentionally does NOT use data/<uuid>/player.yml. That
 * file has special meaning to BiaList and RecoverStore: BiaList.isBypassEntry
 * treats a player.yml that exists but has neither a "bypass" nor a "recover"
 * key as an implicit bypass entry (see BiaList.java). Writing toast state
 * into that same file without also setting "bypass: false" would silently
 * make every player who receives a toast skip all AuthMeBia dialogs forever
 * -- this happened during testing and disabled the login dialog entirely for
 * any player who triggered a toast. Toast state now lives in its own file,
 * data/<uuid>/toasts.yml, completely separate from the bypass/recover file.
 */
public final class ToastStore {

    private final AuthMeBia plugin;
    // uuid -> set of toast names already shown (persisted)
    private final java.util.Map<UUID, Set<String>> shown = new ConcurrentHashMap<>();

    public ToastStore(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    private File fileFor(UUID uuid) {
        return new File(new File(new File(plugin.getDataFolder(), "data"), uuid.toString()), "toasts.yml");
    }

    public boolean hasShown(UUID uuid, String toastName) {
        Set<String> cached = shown.get(uuid);
        if (cached != null) return cached.contains(toastName);

        File file = fileFor(uuid);
        if (!file.exists()) return false;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Set<String> onDisk = ConcurrentHashMap.newKeySet();
        onDisk.addAll(yaml.getStringList("toasts_shown"));
        shown.put(uuid, onDisk);
        return onDisk.contains(toastName);
    }

    public void markShown(UUID uuid, String toastName) {
        Set<String> cached = shown.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (!cached.add(toastName)) return;

        File file = fileFor(uuid);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder to record toast for " + uuid);
            return;
        }
        YamlConfiguration yaml = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        yaml.set("toasts_shown", new java.util.ArrayList<>(cached));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to record shown toast '" + toastName + "' for " + uuid + ": " + e.getMessage());
        }
    }
}
