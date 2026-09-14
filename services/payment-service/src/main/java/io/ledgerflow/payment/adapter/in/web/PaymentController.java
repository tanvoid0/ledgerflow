package io.ledgerflow.payment.adapter.in.web;

import io.ledgerflow.events.Money;
import io.ledgerflow.payment.application.Payments;
import io.ledgerflow.payment.domain.model.PaymentState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
class PaymentController {

    private final Payments payments;

    /** amountMinor is held on, and later captured from, each wallet listed. */
    record PaymentRequest(@NotNull UUID accountId,
                          @NotEmpty List<String> wallets,
                          @Positive long amountMinor,
                          @NotBlank @Size(min = 3, max = 3) String currency) {}

    /** 202, not 201: the payment exists, but three other services still have to answer. Poll the GET. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    PaymentState start(@Valid @RequestBody PaymentRequest req) {
        return payments.start(req.accountId(), req.wallets(), new Money(req.amountMinor(), req.currency()));
    }

    @GetMapping("/{id}")
    PaymentState byId(@PathVariable UUID id) {
        return payments.find(id).orElseThrow(() -> new ErrorResponseException(HttpStatus.NOT_FOUND));
    }
}
