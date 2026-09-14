package io.ledgerflow.account;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // one context per set of bean overrides, so two of these can be alive in one JVM: the suffix keeps the names apart
    private final String suffix = UUID.randomUUID().toString().substring(0, 4);

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17")
                .withCreateContainerCmdModifier(cmd -> cmd.withName("ledgerflow-test-account-postgres-" + suffix))
                .withLabel("com.docker.compose.project", "ledgerflow");   // Docker Desktop groups by this label
    }
}
