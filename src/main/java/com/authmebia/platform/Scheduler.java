package com.authmebia.platform;

import com.authmebia.AuthMeBia;
import org.bukkit.entity.Player;

import java.util.concurrent.TimeUnit;

public final class Scheduler {

    private final AuthMeBia plugin;

    public Scheduler(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public void runAsync(Runnable task) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public void runOnPlayer(Player player, Runnable task) {
        if (plugin.isFolia()) {
            player.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    public void runGlobal(Runnable task) {
        if (plugin.isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    public void runLater(Player player, Runnable task, long ticks) {
        long delay = Math.max(1L, ticks);
        if (plugin.isFolia()) {
            player.getScheduler().runDelayed(plugin, t -> task.run(), null, delay);
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
        }
    }

    public void runLaterGlobal(Runnable task, long ticks) {
        long delay = Math.max(1L, ticks);
        if (plugin.isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delay);
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
        }
    }

    public void runDelayedAsync(Runnable task, long timeoutMs) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> task.run(), timeoutMs, TimeUnit.MILLISECONDS);
        } else {
            long ticks = Math.max(1L, timeoutMs / 50L);
            plugin.getServer().getScheduler().runTaskLater(plugin, task, ticks);
        }
    }

    public void runDelayedAsyncSeconds(Runnable task, long seconds) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> task.run(), seconds, TimeUnit.SECONDS);
        } else {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, seconds * 20L);
        }
    }
}
