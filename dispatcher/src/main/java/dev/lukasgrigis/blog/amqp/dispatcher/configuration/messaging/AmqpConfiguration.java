package dev.lukasgrigis.blog.amqp.dispatcher.configuration.messaging;

import com.rabbitmq.client.impl.CredentialsRefreshService;
import com.rabbitmq.client.impl.DefaultCredentialsRefreshService.DefaultCredentialsRefreshServiceBuilder;
import dev.lukasgrigis.blog.amqp.dispatcher.messaging.JobPublisher;
import dev.lukasgrigis.blog.amqp.dispatcher.security.OAuth2CredentialsProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.ConnectionFactoryCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the dispatcher's messaging topology (prefix {@code app.amqp}).
 *
 * <p>Replaces scattered {@code @Value} lookups: the broker registration id, the target exchange, and the
 * routing key live in one place with safe defaults baked in via {@link DefaultValue}, and are validated
 * on startup so a blank override fails fast instead of misrouting at runtime.
 */
@Validated
@ConfigurationProperties(prefix = "app.amqp")
record AmqpProperties(

    @DefaultValue("rabbitmq-broker") @NotBlank String brokerRegistrationId,
    @DefaultValue @Valid Exchanges exchanges,
    @DefaultValue @Valid RoutingKeys routingKeys
) {

    record Exchanges(@DefaultValue("jobs") @NotBlank String jobs) {

    }

    record RoutingKeys(@DefaultValue("job.submitted") @NotBlank String jobSubmitted) {

    }

}

/**
 * Sources the AMQP connection password from an OAuth2 access token while leaving Spring Boot in charge of
 * everything else.
 *
 * <p>Instead of hand-building a {@link com.rabbitmq.client.ConnectionFactory} (which would back off Boot's
 * auto-configuration and silently drop every other {@code spring.rabbitmq.*} setting — SSL bundles, addresses,
 * timeouts), we register a {@link ConnectionFactoryCustomizer}. Boot still builds and tunes the
 * {@code CachingConnectionFactory}; we only swap in the {@link OAuth2CredentialsProvider} so the broker is
 * authenticated with the current token. The {@code RabbitTemplate} is auto-configured and automatically
 * picks up the {@link MessageConverter} bean below.
 *
 * <p>The customizer also installs a {@link CredentialsRefreshService}. Because {@link OAuth2CredentialsProvider}
 * reports the token's remaining lifetime, the RabbitMQ client proactively renews the token on the live
 * connection (via the AMQP {@code update-secret} method) before it expires — so the cached publishing
 * connection never outlives its credential.
 */
@Configuration
@EnableConfigurationProperties(AmqpProperties.class)
class AmqpConfiguration {

    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    CredentialsRefreshService credentialsRefreshService() {
        // Renews each token once 80% of its lifetime has elapsed (the builder default) and pushes the new
        // token to the broker on the open connection, so a stable connection never outlives its credential.
        return new DefaultCredentialsRefreshServiceBuilder().build();
    }

    @Bean
    ConnectionFactoryCustomizer oauth2CredentialsCustomizer(
        OAuth2AuthorizedClientManager authorizedClientManager,
        OAuth2AuthorizedClientService authorizedClientService,
        CredentialsRefreshService credentialsRefreshService,
        AmqpProperties properties
    ) {
        return factory -> {
            final var provider = new OAuth2CredentialsProvider(
                properties.brokerRegistrationId(),
                authorizedClientManager,
                authorizedClientService
            );
            factory.setCredentialsProvider(provider);
            factory.setCredentialsRefreshService(credentialsRefreshService);
        };
    }

    @Bean
    JobPublisher jobPublisher(RabbitTemplate rabbitTemplate, AmqpProperties properties) {
        return new JobPublisher(
            rabbitTemplate,
            properties.exchanges().jobs(),
            properties.routingKeys().jobSubmitted()
        );

    }

}
