package com.authmebia.cmd.help;

import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Help {

    private Help() {}

    public static void execute(org.bukkit.command.CommandSender sender) {
        boolean op = sender.isOp();
        StringBuilder sb = new StringBuilder();
        sb.append(line("/bia reload | /bia rl", "Reload config (OP only)"));
        sb.append(line("/bia info", "Plugin info"));
        sb.append(line("/bia add <player>", "Add a player to the dialog bypass list"));
        sb.append(line("/bia rm <player>", "Remove a player from the dialog bypass list"));
        sb.append(line("/bia recover <player>", "Force a password reset on the player's next login"));
        if (op) {
            sb.append(line("/bia debug <feature> <true|false|show>", "Test a feature (OP only)"));
            sb.append(note("  Features: captcha, email, register, login, recover, rule"));
            sb.append(note("  Use 'show' with captcha/email to preview the dialog GUI"));
            sb.append(line("/bia notifier <toast> <player> show [seconds]", "Preview a custom toast notification (OP only)"));
            sb.append(line("/bia screen <id> [player]", "Show a custom screen to a player (OP only)"));
        }
        sb.append(line("/bia help", "Show this help", false));

        sender.sendMessage(MiniMessage.miniMessage().deserialize(sb.toString()));
    }

    private static String line(String command, String description) {
        return line(command, description, true);
    }

    private static String line(String command, String description, boolean newline) {
        return "<blue>" + escape(command) + "</blue>  <gray>- " + escape(description) + "</gray>"
                + (newline ? "\n" : "");
    }

    private static String note(String text) {
        return "<gray>" + escape(text) + "</gray>\n";
    }

    private static String escape(String s) {
        return s.replace("<", "\\<");
    }
}
