package com.authmebia.cfg;

/**
 * Controls which identity check auto.bedrock_autologin trusts. See the
 * documentation on auto.bedrock_mode in config.yml for the full explanation
 * of the security tradeoff between these two modes.
 */
public enum BedrockAutoLoginMode {
    /** Only trust a Floodgate player with a verified linked Java account. Safe, default. */
    LINK,
    /** Trust any Floodgate/Geyser player with no linked-account check. Unsafe unless Geyser's own online auth-type is enabled. */
    GEYSER;

    public static BedrockAutoLoginMode parse(String raw) {
        if (raw == null) return LINK;
        return switch (raw.trim().toLowerCase()) {
            case "geyser" -> GEYSER;
            default -> LINK;
        };
    }
}
