package dev.pointer.provider.llm;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public interface HttpTransport {

    StreamResponse send(Request request) throws IOException;

    record Request(String method, String url, Map<String, String> headers, byte[] body) {}

    record StreamResponse(int status, InputStream body, Closeable onClose) implements Closeable {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        @Override
        public void close() throws IOException {
            try {
                body.close();
            } finally {
                onClose.close();
            }
        }
    }
}
