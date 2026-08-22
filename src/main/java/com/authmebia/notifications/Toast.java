package com.authmebia.notifications;

import net.kyori.adventure.text.Component;

public record Toast(
        String name,
        Check check,
        Component title,
        Component content,
        String icon,
        String sound,
        int delaySeconds,
        Frame frame
) {
    public enum Check {
        FIRST_REGISTER,
        FIRST_LOGIN,
        FIRST_MESSAGE,
        FIRST_ADVANCEMENT;

        public static Check parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase()) {
                case "first_register" -> FIRST_REGISTER;
                case "first_login" -> FIRST_LOGIN;
                case "first_message" -> FIRST_MESSAGE;
                case "first_advancement" -> FIRST_ADVANCEMENT;
                default -> null;
            };
        }
    }
    public enum Frame {
        TASK,
        GOAL,
        CHALLENGE;

        public static Frame parse(String raw) {
            if (raw == null) return TASK;
            return switch (raw.trim().toLowerCase()) {
                case "goal" -> GOAL;
                case "challenge" -> CHALLENGE;
                case "task" -> TASK;
                default -> TASK;
            };
        }

        public String jsonValue() {
            return switch (this) {
                case TASK -> "task";
                case GOAL -> "goal";
                case CHALLENGE -> "challenge";
            };
        }
    }
}
