package dev.lukasgrigis.blog.amqp.reporter.security;

import com.rabbitmq.client.impl.CredentialsProvider;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

/**
 * RabbitMQ credentials provider that sources its password from an OAuth2 access token.
 *
 * <p>The token is fetched and cached by Spring Security's {@link OAuth2AuthorizedClientManager}. By also
 * reporting {@link #getTimeBeforeExpiration()} and implementing {@link #refresh()}, this provider lets the
 * RabbitMQ client's {@code CredentialsRefreshService} renew the token on a live connection — via the AMQP
 * {@code update-secret} method — before it expires, so a long-lived connection is never cut off when its
 * original token's lifespan runs out.
 */
public class OAuth2CredentialsProvider implements CredentialsProvider {

    private static final UsernamePasswordAuthenticationToken PRINCIPAL =
        UsernamePasswordAuthenticationToken.unauthenticated("amqp-client", null);

    private final String registrationId;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final OAuth2AuthorizedClientService authorizedClientService;

    public OAuth2CredentialsProvider(
        String registrationId,
        OAuth2AuthorizedClientManager authorizedClientManager,
        OAuth2AuthorizedClientService authorizedClientService
    ) {
        this.registrationId = registrationId;
        this.authorizedClientManager = authorizedClientManager;
        this.authorizedClientService = authorizedClientService;
    }

    @Override
    public String getUsername() {
        return "";
    }

    @Override
    public String getPassword() {
        return currentToken().getTokenValue();
    }

    /**
     * Remaining lifetime of the current token. The {@code CredentialsRefreshService} uses this to schedule a
     * proactive refresh (by default once 80% of the lifetime has elapsed) before the broker stops honoring it.
     */
    @Override
    public Duration getTimeBeforeExpiration() {
        final Instant expiresAt = currentToken().getExpiresAt();
        return expiresAt == null ? Duration.ZERO : Duration.between(Instant.now(), expiresAt);
    }

    /**
     * Evicts the cached authorized client so the next {@link #getPassword()} mints a brand-new token. The
     * {@code CredentialsRefreshService} calls this, then re-reads the password and pushes it to the broker on
     * the open connection via {@code update-secret}.
     */
    @Override
    public void refresh() {
        authorizedClientService.removeAuthorizedClient(registrationId, PRINCIPAL.getName());
    }

    private OAuth2AccessToken currentToken() {
        final var request = OAuth2AuthorizeRequest
            .withClientRegistrationId(registrationId)
            .principal(PRINCIPAL)
            .build();
        final var client = authorizedClientManager.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException(
                "Unable to obtain OAuth2 access token for client registration '" + registrationId + "'");
        }
        return client.getAccessToken();
    }

}
