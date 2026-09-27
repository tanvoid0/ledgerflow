package io.ledgerflow.ticketing.adapter.in.web;

import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import io.ledgerflow.starter.web.LedgerflowWebAutoConfiguration;
import io.ledgerflow.ticketing.application.Ticketing;
import io.ledgerflow.ticketing.application.Ticketing.Order;
import io.ledgerflow.ticketing.config.TicketingProperties;
import io.ledgerflow.ticketing.config.TicketingSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The ticketing chain overrides the starter's: GETs are public, holds need a customer. */
@WebMvcTest(EventsController.class)
@ImportAutoConfiguration({LedgerflowWebAutoConfiguration.class, LedgerflowSecurityAutoConfiguration.class})
@Import({TicketingSecurityConfig.class, EventsControllerTest.Config.class})
class EventsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean Ticketing ticketing;

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        TicketingProperties props() {
            return new TicketingProperties(Duration.ofMinutes(5), "MAIN", "http://payment", 6);
        }
    }

    @Test
    void anonymousReadsTheCatalog() throws Exception {
        when(ticketing.events()).thenReturn(List.of());
        mvc.perform(get("/api/v1/events")).andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    void anonymousCannotHold() throws Exception {
        mvc.perform(post("/api/v1/events/" + UUID.randomUUID() + "/holds")
                        .contentType("application/json").content("{\"seats\":[\"A1\"]}"))
                .andExpect(status().isUnauthorized());
    }

    /** accountId comes from the token's subject, never the body - the body carries seats only. */
    @Test
    void aCustomerHoldsWithTheTokensSubjectRegardlessOfBody() throws Exception {
        var eventId = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        when(ticketing.hold(eq(eventId), eq(accountId), any(), any())).thenReturn(orderId);
        when(ticketing.order(orderId)).thenReturn(Optional.of(new Order(orderId, eventId, "t", accountId, "x",
                List.of("A1"), 100, "GBP", "HELD", null, null, null, null, null)));

        mvc.perform(post("/api/v1/events/" + eventId + "/holds")
                        .contentType("application/json").content("{\"seats\":[\"A1\"]}")
                        .with(jwt().jwt(j -> j.subject(accountId.toString()).claim("realm_access", Map.of("roles", List.of("customer"))))
                                .authorities(LedgerflowSecurityAutoConfiguration.realmRoles())))
                .andExpect(status().isCreated());
        verify(ticketing).hold(eq(eventId), eq(accountId), any(), any());
    }

    @Test
    void aReaderWithoutCustomerCannotHold() throws Exception {
        mvc.perform(post("/api/v1/events/" + UUID.randomUUID() + "/holds")
                        .contentType("application/json").content("{\"seats\":[\"A1\"]}")
                        .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("account-read"))))
                                .authorities(LedgerflowSecurityAutoConfiguration.realmRoles())))
                .andExpect(status().isForbidden());
    }
}
