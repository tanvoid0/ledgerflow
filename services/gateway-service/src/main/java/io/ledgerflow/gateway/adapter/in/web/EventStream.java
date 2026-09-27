package io.ledgerflow.gateway.adapter.in.web;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The last 200 records crossing the broker, and every open SSE connection. A new subscriber gets
 * the buffer first, in order, so a page that connects mid-payment still sees its start.
 */
@Component
public class EventStream {

    private static final int CAPACITY = 200;

    private final ArrayDeque<StreamRecord> buffer = new ArrayDeque<>(CAPACITY);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public SseEmitter subscribe() {
        var emitter = new SseEmitter(TimeUnit.HOURS.toMillis(1));
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        // a record published between this snapshot and the add below can double up on this one subscriber;
        // fine for a demo stream, sequence buffer+emitters under one lock if that ever matters
        for (var record : snapshot()) send(emitter, record);
        emitters.add(emitter);
        return emitter;
    }

    public void publish(StreamRecord record) {
        lock.lock();
        try {
            buffer.addLast(record);
            while (buffer.size() > CAPACITY) buffer.removeFirst();
        } finally {
            lock.unlock();
        }
        for (var emitter : emitters) send(emitter, record);
    }

    List<StreamRecord> snapshot() {
        lock.lock();
        try {
            return List.copyOf(buffer);
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    private void send(SseEmitter emitter, StreamRecord record) {
        try {
            emitter.send(SseEmitter.event().name("record").data(record));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
    }
}
