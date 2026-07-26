package com.authmebia.cfg;

import com.authmebia.AuthMeBia;
import com.authmebia.dialog.Mode;

/**
 * A Cfg view used for Bedrock (Geyser/Floodgate) connections. Overrides only
 * authMode(), mainButtonWidth(), and inputWidth() with the dialog.bedrock.*
 * settings when dialog.bedrock.enabled is true in config.yml; every other
 * accessor is inherited unchanged from Cfg, so all dialog files (Login,
 * Register, Recover, Premium, Captcha, etc.) automatically render with
 * Bedrock-appropriate sizing and input mode without any of them needing to
 * know about Bedrock at all -- AuthMe.java just passes this instead of the
 * plain Cfg for connections detected as Bedrock.
 *
 * Shares the already-loaded FileConfiguration of the real Cfg instance (see
 * Cfg's package-visible constructor) so building one per connection has no
 * side effects and does not re-read config.yml from disk.
 */
public final class BedrockCfg extends Cfg {

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
}
