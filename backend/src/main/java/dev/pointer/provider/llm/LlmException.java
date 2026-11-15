package dev.pointer.provider.llm;

import java.time.Duration;
import java.util.Optional;

public class LlmException extends RuntimeException {

    private final String code;
    private final int status;
    private final Duration retryAfter;

    public LlmException(String code, String message, int status) {
        this(code, message, status, null);
    }

    public LlmException(String code, String message, int status, Duration retryAfter) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public LlmException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = 0;
        this.retryAfter = null;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
