package com.authmebia.dialog;

public enum Mode {
    PASSWORD,
    PIN,
    SLIDER;

    public static Mode parse(String raw) {
        if (raw == null) return PASSWORD;
        return switch (raw.trim().toLowerCase()) {
            case "pin" -> PIN;
            case "slider" -> SLIDER;
            default -> PASSWORD;
        };
    }
}
