package com.authmebia;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class AuthMe implements Listener {

    // Ticks to wait after forceRegister before calling forceLogin.
    // AuthMe hashes the password and writes to storage asynchronously inside
    // forceRegister; the player is not yet present in AuthMe's registered
    // cache until that write completes. One tick (~50 ms) is not a safe
    // guarantee under server load or slow disk I/O. Ten ticks gives roughly
    // 500 ms of headroom, which covers normal async completion while still
    // feeling instantaneous to the player.
    private static final long REGISTER_LOGIN_DELAY_TICKS = 10L;

    private final AuthMeBia plugin;
    private Object api;
    private Method isRegistered;
    private Method forceLogin;
    private Method forceLogout;
    private Method forceRegister;
    private Method checkPassword;

    // Cached values read from AuthMe's config.yml on plugin enable/reload.
    // Both fields are read on the main thread (PlayerJoinEvent) and written
    // only from enable/reload, so a plain volatile is sufficient.
    private volatile boolean cachedBlindEffectEnabled = false;
    private volatile boolean cachedAuthMeCaptchaEnabled = false;

    final Map<UUID, String> pendingRegister = new ConcurrentHashMap<>();
    final Map<UUID, Boolean> pendingForceLogin = new ConcurrentHashMap<>();

    public AuthMe(AuthMeBia plugin) {
        this.plugin = plugin;
        initReflection();
        refreshAuthMeConfigCache();
    }

    private void initReflection() {
        try {
            Class<?> cls = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            api = cls.getMethod("getInstance").invoke(null);
            isRegistered = cls.getMethod("isRegistered", String.class);
            forceLogin = cls.getMethod("forceLogin", Player.class);
            forceLogout = cls.getMethod("forceLogout", Player.class);
            forceRegister = cls.getMethod("forceRegister", Player.class, String.class);
            checkPassword = cls.getMethod("checkPassword", String.class, String.class);
        } catch (Exception e) {
            plugin.getLogger().severe("AuthMe API bind failed: " + e.getMessage());
        }
    }

    /**
     * Reads AuthMe's config.yml once and caches the values used at runtime.
     * Call this on plugin enable and on every /bia reload so the cache stays
     * in sync if an admin edits AuthMe's config between reloads.
     */
    public void refreshAuthMeConfigCache() {
        org.bukkit.plugin.Plugin authme = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = plugin.getServer().getPluginManager().getPlugin("AuthMeReloaded");
        if (authme == null) {
            cachedBlindEffectEnabled = false;
            cachedAuthMeCaptchaEnabled = false;
            return;
        }

        File file = new File(authme.getDataFolder(), "config.yml");
        if (!file.exists()) {
            cachedBlindEffectEnabled = false;
            cachedAuthMeCaptchaEnabled = false;
            return;
        }

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

            if (yaml.isSet("settings.applyBlindEffect")) {
                cachedBlindEffectEnabled = yaml.getBoolean("settings.applyBlindEffect", false);
            } else {
                cachedBlindEffectEnabled = yaml.getBoolean("applyBlindEffect", false);
            }

            cachedAuthMeCaptchaEnabled = yaml.getBoolean("captcha.useCaptcha", false);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read AuthMe config.yml: " + e.getMessage());
            cachedBlindEffectEnabled = false;
            cachedAuthMeCaptchaEnabled = false;
        }
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        if (!plugin.cfg().dialogEnabled()) return;
        if (!plugin.cfg().dialogPreSpawn()) return;

        PlayerConfigurationConnection connection = event.getConnection();
        String name = connection.getProfile().getName();
        UUID uuid = connection.getProfile().getId();
        if (name == null || uuid == null) return;

        if (plugin.biaList().isBypassed(uuid)) {
            return;
        }

        Cfg cfg = plugin.cfg();
        Lang lang = plugin.lang();

        // The vanilla Dialog UI only exists from protocol 771 (1.21.6)
        // onward; older clients cannot render a dialog packet at all, and
        // sending one anyway leaves the connection stuck until the server's
        // network read-timeout eventually kicks the player. ViaVersion is
        // queried with a short bounded wait here (configurable via
        // dialog.protocol_detect_wait_ms) to cover the small timing window
        // where the connection's protocol version has not finished being
        // associated with its UUID yet when this event fires. If the
        // version still cannot be determined after that wait, dialogs are
        // treated as unsupported and this player falls back to AuthMe's
        // own /login and /register commands once they spawn instead.
        boolean dialogsSupported = ProtocolGate.supportsDialogsBlocking(
                uuid, null, cfg.dialogMinProtocolVersion(), cfg.dialogProtocolDetectWaitMillis());
        if (!dialogsSupported) {
            return;
        }

        String ip = IpGuard.resolveIp(connection);

        if (captchaRequired(cfg, uuid)) {
            boolean verified = Menu.showCaptchaBlocking(connection, cfg, lang, plugin.captcha());
            if (!verified) {
                connection.disconnect(lang.disconnectVerificationFailed(ip));
                return;
            }
            plugin.captcha().markTrusted(uuid, cfg.captchaTrustDurationSeconds());
        }

        boolean registered = isRegisteredByName(name);

        if (!registered) {
            String password = Menu.showRegisterBlocking(connection, cfg, lang);
            if (password == null) {
                connection.disconnect(lang.disconnectRegistrationCancelled(ip));
                return;
            }
            pendingRegister.put(uuid, password);

            if (cfg.ruleEnabled()) {
                boolean agreed = Menu.showRuleBlocking(connection, cfg);
                if (!agreed) {
                    connection.disconnect(lang.disconnectMustAgreeRules(ip));
                    pendingRegister.remove(uuid);
                    return;
                }
            }

            pendingForceLogin.put(uuid, true);
        } else {
            boolean ok = Menu.showLoginBlocking(connection, name, cfg, lang, this, plugin.ipGuard(), ip);
            if (!ok) {
                connection.disconnect(lang.disconnectLoginFailed(ip));
            } else {
                pendingForceLogin.put(uuid, true);
            }
        }
    }

    private boolean captchaRequired(Cfg cfg, UUID uuid) {
        if (!cfg.captchaEnabled()) return false;
        if (!cachedAuthMeCaptchaEnabled) return false;
        return !plugin.captcha().isTrusted(uuid);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        String pending = pendingRegister.remove(uuid);
        boolean justRegistered = pending != null;
        if (justRegistered) {
            boolean doLogin = pendingForceLogin.remove(uuid) != null;
            runOnPlayer(player, () -> {
                try {
                    forceRegister.invoke(api, player, pending);
                } catch (Exception e) {
                    plugin.getLogger().warning("forceRegister failed for " + player.getName() + ": " + e.getMessage());
                }
                if (doLogin) {
                    // forceRegister dispatches an async password-hash + storage write
                    // internally. Calling forceLogin on the same tick races against
                    // that write and loses -- the player is not yet in AuthMe's
                    // registered cache, so the login silently does nothing and the
                    // player is left stuck needing /login manually.
                    // REGISTER_LOGIN_DELAY_TICKS gives enough headroom for the async
                    // write to complete under normal server load and slow disk I/O.
                    runOnPlayerDelayed(player, () -> {
                        boolean loggedIn = false;
                        try {
                            loggedIn = (boolean) forceLogin.invoke(api, player);
                        } catch (Exception e) {
                            plugin.getLogger().warning("forceLogin failed for " + player.getName() + ": " + e.getMessage());
                        }
                        if (loggedIn) {
                            clearBlindEffect(player);
                        }
                    }, REGISTER_LOGIN_DELAY_TICKS);
                }
            });
        } else if (pendingForceLogin.remove(uuid) != null) {
            runOnPlayer(player, () -> {
                boolean loggedIn = false;
                try {
                    loggedIn = (boolean) forceLogin.invoke(api, player);
                } catch (Exception e) {
                    plugin.getLogger().warning("Force-login failed for " + player.getName() + ": " + e.getMessage());
                }
                if (loggedIn) {
                    clearBlindEffect(player);
                }
            });
        }

        if (plugin.cfg().dialogEnabled() && !plugin.cfg().dialogPreSpawn()
                && !plugin.biaList().isBypassed(uuid)
                && ProtocolGate.supportsDialogs(uuid, player, plugin.cfg().dialogMinProtocolVersion())) {
            boolean registered = isRegisteredByName(player.getName());
            runOnPlayer(player, () -> {
                if (registered) {
                    Menu.showLoginIngame(player, plugin.cfg(), plugin.lang(), this, plugin.ipGuard());
                } else {
                    Menu.showRegisterIngame(player, plugin.cfg(), plugin.lang(), this);
                }
            });
        }

        if (justRegistered && (plugin.cfg().welcomeImageEnabled() || plugin.cfg().discordEnabled())) {
            runAsync(() -> new com.authmebia.api.Welcome(plugin).handle(player));
        }
    }

    private void runOnPlayer(Player player, Runnable task) {
        if (plugin.isFolia()) {
            player.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private void runOnPlayerDelayed(Player player, Runnable task, long delayTicks) {
        if (plugin.isFolia()) {
            player.getScheduler().runDelayed(plugin, t -> task.run(), null, delayTicks);
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    private void runAsync(Runnable task) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public boolean isRegisteredByName(String name) {
        try { return (boolean) isRegistered.invoke(api, name); }
        catch (Exception e) { return false; }
    }

    public boolean login(Player player) {
        try {
            boolean result = (boolean) forceLogin.invoke(api, player);
            if (result) {
                clearBlindEffect(player);
            }
            return result;
        }
        catch (Exception e) { return false; }
    }

    public void logout(Player player) {
        try { forceLogout.invoke(api, player); }
        catch (Exception ignored) {}
    }

    public boolean checkPassword(String name, String password) {
        try { return (boolean) checkPassword.invoke(api, name, password); }
        catch (Exception e) { return false; }
    }

    public boolean registerAndLogin(Player player, String password) {
        try {
            forceRegister.invoke(api, player, password);
        } catch (Exception e) {
            plugin.getLogger().warning("forceRegister failed for " + player.getName() + ": " + e.getMessage());
            return false;
        }
        // forceRegister is async internally; see REGISTER_LOGIN_DELAY_TICKS.
        runOnPlayerDelayed(player, () -> {
            boolean loggedIn = false;
            try {
                loggedIn = (boolean) forceLogin.invoke(api, player);
            } catch (Exception e) {
                plugin.getLogger().warning("forceLogin failed for " + player.getName() + ": " + e.getMessage());
            }
            if (loggedIn) {
                clearBlindEffect(player);
            }
            if (plugin.cfg().welcomeImageEnabled() || plugin.cfg().discordEnabled()) {
                runAsync(() -> new com.authmebia.api.Welcome(plugin).handle(player));
            }
        }, REGISTER_LOGIN_DELAY_TICKS);
        return true;
    }

    /**
     * Removes AuthMe's pre-login blindness effect from the player, if AuthMe
     * has it enabled. Call this only after a force-login/register call has
     * confirmed success.
     * <p>
     * forceRegister/forceLogin (called via reflection above) authenticate
     * the player without going through AuthMe's normal command flow. AuthMe
     * applies and removes the blindness effect as part of its own join/limbo
     * handling, and that handling can run on a different tick than this
     * plugin's force-login call -- the effect can end up applied to a
     * player who is already logged in, with nothing left in AuthMe's flow
     * to ever remove it again. Explicitly removing it here right after a
     * successful force-login/register closes that gap.
     */
    private void clearBlindEffect(Player player) {
        if (!cachedBlindEffectEnabled) return;
        if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }
}
