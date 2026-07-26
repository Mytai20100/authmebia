package com.authmebia.listeners.placeholderapi;

import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/**
 * Reflection bridge to PlaceholderAPI, following the same soft-dependency
 * pattern as listeners.version.Version (ViaVersion) and
 * listeners.floodgate.Floodgate: no compile-time dependency, everything
 * resolved via reflection, and every failure degrades to "return the text
 * unchanged" instead of throwing.
 *
 * Used to let config.yml text fields (titles, content, button labels, chat
 * messages) use %placeholder% syntax from any installed PlaceholderAPI
 * expansion (LuckPerms prefixes, Vault ranks, etc), in addition to the
 * plugin's own {player} substitution and MiniMessage formatting.
 */
public final class PlaceholderApi {

    private static volatile boolean checked = false;
    private static volatile Method setPlaceholdersMethod = null;

    private PlaceholderApi() {}

    public static void reset() {
        synchronized (PlaceholderApi.class) {
            checked = false;
            setPlaceholdersMethod = null;
        }
    }

    public static boolean isAvailable() {
        ensureLookup();
        return setPlaceholdersMethod != null;
    }

    /**
     * Replaces %placeholder% patterns in text using PlaceholderAPI, if it is
     * installed. Returns the text unchanged if PlaceholderAPI is missing,
     * player is null, text is null/blank, or resolution fails for any
     * reason -- this never throws and never blocks on a missing plugin.
     */
    public static String apply(OfflinePlayer player, String text) {
        if (text == null || text.isEmpty() || player == null) return text;
        ensureLookup();
        if (setPlaceholdersMethod == null) return text;
        try {
            Object result = setPlaceholdersMethod.invoke(null, player, text);
            return result instanceof String s ? s : text;
        } catch (Exception e) {
            return text;
        }
    }

    private static void ensureLookup() {
        if (checked) return;
        synchronized (PlaceholderApi.class) {
            if (checked) return;
            checked = true;
            try {
                Class<?> apiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                setPlaceholdersMethod = apiClass.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (Throwable t) {
                setPlaceholdersMethod = null;
            }
        }
    }
}
