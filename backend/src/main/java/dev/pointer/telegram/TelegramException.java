package dev.pointer.telegram;

class TelegramException extends RuntimeException {

    private final int errorCode;

    TelegramException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    int errorCode() {
        return errorCode;
    }

    boolean isParseError() {
        return errorCode == 400 && getMessage() != null && getMessage().contains("can't parse entities");
    }

    boolean isNotModified() {
        return errorCode == 400 && getMessage() != null && getMessage().contains("message is not modified");
    }
}
