package io.ledgerflow.balance;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import java.util.UUID;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // one context per set of bean overrides, so two of these can be alive in one JVM: the suffix keeps the names apart
    private final String suffix = UUID.randomUUID().toString().substring(0, 4);

    @Bean
    @ServiceConnection
    RedpandaContainer redpanda() {
        return new RedpandaContainer("redpandadata/redpanda:v25.2.1")
                .withCreateContainerCmdModifier(cmd -> cmd.withName("ledgerflow-test-balance-redpanda-" + suffix))
                .withLabel("com.docker.compose.project", "ledgerflow");
    }

    @Bean
    @ServiceConnection(name = "redis")   // no Testcontainers module for Redis; Boot matches the plain image by name
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379)
                .withCreateContainerCmdModifier(cmd -> cmd.withName("ledgerflow-test-balance-redis-" + suffix))
                .withLabel("com.docker.compose.project", "ledgerflow");
    }
}
