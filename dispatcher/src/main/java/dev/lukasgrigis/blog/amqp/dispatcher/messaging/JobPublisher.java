package dev.lukasgrigis.blog.amqp.dispatcher.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

public class JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public JobPublisher(
        RabbitTemplate rabbitTemplate,
        String exchange,
        String routingKey
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publish(String id, Map<String, Object> payload) {
        log.info("Publishing job {} to exchange '{}' (routing key '{}')", id, exchange, routingKey);
        rabbitTemplate.convertAndSend(exchange, routingKey, payload);
    }

}
