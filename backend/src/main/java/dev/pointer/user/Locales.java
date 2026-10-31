package dev.pointer.user;

import java.util.Set;

public final class Locales {

    public static final String PATTERN = "en|ru";
    public static final String DEFAULT = "en";

    private static final Set<String> SUPPORTED = Set.of("en", "ru");

    private Locales() {}

    public static String supportedOrDefault(String locale) {
        return locale != null && SUPPORTED.contains(locale) ? locale : DEFAULT;
    }
}
