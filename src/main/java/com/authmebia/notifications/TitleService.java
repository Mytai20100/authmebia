package com.authmebia.notifications;

import com.authmebia.AuthMeBia;
import com.authmebia.cfg.Cfg;
import com.authmebia.cfg.TitleScript;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays {@code <titles:name>} sequences. Only one sequence runs per player at
 * a time; sequences requested while one is playing are appended, so a custom
 * screen's titles are not cut off by the login/register titles that arrive
 * just after authentication.
 */
public final class TitleService {

    private static final long FADE_IN_OUT_TICKS = 10L;
    private final AuthMeBia plugin;
    private final Map<UUID, Long> generations = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<QueuedTitle>> queues = new ConcurrentHashMap<>();
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    private record QueuedTitle(String name, long delayTicksOverride) {}

    public TitleService(AuthMeBia plugin) {
        this.plugin = plugin;
    }

    public void play(Player player, TitleScript script, Collection<String> triggers) {
        if (player == null || !player.isOnline() || script == null || script.isEmpty()) return;
        if (!script.matchesAny(triggers)) return;
        play(player, script.entries());
    }

    public void play(Player player, List<String> titleNames) {
        if (titleNames == null || titleNames.isEmpty()) return;
        List<QueuedTitle> entries = new ArrayList<>(titleNames.size());
        for (String name : titleNames) {
            if (name != null && !name.isBlank()) entries.add(new QueuedTitle(name, 0L));
        }
        enqueue(player, entries);
    }

    /**
     * Shows one title immediately, cancelling anything currently playing for
     * this player. A positive {@code delayTicksOverride} replaces the entry's
     * configured delay; {@code 0} keeps the configured delay.
     */
    public void preview(Player player, String titleName, long delayTicksOverride) {
        if (player == null || !player.isOnline() || titleName == null || titleName.isBlank()) return;
        cancel(player);
        enqueue(player, List.of(new QueuedTitle(titleName, Math.max(0L, delayTicksOverride))));
    }

    private void enqueue(Player player, List<QueuedTitle> entries) {
        if (player == null || !player.isOnline() || entries == null || entries.isEmpty()) return;
        UUID uuid = player.getUniqueId();
        long generation;
        boolean start;
        synchronized (this) {
            queues.computeIfAbsent(uuid, k -> new ArrayDeque<>()).addAll(entries);
            start = running.add(uuid);
            if (start) generation = generations.merge(uuid, 1L, Long::sum);
            else generation = 0L;
        }
        if (start) playNext(player, generation);
    }

    public void cancel(Player player) {
        if (player == null) return;
        cancel(player.getUniqueId());
    }

    public void forget(UUID uuid) {
        if (uuid == null) return;
        synchronized (this) {
            generations.remove(uuid);
            queues.remove(uuid);
            running.remove(uuid);
        }
    }

    private void cancel(UUID uuid) {
        synchronized (this) {
            generations.merge(uuid, 1L, Long::sum);
            queues.remove(uuid);
            running.remove(uuid);
        }
    }

    private void playNext(Player player, long generation) {
        try {
            playNextUnchecked(player, generation);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Failed to play custom title for " + player.getName() + ": " + e.getMessage());
            discard(player.getUniqueId(), generation);
        }
    }

    private void playNextUnchecked(Player player, long generation) {
        if (!player.isOnline() || generation != current(player)) {
            discard(player.getUniqueId(), generation);
            return;
        }

        UUID uuid = player.getUniqueId();
        QueuedTitle queued;
        CustomTitle title;
        while (true) {
            queued = pollNext(uuid);
            if (queued == null) return;
            final String titleName = queued.name();
            CustomTitle candidate;
            try {
                candidate = Cfg.withPlayerContextValue(player.getName(),
                        () -> plugin.cfg().customTitle(titleName));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Invalid custom title '" + titleName + "': " + e.getMessage());
                continue;
            }
            if (candidate != null && !candidate.isEmpty()) {
                title = candidate;
                break;
            }
        }

        if (generation != current(uuid) || !player.isOnline()) {
            discard(uuid, generation);
            return;
        }
        final CustomTitle currentTitle = title;

        long delay = queued.delayTicksOverride() > 0L
                ? queued.delayTicksOverride()
                : currentTitle.delayTicks();
        final long currentDelay = Math.max(1L, delay);
        Cfg.withPlayerContext(player.getName(), () -> {
            if (currentTitle.hasTitle()) {
                Component main = currentTitle.title() == null ? Component.empty() : currentTitle.title();
                Component sub = currentTitle.subtitle() == null ? Component.empty() : currentTitle.subtitle();
                long stay = Math.max(1L, currentDelay - FADE_IN_OUT_TICKS * 2);
                player.showTitle(Title.title(main, sub, Title.Times.times(
                        Duration.ofMillis(FADE_IN_OUT_TICKS * 50L),
                        Duration.ofMillis(stay * 50L),
                        Duration.ofMillis(FADE_IN_OUT_TICKS * 50L))));
            }
            if (currentTitle.hasActionbar()) {
                player.sendActionBar(currentTitle.actionbar());
            } else {
                player.sendActionBar(Component.empty());
            }
        });

        if (currentTitle.hasActionbar()) {
            for (long tick = 20L; tick < currentDelay; tick += 20L) {
                long repeatAt = tick;
                runAt(player, repeatAt, () -> {
                    if (!player.isOnline() || generation != current(player)) return;
                    try {
                        Cfg.withPlayerContext(player.getName(), () -> player.sendActionBar(currentTitle.actionbar()));
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Failed to send custom actionbar: " + e.getMessage());
                    }
                });
            }
        }
        runAt(player, currentDelay, () -> playNext(player, generation));
    }

    private QueuedTitle pollNext(UUID uuid) {
        synchronized (this) {
            Deque<QueuedTitle> queue = queues.get(uuid);
            if (queue == null || queue.isEmpty()) {
                running.remove(uuid);
                queues.remove(uuid);
                return null;
            }
            return queue.poll();
        }
    }

    private void discard(UUID uuid, long generation) {
        synchronized (this) {
            if (generation == current(uuid)) {
                generations.merge(uuid, 1L, Long::sum);
                queues.remove(uuid);
                running.remove(uuid);
            }
        }
    }

    private long current(Player player) {
        Long value = generations.get(player.getUniqueId());
        return value == null ? 0L : value;
    }

    private long current(UUID uuid) {
        Long value = generations.get(uuid);
        return value == null ? 0L : value;
    }

    private void runAt(Player player, long delayTicks, Runnable task) {
        long delay = Math.max(1L, delayTicks);
        if (plugin.isFolia()) {
            player.getScheduler().runDelayed(plugin, t -> task.run(), null, delay);
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
        }
    }
}
