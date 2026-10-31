package dev.pointer.provider.llm;

public record Endpoint(String baseUrl, String apiKey, HttpTransport transport) {

    public String url(String path) {
        var base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }
}
