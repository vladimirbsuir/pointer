package dev.pointer.container;

import java.security.SecureRandom;

record VncPasswords(String full, String view) {

    static final String FULL_VARIABLE = "POINTER_VNC_PASSWORD";
    static final String VIEW_VARIABLE = "POINTER_VNC_VIEW_PASSWORD";
    static final int LENGTH = 8;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    static final VncPasswords NONE = new VncPasswords("", "");

    static VncPasswords generate() {
        return new VncPasswords(random(), random());
    }

    private static String random() {
        var password = new StringBuilder(LENGTH);
        for (var index = 0; index < LENGTH; index++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
