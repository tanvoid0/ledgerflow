package io.ledgerflow.balance.adapter.in.web;

import io.ledgerflow.balance.application.Balances;
import io.ledgerflow.balance.application.WalletBalance;
import io.ledgerflow.balance.config.BalanceSecurityConfig;
import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import io.ledgerflow.starter.web.LedgerflowWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reachable on its own host port, so the gateway's owner rule has to hold here too. */
@WebMvcTest(BalanceController.class)
@ImportAutoConfiguration({LedgerflowWebAutoConfiguration.class, LedgerflowSecurityAutoConfiguration.class})
@Import(BalanceSecurityConfig.class)
class BalanceControllerTest {

    static final UUID ALICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID BOB = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    Balances balances;

    @Test
    void aCustomerReadsItsOwnBalances() throws Exception {
        when(balances.account(ALICE)).thenReturn(List.of(new WalletBalance("MAIN", "GBP", 5000, 0)));
        mvc.perform(get("/api/v1/balances/{a}", ALICE).with(token(ALICE, "customer")))
                .andExpect(status().isOk());
    }

    @Test
    void aCustomerCannotReadSomeoneElsesBalances() throws Exception {
        mvc.perform(get("/api/v1/balances/{a}/MAIN", BOB).with(token(ALICE, "customer")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(balances);
    }

    @Test
    void staffReadsAnyAccount() throws Exception {
        when(balances.account(BOB)).thenReturn(List.of(new WalletBalance("MAIN", "GBP", 5000, 0)));
        mvc.perform(get("/api/v1/balances/{a}", BOB).with(token(UUID.randomUUID(), "account-read")))
                .andExpect(status().isOk());
    }

    static RequestPostProcessor token(UUID subject, String role) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(LedgerflowSecurityAutoConfiguration.realmRoles());
    }
}
