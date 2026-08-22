package com.authmebia.cfg;

import com.authmebia.AuthMeBia;
import com.authmebia.dialog.Mode;
import com.authmebia.dialog.util.IconSpec;

public final class BedrockCfg extends Cfg {

    public enum AutoLoginMode {
        LINK,
        GEYSER;
        public static AutoLoginMode parse(String raw) {
            if (raw == null) return LINK;
            return switch (raw.trim().toLowerCase()) {
                case "geyser" -> GEYSER;
                default -> LINK;
            };
        }
    }

    public BedrockCfg(AuthMeBia plugin, Cfg base) {
        super(plugin, base.config);
    }
    @Override
    public Mode authMode() {
        return bedrockAuthMode();
    }
    @Override
    public int mainButtonWidth() {
        return bedrockDialogOverrideEnabled() ? bedrockButtonWidth() : super.mainButtonWidth();
    }
    @Override
    public int inputWidth() {
        return bedrockDialogOverrideEnabled() ? bedrockInputWidth() : super.inputWidth();
    }
    @Override
    public IconSpec icon(String name) {
        return null;
    }
}
