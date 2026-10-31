package dev.pointer.auth.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pointer.github")
record GitHubProperties(String clientId, String clientSecret, String authorizeUrl, String tokenUrl, String apiUrl) {

    boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
