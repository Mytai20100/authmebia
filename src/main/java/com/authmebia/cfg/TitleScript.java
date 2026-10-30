package com.authmebia.cfg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One title sequence and the post-join events that may trigger it.
 *
 * <p>{@code titles_scripts} is a string or list containing
 * {@code <titles:name>} references, in display order. {@code titles_trigger}
 * is an optional event name or list of event names. With no trigger list the
 * caller decides the default event for that section.</p>
 */
public final class TitleScript {

    private static final Pattern TITLE_TAG =
            Pattern.compile("<titles:([^>\\s]+)>", Pattern.CASE_INSENSITIVE);
    private static final Set<String> VALID_TRIGGERS = Set.of(
            "is_registed", "is_registered", "is_login",
            "is_logout", "is_rejoin", "is_firstjoin", "is_recover");

    private final List<String> entries;
    private final Set<String> triggers;

    private TitleScript(List<String> entries, Set<String> triggers) {
        this.entries = List.copyOf(entries);
        this.triggers = Collections.unmodifiableSet(new LinkedHashSet<>(triggers));
    }

    public static TitleScript parse(Object scripts, Object triggerConfig) {
        List<String> entries = parseEntries(scripts);
        Set<String> triggers = parseTriggers(triggerConfig, scripts);
        if (entries.isEmpty() || triggers == null) {
            return new TitleScript(List.of(), Set.of());
        }
        return new TitleScript(entries, triggers);
    }

    private static List<String> parseEntries(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof String s) {
            collectTags(s, result);
        } else if (raw instanceof Collection<?> list) {
            for (Object value : list) {
                if (value instanceof String s) collectTags(s, result);
            }
        }
        return result;
    }

    private static void collectTags(String raw, List<String> out) {
        if (raw == null || raw.isBlank()) return;
        Matcher matcher = TITLE_TAG.matcher(raw);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) out.add(name);
        }
    }

    private static Set<String> parseTriggers(Object raw, Object scripts) {
        // A map-shaped titles_scripts block is not supported on purpose: it is
        // too easy to confuse a title name with an event name. Refuse it
        // instead of silently playing the wrong sequence.
        if (scripts instanceof java.util.Map<?, ?>) return null;

        Set<String> result = new LinkedHashSet<>();
        if (raw instanceof String s) {
            addTrigger(result, s);
        } else if (raw instanceof Collection<?> list) {
            for (Object value : list) {
                if (value instanceof String s) addTrigger(result, s);
            }
        }
        return result;
    }

    private static void addTrigger(Set<String> out, String raw) {
        if (raw == null) return;
        String trigger = normalizeTrigger(raw);
        if (!trigger.isEmpty() && VALID_TRIGGERS.contains(trigger)) {
            out.add(trigger);
        }
    }

    public static String normalizeTrigger(String raw) {
        if (raw == null) return "";
        String trigger = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (trigger.equals("is_registered")) trigger = "is_registed";
        return trigger;
    }

    public boolean matches(String trigger) {
        if (entries.isEmpty()) return false;
        if (triggers.isEmpty()) return true;
        String normalized = normalizeTrigger(trigger);
        return !normalized.isEmpty() && triggers.contains(normalized);
    }

    public boolean matchesAny(Collection<String> candidates) {
        if (entries.isEmpty()) return false;
        if (triggers.isEmpty()) return true;
        for (String candidate : candidates) {
            if (matches(candidate)) return true;
        }
        return false;
    }

    public List<String> entries() {
        return entries;
    }

    public boolean hasTriggerFilter() {
        return !triggers.isEmpty();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
