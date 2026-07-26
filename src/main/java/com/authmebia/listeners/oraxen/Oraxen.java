package com.authmebia.listeners.oraxen;

import com.authmebia.AuthMeBia;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Optional integration point for Oraxen (custom items/resource pack
 * plugin). Controlled by integrations.oraxen.enabled in config.yml.
 *
 * Same safety contract as ItemsAdder/NexoMC: only checks presence/API
 * loadability, never affects the auth flow, disables itself quietly if
 * unavailable.
 */
public final class Oraxen implements Listener {

    private final AuthMeBia plugin;
    private volatile boolean available = false;

    public Oraxen(AuthMeBia plugin) {
        this.plugin = plugin;
        checkAvailability();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if ("Oraxen".equalsIgnoreCase(event.getPlugin().getName())) {
            checkAvailability();
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private void checkAvailability() {
        if (!plugin.cfg().oraxenIntegrationEnabled()) {
            available = false;
            return;
        }
        try {
            if (plugin.getServer().getPluginManager().getPlugin("Oraxen") == null) {
                available = false;
                return;
            }
            Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            available = true;
            plugin.getLogger().info("Oraxen integration active.");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().info("Oraxen not available; integration disabled (" + t + ")");
        }
    }
}
