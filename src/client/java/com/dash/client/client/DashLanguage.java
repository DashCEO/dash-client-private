package com.dash.client.client;

public enum DashLanguage {
    UKRAINIAN("uk_ua", "Українська"),
    RUSSIAN("ru_ru", "Русский"),
    ENGLISH("en_us", "English");

    private final String code;
    private final String displayName;

    DashLanguage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    String code() {
        return code;
    }

    String displayName() {
        return displayName;
    }

    static DashLanguage fromCode(String code) {
        for (DashLanguage language : values()) {
            if (language.code.equals(code)) return language;
        }
        return UKRAINIAN;
    }

    public static String text(String ukrainian, String russian, String english) {
        return switch (HudSettings.language()) {
            case UKRAINIAN -> ukrainian;
            case RUSSIAN -> russian;
            case ENGLISH -> english;
        };
    }
}
