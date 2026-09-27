package io.ledgerflow.gateway.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class StreamController {

    private final EventStream stream;

    StreamController(EventStream stream) {
        this.stream = stream;
    }

    @GetMapping(value = "/api/v1/stream", produces = "text/event-stream")
    SseEmitter stream() {
        return stream.subscribe();
    }
}
