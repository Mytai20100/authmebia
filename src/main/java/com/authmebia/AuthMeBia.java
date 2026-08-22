package com.authmebia;

import com.authmebia.cfg.Cfg;
import com.authmebia.cmd.Cmd;
import com.authmebia.data.PlayerDataStore;
import com.authmebia.lang.Lang;
import com.authmebia.listeners.bialist.BiaList;
import com.authmebia.listeners.captcha.Captcha;
import com.authmebia.listeners.ipguard.IpGuard;
import com.authmebia.listeners.recoverstore.RecoverStore;
import com.authmebia.listeners.screendismiss.ScreenDismissStore;
import com.authmebia.listeners.version.Version;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import okhttp3.OkHttpClient;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

@SuppressWarnings("UnstableApiUsage")
public final class AuthMeBia extends JavaPlugin {

    private static AuthMeBia instance;
    private Cfg cfg;
    private Captcha captcha;
    private IpGuard ipGuard;
    private Lang lang;
    private PlayerDataStore playerDataStore;
    private BiaList biaList;
    private RecoverStore recoverStore;
    private ScreenDismissStore screenDismissStore;
    private boolean foliaServer;
    private OkHttpClient httpClient;
    private AuthMe authMeListener;
    private com.authmebia.notifications.ToastListener toastListener;

    @Override
    public void onEnable() {
        instance = this;
        foliaServer = detectFolia();

        if (!checkAuthMe()) {
            getLogger().severe("AuthMe (or any fork) is not installed or not enabled. Disabling AuthMeBia.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        cfg = new Cfg(this);
        lang = new Lang(this);
        captcha = new Captcha();
        ipGuard = new IpGuard();
        playerDataStore = new PlayerDataStore(this);
        biaList = new BiaList(this, playerDataStore);
        recoverStore = new RecoverStore(this, playerDataStore);
        screenDismissStore = new ScreenDismissStore(this, playerDataStore);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        if (!new java.io.File(getDataFolder(), "welcome.json").exists()) {
            saveResource("welcome.json", false);
        }

        if (!new java.io.File(getDataFolder(), "background.png").exists()) {
            saveResource("background.png", false);
        }

        if (!new java.io.File(getDataFolder(), "doc/README.md").exists()) {
            saveResource("doc/README.md", false);
        }

        saveDefaultLang("lang/en.yml");
        saveDefaultLang("lang/vi.yml");

        authMeListener = new AuthMe(this);
        getServer().getPluginManager().registerEvents(authMeListener, this);
        toastListener = new com.authmebia.notifications.ToastListener(this);
        getServer().getPluginManager().registerEvents(toastListener, this);
        getServer().getPluginManager().registerEvents(new com.authmebia.dialog.shared.InventoryAuthInput(), this);
        getServer().getPluginManager().registerEvents(new com.authmebia.listeners.itemsadder.ItemsAdder(this), this);
        getServer().getPluginManager().registerEvents(new com.authmebia.listeners.nexomc.NexoMC(this), this);
        getServer().getPluginManager().registerEvents(new com.authmebia.listeners.oraxen.Oraxen(this), this);

        LifecycleEventManager<org.bukkit.plugin.Plugin> manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            new Cmd(this).register(commands);
        });

        warnIfAuthMeDialogConflict();
        warnIfBedrockGeyserModeUnsafe();
        warnIfPinSliderTooShort();

        getLogger().info("AuthMeBia enabled on " + platformName() + ".");
    }

    private void warnIfPinSliderTooShort() {
        if (cfg().authMode() != com.authmebia.dialog.Mode.PIN
                && cfg().authMode() != com.authmebia.dialog.Mode.SLIDER
                && cfg().bedrockAuthMode() != com.authmebia.dialog.Mode.PIN
                && cfg().bedrockAuthMode() != com.authmebia.dialog.Mode.SLIDER) {
            return;
        }

        org.bukkit.plugin.Plugin authme = getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = getServer().getPluginManager().getPlugin("AuthMeReloaded");
        if (authme == null) return;

        java.io.File file = new java.io.File(authme.getDataFolder(), "config.yml");
        if (!file.exists()) return;

        try {
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            int minPasswordLength = yaml.getInt("settings.security.minPasswordLength", 5);

            int pinLength = cfg().pinLength();
            if (cfg().authMode() == com.authmebia.dialog.Mode.PIN
                    || cfg().bedrockAuthMode() == com.authmebia.dialog.Mode.PIN) {
                if (pinLength < minPasswordLength) {
                    getLogger().warning("auth_mode.pin.length is " + pinLength
                            + ", but AuthMe's settings.security.minPasswordLength is " + minPasswordLength
                            + ". Every PIN register/login will be rejected by AuthMe as too short. "
                            + "Raise auth_mode.pin.length to at least " + minPasswordLength
                            + ", or lower minPasswordLength in AuthMe's own config.yml.");
                }
            }

            int sliderLength = cfg().sliderLength();
            if (cfg().authMode() == com.authmebia.dialog.Mode.SLIDER
                    || cfg().bedrockAuthMode() == com.authmebia.dialog.Mode.SLIDER) {
                if (sliderLength < minPasswordLength) {
                    getLogger().warning("auth_mode.slider.length is " + sliderLength
                            + ", but AuthMe's settings.security.minPasswordLength is " + minPasswordLength
                            + ". Every slider register/login will be rejected by AuthMe as too short. "
                            + "Raise auth_mode.slider.length to at least " + minPasswordLength
                            + ", or lower minPasswordLength in AuthMe's own config.yml.");
                }
            }
        } catch (Exception e) {
            getLogger().warning("Could not read AuthMe config.yml for PIN/slider length check: " + e.getMessage());
        }
    }

