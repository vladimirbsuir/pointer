package dev.pointer.provider.llm;

public class LlmException extends RuntimeException {

    private final String code;
    private final int status;

    public LlmException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public LlmException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = 0;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
