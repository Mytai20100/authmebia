package com.authmebia;

import com.authmebia.cfg.Cfg;
import com.authmebia.dialog.Mode;
import com.authmebia.dialog.captcha.Captcha;
import com.authmebia.dialog.customscreen.CustomScreen;
import com.authmebia.dialog.emailverify.EmailVerify;
import com.authmebia.dialog.login.Login;
import com.authmebia.dialog.recover.Recover;
import com.authmebia.dialog.register.Register;
import com.authmebia.dialog.rule.Rule;
import com.authmebia.dialog.shared.AuthInput;
import com.authmebia.lang.Lang;
import com.authmebia.dialog.util.Util;
import com.authmebia.listeners.version.Version;
import com.authmebia.listeners.ipguard.IpGuard;
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
    private Method dsHasSession;
    private Method authIsPremium;
    private Method authGetPremiumUuid;
    private Method authGetEmail;
    private volatile boolean cachedPremiumEnabled = false;
    private Object playerCache;
    private Method pcIsAuthenticatedByName;
    private Object emailService;
    private Method emailHasAllInfo;
    private Method emailSendVerification;
    private Method dsUpdateEmail;
    private Method authSetEmail;
    public final Map<UUID, String> pendingEmail = new ConcurrentHashMap<>();
    private Object totpAuthenticator;
    private Method totpCheckCode;
    private Method authGetTotpKey;
    private volatile boolean cachedBlindEffectEnabled = false;
    private volatile boolean cachedAuthMeCaptchaEnabled = false;
    private volatile Boolean debugCaptchaOverride = null;
    private volatile Boolean debugEmailOverride = null;
    private volatile boolean cachedSessionsEnabled = false;
    private volatile long cachedSessionTimeoutMinutes = 0;
    private volatile long cachedConfigLastModified = -1L;
    final Map<UUID, String> pendingRegister = new ConcurrentHashMap<>();
    final Map<UUID, Boolean> pendingForceLogin = new ConcurrentHashMap<>();
    final Map<UUID, Boolean> pendingAutoLogin = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> pendingLoginFutures = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> pendingRegisterFutures = new ConcurrentHashMap<>();

    public AuthMe(AuthMeBia plugin) {
        this.plugin = plugin;
        initReflection();
        refreshAuthMeConfigCache();
    }

    private Method getLastIp;
    private Method getLastLoginTime;

    private void initReflection() {
        try {
            Class<?> cls = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            api = cls.getMethod("getInstance").invoke(null);
            isRegistered = cls.getMethod("isRegistered", String.class);
            forceLogin = cls.getMethod("forceLogin", Player.class);
            forceLogout = cls.getMethod("forceLogout", Player.class);
            forceRegister = cls.getMethod("forceRegister", Player.class, String.class);
            checkPassword = cls.getMethod("checkPassword", String.class, String.class);
            changePassword = cls.getMethod("changePassword", String.class, String.class);
            isAuthenticated = cls.getMethod("isAuthenticated", Player.class);
            registerPlayer = cls.getMethod("registerPlayer", String.class, String.class);
            getLastIp = cls.getMethod("getLastIp", String.class);
            getLastLoginTime = cls.getMethod("getLastLoginTime", String.class);
        } catch (Exception e) {
            plugin.getLogger().severe("AuthMe API bind failed: " + e.getMessage());
        }

        if (api == null) {
            plugin.getLogger().severe("AuthMeApi.getInstance() returned null during startup; "
                    + "AuthMe may not be fully initialized yet. Premium skip, session autologin, "
                    + "email, and 2FA features will be unavailable until the server is reloaded/restarted "
                    + "with AuthMe already enabled.");
            return;
        }

        try {
            java.lang.reflect.Field dsField = api.getClass().getDeclaredField("dataSource");
            dsField.setAccessible(true);
            dataSource = dsField.get(api);
            Class<?> dsClass = Class.forName("fr.xephi.authme.datasource.DataSource");
            dsGetAuth = dsClass.getMethod("getAuth", String.class);
            dsHasSession = dsClass.getMethod("hasSession", String.class);
            Class<?> authClass = Class.forName("fr.xephi.authme.data.auth.PlayerAuth");
            authIsPremium = authClass.getMethod("isPremium");
            authGetPremiumUuid = authClass.getMethod("getPremiumUuid");
            authGetEmail = authClass.getMethod("getEmail");
        } catch (Throwable t) {
            plugin.getLogger().warning("AuthMe DataSource reflection unavailable; premium skip and "
                    + "session autologin both disabled (" + t + ")");
            dataSource = null;
        }

        try {
            java.lang.reflect.Field pcField = api.getClass().getDeclaredField("playerCache");
            pcField.setAccessible(true);
            playerCache = pcField.get(api);
            Class<?> pcClass = Class.forName("fr.xephi.authme.data.auth.PlayerCache");
            pcIsAuthenticatedByName = pcClass.getMethod("isAuthenticated", String.class);
        } catch (Throwable t) {
            plugin.getLogger().info("AuthMe PlayerCache reflection unavailable; "
                    + "post-join session dialog-skip fallback disabled, but "
                    + "auto.session_autologin still works via getLastIp/getLastLoginTime (" + t + ")");
            playerCache = null;
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
            cachedSessionsEnabled = false;
            cachedConfigLastModified = -1L;
            return;
        }

        File file = new File(authme.getDataFolder(), "config.yml");
        if (!file.exists()) {
            cachedBlindEffectEnabled = false;
            cachedAuthMeCaptchaEnabled = false;
            cachedPremiumEnabled = false;
            cachedSessionsEnabled = false;
            cachedConfigLastModified = -1L;
            return;
        }

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

            if (yaml.isSet("settings.applyBlindEffect")) {
                cachedBlindEffectEnabled = yaml.getBoolean("settings.applyBlindEffect", false);
            } else {
                cachedBlindEffectEnabled = yaml.getBoolean("applyBlindEffect", false);
            }

            cachedSessionsEnabled = yaml.getBoolean("settings.sessions.enabled", false);
            cachedSessionTimeoutMinutes = yaml.getLong("settings.sessions.timeout", 10);
            cachedAuthMeCaptchaEnabled = yaml.getBoolean("Security.captcha.useCaptcha", false);
            cachedPremiumEnabled = yaml.getBoolean("settings.enablePremium", false);
            cachedConfigLastModified = file.lastModified();
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read AuthMe config.yml: " + e.getMessage());
            cachedBlindEffectEnabled = false;
            cachedAuthMeCaptchaEnabled = false;
            cachedPremiumEnabled = false;
        }
    }
    private void ensureAuthMeConfigFresh() {
        org.bukkit.plugin.Plugin authme = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = plugin.getServer().getPluginManager().getPlugin("AuthMeReloaded");
        if (authme == null) return;

        File file = new File(authme.getDataFolder(), "config.yml");
        long onDisk = file.exists() ? file.lastModified() : -1L;
        if (onDisk != cachedConfigLastModified) {
            refreshAuthMeConfigCache();
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

        if (plugin.cfg().sessionAutologinEnabled()
                && isSessionEligibleByLastLogin(name, IpGuard.resolveIp(connection))) {
            return;
        }

        UUID autoLoginJavaUuid = resolveAutoLoginJavaUuid(uuid, name);
        if (autoLoginJavaUuid != null) {
            pendingAutoLogin.put(uuid, true);
            return;
        }

        Cfg cfg = resolveEffectiveCfg(uuid);
        Lang lang = plugin.lang();

        if (!Version.supportsDialogs(uuid, null, cfg.dialogMinProtocolVersion())) {
            return;
        }

        String ip = IpGuard.resolveIp(connection);

        java.util.concurrent.atomic.AtomicBoolean authed = new java.util.concurrent.atomic.AtomicBoolean(false);
        if (cfg.loginTimeoutEnabled() && cfg.loginTimeoutSeconds() > 0) {
            scheduleDisconnect(connection, authed, cfg.loginTimeoutKickMessage(), cfg.loginTimeoutSeconds());
        }

        if (captchaRequired(cfg, uuid)) {
            boolean verified = Captcha.showCaptchaBlocking(connection, cfg, lang, plugin.captcha());
            if (!verified) {
                connection.disconnect(lang.disconnectVerificationFailed(ip));
                return;
            }
            plugin.captcha().markTrusted(uuid, cfg.captchaTrustDurationSeconds());
        }

        boolean registered = isRegisteredByName(name);

        if (registered && plugin.recoverStore().isFlagged(uuid)) {
            String newPass = Recover.showRecoverBlocking(connection, cfg);
            if (newPass == null) {
                connection.disconnect(lang.disconnectLoginFailed(ip));
                return;
            }
            changePassword(name, newPass);
            plugin.recoverStore().clear(uuid);
            showPrejoinScreensBlocking(connection, name);
            pendingForceLogin.put(uuid, true);
            authed.set(true);
            return;
        }

        if (!registered) {
            String password = Register.showRegisterBlocking(connection, cfg, lang);
            if (password == null) {
                connection.disconnect(lang.disconnectRegistrationCancelled(ip));
                return;
            }
            pendingRegister.put(uuid, password);

            if (cfg.ruleEnabled()) {
                boolean agreed = Rule.showRuleBlocking(connection, cfg);
                if (!agreed) {
                    connection.disconnect(lang.disconnectMustAgreeRules(ip));
                    pendingRegister.remove(uuid);
                    return;
                }
            }

            showPrejoinScreensBlocking(connection, name);
            pendingForceLogin.put(uuid, true);
            authed.set(true);
        } else {
            boolean ok = Login.showLoginBlocking(connection, name, cfg, lang, this, plugin.ipGuard(), ip);
            if (!ok) {
                connection.disconnect(lang.disconnectLoginFailed(ip));
            } else {
                showPrejoinScreensBlocking(connection, name);
                pendingForceLogin.put(uuid, true);
                authed.set(true);
            }
        }
    }

    private void showPrejoinScreensBlocking(PlayerConfigurationConnection conn, String playerName) {
        UUID uuid = conn.getProfile().getId();
        Cfg.withPlayerContext(playerName, () -> {
            for (CustomScreen screen : plugin.cfg().customScreens()) {
                if (!screen.enabled()) continue;
                if (screen.trigger() != com.authmebia.dialog.customscreen.CustomScreen.Trigger.PREJOIN) continue;
                if (CustomScreen.isAutoDismissedFor(screen, uuid)) continue;
                if (!conn.isConnected()) break;
                CustomScreen.showCustomScreenBlocking(conn, screen, playerName);
            }
        });
    }

    private void showPostJoinScreens(Player player) {
        UUID uuid = player.getUniqueId();
        Cfg.withPlayerContext(player.getName(), () -> {
            for (CustomScreen screen : plugin.cfg().customScreens()) {
                if (!screen.enabled()) continue;
                if (screen.trigger() != com.authmebia.dialog.customscreen.CustomScreen.Trigger.POSTJOIN) continue;
                if (CustomScreen.isAutoDismissedFor(screen, uuid)) continue;
                CustomScreen.showCustomScreen(player, screen, player.getName());
                return;
            }
        });
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

        if (pendingAutoLogin.remove(uuid) != null) {
            runAsync(() -> completeAutoLogin(player));
            return;
        }

        String pending = pendingRegister.remove(uuid);
        if (pending != null) {
            boolean doLogin = pendingForceLogin.remove(uuid) != null;
            boolean numeric = plugin.cfg().authMode() != Mode.PASSWORD;
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
                && !(plugin.cfg().sessionAutologinEnabled()
                        && (isSessionAuthenticated(player.getName())
                                || isSessionEligibleByLastLogin(player.getName(), Util.ipOf(player))))) {
            Cfg cfg = resolveEffectiveCfg(uuid);
            boolean registered = isRegisteredByName(player.getName());
            boolean recover = registered && plugin.recoverStore().isFlagged(uuid);
            boolean dialogsSupported = Version.supportsDialogs(uuid, player, cfg.dialogMinProtocolVersion());
            boolean numericMode = cfg.authMode() == Mode.PIN || cfg.authMode() == Mode.SLIDER;

            if (dialogsSupported) {
                runOnPlayer(player, () -> {
                    if (recover) {
                        Recover.showRecoverIngame(player, cfg, this, () -> {
                            plugin.recoverStore().clear(uuid);
                            if (!isAuthenticated(player)) runAsync(() -> login(player));
                        });
                    } else if (registered) {
                        Login.showLoginIngame(player, cfg, plugin.lang(), this, plugin.ipGuard());
                    } else {
                        Register.showRegisterIngame(player, cfg, plugin.lang(), this);
                    }
                    if (plugin.cfg().loginTimeoutEnabled()) {
                        startPostSpawnTimeout(player);
                    }
                });
            } else if (numericMode) {
                runOnPlayer(player, () -> {
                    if (recover) {
                        Recover.showRecoverInventoryFallback(player, cfg, this, () -> {
                            plugin.recoverStore().clear(uuid);
                            if (!isAuthenticated(player)) runAsync(() -> login(player));
                        });
                    } else if (registered) {
                        Login.showLoginInventoryFallback(player, cfg, plugin.lang(), this, plugin.ipGuard());
                    } else {
                        Register.showRegisterInventoryFallback(player, cfg, plugin.lang(), this);
                    }
                    if (plugin.cfg().loginTimeoutEnabled()) {
                        startPostSpawnTimeout(player);
                    }
                });
            }
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

    public void runAsync(Runnable task) {
        if (plugin.isFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

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
        UUID uuid = player.getUniqueId();
        Runnable task = () -> {
            if (!player.isOnline() || isAuthenticated(player)) return;
            if (kick) {
                player.kick(plugin.cfg().loginTimeoutKickMessage());
            } else {
                Cfg cfg = resolveEffectiveCfg(uuid);
                boolean registered = isRegisteredByName(player.getName());
                if (registered) {
                    Login.showLoginIngame(player, cfg, plugin.lang(), this, plugin.ipGuard());
                } else {
                    Register.showRegisterIngame(player, cfg, plugin.lang(), this);
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
        EmailVerify.clearEmailSession(uuid);
        pendingEmail.remove(uuid);
        com.authmebia.dialog.Dialoglib.clearEscapeGuard(uuid);
    }

    public boolean isRegisteredByName(String name) {
        try { return (boolean) isRegistered.invoke(api, name); }
        catch (Exception e) { return false; }
    }

    public void login(Player player) {
        runLater(player, () -> doForceLogin(player, false), 5L);
    }

    private void doForceLogin(Player player, boolean welcome) {
        doForceLogin(player, welcome, null);
    }

    private void doForceLogin(Player player, boolean welcome, Runnable onSuccess) {
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
            runOnPlayer(player, () -> {
                clearBlindEffect(player);
                closeDialog(player);
                showPostJoinScreens(player);
            });
            if (welcome && (plugin.cfg().welcomeImageEnabled() || plugin.cfg().discordEnabled())) {
                runAsync(() -> new com.authmebia.api.Welcome(plugin).handle(player));
            }
            if (onSuccess != null) {
                onSuccess.run();
            }
        });
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
        runLater(player, () -> doForceLogin(player, true), 1L);
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
    private Cfg resolveEffectiveCfg(UUID uuid) {
        Cfg base = plugin.cfg();
        if (com.authmebia.listeners.floodgate.Floodgate.isFloodgatePlayer(uuid)) {
            return new com.authmebia.cfg.BedrockCfg(plugin, base);
        }
        return base;
    }
    private UUID resolveAutoLoginJavaUuid(UUID connectingUuid, String name) {
        Cfg cfg = plugin.cfg();

        if (cfg.premiumAutologinEnabled() && isPremiumSkip(connectingUuid, name)) {
            return connectingUuid;
        }

        if (cfg.bedrockAutologinEnabled()
                && com.authmebia.listeners.floodgate.Floodgate.isFloodgatePlayer(connectingUuid)) {
            if (cfg.bedrockAutoLoginMode() == com.authmebia.cfg.BedrockCfg.AutoLoginMode.GEYSER) {
                return connectingUuid;
            }

            UUID linkedJavaUuid = com.authmebia.listeners.floodgate.Floodgate.getLinkedJavaUuid(connectingUuid);
            if (linkedJavaUuid != null && linkedJavaUuid.version() == 4) {
                return linkedJavaUuid;
            }
        }

        return null;
    }
    private void completeAutoLogin(Player player) {
        String name = player.getName();
        boolean registered = isRegisteredByName(name);

        if (!registered) {
            String placeholder = com.authmebia.dialog.util.Util.genInternalPlaceholderPassword();
            if (!registerPlayerNoValidation(name, placeholder)) {
                plugin.getLogger().warning("Auto-login registerPlayer failed for " + name
                        + "; falling back to the normal register dialog.");
                runOnPlayer(player, () -> Register.showRegisterIngame(player, resolveEffectiveCfg(player.getUniqueId()), plugin.lang(), this));
                return;
            }
        }

        runLater(player, () -> doForceLogin(player, false, () ->
                runOnPlayer(player, () -> com.authmebia.dialog.premium.Premium.showResetIngame(
                        player, resolveEffectiveCfg(player.getUniqueId()), this, () -> {}))
        ), 5L);
    }
    public boolean isSessionEligibleByLastLogin(String name, String ip) {
        ensureAuthMeConfigFresh();
        if (!cachedSessionsEnabled || name == null || ip == null) return false;
        if (getLastIp == null || getLastLoginTime == null) return false;
        if (dataSource == null || dsHasSession == null) return false;
        try {
            Object hasSessionObj = dsHasSession.invoke(dataSource, name.toLowerCase(java.util.Locale.ROOT));
            if (!(hasSessionObj instanceof Boolean hasSession) || !hasSession) return false;

            String lastIp = (String) getLastIp.invoke(api, name);
            if (lastIp == null) return false;
            if (!lastIp.equalsIgnoreCase(ip)) {
                return false;
            }
            Object lastLoginObj = getLastLoginTime.invoke(api, name);
            if (!(lastLoginObj instanceof java.time.Instant lastLogin)) return false;
            long elapsedMs = System.currentTimeMillis() - lastLogin.toEpochMilli();
            return elapsedMs > 0 && elapsedMs < cachedSessionTimeoutMinutes * 60_000L;
        } catch (Exception e) {
            return false;
        }
    }
    public boolean isSessionAuthenticated(String name) {
        if (playerCache == null || pcIsAuthenticatedByName == null || name == null) return false;
        try {
            Object result = pcIsAuthenticatedByName.invoke(playerCache, name);
            return result instanceof Boolean b && b;
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
    public String getStoredEmail(String name) {
        if (dataSource == null || dsGetAuth == null || authGetEmail == null || name == null) return null;
        try {
            Object auth = dsGetAuth.invoke(dataSource, name.toLowerCase(java.util.Locale.ROOT));
            if (auth == null) return null;
            return (String) authGetEmail.invoke(auth);
        } catch (Exception e) {
            return null;
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
        runLater(player, () -> doForceRegister(player, password), 1L);
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
            if (!ok) {
                if (!player.isOnline()) return;
                if (isAuthenticated(player)) {
                    finishRegisterLogin(player);
                } else {
                    plugin.getLogger().warning("forceRegister for " + player.getName()
                            + " did not fire LoginEvent in time; retrying forceLogin explicitly");
                    doForceLogin(player, false);
                }
                return;
            }
            finishRegisterLogin(player);
        });
    }
    private void finishRegisterLogin(Player player) {
        runOnPlayer(player, () -> { clearBlindEffect(player); closeDialog(player); });
        String email = pendingEmail.remove(player.getUniqueId());
        if (email != null) {
            runAsync(() -> storeEmail(player.getName(), email));
        }
        if (plugin.cfg().welcomeImageEnabled() || plugin.cfg().discordEnabled()) {
            runAsync(() -> new com.authmebia.api.Welcome(plugin).handle(player));
        }
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
            return (boolean) totpCheckCode.invoke(totpAuthenticator, auth, code);
        } catch (Exception e) {
            return false;
        }
    }
}
