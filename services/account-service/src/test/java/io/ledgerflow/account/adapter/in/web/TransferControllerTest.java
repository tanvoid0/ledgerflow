package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.DuplicateEntryException;
import io.ledgerflow.account.application.InsufficientFundsException;
import io.ledgerflow.account.application.LedgerRepository;
import io.ledgerflow.account.application.PostTransfer;
import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.events.Money;
import io.ledgerflow.events.WalletRef;
import io.ledgerflow.starter.messaging.OutboxAppender;
import io.ledgerflow.starter.web.LedgerflowSecurityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// PostTransfer carries @PreAuthorize, and method security is AOP: a @MockitoBean replaces the bean outright,
// leaving no proxy to advise. @Import brings in the real bean instead, wired to mocked collaborators.
@WebMvcTest(TransferController.class)
@ImportAutoConfiguration(LedgerflowSecurityAutoConfiguration.class)
@Import(PostTransfer.class)
@WithMockUser(roles = "ledger-write")
class TransferControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    LedgerRepository ledger;

    @MockitoBean
    OutboxAppender outbox;

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();

    private static final String BODY = """
            {"fromWalletId":"%s","toWalletId":"%s","amountMinor":100,"currency":"GBP","description":"t"}
            """.formatted(FROM, TO);

    @Test
    void missingIdempotencyKeyIsRejectedBeforeAnyMoneyMoves() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Missing header"));
    }

    @Test
    void insufficientFundsIsA422Problem() throws Exception {
        when(ledger.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(ledger.debitIfSufficient(eq(FROM), eq(Money.gbp(100)))).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
    }

    @Test
    void aRacedDuplicateKeyIsA409() throws Exception {
        when(ledger.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(ledger.debitIfSufficient(any(), any())).thenReturn(Optional.of(new WalletRef(FROM, "A")));
        when(ledger.credit(any(), any())).thenReturn(new WalletRef(TO, "B"));
        doThrow(new DuplicateEntryException("k")).when(ledger).append(any());

        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate request"));
    }

    @Test
    void aValidTransferIs201WithTheEntryId() throws Exception {
        when(ledger.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(ledger.debitIfSufficient(eq(FROM), eq(Money.gbp(100)))).thenReturn(Optional.of(new WalletRef(FROM, "A")));
        when(ledger.credit(eq(TO), eq(Money.gbp(100)))).thenReturn(new WalletRef(TO, "B"));

        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").value("k"));
    }

    @Test
    void aNegativeAmountFailsValidation() throws Exception {
        var bad = BODY.replace("\"amountMinor\":100", "\"amountMinor\":-5");

        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.startsWith("amountMinor")));
    }

    @Test
    @WithAnonymousUser
    void noTokenIsA401() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aReaderCannotMoveMoney() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY)
                        .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("account-read"))))
                                .authorities(LedgerflowSecurityAutoConfiguration.realmRoles())))
                .andExpect(status().isForbidden());
    }

    @Test
    void ledgerWriteGetsThrough() throws Exception {
        when(ledger.findByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(ledger.debitIfSufficient(eq(FROM), eq(Money.gbp(100)))).thenReturn(Optional.of(new WalletRef(FROM, "A")));
        when(ledger.credit(eq(TO), eq(Money.gbp(100)))).thenReturn(new WalletRef(TO, "B"));

        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY)
                        .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("ledger-write"))))
                                .authorities(LedgerflowSecurityAutoConfiguration.realmRoles())))
                .andExpect(status().isCreated());
    }
}
