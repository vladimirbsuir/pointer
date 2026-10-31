package dev.pointer.auth.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.pointer.common.error.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class RestGitHubClient implements GitHubClient {

    private static final String API_VERSION = "2022-11-28";

    private final GitHubProperties properties;
    private final RestClient restClient = RestClient.create();

    RestGitHubClient(GitHubProperties properties) {
        this.properties = properties;
    }

    @Override
    public String exchangeCode(String code, String codeVerifier, String redirectUri) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);
        var response = call(() -> restClient
                .post()
                .uri(properties.tokenUrl())
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class));
        if (response == null || response.accessToken() == null) {
            throw ApiException.unauthorized(
                    "github_code_rejected",
                    response == null || response.error() == null ? "GitHub rejected the code" : response.error());
        }
        return response.accessToken();
    }

    @Override
    public GitHubUser fetchUser(String accessToken) {
        var user = call(() -> api(accessToken, "/user").body(GitHubUser.class));
        if (user == null) {
            throw ApiException.unavailable("github_unavailable", "GitHub returned no user");
        }
        return user;
    }

    @Override
    public Optional<String> fetchPrimaryVerifiedEmail(String accessToken) {
        List<EmailEntry> emails = call(() -> api(accessToken, "/user/emails").body(EmailEntry.LIST_TYPE));
        return emails == null
                ? Optional.empty()
                : emails.stream()
                        .filter(email -> email.primary() && email.verified())
                        .map(EmailEntry::email)
                        .findFirst();
    }

    private RestClient.ResponseSpec api(String accessToken, String path) {
        return restClient
                .get()
                .uri(properties.apiUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", API_VERSION)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve();
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException exception) {
            throw ApiException.unavailable("github_unavailable", "GitHub request failed");
        }
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("error_description") String error) {}

    record EmailEntry(String email, boolean primary, boolean verified) {

        static final ParameterizedTypeReference<List<EmailEntry>> LIST_TYPE = new ParameterizedTypeReference<>() {};
    }
}
