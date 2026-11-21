package com.authmebia.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Renders MiniMessage input as Discord markdown.
 *
 * <p>Discord only understands markdown, so the Adventure tree produced by
 * MiniMessage is walked and every decoration is turned into its markdown
 * counterpart (bold, italic, underline, strikethrough, spoiler) plus clickable
 * links. Colors have no Discord equivalent and are intentionally dropped.</p>
 */
public final class DiscordMarkdown {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private DiscordMarkdown() {}

    public static String render(String input) {
        if (input == null || input.isEmpty()) return "";
        Component component;
        try {
            component = MM.deserialize(input);
        } catch (RuntimeException e) {
            // Malformed MiniMessage should never drop the whole notification.
            return input;
        }
        StringBuilder out = new StringBuilder();
        append(out, component, Style.empty());
        return out.toString();
    }

    private static void append(StringBuilder out, Component component, Style parentStyle) {
        Style style = parentStyle.merge(component.style());
        if (component instanceof TextComponent text) {
            out.append(decorate(text.content(), style));
        } else if (component.children().isEmpty()) {
            out.append(decorate(PlainTextComponentSerializer.plainText().serialize(component), style));
        }
        for (Component child : component.children()) {
            append(out, child, style);
        }
    }

    private static String decorate(String text, Style style) {
        if (text == null || text.isEmpty()) return "";
        String result = text;
        if (style.hasDecoration(TextDecoration.OBFUSCATED)) result = "||" + result + "||";
        if (style.hasDecoration(TextDecoration.STRIKETHROUGH)) result = "~~" + result + "~~";
        if (style.hasDecoration(TextDecoration.UNDERLINED)) result = "__" + result + "__";
        if (style.hasDecoration(TextDecoration.ITALIC)) result = "*" + result + "*";
        if (style.hasDecoration(TextDecoration.BOLD)) result = "**" + result + "**";
        ClickEvent click = style.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.OPEN_URL && click.value() != null) {
            result = "[" + result + "](" + click.value() + ")";
        }
        return result;
    }
}
