package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.DuplicateEntryException;
import io.ledgerflow.account.application.InsufficientFundsException;
import io.ledgerflow.account.application.PostTransfer;
import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.events.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PostTransfer postTransfer;

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
        when(postTransfer.transfer(any(), any(), any(), any(), any()))
                .thenThrow(new InsufficientFundsException(FROM));

        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
    }

    @Test
    void aRacedDuplicateKeyIsA409() throws Exception {
        when(postTransfer.transfer(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateEntryException("k"));

        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate request"));
    }

    @Test
    void aValidTransferIs201WithTheEntryId() throws Exception {
        var entry = JournalEntry.transfer("k", FROM, TO, Money.gbp(100), "t");
        when(postTransfer.transfer(eq("k"), eq(FROM), eq(TO), eq(Money.gbp(100)), eq("t"))).thenReturn(entry);

        mvc.perform(post("/api/v1/transfers").contentType("application/json")
                        .header("Idempotency-Key", "k").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryId").value(entry.id().toString()))
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
}
