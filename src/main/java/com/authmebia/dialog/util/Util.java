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

    /**
     * Generates a random internal placeholder password, used only for
     * accounts created via auto.premium_autologin / auto.bedrock_autologin
     * (AuthMe requires some password on file even though the account is
     * about to log in without one being typed). The player is always shown
     * the mandatory password-set dialog (dialog/premium/Premium.java)
     * immediately after, which overwrites this value; it is never displayed
     * or communicated to the player.
     */
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

    public static void runOnMain(Player player, Runnable task) {
        if (AuthMeBia.get().isFolia()) {
            player.getScheduler().run(AuthMeBia.get(), t -> task.run(), null);
        } else {
            AuthMeBia.get().getServer().getScheduler().runTask(AuthMeBia.get(), task);
        }
    }
}
