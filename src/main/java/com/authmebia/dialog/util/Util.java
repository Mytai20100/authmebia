package com.authmebia.dialog.util;

import com.authmebia.AuthMeBia;
import org.bukkit.entity.Player;

import java.security.SecureRandom;

public final class Util {

    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    private Util() {}

    public static boolean isValidEmail(String email) {
        return email != null && email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }

    public static String genNumericCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(CODE_RANDOM.nextInt(10));
        return sb.toString();
    }

    public static String genInternalPlaceholderPassword() {
        StringBuilder sb = new StringBuilder(32);
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        for (int i = 0; i < 32; i++) sb.append(alphabet.charAt(CODE_RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }

    public static String ipOf(Player player) {
        return player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress() : null;
    }

    public static boolean runOnMain(Player player, Runnable task) {
        AuthMeBia plugin = AuthMeBia.get();
        if (plugin == null || player == null || task == null) return false;
        if (plugin.isFolia()) {
            player.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
        return true;
    }

    /**
     * Runs a server command dispatch on the global region thread. Player
     * commands must use {@link #runOnMain(Player, Runnable)} instead because
     * Folia requires them on the owning player's region.
     */
    public static boolean runGlobal(Runnable task) {
        AuthMeBia plugin = AuthMeBia.get();
        if (plugin == null || task == null) return false;
        if (plugin.isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
        return true;
    }
}
