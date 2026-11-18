package dev.pointer.provider.llm;

public record Endpoint(String baseUrl, String apiKey, HttpTransport transport, boolean sendsCacheKey) {

    public Endpoint(String baseUrl, String apiKey, HttpTransport transport) {
        this(baseUrl, apiKey, transport, false);
    }

    public String url(String path) {
        var base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }
}
