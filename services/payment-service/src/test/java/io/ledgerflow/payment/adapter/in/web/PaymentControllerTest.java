package io.ledgerflow.payment.adapter.in.web;

import io.ledgerflow.events.Money;
import io.ledgerflow.payment.application.Payments;
import io.ledgerflow.payment.config.PaymentSecurityConfig;
import io.ledgerflow.payment.domain.model.PaymentState.Captured;
import io.ledgerflow.payment.domain.model.PaymentState.Requested;
import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import io.ledgerflow.starter.web.LedgerflowWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@ImportAutoConfiguration({LedgerflowWebAutoConfiguration.class, LedgerflowSecurityAutoConfiguration.class})
@Import(PaymentSecurityConfig.class)
@WithMockUser(roles = "account-read")
class PaymentControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    Payments payments;

    @Test
    void listReturnsTheNewestPaymentsInTheSameShapeAsById() throws Exception {
        var id = UUID.randomUUID();
        when(payments.list(50)).thenReturn(List.of(new Captured(id, List.of(), null, null)));

        mvc.perform(get("/api/v1/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(id.toString()))
                .andExpect(jsonPath("$[0].state").value("Captured"));
    }

    static final UUID ALICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID BOB = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** Ticketing relays the customer's own bearer, so a customer token reaches this endpoint: it may only spend its own account. */
    @Test
    void aCustomerCannotPayFromSomeoneElsesAccount() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType("application/json").content(body(BOB)).with(token(ALICE, "customer")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }

    /** The service is on its own host port too, so a customer token must not list everyone's payments by skipping the gateway. */
    @Test
    void aCustomerCannotListPayments() throws Exception {
        mvc.perform(get("/api/v1/payments").with(token(ALICE, "customer")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }

    @Test
    void aCustomerPaysFromItsOwnAccount() throws Exception {
        when(payments.start(eq(ALICE), anyList(), any(), any())).thenReturn(requested(ALICE));
        mvc.perform(post("/api/v1/payments").contentType("application/json").content(body(ALICE)).with(token(ALICE, "customer")))
                .andExpect(status().isAccepted());
    }

    /** ops, the scenario scripts and k6 pay on behalf of any account. */
    @Test
    void ledgerWritePaysFromAnyAccount() throws Exception {
        when(payments.start(eq(BOB), anyList(), any(), any())).thenReturn(requested(BOB));
        mvc.perform(post("/api/v1/payments").contentType("application/json").content(body(BOB)).with(token(UUID.randomUUID(), "ledger-write")))
                .andExpect(status().isAccepted());
    }

    static String body(UUID accountId) {
        return """
                {"accountId":"%s","wallets":["MAIN"],"amountMinor":2800,"currency":"GBP"}""".formatted(accountId);
    }

    static Requested requested(UUID accountId) {
        return new Requested(UUID.randomUUID(), accountId, List.of("MAIN"), Money.gbp(2800), List.of());
    }

    static RequestPostProcessor token(UUID subject, String role) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(LedgerflowSecurityAutoConfiguration.realmRoles());
    }

    @Test
    void limitIsClampedTo200() throws Exception {
        when(payments.list(200)).thenReturn(List.of());

        mvc.perform(get("/api/v1/payments").param("limit", "9999"))
                .andExpect(status().isOk());
    }
}
