package io.ledgerflow.gateway.adapter.in.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventStreamTest {

    @Test
    void capsAt200AndKeepsArrivalOrder() {
        var stream = new EventStream();
        for (int i = 0; i < 250; i++) {
            stream.publish(new StreamRecord("t", 0, i, "k" + i, i, "v" + i));
        }

        var snapshot = stream.snapshot();
        assertThat(snapshot).hasSize(200);
        assertThat(snapshot.getFirst().offset()).isEqualTo(50);   // the oldest 50 fell off the front
        assertThat(snapshot.getLast().offset()).isEqualTo(249);
    }

    @Test
    void aNewSubscriberReplaysTheBufferFirstAndInOrder() {
        var stream = new EventStream();
        stream.publish(new StreamRecord("t", 0, 1, "k1", 1, "v1"));
        stream.publish(new StreamRecord("t", 0, 2, "k2", 2, "v2"));

        stream.subscribe();   // no assertion on the emitter itself: SseEmitter has no unstarted response to inspect

        assertThat(stream.snapshot()).extracting(StreamRecord::offset).containsExactly(1L, 2L);
    }
}
