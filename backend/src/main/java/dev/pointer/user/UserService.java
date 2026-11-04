package dev.pointer.user;

import dev.pointer.common.error.ApiException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final UserSettingsRepository settings;
    private final Clock clock;

    @Transactional
    public User createWithPassword(String email, String passwordHash, String displayName, String locale) {
        ensureEmailAvailable(email);
        var user = User.withPassword(
                email, passwordHash, displayName, Locales.supportedOrDefault(locale), clock.instant());
        return persistNewUser(user);
    }

    @Transactional
    public User createFromIdentity(
            String email,
            String displayName,
            String locale,
            String provider,
            String providerUserId,
            String providerLogin) {
        ensureEmailAvailable(email);
        var user = persistNewUser(
                User.withoutPassword(email, displayName, Locales.supportedOrDefault(locale), clock.instant()));
        identities.save(new UserIdentity(user.getId(), provider, providerUserId, providerLogin, clock.instant()));
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return users.findByEmail(email);
    }

    @Transactional(readOnly = true)
    public boolean emailExists(String email) {
        return users.existsByEmail(email);
    }

    @Transactional(readOnly = true)
    public User getActive(UUID userId) {
        var user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("user_not_found", "User does not exist"));
        if (user.isDisabled()) {
            throw ApiException.forbidden("account_disabled", "Account is disabled");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<UserIdentity> findIdentity(String provider, String providerUserId) {
        return identities.findByProviderAndProviderUserId(provider, providerUserId);
    }

    @Transactional
    public void refreshIdentityLogin(UserIdentity identity, String providerLogin) {
        if (!identity.getProviderLogin().equals(providerLogin)) {
            identity.updateLogin(providerLogin, clock.instant());
            identities.save(identity);
        }
    }

    @Transactional
    public void linkIdentity(UUID userId, String provider, String providerUserId, String providerLogin) {
        identities.findByProviderAndProviderUserId(provider, providerUserId).ifPresent(existing -> {
            throw existing.getUserId().equals(userId)
                    ? ApiException.conflict("identity_already_linked", "This account is already linked")
                    : ApiException.conflict("identity_in_use", "This account is linked to another user");
        });
        if (identities.findByUserIdAndProvider(userId, provider).isPresent()) {
            throw ApiException.conflict("provider_already_linked", "Another account of this provider is linked");
        }
        identities.save(new UserIdentity(userId, provider, providerUserId, providerLogin, clock.instant()));
    }

    @Transactional
    public void changePassword(User user, String passwordHash) {
        user.changePassword(passwordHash, clock.instant());
        users.save(user);
    }

    @Transactional
    public MeResponse update(UUID userId, UpdateMeRequest request) {
        var user = getActive(userId);
        if (request.displayName() != null) {
            user.rename(request.displayName().strip(), clock.instant());
        }
        if (request.locale() != null) {
            user.changeLocale(request.locale(), clock.instant());
        }
        users.save(user);
        return me(user);
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        return me(getActive(userId));
    }

    @Transactional(readOnly = true)
    public MeResponse me(User user) {
        var github = identities
                .findByUserIdAndProvider(user.getId(), UserIdentity.GITHUB)
                .map(identity -> new MeResponse.GitHubAccount(identity.getProviderLogin()))
                .orElse(null);
        return new MeResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getLocale(), user.hasPassword(), github);
    }

    private User persistNewUser(User user) {
        var saved = users.save(user);
        settings.save(new UserSettings(saved.getId(), clock.instant()));
        return saved;
    }

    private void ensureEmailAvailable(String email) {
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
        }
    }
}
