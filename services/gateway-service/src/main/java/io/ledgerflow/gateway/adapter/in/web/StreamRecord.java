package io.ledgerflow.gateway.adapter.in.web;

/** One record as it crossed the broker. value is the parsed JSON payload, or the raw string if it did not parse. */
public record StreamRecord(String topic, int partition, long offset, String key, long timestamp, Object value) {}
