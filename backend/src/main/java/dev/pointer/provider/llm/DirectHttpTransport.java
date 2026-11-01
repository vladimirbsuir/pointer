package dev.pointer.provider.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import org.springframework.stereotype.Component;

@Component
public class DirectHttpTransport implements HttpTransport {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(3);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public StreamResponse send(Request request, Cancellation cancellation) throws IOException {
        var builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(RESPONSE_TIMEOUT);
        request.headers().forEach(builder::header);
        var publisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
        builder.method(request.method(), publisher);
        var pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        cancellation.attach(() -> pending.cancel(true));
        try {
            var response = pending.get();
            return new StreamResponse(response.statusCode(), response.body(), () -> {});
        } catch (CancellationException exception) {
            throw new IOException("Request cancelled", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new IOException(
                        "The provider did not respond within " + RESPONSE_TIMEOUT.toMinutes() + " minutes");
            }
            throw exception.getCause() instanceof IOException io ? io : new IOException(exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", exception);
        } finally {
            cancellation.detach();
        }
    }
}
