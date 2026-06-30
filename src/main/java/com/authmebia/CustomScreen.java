package com.authmebia;

import net.kyori.adventure.text.Component;

import java.util.List;

public record CustomScreen(
        String id,
        boolean enabled,
        Component title,
        Component content,
        boolean allowClose,
        int buttonWidth,
        List<Button> buttons,
        Trigger trigger,
        String soundOnShow
) {
    /**
     * When this screen is automatically shown.
     * COMMAND  – only via /bia screen (default).
     * POSTJOIN – shown after the player authenticates and spawns in-game.
     * PREJOIN  – shown during the pre-spawn configuration phase (blocking), after auth.
     */
    public enum Trigger {
        COMMAND, POSTJOIN, PREJOIN;

        public static Trigger parse(String raw) {
            if (raw == null) return COMMAND;
            return switch (raw.trim().toLowerCase()) {
                case "postjoin" -> POSTJOIN;
                case "prejoin"  -> PREJOIN;
                default         -> COMMAND;
            };
        }
    }

    public record Button(Component label, Action action, String value, int width, String sound) {
        public enum Action {
            CLOSE, OPEN_URL, COPY,
            /** Dispatch a command as the player. */
            COMMAND,
            /** Dispatch a command as the console. */
            CONSOLE;

            public static Action parse(String raw) {
                if (raw == null) return CLOSE;
                return switch (raw.trim().toLowerCase()) {
                    case "open_url" -> OPEN_URL;
                    case "copy"     -> COPY;
                    case "command"  -> COMMAND;
                    case "console"  -> CONSOLE;
                    default         -> CLOSE;
                };
            }
        }
    }
}
