package dev.pointer.session;

public final class SessionStatus {

    public static final String IDLE = "idle";
    public static final String RUNNING = "running";
    public static final String WAITING_FOR_APPROVAL = "waiting_for_approval";
    public static final String WAITING_FOR_EXECUTOR = "waiting_for_executor";
    public static final String ERROR = "error";

    private SessionStatus() {}
}
