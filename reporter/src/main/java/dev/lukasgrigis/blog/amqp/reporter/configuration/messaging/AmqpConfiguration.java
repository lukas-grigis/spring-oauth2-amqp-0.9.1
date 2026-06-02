package dev.lukasgrigis.blog.amqp.reporter.configuration.messaging;

import com.rabbitmq.client.impl.CredentialsRefreshService;
import com.rabbitmq.client.impl.DefaultCredentialsRefreshService.DefaultCredentialsRefreshServiceBuilder;
import dev.lukasgrigis.blog.amqp.reporter.messaging.ResultConsumer;
import dev.lukasgrigis.blog.amqp.reporter.security.OAuth2CredentialsProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Typed, validated binding for the reporter's messaging topology (prefix {@code app.amqp}).
 *
 * <p>The reporter only ever reads from the {@code results.out} queue — it has no exchange or routing key
 * because it cannot publish. The {@code @RabbitListener} resolves the queue name straight from the
 * {@code app.amqp.queues.results-out} placeholder; binding it here as a validated component just makes a
 * blank value fail fast at startup.
 */
@Validated
@ConfigurationProperties(prefix = "app.amqp")
record AmqpProperties(

    @DefaultValue("rabbitmq-broker") @NotBlank String brokerRegistrationId,
    @DefaultValue @Valid Queues queues
) {

    record Queues(@DefaultValue("results.out") @NotBlank String resultsOut) {

    }

}

/**
 * Sources the AMQP connection password from an OAuth2 access token while leaving Spring Boot in charge of
 * everything else.
 *
 * <p>A {@link ConnectionFactoryCustomizer} swaps the {@link OAuth2CredentialsProvider} onto the underlying
 * factory; Boot auto-configures the {@code CachingConnectionFactory} and the listener container that drives
 * {@link ResultConsumer}, which picks up the {@link MessageConverter} bean below. There is deliberately no
 * {@code RabbitTemplate} here — the reporter's token carries {@code results_read} only, so the broker would
 * refuse any publish anyway.
 *
 * <p>The customizer also installs a {@link CredentialsRefreshService}. Because {@link OAuth2CredentialsProvider}
 * reports the token's remaining lifetime, the RabbitMQ client proactively renews the token on the live
 * connection (via the AMQP {@code update-secret} method) before it expires — so this long-lived consumer is
 * never refused operations when its original token's lifespan runs out.
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
    ResultConsumer resultConsumer() {
        return new ResultConsumer();
    }

}
