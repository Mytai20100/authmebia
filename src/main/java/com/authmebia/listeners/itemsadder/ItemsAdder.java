package com.authmebia.listeners.itemsadder;

import com.authmebia.AuthMeBia;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Optional integration point for ItemsAdder (custom items/resource pack
 * plugin). Controlled by integrations.itemsadder.enabled in config.yml.
 *
 * This class only verifies that ItemsAdder is present and its API class is
 * loadable; it never touches the auth flow directly. If ItemsAdder is
 * missing or its API changes in a way that breaks the class lookup, the
 * integration silently disables itself and logs at info level, without
 * throwing or affecting registration/login.
 */
public final class ItemsAdder implements Listener {

    private final AuthMeBia plugin;
    private volatile boolean available = false;

    public ItemsAdder(AuthMeBia plugin) {
        this.plugin = plugin;
        checkAvailability();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if ("ItemsAdder".equalsIgnoreCase(event.getPlugin().getName())) {
            checkAvailability();
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private void checkAvailability() {
        if (!plugin.cfg().itemsAdderIntegrationEnabled()) {
            available = false;
            return;
        }
        try {
            if (plugin.getServer().getPluginManager().getPlugin("ItemsAdder") == null) {
                available = false;
                return;
            }
            Class.forName("dev.lone.itemsadder.api.CustomStack");
            available = true;
            plugin.getLogger().info("ItemsAdder integration active.");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().info("ItemsAdder not available; integration disabled (" + t + ")");
        }
    }
}
