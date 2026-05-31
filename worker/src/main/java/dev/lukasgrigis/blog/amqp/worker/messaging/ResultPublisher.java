package dev.lukasgrigis.blog.amqp.worker.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

public class ResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(ResultPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public ResultPublisher(
        RabbitTemplate rabbitTemplate,
        String exchange,
        String routingKey
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publish(Map<String, Object> result) {
        log.info("Publishing result {} to exchange '{}' (routing key '{}')", result.get("id"), exchange, routingKey);
        rabbitTemplate.convertAndSend(exchange, routingKey, result);
    }

}
