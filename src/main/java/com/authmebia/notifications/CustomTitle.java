package com.authmebia.notifications;

import net.kyori.adventure.text.Component;

/**
 * One named entry under {@code custom_titles:}. A null component means the
 * corresponding line was not configured.
 */
public record CustomTitle(
        String name,
        Component title,
        Component subtitle,
        Component actionbar,
        long delayTicks
) {
    public boolean hasTitle() {
        return title != null || subtitle != null;
    }

    public boolean hasActionbar() {
        return actionbar != null;
    }

    public boolean isEmpty() {
        return !hasTitle() && !hasActionbar();
    }
}
