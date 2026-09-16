package io.ledgerflow.settlement;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the k8s startup/readiness/liveness probes in k8s/base actually hit, over a real socket rather than
 * MockMvc's servlet-in-process shortcut. Readiness lists db so a database outage pulls the pod out of the
 * Service before it can 500 a request; liveness stays the bare livenessState so the same outage never
 * triggers a restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ProbesTest {

    @LocalServerPort
    int port;

    private RestClient rest() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    void livenessIsUp() {
        Map<String, Object> body = rest().get().uri("/actuator/health/liveness")
                .retrieve().body(Map.class);

        assertThat(body).containsEntry("status", "UP");
    }

    @Test
    void readinessIsUpAndListsDb() {
        Map<String, Object> body = rest().get().uri("/actuator/health/readiness")
                .retrieve().body(Map.class);

        assertThat(body).containsEntry("status", "UP");
        @SuppressWarnings("unchecked")
        var components = (Map<String, Object>) body.get("components");
        assertThat(components).containsKey("db");
    }
}
