package com.authmebia.update;

import com.authmebia.AuthMeBia;
import com.authmebia.dialog.util.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.xephi.authme.events.LoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import okhttp3.Request;
import okhttp3.Response;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class UpdateChecker implements Listener {

    private static final String PROJECT_SLUG = "authmebia";
    private static final String API_URL = "https://api.modrinth.com/v2/project/" + PROJECT_SLUG + "/version";
    private static final String PROJECT_URL = "https://modrinth.com/plugin/" + PROJECT_SLUG;
    private static final String PERMISSION = "authmebia.update";

    private final AuthMeBia plugin;
    private final String currentVersion;
    private final AtomicBoolean startupCheck = new AtomicBoolean(false);
    private final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private final Set<UUID> notified = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> notifiedVersion = new AtomicReference<>("");
    private final AtomicReference<String> consoleVersion = new AtomicReference<>("");
    private final AtomicReference<String> latestVersion = new AtomicReference<>("");

    public UpdateChecker(AuthMeBia plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        runLaterGlobal(200L, this::startupOnce);
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        try {
            if (event.getType() == ServerLoadEvent.LoadType.STARTUP) {
                startupOnce();
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Update checker startup scheduling failed: " + t.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            if (!plugin.cfg().updateCheckerEnabled()) return;
            Player player = event.getPlayer();
            if (player == null || !player.hasPermission(PERMISSION)) return;
            runLater(player, 40L, () -> {
                try {
                    scheduleCheck();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Update checker join scheduling failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Update checker join handler failed: " + t.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) {
        try {
            if (!plugin.cfg().updateCheckerEnabled()) return;
            Player player = event.getPlayer();
            if (player == null || !player.hasPermission(PERMISSION)) return;
            runLater(player, 20L, () -> {
                try {
                    notifyCached();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Update checker login notification failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Update checker login handler failed: " + t.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            Player player = event.getPlayer();
            if (player != null) {
                notified.remove(player.getUniqueId());
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Update checker quit handler failed: " + t.getMessage());
        }
    }

    private void startupOnce() {
        if (!plugin.cfg().updateCheckerEnabled()) return;
        if (!startupCheck.compareAndSet(false, true)) return;
        scheduleCheck();
    }

    private void scheduleCheck() {
        if (!plugin.cfg().updateCheckerEnabled()) return;
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> checkSafely());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::checkSafely);
        }
    }

    private void checkSafely() {
        if (!plugin.cfg().updateCheckerEnabled()) return;
        if (!checkInFlight.compareAndSet(false, true)) return;
        try {
            Release latest = fetchLatestRelease();
            if (latest == null) return;
            if (!isNewer(latest.version(), currentVersion)) {
                latestVersion.set("");
                if (!currentVersion.equals(consoleVersion.getAndSet(currentVersion))) {
                    plugin.getLogger().info(plugin.lang().plain("update.console_up_to_date",
                            "AuthMeBia is up to date ({current}).",
                            Map.of("current", currentVersion, "latest", latest.version())));
                }
                return;
            }
            latestVersion.set(latest.version());
            if (!latest.version().equals(consoleVersion.getAndSet(latest.version()))) {
                plugin.getLogger().warning(plugin.lang().plain("update.console_outdated",
                        "AuthMeBia {current} is outdated; latest release is {latest}: {url}",
                        Map.of("current", currentVersion, "latest", latest.version(),
                                "url", PROJECT_URL)));
            }
            notifyPlayers(latest.version());
        } catch (Throwable t) {
            plugin.getLogger().warning("Update check failed: " + t.getMessage());
        } finally {
            checkInFlight.set(false);
        }
    }

    private Release fetchLatestRelease() throws Exception {
        Request request = new Request.Builder()
                .url(API_URL)
                .header("User-Agent", "mytai20100/authmebia/" + currentVersion)
                .header("Accept", "application/json")
                .build();
        try (Response response = plugin.httpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                plugin.getLogger().warning("Update check returned HTTP " + response.code());
                return null;
            }
            String body = response.peekBody(2_000_000).string();
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) return null;
            JsonArray versions = root.getAsJsonArray();
            Release best = null;
            for (JsonElement element : versions) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (object.has("version_type") && !object.get("version_type").isJsonNull()
                        && !"release".equalsIgnoreCase(object.get("version_type").getAsString())) {
                    continue;
                }
                if (object.has("status") && !object.get("status").isJsonNull()
                        && !"listed".equalsIgnoreCase(object.get("status").getAsString())) {
                    continue;
                }
                if (!object.has("version_number") || object.get("version_number").isJsonNull()) continue;
                String version = object.get("version_number").getAsString();
                if (version == null || version.isBlank()) continue;
                if (best == null || isNewer(version, best.version())) {
                    best = new Release(version);
                }
            }
            return best;
        }
    }

    private void notifyCached() {
        if (!plugin.cfg().updateCheckerEnabled()) return;
        String latest = latestVersion.get();
        if (!latest.isBlank()) {
            notifyPlayers(latest);
        }
    }

    private void notifyPlayers(String latestVersion) {
        if (!plugin.cfg().updateCheckerEnabled()) return;
        if (!latestVersion.equals(notifiedVersion.get())) {
            synchronized (notified) {
                if (!latestVersion.equals(notifiedVersion.get())) {
                    notified.clear();
                    notifiedVersion.set(latestVersion);
                }
            }
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.hasPermission(PERMISSION)) continue;
            UUID uuid = player.getUniqueId();
            if (notified.contains(uuid)) continue;
            Util.runOnMain(player, () -> {
                try {
                    if (!player.isOnline()) return;
                    if (!plugin.cfg().updateCheckerEnabled()) return;
                    if (!player.hasPermission(PERMISSION)) return;
                    if (plugin.authMeListener() == null) return;
                    if (!plugin.authMeListener().isAuthenticated(player)) return;
                    if (!notified.add(uuid)) return;
                    Component prefix = plugin.lang().component("update.prefix", "[AuthMeBia]", Map.of());
                    Component message = plugin.lang().component("update.new_available",
                            "A new AuthMeBia version is available: {latest}", Map.of("latest", latestVersion))
                            .clickEvent(ClickEvent.openUrl(PROJECT_URL))
                            .hoverEvent(HoverEvent.showText(plugin.lang().component("update.download",
                                    "Click to download", Map.of())));
                    player.sendMessage(prefix.append(Component.text(" ")).append(message));
                } catch (Throwable t) {
                    plugin.getLogger().warning("Update notification failed: " + t.getMessage());
                }
            });
        }
    }

    private void runLater(Player player, long delayTicks, Runnable task) {
        Runnable safe = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().warning("Update checker delayed task failed: " + t.getMessage());
            }
        };
        if (plugin.isFolia()) {
            player.getScheduler().runDelayed(plugin, t -> safe.run(), null, delayTicks);
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, safe, delayTicks);
        }
    }

    private void runLaterGlobal(long delayTicks, Runnable task) {
        Runnable safe = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().warning("Update checker delayed task failed: " + t.getMessage());
            }
        };
        if (plugin.isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> safe.run(), delayTicks);
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, safe, delayTicks);
        }
    }

    private static boolean isNewer(String candidate, String current) {
        int[] left = numericParts(candidate);
        int[] right = numericParts(current);
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] numericParts(String version) {
        if (version == null) return new int[0];
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        int suffix = cleaned.indexOf('-');
        if (suffix >= 0) cleaned = cleaned.substring(0, suffix);
        int plus = cleaned.indexOf('+');
        if (plus >= 0) cleaned = cleaned.substring(0, plus);
        if (cleaned.isEmpty()) return new int[0];
        String[] parts = cleaned.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) {
                result[i] = 0;
            } else {
                try {
                    result[i] = Integer.parseInt(digits);
                } catch (NumberFormatException e) {
                    result[i] = Integer.MAX_VALUE;
                }
            }
        }
        return result;
    }

    private record Release(String version) {}
}