    private void warnIfBedrockGeyserModeUnsafe() {
        if (!cfg().bedrockAutologinEnabled()) return;
        if (cfg().bedrockAutoLoginMode() != com.authmebia.cfg.BedrockCfg.AutoLoginMode.GEYSER) return;

        getLogger().warning("auto.bedrock_mode is set to 'geyser'. This trusts ANY Bedrock/Geyser "
                + "connection with NO account-linking verification -- it is only safe if Geyser's own "
                + "auth-type is set to 'online' (real Xbox Live sign-in) in Geyser's config.yml. If Geyser "
                + "is running in offline mode, this setting lets anyone auto-login as any Bedrock identity "
                + "with no password. See the auto.bedrock_mode comment in config.yml for details.");
    }

    private void warnIfAuthMeDialogConflict() {
        org.bukkit.plugin.Plugin authme = getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = getServer().getPluginManager().getPlugin("AuthMeReloaded");
        if (authme == null) return;

        java.io.File file = new java.io.File(authme.getDataFolder(), "config.yml");
        if (!file.exists()) return;

        try {
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            boolean pre = yaml.getBoolean("settings.registration.dialog.preJoin.enable", true);
            boolean post = yaml.getBoolean("settings.registration.dialog.postJoin.enable", true);
            if (pre || post) {
                getLogger().warning("AuthMe's built-in dialog is enabled "
                        + "(settings.registration.dialog.preJoin/postJoin.enable). "
                        + "Disable it in AuthMe's config.yml to prevent two dialogs appearing at once.");
            }
        } catch (Exception e) {
            getLogger().warning("Could not read AuthMe config.yml for dialog-conflict check: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        Version.reset();
        com.authmebia.listeners.floodgate.Floodgate.reset();

        if (captcha != null) {
            captcha.shutdown();
        }

        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }

        getLogger().info("AuthMeBia disabled.");
    }

    private void saveDefaultLang(String resource) {
        java.io.File file = new java.io.File(getDataFolder(), resource);
        if (!file.exists()) {
            saveResource(resource, false);
        }
    }

    public static AuthMeBia get() {
        return instance;
    }

    public Cfg cfg() {
        return cfg;
    }

    public Captcha captcha() {
        return captcha;
    }

    public IpGuard ipGuard() {
        return ipGuard;
    }

    public Lang lang() {
        return lang;
    }

    public BiaList biaList() {
        return biaList;
    }

    public RecoverStore recoverStore() {
        return recoverStore;
    }

    public ScreenDismissStore screenDismissStore() {
        return screenDismissStore;
    }

    public PlayerDataStore playerDataStore() {
        return playerDataStore;
    }

    public OkHttpClient httpClient() {
        return httpClient;
    }

    public boolean isFolia() {
        return foliaServer;
    }

    public String platformName() {
        String serverName = getServer().getName();
        boolean isPaperApi;
        try {
            Class.forName("io.papermc.paper.configuration.Configuration");
            isPaperApi = true;
        } catch (ClassNotFoundException ignored) {
            isPaperApi = false;
        }

        if (!isPaperApi) {
            // No Paper API classes at all -- plain Bukkit/Spigot/CraftBukkit.
            return serverName != null && !serverName.isBlank() ? serverName : "Bukkit";
        }

        String base = foliaServer ? "Folia" : "Paper";
        if (serverName != null && !serverName.isBlank()
                && !serverName.equalsIgnoreCase(base)
                && !serverName.equalsIgnoreCase("CraftBukkit")) {
            return serverName + " (" + base + ")";
        }
        return base;
    }

    private boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private boolean checkAuthMe() {
        org.bukkit.plugin.Plugin authme = getServer().getPluginManager().getPlugin("AuthMe");
        if (authme == null) authme = getServer().getPluginManager().getPlugin("AuthMeReloaded");
        if (authme == null || !authme.isEnabled()) return false;
        try {
            Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            return true;
        } catch (ClassNotFoundException e) {
            getLogger().severe("AuthMe API v3 not found. Incompatible AuthMe version.");
            return false;
        }
    }

    public AuthMe authMeListener() {
        return authMeListener;
    }
    public com.authmebia.notifications.ToastListener toastListener() {
        return toastListener;
    }
}
