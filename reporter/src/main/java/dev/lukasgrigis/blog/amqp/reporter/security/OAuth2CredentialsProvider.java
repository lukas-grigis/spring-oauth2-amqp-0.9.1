package dev.lukasgrigis.blog.amqp.reporter.security;

import com.rabbitmq.client.impl.CredentialsProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

/**
 * RabbitMQ credentials provider that sources its password from an OAuth2 access token.
 * The access token is fetched (and refreshed) by Spring Security's OAuth2AuthorizedClientManager.
 */
public class OAuth2CredentialsProvider implements CredentialsProvider {

    private static final UsernamePasswordAuthenticationToken PRINCIPAL =
        UsernamePasswordAuthenticationToken.unauthenticated("amqp-client", null);

    private final String registrationId;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public OAuth2CredentialsProvider(String registrationId, OAuth2AuthorizedClientManager authorizedClientManager) {
        this.registrationId = registrationId;
        this.authorizedClientManager = authorizedClientManager;
    }

    @Override
    public String getUsername() {
        return "";
    }

    @Override
    public String getPassword() {
        final var request = OAuth2AuthorizeRequest
            .withClientRegistrationId(registrationId)
            .principal(PRINCIPAL)
            .build();
        final var client = authorizedClientManager.authorize(request);
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException(
                "Unable to obtain OAuth2 access token for client registration '" + registrationId + "'");
        }
        return client.getAccessToken().getTokenValue();
    }

}
