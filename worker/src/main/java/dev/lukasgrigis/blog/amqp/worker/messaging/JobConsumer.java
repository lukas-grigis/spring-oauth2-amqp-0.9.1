package dev.lukasgrigis.blog.amqp.worker.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.util.Map;

public class JobConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    private final ResultPublisher resultPublisher;

    public JobConsumer(ResultPublisher resultPublisher) {
        this.resultPublisher = resultPublisher;
    }

    @RabbitListener(queues = "${app.amqp.queues.jobs-in}")
    public void handle(Map<String, Object> job) {
        final var id = String.valueOf(job.get("id"));
        final var rawPayload = job.get("payload");
        final var payload = rawPayload == null ? "" : rawPayload.toString();

        log.info("Processing job {} (payload='{}')", id, payload);
        final var result = payload.toUpperCase();

        resultPublisher.publish(Map.of("id", id, "result", result));
    }

}
