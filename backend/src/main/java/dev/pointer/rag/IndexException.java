package dev.pointer.rag;

class IndexException extends RuntimeException {

    private final String code;

    IndexException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
