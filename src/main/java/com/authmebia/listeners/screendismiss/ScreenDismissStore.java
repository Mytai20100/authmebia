package com.authmebia.listeners.screendismiss;

import com.authmebia.AuthMeBia;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Backs the custom_screens "checkbox_action: close" option: when a player
 * ticks that screen's checkbox before dismissing it, the screen must never
 * be shown to them again, across restarts. Persisted per player (not just
 * in memory) for the same reason RecoverStore is: a "postjoin"/"prejoin"
 * screen is checked again on every future join, potentially after a
 * server restart, so an in-memory-only flag would silently reset and the
 * screen would come back despite the player having opted out.
 *
 * One small YAML file per player (data/&lt;uuid&gt;/dismissed_screens.yml)
 * rather than one shared file, matching RecoverStore's and BiaList's
 * existing per-player layout under the plugin's data folder, so all of
 * this plugin's per-player state lives under the same uuid-keyed
 * directory and a player's data can be inspected or removed as one unit.
 */
public final class ScreenDismissStore {

    private final AuthMeBia plugin;
    // uuid -> set of custom_screens ids permanently dismissed by that player.
    private final Map<UUID, Set<String>> dismissed = new ConcurrentHashMap<>();

    public ScreenDismissStore(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    private File rootDir() {
        return new File(plugin.getDataFolder(), "data");
    }

    private File fileFor(UUID uuid) {
        return new File(new File(rootDir(), uuid.toString()), "dismissed_screens.yml");
    }

    /**
     * True if this player has permanently dismissed the given screen id.
     * Lazily loads that player's file on first check rather than scanning
     * every player's data folder up front at startup (unlike RecoverStore,
     * which needs the full set eagerly to answer isFlagged() for players
     * who haven't joined yet -- this only ever needs to answer for the
     * player currently in front of it).
     */
    public boolean isDismissed(UUID uuid, String screenId) {
        if (uuid == null || screenId == null) return false;
        Set<String> set = dismissed.computeIfAbsent(uuid, this::load);
        return set.contains(screenId.toLowerCase(java.util.Locale.ROOT));
    }

    private Set<String> load(UUID uuid) {
        Set<String> set = new CopyOnWriteArraySet<>();
        File file = fileFor(uuid);
        if (!file.exists()) return set;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            List<String> ids = yaml.getStringList("dismissed");
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    set.add(id.toLowerCase(java.util.Locale.ROOT));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read dismissed_screens.yml for " + uuid + ": " + e.getMessage());
        }
        return set;
    }

    /**
     * Marks screenId as permanently dismissed for this player and
     * persists it immediately, so it survives a crash/restart right after
     * the player ticks the checkbox and closes the dialog.
     */
    public void markDismissed(UUID uuid, String screenId) {
        if (uuid == null || screenId == null || screenId.isBlank()) return;
        String key = screenId.toLowerCase(java.util.Locale.ROOT);
        dismissed.computeIfAbsent(uuid, this::load).add(key);

        File file = fileFor(uuid);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder for " + uuid);
            return;
        }
        YamlConfiguration yaml = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        List<String> ids = new java.util.ArrayList<>(yaml.getStringList("dismissed"));
        if (!ids.contains(key)) {
            ids.add(key);
        }
        yaml.set("dismissed", ids);
        yaml.set("uuid", uuid.toString());
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to persist dismissed screen '" + screenId + "' for " + uuid + ": " + e.getMessage());
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
        File file = fileFor(uuid);

        if (screenId == null) {
            dismissed.remove(uuid);
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("Failed to delete dismissed_screens.yml for " + uuid);
            }
            return;
        }

        String key = screenId.toLowerCase(java.util.Locale.ROOT);
        Set<String> set = dismissed.get(uuid);
        if (set != null) set.remove(key);
        if (!file.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> ids = new java.util.ArrayList<>(yaml.getStringList("dismissed"));
        ids.removeIf(key::equalsIgnoreCase);
        yaml.set("dismissed", ids);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to clear dismissed screen '" + screenId + "' for " + uuid + ": " + e.getMessage());
        }
    }
}
