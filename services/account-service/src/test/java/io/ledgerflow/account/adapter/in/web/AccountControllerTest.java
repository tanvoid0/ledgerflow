package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.AccountRepository;
import io.ledgerflow.account.application.OnboardAccount;
import io.ledgerflow.account.domain.model.Account;
import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import io.ledgerflow.starter.web.LedgerflowWebAutoConfiguration;
import io.ledgerflow.account.config.AccountSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@ImportAutoConfiguration({LedgerflowWebAutoConfiguration.class, LedgerflowSecurityAutoConfiguration.class})   // the slice does not scan our starter
@Import(AccountSecurityConfig.class)   // the real chain: self-onboard is authenticated, everything else staff-only
@WithMockUser(roles = "account-read")
class AccountControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountRepository accounts;

    @MockitoBean
    OnboardAccount onboard;

    @Test
    void unknownAccountGivesProblemJson() throws Exception {
        when(accounts.findById(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Account not found"));
    }

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        when(accounts.findAll()).thenReturn(List.of());

        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void onboardUsesTheTokenSubNotAPathId() throws Exception {
        var id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(new Account(id, "Carol", List.of())));

        mvc.perform(put("/api/v1/accounts/onboard").with(jwt().jwt(j -> j.subject(id.toString()).claim("name", "Carol"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Carol"));

        verify(onboard).onboard(eq(id), eq("Carol"));
    }
}
