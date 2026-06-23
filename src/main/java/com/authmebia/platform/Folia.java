package com.authmebia.platform;

import com.authmebia.AuthMeBia;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Entity;

import java.util.function.Consumer;

public class Folia {

    private final AuthMeBia plugin;

    public Folia(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public void runAsync(Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public void runOnEntity(Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, t -> task.run(), null);
    }

    public void runGlobal(Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> task.run());
    }

    public void runGlobalDelayed(Runnable task, long delayTicks) {
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
    }
}
