package dev.pointer.provider.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class DirectHttpTransport implements HttpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public StreamResponse send(Request request) throws IOException {
        var builder = HttpRequest.newBuilder(URI.create(request.url()));
        request.headers().forEach(builder::header);
        var publisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
        builder.method(request.method(), publisher);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new StreamResponse(response.statusCode(), response.body(), () -> {});
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", exception);
        }
    }
}
