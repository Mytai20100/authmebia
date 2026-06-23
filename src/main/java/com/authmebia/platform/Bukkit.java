package com.authmebia.platform;

import com.authmebia.AuthMeBia;
import org.bukkit.entity.Entity;

public class Bukkit {

    private final AuthMeBia plugin;

    public Bukkit(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public void runAsync(Runnable task) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    public void runOnEntity(Entity entity, Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    public void runGlobal(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    public void runGlobalDelayed(Runnable task, long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
    }
}
