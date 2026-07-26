package com.authmebia.listeners.nexomc;

import com.authmebia.AuthMeBia;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Optional integration point for NexoMC (custom items/resource pack plugin,
 * successor to Oraxen). Controlled by integrations.nexomc.enabled in
 * config.yml.
 *
 * Same safety contract as ItemsAdder: only checks presence/API loadability,
 * never affects the auth flow, disables itself quietly if unavailable.
 */
public final class NexoMC implements Listener {

    private final AuthMeBia plugin;
    private volatile boolean available = false;

    public NexoMC(AuthMeBia plugin) {
        this.plugin = plugin;
        checkAvailability();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if ("Nexo".equalsIgnoreCase(event.getPlugin().getName())) {
            checkAvailability();
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private void checkAvailability() {
        if (!plugin.cfg().nexoMcIntegrationEnabled()) {
            available = false;
            return;
        }
        try {
            if (plugin.getServer().getPluginManager().getPlugin("Nexo") == null) {
                available = false;
                return;
            }
            Class.forName("com.nexomc.nexo.api.NexoItems");
            available = true;
            plugin.getLogger().info("NexoMC integration active.");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().info("NexoMC not available; integration disabled (" + t + ")");
        }
    }
}
