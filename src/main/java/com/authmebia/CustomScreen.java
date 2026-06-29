package com.authmebia;

import net.kyori.adventure.text.Component;

import java.util.List;

public record CustomScreen(
        String id,
        Component title,
        Component content,
        boolean allowClose,
        int buttonWidth,
        List<Button> buttons
) {
    public record Button(Component label, Action action, String value, int width) {
        public enum Action { CLOSE, OPEN_URL, COPY }
    }
}
