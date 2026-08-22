package com.authmebia.listeners.placeholderapi;

import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

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
