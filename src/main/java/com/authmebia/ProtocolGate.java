package com.authmebia;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

public final class ProtocolGate {

    private static volatile boolean checkedForViaVersion = false;
    private static volatile Object viaApi = null;
    private static volatile Method getPlayerVersionByUuid = null;
    private static volatile Method getPlayerVersionByPlayer = null;

    private ProtocolGate() {}

    public static void reset() {
        synchronized (ProtocolGate.class) {
            checkedForViaVersion = false;
            viaApi = null;
            getPlayerVersionByUuid = null;
            getPlayerVersionByPlayer = null;
        }
    }

    public static int getProtocolVersion(UUID playerUuid, Player playerOrNull) {
        ensureViaVersionLookup();
        if (viaApi == null) {
            debugLog("ViaVersion API not available (not installed, or lookup failed)");
            return -1;
        }

        try {
            if (playerOrNull != null && getPlayerVersionByPlayer != null) {
                Object result = getPlayerVersionByPlayer.invoke(viaApi, playerOrNull);
                if (result instanceof Integer i) {
                    debugLog("getPlayerVersion(Player) returned " + i + " for " + playerOrNull.getName());
                    return i;
                }
            }
            if (playerUuid != null && getPlayerVersionByUuid != null) {
                Object result = getPlayerVersionByUuid.invoke(viaApi, playerUuid);
                if (result instanceof Integer i) {
                    debugLog("getPlayerVersion(UUID) returned " + i + " for " + playerUuid);
                    return i;
                }
            }
        } catch (Exception e) {
            debugLog("getPlayerVersion invocation threw: " + e);
        }
        debugLog("Could not resolve protocol version for uuid=" + playerUuid
                + " (byPlayer=" + getPlayerVersionByPlayer + ", byUuid=" + getPlayerVersionByUuid + ")");
        return -1;
    }

    private static void debugLog(String message) {
        if (Boolean.getBoolean("authmebia.debugProtocolGate")) {
            System.out.println("[AuthMeBia/ProtocolGate] " + message);
        }
    }

    public static boolean supportsDialogs(UUID playerUuid, Player playerOrNull, int minProtocolVersion) {
        try {
            if (org.bukkit.Bukkit.getPluginManager().getPlugin("ViaVersion") == null) {
                return true;
            }
            ensureViaVersionLookup();
            if (viaApi == null) {
                return true;
            }
            int version = getProtocolVersion(playerUuid, playerOrNull);
            if (version < 0) return true;
            return version >= minProtocolVersion;
        } catch (Exception e) {
            return true;
        }
    }

    private static void ensureViaVersionLookup() {
        if (checkedForViaVersion) return;
        synchronized (ProtocolGate.class) {
            if (checkedForViaVersion) return;
            checkedForViaVersion = true;
            try {
                Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
                Method getApi = viaClass.getMethod("getAPI");
                Object api = getApi.invoke(null);
                viaApi = api;

                try {
                    getPlayerVersionByUuid = api.getClass().getMethod("getPlayerVersion", UUID.class);
                } catch (NoSuchMethodException ignored) {}
                try {
                    getPlayerVersionByPlayer = api.getClass().getMethod("getPlayerVersion", Object.class);
                } catch (Exception ignored) {}

                if (getPlayerVersionByUuid == null && getPlayerVersionByPlayer == null) {
                    viaApi = null;
                }
            } catch (Throwable t) {
                viaApi = null;
                getPlayerVersionByUuid = null;
                getPlayerVersionByPlayer = null;
            }
        }
    }
}
