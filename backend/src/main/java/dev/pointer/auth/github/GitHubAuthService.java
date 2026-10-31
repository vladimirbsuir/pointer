package dev.pointer.auth.github;

import dev.pointer.auth.AuthResponse;
import dev.pointer.auth.AuthSessionService;
import dev.pointer.common.error.ApiException;
import dev.pointer.device.DeviceInfo;
import dev.pointer.user.Emails;
import dev.pointer.user.MeResponse;
import dev.pointer.user.UserIdentity;
import dev.pointer.user.UserService;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
class GitHubAuthService {

    static final String SCOPES = "read:user user:email";

    private static final Pattern CODE_CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43,128}");
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String CALLBACK_PATH = "/callback";

    private final GitHubProperties properties;
    private final GitHubClient client;
    private final UserService userService;
    private final AuthSessionService sessions;

    GitHubAuthService(
            GitHubProperties properties, GitHubClient client, UserService userService, AuthSessionService sessions) {
        this.properties = properties;
        this.client = client;
        this.userService = userService;
        this.sessions = sessions;
    }

    String authorizationUrl(String codeChallenge, String state, String redirectUri) {
        ensureConfigured();
        validateRedirectUri(redirectUri);
        if (!CODE_CHALLENGE.matcher(codeChallenge).matches()) {
            throw ApiException.badRequest("invalid_code_challenge", "Code challenge is malformed");
        }
        return UriComponentsBuilder.fromUriString(properties.authorizeUrl())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", SCOPES)
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("allow_signup", "true")
                .encode()
                .toUriString();
    }

    @Transactional
    AuthResponse signIn(String code, String codeVerifier, String redirectUri, DeviceInfo device, String locale) {
        var account = fetchAccount(code, codeVerifier, redirectUri);
        var providerUserId = Long.toString(account.user().id());
        var existing = userService.findIdentity(UserIdentity.GITHUB, providerUserId);
        if (existing.isPresent()) {
            userService.refreshIdentityLogin(existing.get(), account.user().login());
            return sessions.start(userService.getActive(existing.get().getUserId()), device);
        }
        var email = account.email();
        if (userService.emailExists(email)) {
            throw ApiException.conflict(
                    "account_exists", "An account with this email exists. Sign in and link GitHub in settings");
        }
        var user = userService.createFromIdentity(
                email,
                displayName(account.user()),
                locale,
                UserIdentity.GITHUB,
                providerUserId,
                account.user().login());
        return sessions.start(user, device);
    }

    @Transactional
    MeResponse link(UUID userId, String code, String codeVerifier, String redirectUri) {
        userService.getActive(userId);
        var account = fetchAccount(code, codeVerifier, redirectUri);
        userService.linkIdentity(
                userId,
                UserIdentity.GITHUB,
                Long.toString(account.user().id()),
                account.user().login());
        return userService.me(userId);
    }

    private GitHubAccount fetchAccount(String code, String codeVerifier, String redirectUri) {
        ensureConfigured();
        validateRedirectUri(redirectUri);
        var accessToken = client.exchangeCode(code, codeVerifier, redirectUri);
        var user = client.fetchUser(accessToken);
        var email = client.fetchPrimaryVerifiedEmail(accessToken)
                .map(Emails::normalize)
                .orElseThrow(() -> ApiException.badRequest(
                        "github_email_unverified", "GitHub account has no verified primary email"));
        return new GitHubAccount(user, email);
    }

    private void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw ApiException.unavailable("github_not_configured", "GitHub sign-in is not configured");
        }
    }

    private static void validateRedirectUri(String redirectUri) {
        try {
            var uri = new URI(redirectUri);
            var valid = "http".equals(uri.getScheme())
                    && LOOPBACK_HOST.equals(uri.getHost())
                    && uri.getPort() > 0
                    && CALLBACK_PATH.equals(uri.getPath())
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
            if (!valid) {
                throw invalidRedirectUri();
            }
        } catch (URISyntaxException exception) {
            throw invalidRedirectUri();
        }
    }

    private static ApiException invalidRedirectUri() {
        return ApiException.badRequest("invalid_redirect_uri", "Redirect URI must be http://127.0.0.1:<port>/callback");
    }

    private static String displayName(GitHubClient.GitHubUser user) {
        return user.name() == null || user.name().isBlank() ? user.login() : user.name();
    }

    private record GitHubAccount(GitHubClient.GitHubUser user, String email) {}
}
