package io.ledgerflow.notification.adapter.in.messaging;

import io.ledgerflow.events.EventEnvelope;
import io.ledgerflow.events.ledger.FundsHeld;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
class FundsHoldListener {

    private static final Logger log = LoggerFactory.getLogger(FundsHoldListener.class);

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(delay = 1000, multiplier = 3.0),   // 1s, 3s, 9s, then the DLT
            retryTopicSuffix = ".retry",
            dltTopicSuffix = ".dlt",
            numPartitions = "3",
            exclude = InvalidPayloadException.class)               // bad data never gets better; skip the retries
    @KafkaListener(topics = FundsHeld.TOPIC)
    void onFundsHeld(EventEnvelope<FundsHeld> event, Acknowledgment ack) {
        var held = event.payload();
        if (held == null || held.wallets() == null || held.wallets().isEmpty()) {
            throw new InvalidPayloadException("event " + event.eventId() + " names no wallet");
        }
        log.info("would email the customer: {} {} held on {} until {} (event {}, request {})",
                held.totalAmount().currency(), held.totalAmount().minorUnits(),
                held.wallets().getFirst().label(), held.expiresAt(),
                event.eventId(), event.correlationId());
        ack.acknowledge();   // after the work, never before
    }

    // ConsumerRecord on purpose: a poison pill would not survive conversion a second time either
    @DltHandler
    void onDead(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("gave up on hold {}: {} ({})", record.key(),
                header(record, KafkaHeaders.EXCEPTION_MESSAGE), header(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN));
        ack.acknowledge();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? "?" : new String(h.value(), StandardCharsets.UTF_8);
    }
}
