package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccountRepository accounts;

    @Test
    void unknownAccountGivesProblemJson() throws Exception {
        when(accounts.findById(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()))
           .andExpect(status().isNotFound())
           .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
           .andExpect(jsonPath("$.title").value("Account not found"));
    }
}
