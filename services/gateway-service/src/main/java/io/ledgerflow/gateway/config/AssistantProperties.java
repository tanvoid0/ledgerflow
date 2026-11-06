package io.ledgerflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The assistant runs inside the gateway and reaches the ledger through the gateway's own public API,
 * so it needs only one base URL: the gateway itself. Same routing, same auth, no new data path.
 */
@ConfigurationProperties("insights")
record AssistantProperties(@DefaultValue("http://localhost:8088") String gatewayBase) {}
