package dev.pointer.provider.llm;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Cancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Closeable> active = new AtomicReference<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            closeQuietly(active.getAndSet(null));
        }
    }

    void attach(Closeable resource) {
        active.set(resource);
        if (cancelled.get()) {
            closeQuietly(active.getAndSet(null));
        }
    }

    void detach() {
        active.set(null);
    }

    private static boolean closeQuietly(Closeable resource) {
        if (resource == null) {
            return false;
        }
        try {
            resource.close();
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
