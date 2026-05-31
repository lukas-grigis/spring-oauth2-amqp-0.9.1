package dev.lukasgrigis.blog.amqp.dispatcher.configuration.messaging;

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
    JobPublisher jobPublisher(RabbitTemplate rabbitTemplate, AmqpProperties properties) {
        return new JobPublisher(
            rabbitTemplate,
            properties.exchanges().jobs(),
            properties.routingKeys().jobSubmitted()
        );

    }

}
