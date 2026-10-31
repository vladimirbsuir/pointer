package dev.pointer.user;

import java.util.Locale;

public final class Emails {

    private Emails() {}

    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
