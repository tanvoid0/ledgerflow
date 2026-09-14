package io.ledgerflow.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /**
     * A bean rather than a static @Container: a static one stops when its test class ends while
     * the cached context lives on, and the outbox poller then spends 30s per tick waiting for a
     * database that is gone. As a bean it is stopped with the context, after the scheduler.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17");
    }
}
