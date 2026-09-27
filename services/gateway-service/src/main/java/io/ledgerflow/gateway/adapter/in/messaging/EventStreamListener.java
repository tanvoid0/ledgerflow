package io.ledgerflow.gateway.adapter.in.messaging;

import io.ledgerflow.gateway.adapter.in.web.EventStream;
import io.ledgerflow.gateway.adapter.in.web.StreamRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Watches every event and command topic; commits nothing and depends on nothing downstream of it. */
@Component
class EventStreamListener {

    private final EventStream stream;
    private final JsonMapper json;

    EventStreamListener(EventStream stream, JsonMapper json) {
        this.stream = stream;
        this.json = json;
    }

    @KafkaListener(topicPattern = "ledgerflow\\..*\\.(events|commands)\\.v1")
    void onRecord(ConsumerRecord<String, String> record) {
        Object value;
        try {
            value = json.readValue(record.value(), Object.class);
        } catch (RuntimeException e) {
            value = record.value();   // not JSON, or not this listener's business to reject: show it as-is
        }
        stream.publish(new StreamRecord(record.topic(), record.partition(), record.offset(), record.key(), record.timestamp(), value));
    }
}
