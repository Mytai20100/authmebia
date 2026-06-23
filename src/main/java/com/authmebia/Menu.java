package com.authmebia;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("UnstableApiUsage")
public class Menu {

    // Maximum time to wait for a single dialog submission. If the client
    // disconnects without submitting (crash, force-quit, network drop), the
    // callback is never invoked and the latch would block forever without this
    // cap. 30 seconds covers any reasonable human interaction time while
    // releasing the async connection thread promptly after an unclean disconnect.
    // The outer while(conn.isConnected()) loop re-shows the dialog if the player
    // is still connected and the latch expired without a submission.
    private static final long DIALOG_AWAIT_SECONDS = 30L;

    private Menu() {}

    public static boolean showCaptchaBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang, Captcha captcha) {
        AtomicReference<String> code = new AtomicReference<>(captcha.generate(cfg.captchaLength()));
        AtomicReference<String> error = new AtomicReference<>(null);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean verified = new AtomicBoolean(false);

            String inputLabel = error.get() != null
                    ? cfg.captchaInputLabel() + "  [" + error.get() + "]"
                    : cfg.captchaInputLabel();

            DialogActionCallback submit = (DialogResponseView r, Audience a) -> {
                try {
                    String typed = r.getText("code");
                    if (captcha.matches(typed, code.get())) {
                        verified.set(true);
                        error.set(null);
                    } else {
                        error.set(lang.errorCaptchaIncorrect());
                        code.set(captcha.generate(cfg.captchaLength()));
                    }
                } finally {
                    latch.countDown();
                }
            };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.captchaTitle(), cfg.captchaContent(code.get()), false,
                            List.of(
                                    DialogInput.text("code", Component.text(inputLabel)).maxLength(16).width(cfg.inputWidth()).build()
                            )))
                    .type(DialogType.multiAction(
                            List.of(btn(cfg, cfg.captchaSubmitButton(), submit)),
                            null, 1))
            ));

            await(latch);
            if (verified.get()) return true;
        }
        return false;
    }

    public static String showRegisterBlocking(PlayerConfigurationConnection conn, Cfg cfg, Lang lang) {
        AtomicReference<String> error = new AtomicReference<>(null);
        String playerName = conn.getProfile().getName();

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>(null);
            AtomicBoolean disconnect = new AtomicBoolean(false);

            String passLabel = error.get() != null ? "Password  [" + error.get() + "]" : "Password";

            DialogActionCallback submit = (DialogResponseView r, Audience a) -> {
                try {
                    String pass = r.getText("password");
                    String confirm = r.getText("confirm");
                    if (pass == null || pass.isBlank()) {
                        error.set(lang.errorPasswordEmpty());
                        return;
                    }
                    if (!pass.equals(confirm)) {
                        error.set(lang.errorPasswordsMismatch());
                        return;
                    }
                    error.set(null);
                    result.set(pass);
                } finally {
                    latch.countDown();
                }
            };

            DialogActionCallback logoutCb = (r, a) -> {
                disconnect.set(true);
                latch.countDown();
            };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.registerTitle(playerName), cfg.registerContent(playerName), false,
                            List.of(
                                    DialogInput.text("password", Component.text(passLabel)).maxLength(64).width(cfg.inputWidth()).build(),
                                    DialogInput.text("confirm", Component.text("Confirm Password")).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.submitRegisterButton(), submit)),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));

            await(latch);
            if (disconnect.get()) return null;
            if (result.get() != null) return result.get();
        }
        return null;
    }

    public static boolean showLoginBlocking(PlayerConfigurationConnection conn, String name, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard, String ip) {
        AtomicReference<String> error = new AtomicReference<>(null);
        AtomicInteger wrongTries = new AtomicInteger(0);

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean(false);
            AtomicBoolean disconnect = new AtomicBoolean(false);
            AtomicBoolean kicked = new AtomicBoolean(false);

            String passLabel = error.get() != null ? "Password  [" + error.get() + "]" : "Password";

            DialogActionCallback loginCb = (DialogResponseView r, Audience a) -> {
                try {
                    String pass = r.getText("password");
                    if (pass == null || pass.isBlank()) {
                        error.set(lang.errorPasswordEmpty());
                        return;
                    }
                    if (!authMe.checkPassword(name, pass)) {
                        error.set(lang.errorWrongPassword());
                        ipGuard.recordFailure(ip, AuthMeBia.get().cfg(), AuthMeBia.get().lang());

                        if (AuthMeBia.get().cfg().loginAttemptsEnabled() && wrongTries.incrementAndGet() >= AuthMeBia.get().cfg().loginMaxTries()) {
                            kicked.set(true);
                        }
                        return;
                    }
                    success.set(true);
                    ipGuard.clearFailures(ip);
                } finally {
                    latch.countDown();
                }
            };

            DialogActionCallback logoutCb = (r, a) -> {
                disconnect.set(true);
                latch.countDown();
            };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.loginTitle(name), cfg.loginContent(name), false,
                            List.of(
                                    DialogInput.text("password", Component.text(passLabel)).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, name,
                            List.of(btn(cfg, cfg.submitLoginButton(), loginCb)),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));

            await(latch);
            if (kicked.get()) return false;
            if (disconnect.get()) return false;
            if (success.get()) return true;
        }
        return false;
    }

    public static boolean showRuleBlocking(PlayerConfigurationConnection conn, Cfg cfg) {
        String playerName = conn.getProfile().getName();

        while (conn.isConnected()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean agreed = new AtomicBoolean(false);

            DialogActionCallback agreeCb = (DialogResponseView r, Audience a) -> {
                try {
                    Boolean checked = r.getBoolean("agree");
                    if (Boolean.TRUE.equals(checked)) {
                        agreed.set(true);
                    }
                } finally {
                    latch.countDown();
                }
            };

            conn.getAudience().showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.ruleTitle(playerName), cfg.ruleContent(playerName), false,
                            List.of(
                                    DialogInput.bool("agree", cfg.ruleCheckboxLabel())
                                            .initial(false)
                                            .build()
                            )))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.ruleAgreeButton(), agreeCb)),
                            null))
            ));

            await(latch);
            if (agreed.get()) return true;
        }
        return false;
    }

    public static void showRegisterIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe) {
        showRegisterIngame(player, cfg, lang, authMe, new AtomicReference<>(null));
    }

    private static void showRegisterIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, AtomicReference<String> lastError) {
        try {
            String playerName = player.getName();
            String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : null;

            DialogActionCallback logoutCb = (r, a) -> {
                if (a instanceof Player p) p.kick(lang.disconnectLogout(p.getName(), ip));
            };

            String passLabel = lastError.get() != null ? "Password  [" + lastError.get() + "]" : "Password";

            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.registerTitle(playerName), cfg.registerContent(playerName), false,
                            List.of(
                                    DialogInput.text("password", Component.text(passLabel)).maxLength(64).width(cfg.inputWidth()).build(),
                                    DialogInput.text("confirm", Component.text("Confirm Password")).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.submitRegisterButton(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String pass = r.getText("password");
                                String confirm = r.getText("confirm");
                                if (pass == null || pass.isBlank()) {
                                    lastError.set(lang.errorPasswordEmpty());
                                    showRegisterIngame(p, cfg, lang, authMe, lastError);
                                    return;
                                }
                                if (!pass.equals(confirm)) {
                                    lastError.set(lang.errorPasswordsMismatch());
                                    showRegisterIngame(p, cfg, lang, authMe, lastError);
                                    return;
                                }
                                authMe.registerAndLogin(p, pass);
                            })),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }

    public static void showLoginIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard) {
        showLoginIngame(player, cfg, lang, authMe, ipGuard, new AtomicInteger(0));
    }

    private static void showLoginIngame(Player player, Cfg cfg, Lang lang, AuthMe authMe, IpGuard ipGuard, AtomicInteger wrongTries) {
        try {
            String playerName = player.getName();
            String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : null;

            DialogActionCallback logoutCb = (r, a) -> {
                if (a instanceof Player p) p.kick(lang.disconnectLogout(p.getName(), ip));
            };

            player.showDialog(Dialog.create(d -> d
                    .empty()
                    .base(buildBase(cfg.loginTitle(playerName), cfg.loginContent(playerName), false,
                            List.of(
                                    DialogInput.text("password", Component.text("Password")).maxLength(64).width(cfg.inputWidth()).build()
                            )))
                    .type(buildType(cfg, playerName,
                            List.of(btn(cfg, cfg.submitLoginButton(), (r, a) -> {
                                if (!(a instanceof Player p)) return;
                                String pass = r.getText("password");
                                if (pass == null || pass.isBlank() || !authMe.checkPassword(p.getName(), pass)) {
                                    ipGuard.recordFailure(ip, cfg, lang);
                                    if (cfg.loginAttemptsEnabled() && wrongTries.incrementAndGet() >= cfg.loginMaxTries()) {
                                        p.kick(lang.disconnectTooManyAttempts(p.getName(), ip));
                                        return;
                                    }
                                    p.sendMessage(lang.messageWrongPassword());
                                    showLoginIngame(p, cfg, lang, authMe, ipGuard, wrongTries);
                                    return;
                                }
                                ipGuard.clearFailures(ip);
                                authMe.login(p);
                            })),
                            btn(cfg, cfg.logoutButton(), logoutCb)))
            ));
        } catch (NoClassDefFoundError ignored) {}
    }

    private static DialogBase buildBase(Component title, Component content, boolean escape, List<DialogInput> inputs) {
        DialogBase.Builder builder = DialogBase.builder(title)
                .canCloseWithEscape(escape)
                .inputs(inputs);

        if (content != null) {
            List<DialogBody> bodies = new ArrayList<>();
            bodies.add(DialogBody.plainMessage(content));
            builder.body(bodies);
        }

        return builder.build();
    }

    private static ActionButton btn(Cfg cfg, Component label, DialogActionCallback cb) {
        return ActionButton.builder(label)
                .width(cfg.mainButtonWidth())
                .action(DialogAction.customClick(cb, ClickCallback.Options.builder().build()))
                .build();
    }

    private static List<ActionButton> linkActionButtons(Cfg cfg, String playerName) {
        List<ActionButton> result = new ArrayList<>();
        if (!cfg.linksEnabled()) return result;

        for (LinkButton link : cfg.linkButtons(playerName)) {
            if (!link.enabled()) continue;
            String value = link.value();
            if (value == null || value.isBlank()) continue;

            ClickEvent clickEvent = link.action() == LinkButton.Action.COPY
                    ? ClickEvent.copyToClipboard(value)
                    : ClickEvent.openUrl(value);

            result.add(ActionButton.builder(link.label())
                    .width(link.width())
                    .action(DialogAction.staticAction(clickEvent))
                    .build());
        }
        return result;
    }

    private static DialogType buildType(Cfg cfg, String playerName, List<ActionButton> submitButtons, ActionButton logoutButton) {
        List<ActionButton> links = linkActionButtons(cfg, playerName);
        LinkButton.Layout layout = cfg.linksLayout();

        if (links.isEmpty()) {
            List<ActionButton> all = new ArrayList<>(submitButtons);
            if (logoutButton != null) all.add(logoutButton);
            return DialogType.multiAction(all, null, 1);
        }

        if (layout == LinkButton.Layout.SEPARATED) {
            List<ActionButton> all = new ArrayList<>(submitButtons.size() + links.size());
            all.addAll(submitButtons);
            all.addAll(links);
            return DialogType.multiAction(all, logoutButton, 1);
        }

        List<ActionButton> all = new ArrayList<>(submitButtons.size() + 1 + links.size());
        all.addAll(submitButtons);
        if (logoutButton != null) all.add(logoutButton);
        all.addAll(links);
        return DialogType.multiAction(all, null, links.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(DIALOG_AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
