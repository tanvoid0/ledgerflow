package io.ledgerflow.account.adapter.in.web;

import io.ledgerflow.account.application.PostTransfer;
import io.ledgerflow.account.domain.model.JournalEntry;
import io.ledgerflow.account.domain.model.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
class TransferController {

    private final PostTransfer postTransfer;

    record TransferRequest(@NotNull UUID fromWalletId,
                           @NotNull UUID toWalletId,
                           @Positive long amountMinor,
                           @NotBlank @Size(min = 3, max = 3) String currency,
                           @NotBlank String description) {}

    record TransferResponse(UUID entryId, String idempotencyKey) {
        static TransferResponse of(JournalEntry e) {
            return new TransferResponse(e.id(), e.idempotencyKey());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TransferResponse transfer(@RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                              @Valid @RequestBody TransferRequest req) {
        var entry = postTransfer.transfer(idempotencyKey, req.fromWalletId(), req.toWalletId(),
                new Money(req.amountMinor(), req.currency()), req.description());
        return TransferResponse.of(entry);
    }
}
