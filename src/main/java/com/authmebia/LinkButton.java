package com.authmebia;

import net.kyori.adventure.text.Component;

public final class LinkButton {

    public enum Action {
        OPEN_URL,
        COPY
    }

    public enum Layout {
        GROUPED,
        SEPARATED;

        public static Layout parse(String raw, Layout fallback) {
            if (raw == null) return fallback;
            try {
                return Layout.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    private final boolean enabled;
    private final Component label;
    private final Action action;
    private final String value;
    private final int width;

    public LinkButton(boolean enabled, Component label, Action action, String value, int width) {
        this.enabled = enabled;
        this.label = label;
        this.action = action;
        this.value = value;
        this.width = width;
    }

    public boolean enabled() {
        return enabled;
    }

    public Component label() {
        return label;
    }

    public Action action() {
        return action;
    }

    public String value() {
        return value;
    }

    public int width() {
        return width;
    }
}
