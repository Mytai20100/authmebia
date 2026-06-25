package com.authmebia;

/**
 * Authentication input mode for the register/login dialogs, selected by
 * {@code auth_mode.mode} in config.yml.
 *
 * <ul>
 *   <li>{@link #PASSWORD} - the original free-text password input (unchanged).</li>
 *   <li>{@link #PIN} - a numeric PIN entered through a 3-column numpad grid
 *       built from {@code DialogType.multiAction(..., columns=3)}.</li>
 *   <li>{@link #SLIDER} - a numeric code entered with per-digit +/- buttons
 *       laid out in a grid. (A native {@code number_range} slider input also
 *       exists in the Dialog packet, but a single continuous slider does not
 *       fit the per-digit code design requested here, so the +/- grid is used.)</li>
 * </ul>
 */
public enum AuthMode {
    PASSWORD,
    PIN,
    SLIDER;

    /**
     * Parses the config string, defaulting to {@link #PASSWORD} for any
     * unknown / blank value so a typo never blocks logins entirely.
     */
    public static AuthMode parse(String raw) {
        if (raw == null) return PASSWORD;
        return switch (raw.trim().toLowerCase()) {
            case "pin" -> PIN;
            case "slider" -> SLIDER;
            default -> PASSWORD;
        };
    }
}
