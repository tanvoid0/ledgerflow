package io.ledgerflow.issuer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import java.util.UUID;

/** Beans, not static @Containers: they live and die with the cached context, not with one test class. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private final String suffix = UUID.randomUUID().toString().substring(0, 4);

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17")
                .withCreateContainerCmdModifier(cmd -> cmd.withName("ledgerflow-test-issuer-postgres-" + suffix))
                .withLabel("com.docker.compose.project", "ledgerflow");
    }

    @Bean
    @ServiceConnection
    RedpandaContainer redpanda() {
        return new RedpandaContainer("redpandadata/redpanda:v25.2.1")
                .withCreateContainerCmdModifier(cmd -> cmd.withName("ledgerflow-test-issuer-redpanda-" + suffix))
                .withLabel("com.docker.compose.project", "ledgerflow");
    }
}
