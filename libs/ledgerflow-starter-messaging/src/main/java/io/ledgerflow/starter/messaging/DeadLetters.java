package io.ledgerflow.starter.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;

/** Where every listener's retries end. Count it, log it, ack it, leave it on the topic for scripts/dlt-replay.sh. */
public class DeadLetters {

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);

    private final MeterRegistry meters;

    DeadLetters(MeterRegistry meters) {
        this.meters = meters;
    }

    // ConsumerRecord on purpose: a poison pill would not survive conversion a second time either
    public void onDead(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("gave up on {} key {}: {} ({})", record.topic(), record.key(),
                header(record, KafkaHeaders.EXCEPTION_MESSAGE), header(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN));
        meters.counter("ledgerflow.dead.letters", "topic", record.topic()).increment();   // the alert is on this, not on the log line
        ack.acknowledge();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? "?" : new String(h.value(), StandardCharsets.UTF_8);
    }
}
