package com.authmebia.cfg;

import com.authmebia.AuthMeBia;
import org.bukkit.configuration.file.FileConfiguration;

public final class CfgSnapshot {

    private CfgSnapshot() {}

    public static Cfg of(AuthMeBia plugin, FileConfiguration config) {
        return new Cfg(plugin, config);
    }
}
