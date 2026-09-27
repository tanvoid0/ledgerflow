package io.ledgerflow.gateway.config;

import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customers hold realm tokens too: they get the storefront's paths and their own balance, nothing staff-only. */
@WebMvcTest(GatewaySecurityConfigTest.Anything.class)
@Import({GatewaySecurityConfig.class, GatewaySecurityConfigTest.Anything.class})
class GatewaySecurityConfigTest {

    static final String ALICE = "22222222-2222-2222-2222-222222222222";
    static final String BOB = "33333333-3333-3333-3333-333333333333";

    /** Stands in for every routed service: whatever the chain lets through answers 200. */
    @RestController
    static class Anything {
        @RequestMapping("/**")
        String ok() {
            return "ok";
        }
    }

    @Autowired MockMvc mvc;

    @ParameterizedTest
    @CsvSource({"GET, /api/v1/stream", "GET, /api/v1/payments", "GET, /api/v1/cases", "GET, /api/v1/accounts",
            "POST, /api/v1/batch/jobs/settlement", "POST, /api/v1/batch/executions/1/stop", "POST, /api/v1/holds",
            "POST, /api/v1/fx/faults", "POST, /api/v1/transfers", "GET, /actuator/metrics", "GET, /actuator/sbom/application",
            "GET, /api/v1/dev/prom/query", "GET, /api/v1/balances/" + BOB, "GET, /api/v1/balances/" + BOB + "/MAIN"})
    void aCustomerIsForbiddenStaffPathsAndOtherAccounts(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).with(token(ALICE, "customer"))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/v1/balances/" + ALICE, "GET, /api/v1/balances/" + ALICE + "/MAIN", "POST, /api/v1/payments",
            "POST, /api/v1/events/e1/holds", "GET, /api/v1/orders", "POST, /api/v1/orders/o1/checkout"})
    void aCustomerReachesTheStorefrontAndItsOwnBalance(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).with(token(ALICE, "customer"))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/v1/events, 200", "GET, /api/v1/events/e1, 200", "GET, /actuator/health/liveness, 200",
            "POST, /api/v1/events/e1/holds, 401", "GET, /api/v1/balances/" + ALICE + ", 401", "GET, /api/v1/stream, 401"})
    void anonymous(String method, String path, int expected) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().is(expected));
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/v1/stream", "GET, /api/v1/payments", "GET, /api/v1/balances/" + BOB, "GET, /actuator/metrics",
            "POST, /api/v1/batch/jobs/settlement", "POST, /api/v1/batch/executions/1/stop", "POST, /api/v1/holds",
            "POST, /api/v1/fx/faults", "POST, /api/v1/transfers", "POST, /api/v1/payments", "GET, /api/v1/dev/prom/query"})
    void opsGoesEverywhere(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).with(token("ops", "ledger-write", "account-read", "dev")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/v1/stream, 200", "GET, /api/v1/payments, 200", "GET, /api/v1/balances/" + BOB + ", 200",
            "GET, /actuator/metrics, 200", "POST, /api/v1/batch/jobs/settlement, 403", "POST, /api/v1/batch/executions/1/stop, 403",
            "POST, /api/v1/holds, 403", "POST, /api/v1/fx/faults, 403", "POST, /api/v1/transfers, 403", "GET, /api/v1/dev/prom/query, 403"})
    void aReaderReadsButNeverWrites(String method, String path, int expected) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).with(token("reader", "account-read"))).andExpect(status().is(expected));
    }

    private static RequestPostProcessor token(String subject, String... roles) {
        return jwt().jwt(j -> j.subject(subject).claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(LedgerflowSecurityAutoConfiguration.realmRoles());
    }
}
