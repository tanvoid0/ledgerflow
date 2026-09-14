package io.ledgerflow.ledger.adapter.in.web;

import io.ledgerflow.events.Money;
import io.ledgerflow.ledger.application.PlaceHold;
import io.ledgerflow.ledger.domain.model.FundsHold;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/holds")
@RequiredArgsConstructor
class HoldController {

    private final PlaceHold placeHold;

    /** One hold of amountMinor is placed on each wallet listed. */
    record HoldRequest(@NotNull UUID accountId,
                       @NotEmpty List<String> wallets,
                       @Positive long amountMinor,
                       @NotBlank @Size(min = 3, max = 3) String currency) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    List<FundsHold> place(@Valid @RequestBody HoldRequest req) {
        return placeHold.place(req.accountId(), req.wallets(), new Money(req.amountMinor(), req.currency()));
    }
}
