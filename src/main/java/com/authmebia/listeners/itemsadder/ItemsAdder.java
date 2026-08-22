package com.authmebia.listeners.itemsadder;

import com.authmebia.AuthMeBia;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

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
