package com.authmebia;

import fr.xephi.authme.events.FailedLoginEvent;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.RegisterEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class AuthMe implements Listener {

    private static final long AUTH_RESULT_TIMEOUT_MS = 5000L;

    private final AuthMeBia plugin;
    private Object api;
    private Method isRegistered;
    private Method forceLogin;
    private Method forceLogout;
    private Method forceRegister;
    private Method checkPassword;
    private Method changePassword;
    private Method isAuthenticated;
    private Method registerPlayer;

    private Object dataSource;
    private Method dsGetAuth;
    private Method authIsPremium;
    private Method authGetPremiumUuid;
    private volatile boolean cachedPremiumEnabled = false;

    private Object emailService;
    private Method emailHasAllInfo;
    private Method emailSendVerification;
    private Method dsUpdateEmail;
    private Method authSetEmail;
    final Map<UUID, String> pendingEmail = new ConcurrentHashMap<>();

    private Object totpAuthenticator;
    private Method totpCheckCode;
    private Method authGetTotpKey;

    private volatile boolean cachedBlindEffectEnabled = false;
    private volatile boolean cachedAuthMeCaptchaEnabled = false;
    private volatile Boolean debugCaptchaOverride = null;
    private volatile Boolean debugEmailOverride = null;

    final Map<UUID, String> pendingRegister = new ConcurrentHashMap<>();
    final Map<UUID, Boolean> pendingForceLogin = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> pendingLoginFutures = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> pendingRegisterFutures = new ConcurrentHashMap<>();

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
            // changePassword(playerName, newPassword) -- used by /bia recover
            // to set a new hash for a forced password reset (Feature 5).
            changePassword = cls.getMethod("changePassword", String.class, String.class);
            isAuthenticated = cls.getMethod("isAuthenticated", Player.class);
            registerPlayer = cls.getMethod("registerPlayer", String.class, String.class);
        } catch (Exception e) {
            plugin.getLogger().severe("AuthMe API bind failed: " + e.getMessage());
        }

        try {
            java.lang.reflect.Field dsField = api.getClass().getDeclaredField("dataSource");
            dsField.setAccessible(true);
            dataSource = dsField.get(api);
            Class<?> dsClass = Class.forName("fr.xephi.authme.datasource.DataSource");
            dsGetAuth = dsClass.getMethod("getAuth", String.class);
            Class<?> authClass = Class.forName("fr.xephi.authme.data.auth.PlayerAuth");
            authIsPremium = authClass.getMethod("isPremium");
            authGetPremiumUuid = authClass.getMethod("getPremiumUuid");
        } catch (Throwable t) {
            plugin.getLogger().info("AuthMe premium reflection unavailable; premium skip disabled (" + t + ")");
            dataSource = null;
        }

        try {
            Object authMePlugin = api.getClass().getMethod("getPlugin").invoke(api);
            java.lang.reflect.Field injField = authMePlugin.getClass().getDeclaredField("injector");
            injField.setAccessible(true);
            Object injector = injField.get(authMePlugin);
            Method getSingleton = Class.forName("ch.jalu.injector.Injector").getMethod("getSingleton", Class.class);
            Class<?> emailSvcClass = Class.forName("fr.xephi.authme.mail.EmailService");
            emailService = getSingleton.invoke(injector, emailSvcClass);
            emailHasAllInfo = emailSvcClass.getMethod("hasAllInformation");
            emailSendVerification = emailSvcClass.getMethod("sendVerificationMail", String.class, String.class, String.class);
            Class<?> authClass = Class.forName("fr.xephi.authme.data.auth.PlayerAuth");
            dsUpdateEmail = Class.forName("fr.xephi.authme.datasource.DataSource")
                    .getMethod("updateEmail", authClass);
            authSetEmail = authClass.getMethod("setEmail", String.class);
        } catch (Throwable t) {
            plugin.getLogger().info("AuthMe email reflection unavailable; email verification disabled (" + t + ")");
            emailService = null;
        }

        try {
            Object authMePlugin = api.getClass().getMethod("getPlugin").invoke(api);
            java.lang.reflect.Field injField = authMePlugin.getClass().getDeclaredField("injector");
            injField.setAccessible(true);
            Object injector = injField.get(authMePlugin);
            Method getSingleton = Class.forName("ch.jalu.injector.Injector").getMethod("getSingleton", Class.class);

            Class<?> totpClass = Class.forName("fr.xephi.authme.security.totp.TotpAuthenticator");
            totpAuthenticator = getSingleton.invoke(injector, totpClass);

            // checkCode(PlayerAuth, String) - verified against AuthMe 6.0.0-SNAPSHOT jar
            Class<?> playerAuthClass = Class.forName("fr.xephi.authme.data.auth.PlayerAuth");
            totpCheckCode = totpClass.getMethod("checkCode", playerAuthClass, String.class);
            authGetTotpKey = playerAuthClass.getMethod("getTotpKey");
        } catch (Throwable t) {
            plugin.getLogger().info("AuthMe TOTP reflection unavailable; 2FA dialog disabled (" + t + ")");
            totpAuthenticator = null;
        }
    }

    public void refreshAuthMeConfigCache() {
        debugCaptchaOverride = null;
        debugEmailOverride = null;

        org.bukkit.plugin.Plugin authme = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = plugin.getServer().getPluginManager().getPlugin("AuthMeReloaded");
        if (authme == null) {
            cachedBlindEffectEnabled = false;
            cachedAuthMeCaptchaEnabled = false;
            cachedPremiumEnabled = false;
            return;
        }

        File file = new File(authme.getDataFolder(), "config.yml");
        if (!file.exists()) {
            cachedBlindEffectEnabled = false;
            cachedAuthMeCaptchaEnabled = false;
            cachedPremiumEnabled = false;
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
            cachedPremiumEnabled = yaml.getBoolean("settings.enablePremium", false);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read AuthMe config.yml: " + e.getMessage());
            cachedBlindEffectEnabled = false;
            cachedAuthMeCaptchaEnabled = false;
            cachedPremiumEnabled = false;
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

        if (isPremiumSkip(uuid, name)) {
            return;
        }

        Cfg cfg = plugin.cfg();
        Lang lang = plugin.lang();

        if (!ProtocolGate.supportsDialogs(uuid, null, cfg.dialogMinProtocolVersion())) {
            return;
        }

        String ip = IpGuard.resolveIp(connection);

        java.util.concurrent.atomic.AtomicBoolean authed = new java.util.concurrent.atomic.AtomicBoolean(false);
        if (cfg.loginTimeoutEnabled() && cfg.loginTimeoutSeconds() > 0) {
            scheduleDisconnect(connection, authed, cfg.loginTimeoutKickMessage(), cfg.loginTimeoutSeconds());
        }

        if (captchaRequired(cfg, uuid)) {
            boolean verified = Menu.showCaptchaBlocking(connection, cfg, lang, plugin.captcha());
            if (!verified) {
                connection.disconnect(lang.disconnectVerificationFailed(ip));
                return;
            }
            plugin.captcha().markTrusted(uuid, cfg.captchaTrustDurationSeconds());
        }

        boolean registered = isRegisteredByName(name);

        if (registered && plugin.recoverStore().isFlagged(uuid)) {
            String newPass = Menu.showRecoverBlocking(connection, cfg);
            if (newPass == null) {
                connection.disconnect(lang.disconnectLoginFailed(ip));
                return;
            }
            changePassword(name, newPass);
            plugin.recoverStore().clear(uuid);
            if (cfg.authWaitEnabled() && cfg.authWaitPreJoin()) {
                Menu.showWaitDialogBlocking(connection, cfg, cfg.authWaitSeconds());
            }
            pendingForceLogin.put(uuid, true);
            authed.set(true);
            return;
        }

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

            if (cfg.authWaitEnabled() && cfg.authWaitPreJoin()) {
                Menu.showWaitDialogBlocking(connection, cfg, cfg.authWaitSeconds());
            }

            pendingForceLogin.put(uuid, true);
            authed.set(true);
        } else {
            boolean ok = Menu.showLoginBlocking(connection, name, cfg, lang, this, plugin.ipGuard(), ip);
            if (!ok) {
                connection.disconnect(lang.disconnectLoginFailed(ip));
            } else {
                pendingForceLogin.put(uuid, true);
                authed.set(true);
            }
        }
    }

    private boolean captchaRequired(Cfg cfg, UUID uuid) {
        if (!cfg.captchaEnabled()) return false;
        boolean authMeCaptchaActive = debugCaptchaOverride != null
                ? debugCaptchaOverride
                : cachedAuthMeCaptchaEnabled;
        if (!authMeCaptchaActive) return false;
        return !plugin.captcha().isTrusted(uuid);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        String pending = pendingRegister.remove(uuid);
        if (pending != null) {
            boolean doLogin = pendingForceLogin.remove(uuid) != null;
            boolean numeric = plugin.cfg().authMode() != AuthMode.PASSWORD;
            if (numeric) {
                runAsync(() -> {
                    if (doLogin) {
                        registerAndLoginNumeric(player, pending);
                    } else if (!registerPlayerNoValidation(player.getName(), pending)) {
                        plugin.getLogger().warning("registerPlayer (PIN/slider) failed for " + player.getName());
                    }
                });
            } else if (doLogin) {
                registerAndLogin(player, pending);
            }
            return;
        } else if (pendingForceLogin.remove(uuid) != null) {
            login(player);
        }

        if (plugin.cfg().dialogEnabled() && !plugin.cfg().dialogPreSpawn()
                && !plugin.biaList().isBypassed(uuid)
                && !isPremiumSkip(uuid, player.getName())
                && ProtocolGate.supportsDialogs(uuid, player, plugin.cfg().dialogMinProtocolVersion())) {
            boolean registered = isRegisteredByName(player.getName());
            boolean recover = registered && plugin.recoverStore().isFlagged(uuid);
            runOnPlayer(player, () -> {
                if (recover) {
                    Menu.showRecoverIngame(player, plugin.cfg(), this, () -> {
                        plugin.recoverStore().clear(uuid);
                        if (!isAuthenticated(player)) runAsync(() -> login(player));
                    });
                } else if (registered) {
                    Menu.showLoginIngame(player, plugin.cfg(), plugin.lang(), this, plugin.ipGuard());
                } else {
                    Menu.showRegisterIngame(player, plugin.cfg(), plugin.lang(), this);
                }
                if (plugin.cfg().loginTimeoutEnabled()) {
                    startPostSpawnTimeout(player);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthMeLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        completeFuture(pendingLoginFutures, player.getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthMeFailedLogin(FailedLoginEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        completeFuture(pendingLoginFutures, player.getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthMeRegister(RegisterEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        completeFuture(pendingRegisterFutures, player.getUniqueId(), true);
    }

    private CompletableFuture<Boolean> awaitLogin(Player player, long timeoutMs) {
        return await(pendingLoginFutures, player.getUniqueId(), timeoutMs);
    }

    private CompletableFuture<Boolean> awaitRegister(Player player, long timeoutMs) {
        return await(pendingRegisterFutures, player.getUniqueId(), timeoutMs);
    }

    private CompletableFuture<Boolean> await(Map<UUID, CompletableFuture<Boolean>> map, UUID uuid, long timeoutMs) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        CompletableFuture<Boolean> previous = map.put(uuid, future);
        if (previous != null && !previous.isDone()) {
            previous.complete(false);
        }
        scheduleTimeout(map, uuid, future, timeoutMs);
        return future;
    }

    private void scheduleTimeout(Map<UUID, CompletableFuture<Boolean>> map, UUID uuid,
                                 CompletableFuture<Boolean> future, long timeoutMs) {
        Runnable timeout = () -> {
            if (map.remove(uuid, future)) {
                future.complete(false);
            }
        };
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> timeout.run(), timeoutMs, TimeUnit.MILLISECONDS);
        } else {
            long delayTicks = Math.max(1L, timeoutMs / 50L);
            plugin.getServer().getScheduler().runTaskLater(plugin, timeout, delayTicks);
        }
    }

    private void completeFuture(Map<UUID, CompletableFuture<Boolean>> map, UUID uuid, boolean value) {
        CompletableFuture<Boolean> future = map.remove(uuid);
        if (future != null) {
            future.complete(value);
        }
    }

    private void runOnPlayer(Player player, Runnable task) {
        if (plugin.isFolia()) {
            player.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    void runAsync(Runnable task) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * login_timeout (Feature 3), pre-spawn: disconnects the still-connected,
     * not-yet-authenticated connection after {@code seconds}. The {@code authed}
     * flag short-circuits the task once auth completed.
     */
    private void scheduleDisconnect(PlayerConfigurationConnection conn,
                                    java.util.concurrent.atomic.AtomicBoolean authed,
                                    net.kyori.adventure.text.Component message, int seconds) {
        Runnable task = () -> {
            if (!authed.get() && conn.isConnected()) conn.disconnect(message);
        };
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runDelayed(plugin, t -> task.run(), seconds, TimeUnit.SECONDS);
        } else {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, seconds * 20L);
        }
    }

    private void startPostSpawnTimeout(Player player) {
        int configured = plugin.cfg().loginTimeoutSeconds();
        boolean kick = configured != 0;
        int delaySeconds = configured == 0 ? 60 : configured;
        Runnable task = () -> {
            if (!player.isOnline() || isAuthenticated(player)) return;
            if (kick) {
                player.kick(plugin.cfg().loginTimeoutKickMessage());
            } else {
                boolean registered = isRegisteredByName(player.getName());
                if (registered) {
                    Menu.showLoginIngame(player, plugin.cfg(), plugin.lang(), this, plugin.ipGuard());
                } else {
                    Menu.showRegisterIngame(player, plugin.cfg(), plugin.lang(), this);
                }
            }
        };
        if (plugin.isFolia()) {
            player.getScheduler().runDelayed(plugin, t -> task.run(), null, delaySeconds * 20L);
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delaySeconds * 20L);
        }
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        AuthInput.clearSession(uuid);
        Menu.clearEmailSession(uuid);
        pendingEmail.remove(uuid);
    }

    public boolean isRegisteredByName(String name) {
        try { return (boolean) isRegistered.invoke(api, name); }
        catch (Exception e) { return false; }
    }

    public void login(Player player) {
        runLater(player, () -> doForceLogin(player, false), 5L);
    }

    private void doForceLogin(Player player, boolean welcome) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<Boolean> future = awaitLogin(player, AUTH_RESULT_TIMEOUT_MS);
        try {
            forceLogin.invoke(api, player);
        } catch (Exception e) {
            plugin.getLogger().warning("forceLogin failed for " + player.getName() + ": " + e.getMessage());
            completeFuture(pendingLoginFutures, uuid, false);
            return;
        }
        future.thenAccept(ok -> {
            if (!ok) return;
            runOnPlayer(player, () -> { clearBlindEffect(player); closeDialog(player); });
            if (welcome && (plugin.cfg().welcomeImageEnabled() || plugin.cfg().discordEnabled())) {
                runAsync(() -> new com.authmebia.api.Welcome(plugin).handle(player));
            }
        });
    }

    private void afterWait(Player player, Runnable forceOp) {
        Cfg cfg = plugin.cfg();
        long ticks;
        if (cfg.authWaitEnabled() && !cfg.authWaitPreJoin()) {
            ticks = Math.max(20L, cfg.authWaitSeconds() * 20L);
            runOnPlayer(player, () -> Menu.showWaitDialog(player, cfg));
        } else {
            ticks = 5L;
        }
        runLater(player, forceOp, ticks);
    }

    private void runLater(Player player, Runnable task, long ticks) {
        long delay = Math.max(1L, ticks);
        if (plugin.isFolia()) {
            player.getScheduler().runDelayed(plugin, t -> task.run(), null, delay);
        } else {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, delay);
        }
    }

    private void closeDialog(Player player) {
        try { player.closeDialog(); } catch (Throwable ignored) {}
    }

    public void logout(Player player) {
        try { forceLogout.invoke(api, player); }
        catch (Exception ignored) {}
    }

    public boolean checkPassword(String name, String password) {
        try { return (boolean) checkPassword.invoke(api, name, password); }
        catch (Exception e) { return false; }
    }

    public void changePassword(String name, String newPassword) {
        try { changePassword.invoke(api, name, newPassword); }
        catch (Exception e) {
            plugin.getLogger().warning("changePassword failed for " + name + ": " + e.getMessage());
        }
    }

    public boolean isAuthenticated(Player player) {
        try { return (boolean) isAuthenticated.invoke(api, player); }
        catch (Exception e) { return false; }
    }

    public boolean registerPlayerNoValidation(String name, String password) {
        try {
            return (boolean) registerPlayer.invoke(api, name, password);
        } catch (Exception e) {
            plugin.getLogger().warning("registerPlayer (no-validation) failed for " + name + ": " + e.getMessage());
            return false;
        }
    }

    public void registerAndLoginNumeric(Player player, String code) {
        if (!registerPlayerNoValidation(player.getName(), code)) {
            return;
        }
        afterWait(player, () -> doForceLogin(player, true));
    }

    public boolean isPremiumSkip(UUID connectingUuid, String name) {
        if (!cachedPremiumEnabled || dataSource == null || dsGetAuth == null || name == null) {
            return false;
        }
        if (connectingUuid == null || connectingUuid.version() != 4) {
            return false;
        }
        try {
            Object auth = dsGetAuth.invoke(dataSource, name.toLowerCase(java.util.Locale.ROOT));
            if (auth == null) return false;
            if (!(boolean) authIsPremium.invoke(auth)) return false;
            Object premiumUuid = authGetPremiumUuid.invoke(auth);
            return connectingUuid.equals(premiumUuid);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEmailVerificationActive() {
        if (!plugin.cfg().emailEnabled()) return false;
        if (debugEmailOverride != null) return debugEmailOverride;
        if (emailService == null || emailHasAllInfo == null) return false;
        try { return (boolean) emailHasAllInfo.invoke(emailService); }
        catch (Exception e) { return false; }
    }

    public boolean sendVerificationEmail(String name, String email, String code) {
        if (emailService == null || emailSendVerification == null) return false;
        try {
            return (boolean) emailSendVerification.invoke(emailService, name, email, code);
        } catch (Exception e) {
            plugin.getLogger().warning("sendVerificationMail failed for " + name + ": " + e.getMessage());
            return false;
        }
    }

    public void storeEmail(String name, String email) {
        if (dataSource == null || dsGetAuth == null || dsUpdateEmail == null || authSetEmail == null) return;
        try {
            Object auth = dsGetAuth.invoke(dataSource, name.toLowerCase(java.util.Locale.ROOT));
            if (auth == null) return;
            authSetEmail.invoke(auth, email);
            dsUpdateEmail.invoke(dataSource, auth);
        } catch (Exception e) {
            plugin.getLogger().warning("storeEmail failed for " + name + ": " + e.getMessage());
        }
    }

    public void registerAndLogin(Player player, String password) {
        afterWait(player, () -> doForceRegister(player, password));
    }

    private void doForceRegister(Player player, String password) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<Boolean> future = awaitLogin(player, AUTH_RESULT_TIMEOUT_MS);
        try {
            forceRegister.invoke(api, player, password);
        } catch (Exception e) {
            plugin.getLogger().warning("forceRegister failed for " + player.getName() + ": " + e.getMessage());
            completeFuture(pendingLoginFutures, uuid, false);
            return;
        }
        future.thenAccept(ok -> {
            if (!ok) return;
            runOnPlayer(player, () -> { clearBlindEffect(player); closeDialog(player); });
            String email = pendingEmail.remove(player.getUniqueId());
            if (email != null) {
                runAsync(() -> storeEmail(player.getName(), email));
            }
            if (plugin.cfg().welcomeImageEnabled() || plugin.cfg().discordEnabled()) {
                runAsync(() -> new com.authmebia.api.Welcome(plugin).handle(player));
            }
        });
    }

    private void clearBlindEffect(Player player) {
        if (!cachedBlindEffectEnabled) return;
        if (player.hasPotionEffect(PotionEffectType.BLINDNESS)) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    public void overrideCachedAuthMeCaptchaEnabled(boolean value) {
        debugCaptchaOverride = value;
    }

    public void overrideCachedEmailEnabled(boolean value) {
        debugEmailOverride = value;
    }

    public boolean hasTotpEnabled(String name) {
        if (totpAuthenticator == null || dataSource == null || dsGetAuth == null || authGetTotpKey == null) return false;
        try {
            Object auth = dsGetAuth.invoke(dataSource, name.toLowerCase(java.util.Locale.ROOT));
            if (auth == null) return false;
            String totpKey = (String) authGetTotpKey.invoke(auth);
            return totpKey != null && !totpKey.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean checkTotpCode(String name, String code) {
        if (totpAuthenticator == null || dataSource == null || dsGetAuth == null || totpCheckCode == null) return false;
        try {
            Object auth = dsGetAuth.invoke(dataSource, name.toLowerCase(java.util.Locale.ROOT));
            if (auth == null) return false;
            // checkCode(PlayerAuth, String) - AuthMe 6.0.0-SNAPSHOT API
            return (boolean) totpCheckCode.invoke(totpAuthenticator, auth, code);
        } catch (Exception e) {
            return false;
        }
    }
}
