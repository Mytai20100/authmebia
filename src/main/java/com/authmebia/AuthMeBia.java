package com.authmebia;

import com.authmebia.platform.Bukkit;
import com.authmebia.platform.Folia;
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
    private BiaList biaList;
    private boolean foliaServer;
    private OkHttpClient httpClient;
    private AuthMe authMeListener;

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
        biaList = new BiaList(this);

        // Single shared HTTP client for all outbound requests (Discord webhooks,
        // avatar fetches). OkHttp manages its own thread pool and connection pool
        // internally; creating multiple instances wastes both. Explicit timeouts
        // prevent hung requests from blocking async threads indefinitely.
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        if (!new java.io.File(getDataFolder(), "welcome.json").exists()) {
            saveResource("welcome.json", false);
        }

        // Sample background image referenced by the default welcome.json.
        // Replace this file with your own art at any size that matches your
        // welcome_size; this one is just a small placeholder.
        if (!new java.io.File(getDataFolder(), "background.png").exists()) {
            saveResource("background.png", false);
        }

        // Customization guide for welcome.json, copied into the plugin's
        // own data folder so it's easy to find right next to the file it
        // documents.
        if (!new java.io.File(getDataFolder(), "doc/README.md").exists()) {
            saveResource("doc/README.md", false);
        }

        saveDefaultLang("lang/en.yml");
        saveDefaultLang("lang/vi.yml");

        authMeListener = new AuthMe(this);
        getServer().getPluginManager().registerEvents(authMeListener, this);

        LifecycleEventManager<org.bukkit.plugin.Plugin> manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            new Cmd(this).register(commands);
        });

        getLogger().info("AuthMeBia enabled on " + platformName() + ".");
    }

    @Override
    public void onDisable() {
        // Reset the ViaVersion lookup cache so a fresh detect happens if the
        // plugin is re-enabled in the same JVM session (e.g. via PlugMan).
        ProtocolGate.reset();

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

    public OkHttpClient httpClient() {
        return httpClient;
    }

    public boolean isFolia() {
        return foliaServer;
    }

    public String platformName() {
        if (foliaServer) return "Folia";
        try {
            Class.forName("io.papermc.paper.configuration.Configuration");
            return "Paper";
        } catch (ClassNotFoundException ignored) {}
        return "Bukkit";
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

    /**
     * Returns the registered AuthMe listener instance. Needed by /bia reload
     * to refresh the cached AuthMe config values (blind effect, captcha state).
     */
    public AuthMe authMeListener() {
        return authMeListener;
    }
}
