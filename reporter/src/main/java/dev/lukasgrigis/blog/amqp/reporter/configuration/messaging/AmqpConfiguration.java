package dev.lukasgrigis.blog.amqp.reporter.configuration.messaging;

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
 */
@Configuration
@EnableConfigurationProperties(AmqpProperties.class)
class AmqpConfiguration {

    @Bean
    MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    ConnectionFactoryCustomizer oauth2CredentialsCustomizer(
        OAuth2AuthorizedClientManager authorizedClientManager,
        AmqpProperties properties
    ) {
        return factory -> factory.setCredentialsProvider(
            new OAuth2CredentialsProvider(
                properties.brokerRegistrationId(),
                authorizedClientManager
            ));
    }

    @Bean
    ResultConsumer resultConsumer() {
        return new ResultConsumer();
    }

}
